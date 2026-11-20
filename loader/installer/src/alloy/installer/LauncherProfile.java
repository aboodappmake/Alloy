package alloy.installer;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import static alloy.installer.Installer.*;
import static alloy.installer.Util.*;

/**
 * Launcher format output (--launcher-profile OUT): the files a launcher needs to install Alloy the way it installs
 * Forge 1.13+ (see docs/launchers.md).
 *
 *   OUT/install_profile.json   Forge installer profile, "spec": 1: libraries to download + processors to run once
 *   OUT/version.json           Mojang-style version (inheritsFrom the vanilla version): classpath + main class
 *   OUT/maven/...              Alloy's own artifacts in maven layout, ready to upload to ALLOY_MAVEN
 *
 * Processors (client): 1. copy Forge's binary patches out of the official Forge installer jar (a normal maven
 * download from maven.minecraftforge.net), 2. Forge's own binarypatcher processor, unchanged (Mojang client jar ->
 * Forge-patched client jar, SHA-1 checked), 3. tools/PrepFml on Forge's loader jars (-> net.alloy.patched:*).
 * Nothing from Mojang or Forge is shipped by Alloy. The hybrid game jar (tools/PrepGame) depends on the instance's
 * Forge mods, so the launch wrapper (alloy.launch.AlloyLaunch) builds it per instance at launch (alloy-prep).
 */
final class LauncherProfile {
    static final String ALLOY_GROUP = "net.alloy";
    static final String PATCHED_GROUP = "net.alloy.patched";
    static final String DERIVED_GROUP = "net.alloy.derived";
    static final String LAUNCH_MAIN = "alloy.launch.AlloyLaunch";

    final Installer in;
    final String alloyVersion, mavenUrl;
    final Path out;
    Path launchJar;   // installer/alloy-launch.jar built by build_installer.py (the profile is added to it here)
    String time;      // fixed release time (reproducible output); null = now

    LauncherProfile(Installer in, Path out, String alloyVersion, String mavenUrl) {
        this.in = in;
        this.out = out.toAbsolutePath().normalize();
        this.alloyVersion = alloyVersion;
        this.mavenUrl = mavenUrl.endsWith("/") ? mavenUrl : mavenUrl + "/";
    }

    String id() { return MC + "-alloy-" + alloyVersion; }

    /**
     * The Java major vanilla Minecraft MC needs, which is what Alloy needs too: 17 for 1.17 to 1.20.4, 21 for 1.20.5 to
     * 1.21.x, 25 for 26.x and newer. Mirrors alloy.launch.AlloyLaunch.need (that one is compiled for Java 8 on its own).
     */
    static int javaMajor(String mc) {
        if (mc == null || !mc.startsWith("1.")) return 25;
        String[] p = mc.split("\\.");
        int minor = p.length > 1 ? num(p[1]) : 0, patch = p.length > 2 ? num(p[2]) : 0;
        return minor >= 21 || minor == 20 && patch >= 5 ? 21 : 17;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D.*", "")); } catch (RuntimeException e) { return 0; }
    }

    /** the javaVersion block for MC's Alloy version json: vanilla's own (from VJ), else {component, majorVersion} from javaMajor */
    static Object javaVersion(Map<String, Object> vj, String mc) {
        if (vj != null && vj.get("javaVersion") instanceof Map<?, ?> m && !m.isEmpty()) return m;
        int j = javaMajor(mc);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("component", j == 17 ? "java-runtime-gamma" : j == 21 ? "java-runtime-delta" : "java-runtime-epsilon");
        out.put("majorVersion", (long) j);
        return out;
    }
    static String installerCoord(String v) { return ALLOY_GROUP + ":alloy-installer:" + v; }
    static String launchCoord(String v) { return ALLOY_GROUP + ":alloy-launch:" + v; }

    // ------------------------------------------------------------------ library entries
    static Map<String, Object> lib(String coord, String url, Path file) throws IOException {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("path", Maven.path(coord));
        a.put("url", url);
        if (file != null && Files.isRegularFile(file)) {
            a.put("sha1", digest(file, "SHA-1"));
            a.put("size", Files.size(file));
        }
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("name", coord);
        l.put("downloads", new LinkedHashMap<>(Map.of("artifact", a)));
        return l;
    }

    /** an entry of a Forge json: name + downloads.artifact (url, sha1, size, path) */
    static String url(Map<String, Object> l) { return (String) Json.obj(Json.obj(l.get("downloads")).get("artifact")).get("url"); }

