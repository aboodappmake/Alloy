package alloy.installer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static alloy.installer.Installer.*;
import static alloy.installer.Util.*;

/**
 * Official Minecraft Launcher profile (same layout Fabric's installer writes):
 *   MC/versions/ID/ID.json   (inheritsFrom the vanilla version, so the launcher fetches vanilla itself)
 *   MC/libraries/...         (Alloy's extra libraries, maven layout, referenced by name + local downloads.artifact)
 *   MC/launcher_profiles.json and MC/launcher_profiles_microsoft_store.json, whichever exist (entry added; each old
 *   file is copied to NAME.alloy-backup-TIMESTAMP first; LauncherProfiles)
 * The profile's gameDir is the Alloy client install folder (mods/, forgemods/, .alloy/ with the prepared hybrid game jar).
 * The official launcher has no pre-launch hook, so the profile starts .alloy/alloy-agent.jar in prep mode
 * (-javaagent, -Dalloy.agent.mode=prep): before Fabric starts it runs  java -jar .alloy/alloy-installer.jar --prep GAMEDIR
 * in a child JVM of the same Java, like Prism's pre-launch command (marker .alloy/launcher-agent.properties).
 */
final class LauncherExport {
    static String ID = "Alloy-" + MC;   // both follow the selected version (Installer.select)
    static String PROFILE_KEY = "alloy-" + MC;
    static final String AGENT_MARKER = ".alloy/launcher-agent.properties";   // alloy.agent.Premain.LAUNCHER_MARKER
    private final Installer in;

    LauncherExport(Installer in) { this.in = in; }

    void build(Path mc) throws Exception {
        mc = mc.toAbsolutePath().normalize();
        LauncherProfiles.check(mc);
        Path libroot = mc.resolve("libraries");
        var vj = Json.obj(Json.parse(Files.readString(in.versionJson)));
        Set<String> vanilla = new HashSet<>();
        for (Path p : in.vanillaClientLibs(vj)) vanilla.add(p.getFileName().toString());
        Path[] dirs = {in.fml, in.fabricLibs, in.forgeLibs, in.clientLibs};

        List<Object> libs = new ArrayList<>();
        Set<String> names = new HashSet<>();
        List<Path> cp = V120.on() ? V120.classpath(in, vj, in.work.resolve("lib120").resolve(V120.universalName())) : in.clientClasspath(vj);
        Path sjhCopy = null;
        for (Path c : cp) {
            if (vanilla.contains(c.getFileName().toString())) continue;   // the vanilla parent version ships these
            Path cn = c.toAbsolutePath().normalize();
            String r = c.getFileName().toString();
            for (Path d : dirs) if (cn.startsWith(d.toAbsolutePath().normalize())) { r = d.toAbsolutePath().normalize().relativize(cn).toString().replace('\\', '/'); break; }
            String[] cm = PrismExport.mavenCoord(r);
            if (!names.add(cm[0])) continue;
            addLib(libs, libroot, cm[0], cm[1], c);
            if (c.getFileName().toString().startsWith("securejarhandler-")) sjhCopy = libroot.resolve(cm[1]);
        }
        Path rj = in.resourcesJar();
        if (!V120.on() && Files.isRegularFile(rj)) addLib(libs, libroot, "alloy.libs:hybrid-client-resources:1", "alloy/libs/hybrid-client-resources/1/hybrid-client-resources-1.jar", rj);

        Path game = in.gameJar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(game)) throw new IOException("hybrid client game jar missing (" + game + "); not prepared yet (--lean install?)");
        List<Object> jvm = new ArrayList<>(List.of("-Dfabric.gameJarPath=" + game, "-Dfabric.log.disableAnsi=true",
                "-Dlog4j2.contextSelector=org.apache.logging.log4j.core.selector.ClassLoaderContextSelector"));
        if (V120.on()) {   // Forge 47 (launch120.py): library folder, srg names, securejarhandler from the launcher's library copy
            jvm.remove(0);
            jvm.addAll(V120.jvmArgs(game.toString(), sjhCopy == null ? "" : sjhCopy.toString(), in.forgeLibs.toAbsolutePath().toString(),
                    in.work.resolve("srg2mojang.tsv").toAbsolutePath().toString()));
        }
        jvm.addAll(JVM_PROPS);
        jvm.addAll(agent(in.target));

        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", ID);
        v.put("inheritsFrom", MC);
        v.put("type", "release");
        String now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).withNano(0).toString();
        v.put("releaseTime", now);
        v.put("time", now);
        v.put("mainClass", "net.fabricmc.loader.impl.launch.knot.KnotClient");
        v.put("javaVersion", LauncherProfile.javaVersion(vj, MC));   // for launchers that read only this file (TLauncher, SKLauncher)
        v.put("arguments", new LinkedHashMap<>(Map.of("jvm", jvm, "game", List.of())));
        v.put("libraries", libs);
        Path vdir = mc.resolve("versions").resolve(ID);
        Files.createDirectories(vdir);
        Files.writeString(vdir.resolve(ID + ".json"), Json.write(v), StandardCharsets.UTF_8);

        // every launcher profile file that exists: back up, add / replace our profile, read back
        Map<String, Object> prof = new LinkedHashMap<>();
        prof.put("name", "Alloy " + MC);
        prof.put("type", "custom");
        prof.put("created", now);
        prof.put("lastUsed", now);
        prof.put("icon", "Furnace");
        prof.put("lastVersionId", ID);
        prof.put("gameDir", in.target.toString());
        LauncherProfiles.write(mc, PROFILE_KEY, prof);
        log("Launcher profile 'Alloy " + MC + "' written to " + mc + " (version " + ID + ", " + libs.size() + " libraries, game dir " + in.target + ")");
        log("Start the Minecraft Launcher and choose that profile (it downloads vanilla " + MC + " itself).");
    }

    /** .alloy/alloy-agent.jar + marker in the game dir; the JVM arguments that run it in prep mode */
    static List<String> agent(Path game) throws IOException {
        Path dir = game.toAbsolutePath().normalize().resolve(".alloy");
        Files.createDirectories(dir);
        Path jar = dir.resolve("alloy-agent.jar");
        byte[] want = Util.resource("/alloy-agent.jar");
        if (!(Files.isRegularFile(jar) && Prep.sameClasses(jar, want))) Files.write(jar, want);
        Files.writeString(game.toAbsolutePath().normalize().resolve(AGENT_MARKER), "# Alloy: the official launcher profile runs .alloy/alloy-agent.jar in prep mode\nprep=true\n", StandardCharsets.UTF_8);
        return List.of("-javaagent:" + jar, "-Dalloy.agent.mode=prep");
    }

    private static void addLib(List<Object> libs, Path libroot, String coord, String mpath, Path src) throws IOException {
        Path dst = libroot.resolve(mpath);
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        Map<String, Object> art = new LinkedHashMap<>();
        art.put("path", mpath);
        art.put("sha1", digest(dst, "SHA-1"));
        art.put("size", Files.size(dst));
        art.put("url", "");
        libs.add(new LinkedHashMap<>(Map.of("name", coord, "downloads", Map.of("artifact", art))));
    }
}
