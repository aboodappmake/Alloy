package alloy.installer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

import static alloy.installer.Installer.*;
import static alloy.installer.Util.*;

/**
 * Prism Launcher / MultiMC instance (port of install.py build_prism_instance): TARGET/prism/NAME/ and NAME.zip.
 * Forge mods are NOT libraries here: Prism builds its class path from the patch json, so the generated alloy_modmixins
 * mod (language adapter) adds forgemods/ to Knot at startup, and the instance's PreLaunchCommand runs alloy-prep.
 */
final class PrismExport {
    static String INSTANCE_NAME = "Alloy 26.1.2";   // "Alloy " + the selected version (Installer.select)
    static final String PATCH_UID = "net.alloy";
    static final String LWJGL_VERSION = "3.4.1";

    /** LWJGL of the selected Minecraft version (Prism's net.minecraft component suggests it) */
    static String lwjgl() {
        String mc = Installer.MC;
        if (mc.equals("1.20.1")) return "3.3.1";
        if (mc.equals("1.20.2") || mc.equals("1.20.4")) return "3.3.2";
        if (mc.startsWith("1.")) return "3.3.3";   // 1.20.5 to 1.21.11
        return LWJGL_VERSION;
    }

    private final Installer in;

    PrismExport(Installer in) { this.in = in; }

    /** {maven coordinate, maven path} for a path relative to a library dir. */
    static String[] mavenCoord(String rel) {
        String[] parts = rel.split("/");
        int n = parts.length;
        if (n >= 4) {
            String art = parts[n - 3], ver = parts[n - 2], fn = parts[n - 1];
            if (fn.endsWith(".jar")) {
                String stem = fn.substring(0, fn.length() - 4);
                String pre = art + "-" + ver;
                if (stem.startsWith(pre)) {
                    String cls = stem.substring(pre.length()).replaceFirst("^-+", "");
                    String coord = String.join(".", Arrays.copyOfRange(parts, 0, n - 3)) + ":" + art + ":" + ver + (cls.isEmpty() ? "" : ":" + cls);
                    return new String[]{coord, rel};
                }
            }
        }
        String stem = rel.substring(rel.lastIndexOf('/') + 1).replaceAll("\\.jar$", "").replaceAll("[^A-Za-z0-9._-]", "_");
        return new String[]{"alloy.libs:" + stem + ":1", "alloy/libs/" + stem + "/1/" + stem + "-1.jar"};
    }