    @SuppressWarnings("unchecked")
    void build() throws Exception {
        if (V120.on())   // the processors (Forge binarypatcher, PrepFml) and AlloyLaunchMain are the 26.x layout; Forge 47 needs V120's
            throw new IOException("--launcher-profile (the hosted launcher format) supports Minecraft 26.x only for now. For the official Minecraft Launcher on "
                    + MC + " use the normal client install with --mc-dir MINECRAFT_FOLDER (the window's \"Install to the official Minecraft Launcher\"), or the Prism zip");
        in.java = in.java == null ? ownJava() : in.java;
        String now = time != null ? time : java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).withNano(0).toString();
        Files.createDirectories(out);
        // inputs: vanilla version json, official Forge installer (both downloaded into the cache when missing)
        in.stepMojang(false, false);
        in.fetchForgeInstaller();
        Map<String, Object> fprof, fver;
        try (ZipFile z = new ZipFile(in.installerJar.toFile())) {
            fprof = Json.obj(Json.parse(new String(z.getInputStream(z.getEntry("install_profile.json")).readAllBytes(), StandardCharsets.UTF_8)));
            fver = Json.obj(Json.parse(new String(z.getInputStream(z.getEntry("version.json")).readAllBytes(), StandardCharsets.UTF_8)));
        }
        var vj = Json.obj(Json.parse(Files.readString(in.versionJson)));
        Set<String> vanillaFiles = new HashSet<>();
        Map<String, String> vanillaByKey = new HashMap<>();   // group:artifact -> coord
        for (Object o : Json.arr(vj.get("libraries"))) {
            var l = Json.obj(o);
            if (libArtifact(l) == null) continue;
            String c = (String) l.get("name");
            vanillaFiles.add(Maven.path(c).replaceAll(".*/", ""));
            vanillaByKey.put(Maven.key(c), c);
        }

        // Fabric Loader + Mixin + ASM (the same jars the installer puts on the class path), from the Fabric maven
        List<String> fabric = new ArrayList<>(List.of("net.fabricmc:fabric-loader:" + LOADER, "net.fabricmc:sponge-mixin:" + MIXIN));
        for (String a : List.of("asm", "asm-tree", "asm-commons", "asm-util", "asm-analysis")) fabric.add("org.ow2.asm:" + a + ":" + ASM);

        // ---------------------------------------------------------------- version.json
        Path alloyMaven = out.resolve("maven");
        Path instJar = alloyMaven.resolve(Maven.path(installerCoord(alloyVersion)));
        Files.createDirectories(instJar.getParent());
        Files.copy(selfJar(), instJar, StandardCopyOption.REPLACE_EXISTING);

        List<Object> libs = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        Path launchOut = alloyMaven.resolve(Maven.path(launchCoord(alloyVersion)));   // written below, after the profile
        List<String[]> patched = new ArrayList<>();   // {forge coord, alloy coord}
        Map<String, String> forgeByArtifact = new HashMap<>();
        for (String c : fabric) {
            Path f = in.fabricLibs.resolve(Maven.path(c));
            fetchPinned(List.of(FABRIC_MAVEN, CENTRAL), Maven.path(c), f, null);
            libs.add(lib(c, FABRIC_MAVEN + "/" + Maven.path(c).replace("+", "%2B"), f));
            keys.add(Maven.key(c));
        }
        for (Object o : Json.arr(fver.get("libraries"))) {
            var l = Json.obj(o);
            String c = (String) l.get("name"), p = Maven.path(c);
            forgeByArtifact.put(c.split(":")[1], c);
            if (skipped(p, SKIP_CLIENT)) continue;   // Forge's Mixin, MixinExtras, bootstrap and patched client: not used by Alloy
            if (vanillaFiles.contains(p.replaceAll(".*/", ""))) continue;   // the vanilla parent version ships it
            if (FML_JARS.matcher(p).find()) {
                String ac = Maven.regroup(c, PATCHED_GROUP);
                patched.add(new String[]{c, ac});
                Map<String, Object> e = lib(ac, "", null);
                libs.add(e);
                keys.add(Maven.key(ac));
                continue;
            }
            if (!keys.add(Maven.key(c))) continue;   // Fabric's copy of the same artifact (ASM) wins, as in the installer
            libs.add(l);
        }
        // Forge's universal jar: listed in install_profile, not in version.json (FML finds it by path in real Forge)
        String universal = "net.minecraftforge:forge:" + FV + ":universal";
        if (patched.stream().noneMatch(x -> x[0].equals(universal))) {
            String ac = Maven.regroup(universal, PATCHED_GROUP);
            patched.add(new String[]{universal, ac});
            libs.add(lib(ac, "", null));
        }
        libs.add(0, null);   // placeholder for alloy-launch (needs the jar's hash, made after the profile)

        // ---------------------------------------------------------------- install_profile.json
        Map<String, Object> fdata = Json.obj(fprof.get("data"));
        String binpatch = DERIVED_GROUP + ":forge-binpatch:" + FV + ":client@lzma";
        String forgeInstaller = "net.minecraftforge:forge:" + FV + ":installer";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("BINPATCH", sided("[" + binpatch + "]", "[" + binpatch.replace(":client@", ":server@") + "]"));
        data.put("PATCHED", fdata.get("PATCHED"));
        data.put("PATCHED_SHA", fdata.get("PATCHED_SHA"));

        List<Object> procs = new ArrayList<>();
        String inst = installerCoord(alloyVersion);
        procs.add(proc(inst, List.of(), List.of("--processor", "extract", "--libraries", "{LIBRARY_DIR}", "--archive", forgeInstaller,
                "--entry", "data/client.lzma", "--output", binpatch), null));
        Map<String, Object> forgePatch = null;
        for (Object o : Json.arr(fprof.get("processors"))) {
            var p = Json.obj(o);
            if (p.get("sides") != null && Json.arr(p.get("sides")).equals(List.of("client")) && Json.arr(p.get("args")).contains("{BINPATCH}")) forgePatch = p;
        }
        if (forgePatch == null) throw new IOException("Forge " + FV + " installer has no client binarypatcher processor: format changed?");
        procs.add(forgePatch);   // Forge's own processor, unchanged
        List<String> fmlArgs = new ArrayList<>(List.of("--processor", "fml", "--libraries", "{LIBRARY_DIR}"));
        for (String[] x : patched) fmlArgs.add(x[0] + "=" + x[1]);
        procs.add(proc(inst, List.of(), fmlArgs, null));

        // libraries the processors need: Forge's processor tools, the official Forge installer, Alloy's installer,
        // and the unpatched Forge jars PrepFml reads
        List<Object> plibs = new ArrayList<>();
        Set<String> pnames = new LinkedHashSet<>();
        Set<String> needed = new HashSet<>();
        needed.add((String) forgePatch.get("jar"));
        for (Object c : Json.arr(forgePatch.get("classpath"))) needed.add((String) c);
        for (Object o : Json.arr(fprof.get("libraries"))) {
            var l = Json.obj(o);
            String c = (String) l.get("name");
            if ((needed.contains(c) || c.equals(universal)) && pnames.add(c)) plibs.add(l);
        }
        plibs.add(lib(forgeInstaller, FORGE_MAVEN + "/" + Maven.path(forgeInstaller), in.installerJar));
        pnames.add(forgeInstaller);
        plibs.add(lib(inst, mavenUrl + Maven.path(inst), instJar));
        for (String[] x : patched) {
            if (!pnames.add(x[0])) continue;
            for (Object o : Json.arr(fver.get("libraries"))) if (x[0].equals(Json.obj(o).get("name"))) plibs.add(o);
        }
        for (String need : List.of("forge-transformers", "modlauncher", "securemodules", "unsafe")) {   // read by alloy-prep (PrepGame -Dalloy.transformers)
            String c = forgeByArtifact.get(need);
            if (c == null) throw new IOException("Forge version.json has no " + need);
            if (!pnames.add(c)) continue;
            for (Object o : Json.arr(fver.get("libraries"))) if (c.equals(Json.obj(o).get("name"))) plibs.add(o);
        }
        for (Object o : plibs) if (url(Json.obj(o)) == null || url(Json.obj(o)).isEmpty()) throw new IOException("profile library without URL: " + Json.obj(o).get("name"));

        // Alloy's own section (ignored by launchers): what alloy-prep needs at launch, in maven coordinates
        List<String> transformers = new ArrayList<>();
        for (String need : List.of("forge-transformers", "modlauncher", "securemodules", "unsafe")) transformers.add(forgeByArtifact.get(need));
        for (String k : List.of("org.apache.logging.log4j:log4j-api", "org.apache.logging.log4j:log4j-core", "com.google.code.gson:gson")) {
            if (vanillaByKey.get(k) == null) throw new IOException("vanilla " + MC + " has no " + k);
            transformers.add(vanillaByKey.get(k));
        }
        Map<String, Object> alloy = new LinkedHashMap<>();
        alloy.put("formatVersion", 1L);
        alloy.put("alloyVersion", alloyVersion);
        alloy.put("minecraft", MC);
        alloy.put("forge", FV);
        alloy.put("fabricLoader", LOADER);
        alloy.put("forgePatched", ((String) Json.obj(fdata.get("PATCHED")).get("client")).replaceAll("^\\[|]$", ""));
        alloy.put("forgeUniversal", universal);
        alloy.put("transformers", transformers);
        List<Object> pl = new ArrayList<>();
        for (String[] x : patched) pl.add(x[1]);
        alloy.put("patchedLibraries", pl);