    private static void copy(Path src, Path dst) throws IOException {
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void rmTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) { for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(x); }
    }

    void build() throws Exception {
        String name = in.instanceName != null ? in.instanceName : INSTANCE_NAME;
        Path root = in.target.resolve("prism").resolve(name);
        rmTree(root);
        Path libroot = root.resolve("libraries"), gdir = root.resolve("minecraft");
        for (Path d : List.of(libroot, gdir.resolve("mods"), gdir.resolve("forgemods/.nested"), root.resolve("patches"), gdir.resolve(".alloy/prep/tlibs")))
            Files.createDirectories(d);
        var vj = Json.obj(Json.parse(Files.readString(in.versionJson)));
        boolean v120 = V120.on();
        Path uniMoj = in.work.resolve("lib120").resolve(V120.universalName());   // 1.20.1: Mojang-named Forge universal (made by prep)
        List<Path> cp = v120 ? V120.classpath(in, vj, uniMoj) : in.clientClasspath(vj);
        Set<String> vanilla = new HashSet<>();
        for (Path p : in.vanillaClientLibs(vj)) vanilla.add(p.getFileName().toString());
        Path[] dirs = {in.fml, in.fabricLibs, in.forgeLibs, in.clientLibs};

        List<Object> libs = new ArrayList<>();
        Map<String, String> seen = new HashMap<>();
        List<String> clashes = new ArrayList<>();
        // Forge's files are not shipped either: neither the PrepFml-patched loader jars (changed LGPL code, made on the
        // player's PC only) nor Forge's own libraries (the first --prep gets them from the Forge maven through the
        // official Forge installer). The zip holds placeholders and prep.properties "libraries" says how to fill them.
        Path fmlN = in.fml.toAbsolutePath().normalize(), forgeN = in.forgeLibs.toAbsolutePath().normalize();
        List<String> fill = new ArrayList<>();
        for (Path c : cp) {
            if (vanilla.contains(c.getFileName().toString())) continue;   // Prism's own net.minecraft component ships them
            Path cn = c.toAbsolutePath().normalize();
            String r = c.getFileName().toString();
            for (Path d : dirs) if (cn.startsWith(d.toAbsolutePath().normalize())) { r = d.toAbsolutePath().normalize().relativize(cn).toString().replace('\\', '/'); break; }
            String[] cm = mavenCoord(r);
            String[] g = cm[0].split(":");
            String k = g[0] + ":" + g[1] + (g.length > 3 ? ":" + g[3] : "");
            if (seen.containsKey(k) && !seen.get(k).equals(g[2])) clashes.add(g[0] + ":" + g[1] + " " + seen.get(k) + " vs " + g[2]);
            seen.put(k, g[2]);
            boolean patched = cn.startsWith(fmlN);
            if (v120 && cn.equals(uniMoj.toAbsolutePath().normalize())) {   // derived on the player's PC: --prep copies it in (prep.properties universalMojang)
                placeholder(libroot.resolve(V120.universalName()));
                libs.add(new LinkedHashMap<>(Map.of("name", "net.minecraftforge:forge:" + FV + ":universal", "MMC-hint", "local")));
                continue;
            }
            if (patched || cn.startsWith(forgeN)) {
                String flat = Path.of(cm[1]).getFileName().toString();
                placeholder(libroot.resolve(flat));
                libs.add(new LinkedHashMap<>(Map.of("name", cm[0], "MMC-hint", "local")));
                fill.add("../libraries/" + flat + "|" + (patched ? "fml" : "forge") + "|" + (patched ? fmlN : forgeN).relativize(cn).toString().replace('\\', '/'));
            } else addLocal(libs, libroot, cm[0], cm[1], c);   // Fabric Loader, Mixin, ASM, netty natives: unchanged, not Forge's
        }
        // version.json for the system class loader (see Prep.gameResourcesJar)
        // placeholders: Prism wants every local library to exist before launch; --prep overwrites them with the real files
        // Prism resolves "MMC-hint": "local" libraries as FLAT files: <instance>/libraries/<artifact>-<version>[-classifier].jar
        String rpath = "hybrid-client-resources-1.jar";
        if (!v120) {   // 1.20.1 has no resources jar (no PrepGame)
            placeholder(libroot.resolve(rpath));
            libs.add(new LinkedHashMap<>(Map.of("name", "alloy.libs:hybrid-client-resources:1", "MMC-hint", "local")));
        }

        String gcoord = "net.alloy:hybrid-client:" + MC;
        String gpath = "hybrid-client-" + MC + ".jar";
        placeholder(libroot.resolve(gpath));   // the hybrid game jar contains Minecraft code: built on the player's PC by --prep

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("formatVersion", 1);
        patch.put("uid", PATCH_UID);
        patch.put("name", "Alloy");
        patch.put("version", LOADER + "-" + FV);
        patch.put("order", 100);
        patch.put("type", "release");
        patch.put("releaseTime", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).withNano(0).toString());
        patch.put("requires", List.of(Map.of("equals", MC, "uid", "net.minecraft")));
        patch.put("mainClass", "net.fabricmc.loader.impl.launch.knot.KnotClient");
        patch.put("mainJar", Map.of("name", gcoord, "MMC-hint", "local"));
        List<Object> jvm = new ArrayList<>(List.of("-Dfabric.gameJarPath=../libraries/" + gpath, "-Dfabric.log.disableAnsi=true",
                "-Dlog4j2.contextSelector=org.apache.logging.log4j.core.selector.ClassLoaderContextSelector"));
        if (v120) {   // Forge 47: library folder (the instance's own Forge install), srg names, a few --add-opens
            Installer gi0 = new Installer("client", gdir, gdir.resolve(".alloy/cache"));
            Path sjh = V120.secureJarHandler(cp);
            jvm.removeIf(a -> a.toString().startsWith("-Dfabric.gameJarPath="));
            jvm.addAll(V120.jvmArgs("../libraries/" + gpath, sjh == null ? "" : "../libraries/" + sjh.getFileName(), rel(gdir, gi0.forgeLibs), ".alloy/srg2mojang.tsv"));
        }
        else jvm.addAll(Installer.vanillaJvmFlags(vj));   // 26.3: -XX:StackShadowPages=32 (Prism's meta may not carry it)
        jvm.addAll(JVM_PROPS);
        patch.put("+jvmArgs", jvm);
        patch.put("libraries", libs);
        Files.writeString(root.resolve("patches/" + PATCH_UID + ".json"), Json.write(patch), StandardCharsets.UTF_8);

        String pv = String.valueOf(patch.get("version"));
        Files.writeString(root.resolve("mmc-pack.json"), packJson(pv, false), StandardCharsets.UTF_8);
        // PreLaunchCommand: $INST_JAVA is the Java Prism launches the game with, $INST_MC_DIR the game dir (minecraft/).
        // Prism's instance.cfg is a QSettings ini: a value that starts with a quote is read as ONE quoted string, so the
        // whole value must be quoted with the inner quotes escaped (tested with Prism 11.1: an unescaped
        // `"$INST_JAVA" -D...` turned into `$INST_JAVA-D...`, i.e. "file not found").
        String pre = "\"$INST_JAVA\" -Duser.language=en -jar \"$INST_MC_DIR/.alloy/alloy-installer.jar\" --prep \"$INST_MC_DIR\"";
        String preIni = "\"" + pre.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        Files.writeString(root.resolve("instance.cfg"), instanceCfg(name, preIni, null), StandardCharsets.UTF_8);

        // game dir: Fabric mods, Forge mods (+ extracted jar-in-jar libs), inject list, prep tool + its inputs
        // Mods go in as the player's original files: a fitted Fabric mod (Fit) or a rewritten Forge mod (Collide) is a
        // changed copy of someone else's jar, so the instance's first --prep makes those again on the player's PC.
        Map<String, String> fittedFrom = new HashMap<>();   // fitted copy in mods/ -> original file name
        Path fitState = in.work.resolve("fit-state.properties");
        if (Files.exists(fitState)) {
            Properties fs = new Properties();
            try (Reader r = Files.newBufferedReader(fitState)) { fs.load(r); }
            for (String k : fs.stringPropertyNames()) {
                String[] v = fs.getProperty(k).split("\\|", -1);
                if (v.length >= 3 && !v[2].isEmpty()) fittedFrom.put(v[2], k);
            }
        }
        Path originals = in.work.resolve("originals");
        int restored = 0;
        // 1.20.1: forgemods/.nested holds jars taken out of the Mojang-named copies; the first --prep makes them again
        for (String sub : v120 ? List.of("mods", "forgemods") : List.of("mods", "forgemods", "forgemods/.nested"))
            if (Files.isDirectory(in.target.resolve(sub))) try (var s = Files.list(in.target.resolve(sub))) {
                for (Path j : s.filter(p -> Files.isRegularFile(p) && (p.toString().endsWith(".jar") || p.toString().endsWith(".inject.txt"))).toList()) {
                    String n = j.getFileName().toString();
                    Path orig = sub.equals("mods") && fittedFrom.containsKey(n) ? originals.resolve(fittedFrom.get(n))
                            : sub.equals("forgemods") ? originals.resolve("forgemods").resolve(n) : null;
                    if (orig != null && Files.isRegularFile(orig)) {
                        copy(orig, gdir.resolve(sub).resolve(orig.getFileName()));
                        if (Files.mismatch(orig, j) != -1) restored++;
                    } else copy(j, gdir.resolve(sub).resolve(n));
                }
            }
        if (restored > 0) log("Prism instance: " + restored + " mod(s) go in as the original files; the first start fits them again on the player's PC");
        if (Files.exists(in.target.resolve("alloy-inject.txt"))) copy(in.target.resolve("alloy-inject.txt"), gdir.resolve("alloy-inject.txt"));
        for (String c : List.of("config/fml.toml", "config/modmenu.json"))   // the install's update-check defaults (or the pack's own files)
            if (Files.exists(in.target.resolve(c)) && !Files.exists(gdir.resolve(c))) copy(in.target.resolve(c), gdir.resolve(c));
        Path pdir = gdir.resolve(".alloy/prep");
        Properties src = new Properties();
        try (Reader r = Files.newBufferedReader(in.work.resolve("prep.properties"))) { src.load(r); }
        // Mojang's client jar and the Forge-patched jars are NOT shipped: --prep downloads / rebuilds them into .alloy/cache
        Installer gi = new Installer("client", gdir, gdir.resolve(".alloy/cache"));
        List<String> tr = new ArrayList<>();
        for (String t : src.getProperty("transformers", "").split(";")) {
            if (t.isBlank()) continue;
            Path sp = in.target.resolve(t).toAbsolutePath().normalize();
            if (sp.startsWith(forgeN)) {   // Forge's jar: read from the instance's own Forge install after the first --prep
                tr.add(rel(gdir, gi.forgeLibs.resolve(forgeN.relativize(sp).toString())));
                continue;
            }
            copy(sp, pdir.resolve("tlibs").resolve(sp.getFileName()));   // log4j, gson
            tr.add(".alloy/prep/tlibs/" + sp.getFileName());
        }
        Properties p = new Properties();
        p.setProperty("side", "client");
        p.setProperty("minecraft", MC);   // --prep picks this version again (without it a 26.2 instance was prepared as 26.1.2)
        p.setProperty("libraries", String.join(";", fill));
        p.setProperty("vanilla", rel(gdir, gi.clientJar));
        p.setProperty("forge", rel(gdir, gi.forgePatched));
        p.setProperty("universal", rel(gdir, gi.forgeUniversal));
        p.setProperty("cache", ".alloy/cache");
        p.setProperty("bootstrap", "1");
        if (v120) {
            p.setProperty("universalMojang", "../libraries/" + V120.universalName());
            p.setProperty("srgNames", ".alloy/srg2mojang.tsv");
        } else {
            p.setProperty("resources", "../libraries/" + rpath);
            p.setProperty("transformers", String.join(";", tr));
        }
        p.setProperty("gameJar", "../libraries/" + gpath);
        try (Writer w = Files.newBufferedWriter(gdir.resolve(".alloy/prep.properties"))) { p.store(w, "alloy-prep configuration (generated)"); }
        copy(selfJar(), gdir.resolve(".alloy/alloy-installer.jar"));
        Files.writeString(gdir.resolve(".alloy/README.txt"),
                "Alloy support dir. No Minecraft or Forge files are shipped: the first start downloads Minecraft from Mojang and runs the\n"
                        + "official Forge installer (cache in .alloy/cache), patches Forge's loader jars on this PC and puts them and Forge's\n"
                        + "libraries into ../libraries (they are placeholders until then), then builds the hybrid game jar ../libraries/" + gpath + "\n"
                        + "(set by -Dfabric.gameJarPath).\n"
                        + "Drop Forge mods into forgemods/ and start the game: the instance's PreLaunchCommand runs alloy-installer.jar --prep,\n"
                        + "which re-applies the mods' access transformers and regenerates mods/alloy-modmixins.jar when forgemods/ changed.\n"
                        + "Forge mods that Prism's mod downloader puts in mods/ work too: prep mirrors them into forgemods/ as frommods_*.jar.\n");

        Path zpath = root.resolveSibling(name + ".zip");
        zip(root, zpath, name, Map.of());
        // MultiMC: its patch reader ignores "+jvmArgs" (Prism reads it), so the same args go into instance.cfg JvmArgs, and its
        // meta has no org.lwjgl3 package for 3.3.3 / 3.4.1 (its net.minecraft json brings LWJGL itself): leave that component out
        Path mpath = root.resolveSibling(name + " (MultiMC).zip");
        List<String> jargs = new ArrayList<>();
        for (Object a : jvm) jargs.add(String.valueOf(a));
        zip(root, mpath, name, Map.of("mmc-pack.json", packJson(pv, true), "instance.cfg", instanceCfg(name, preIni, jargs)));
        log("Prism instance: " + root + "  (" + libs.size() + " libraries, " + clashes.size() + " same-artifact version clashes)");
        for (String c : clashes) log("  clash (Prism keeps one): " + c);
        log("Prism zip: " + zpath + "  (Prism: Add Instance > Import > this zip)");
        log("MultiMC zip: " + mpath + "  (MultiMC: Add Instance > Import from zip; Java arguments are in the instance settings, set Java " + LauncherProfile.javaMajor(MC) + ")");
    }

    /** LWJGL versions MultiMC's meta has an org.lwjgl3 package for (meta.multimc.org/v1/org.lwjgl3/index.json, 2026-11) */
    static final Set<String> MULTIMC_LWJGL = Set.of("3.3.1", "3.2.2", "3.2.1", "3.1.6", "3.1.2");

    /** mmc-pack.json; for MULTIMC the org.lwjgl3 component (and the suggestion for it) is left out when MultiMC's meta lacks that LWJGL */
    static String packJson(String alloyVersion, boolean multimc) {
        boolean lw = !multimc || MULTIMC_LWJGL.contains(lwjgl());
        List<Object> comps = new ArrayList<>();
        if (lw) comps.add(Map.of("cachedName", "LWJGL 3", "cachedVersion", lwjgl(), "cachedVolatile", true, "dependencyOnly", true, "uid", "org.lwjgl3", "version", lwjgl()));
        Map<String, Object> mc = new LinkedHashMap<>();
        mc.put("cachedName", "Minecraft");
        if (lw) mc.put("cachedRequires", List.of(Map.of("suggests", lwjgl(), "uid", "org.lwjgl3")));
        mc.put("cachedVersion", MC);
        mc.put("important", true);
        mc.put("uid", "net.minecraft");
        mc.put("version", MC);
        comps.add(mc);
        comps.add(Map.of("cachedName", "Alloy", "cachedRequires", List.of(Map.of("equals", MC, "uid", "net.minecraft")),
                "cachedVersion", alloyVersion, "uid", PATCH_UID, "version", alloyVersion));
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("formatVersion", 1);
        pack.put("components", comps);
        return Json.write(pack);
    }

    /** a QSettings ini value: quoted as one string, backslashes and quotes escaped (an unquoted value starting with a quote breaks) */
    static String iniQuote(String v) { return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    /** one command line from ARGS: an argument with a space or a quote is put in double quotes (MultiMC splits JvmArgs like a shell) */
    static String joinArgs(List<String> args) {
        List<String> out = new ArrayList<>();
        for (String a : args) out.add(a.isEmpty() || a.contains(" ") || a.contains("\"") || a.contains("\t") ? "\"" + a.replace("\"", "\\\"") + "\"" : a);
        return String.join(" ", out);
    }

    /** instance.cfg; with JVM (MultiMC) the Java arguments go in JvmArgs (OverrideJavaArgs=true), else Prism reads the patch's +jvmArgs */
    static String instanceCfg(String name, String preIni, List<String> jvm) {
        return "[General]\nConfigVersion=1.3\nInstanceType=OneSix\nname=" + name + "\n"
                + "AutomaticJava=true\nOverrideMemory=true\nMinMemAllocation=1024\nMaxMemAlloc=" + Util.clientHeapMb() + "\n"
                + (jvm == null ? "OverrideJavaArgs=false\n" : "OverrideJavaArgs=true\nJvmArgs=" + iniQuote(joinArgs(jvm)) + "\n")
                + "OverrideCommands=true\nPreLaunchCommand=" + preIni + "\n";
    }

    /** ROOT as NAME/... into ZPATH; REPLACE maps a file relative to ROOT to other text */
    static void zip(Path root, Path zpath, String name, Map<String, String> replace) throws IOException {
        Files.deleteIfExists(zpath);
        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zpath), 1 << 20)); var s = Files.walk(root)) {
            for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                z.putNextEntry(new ZipEntry(name + "/" + rel));
                if (replace.containsKey(rel)) z.write(replace.get(rel).getBytes(StandardCharsets.UTF_8));
                else Files.copy(f, z);
                z.closeEntry();
            }
        }
    }

    private static void placeholder(Path dst) throws IOException {
        Files.createDirectories(dst.getParent());
        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(dst), 1 << 20))) {
            z.putNextEntry(new ZipEntry("alloy-placeholder.txt"));
            z.write("Replaced by alloy-installer.jar --prep on the first start.\n".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
    }

    private static void addLocal(List<Object> libs, Path libroot, String coord, String mpath, Path src) throws IOException {
        copy(src, libroot.resolve(Path.of(mpath).getFileName().toString()));   // flat: Prism's local-library lookup
        libs.add(new LinkedHashMap<>(Map.of("name", coord, "MMC-hint", "local")));
    }
}