        Map<String, Object> prof = new LinkedHashMap<>();
        prof.put("_comment", List.of("Alloy launcher install profile (Forge installer format, spec 1). See docs/launchers.md.",
                "Downloads Minecraft and Forge from their official servers and builds Alloy's files on the player's PC."));
        prof.put("spec", 1L);
        prof.put("profile", "alloy");
        prof.put("version", id());
        prof.put("minecraft", MC);
        prof.put("json", "/version.json");
        prof.put("welcome", "Alloy " + alloyVersion + ": Forge and Fabric mods in one game (Minecraft " + MC + ", Forge " + FV + ", Fabric Loader " + LOADER + ")");
        prof.put("data", data);
        prof.put("processors", procs);
        prof.put("libraries", plibs);
        prof.put("alloy", alloy);
        String profText = Json.write(prof);
        Files.writeString(out.resolve("install_profile.json"), profText, StandardCharsets.UTF_8);

        // alloy-launch jar = wrapper classes + this profile (the wrapper runs the processors itself when a launcher did not)
        if (launchJar == null || !Files.isRegularFile(launchJar)) throw new IOException("alloy-launch.jar missing (build it with installer/build_installer.py): " + launchJar);
        Files.createDirectories(launchOut.getParent());
        try (ZipFile src = new ZipFile(launchJar.toFile()); ZipOutputStream zo = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(launchOut)))) {
            for (var en = src.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.getName().equals("alloy/launch/install_profile.json") || e.getName().equals("alloy/launch/alloy-launch.properties")) continue;
                ZipEntry ne = new ZipEntry(e.getName());
                ne.setTime(e.getTime());
                zo.putNextEntry(ne);
                if (!e.isDirectory()) try (var is = src.getInputStream(e)) { is.transferTo(zo); }
                zo.closeEntry();
            }
            put(zo, "alloy/launch/install_profile.json", profText.getBytes(StandardCharsets.UTF_8));
            put(zo, "alloy/launch/alloy-launch.properties", ("alloyVersion=" + alloyVersion + "\ninstaller=" + inst + "\nminecraft=" + MC
                    + "\njava=" + javaMajor(MC) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        libs.set(0, lib(launchCoord(alloyVersion), mavenUrl + Maven.path(launchCoord(alloyVersion)), launchOut));

        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", id());
        v.put("inheritsFrom", MC);
        v.put("type", "release");
        v.put("time", now);
        v.put("releaseTime", now);
        v.put("mainClass", LAUNCH_MAIN);
        v.put("javaVersion", javaVersion(vj, MC));   // launchers that read only this file (no inheritsFrom merge) still pick the right Java
        List<Object> jvm = new ArrayList<>(List.of("-Dalloy.librariesDir=${library_directory}", "-Dfabric.log.disableAnsi=true",
                "-Dlog4j2.contextSelector=org.apache.logging.log4j.core.selector.ClassLoaderContextSelector"));
        jvm.addAll(JVM_PROPS);
        v.put("arguments", new LinkedHashMap<>(Map.of("game", List.of(), "jvm", jvm)));
        v.put("libraries", libs);
        Files.writeString(out.resolve("version.json"), Json.write(v), StandardCharsets.UTF_8);
        log("launcher profile " + id() + ": " + out.resolve("install_profile.json") + " (" + procs.size() + " processors, " + plibs.size()
                + " install libraries), " + out.resolve("version.json") + " (" + libs.size() + " libraries, " + patched.size() + " built on install)");
        log("upload " + alloyMaven + " to " + mavenUrl);
    }

    static Map<String, Object> sided(String client, String server) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("client", client);
        m.put("server", server);
        return m;
    }

    static Map<String, Object> proc(String jar, List<String> cp, List<String> args, Map<String, String> outputs) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("sides", List.of("client"));
        p.put("jar", jar);
        p.put("classpath", cp);
        p.put("args", args);
        if (outputs != null) p.put("outputs", outputs);
        return p;
    }

    static boolean skipped(String j, String[] skip) {
        for (String s : skip) if (j.contains(s)) return true;
        return false;
    }

    private static void put(ZipOutputStream z, String name, byte[] b) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(0);
        z.putNextEntry(e);
        z.write(b);
        z.closeEntry();
    }
}
