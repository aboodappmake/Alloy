package alloy.installer;

import java.nio.file.*;
import java.util.*;

/**
 * alloy-installer.jar
 *   java -jar alloy-installer.jar --side client|server --dir TARGET [--forge-mods DIR] [--fabric-mods DIR]
 *                                    [--cache DIR] [--java JAVA_HOME_OR_EXE] [--eula] [--no-assets]
                  [--mc-dir MINECRAFT_FOLDER]   (client) also add an official Minecraft Launcher profile there
 *   java -jar alloy-installer.jar --prep GAMEDIR [--force]     (drop-in mods: re-prepare when forgemods/ changed)
 *   java -jar alloy-installer.jar --report [--game GAMEDIR]   (crash report zip for testers, personal data removed)
 *   java -jar alloy-installer.jar                              (no arguments: small GUI)
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--gui")) {
            if (java.awt.GraphicsEnvironment.isHeadless()) { usage(); System.exit(2); }
            Gui.show();
            return;
        }
        if (isHelp(args[0])) { usage(); return; }   // INSTALL.md tells players to run --help: exit 0, not the error code 2
        if (args[0].equals("--check-pins")) {   // build check: every download of every bundled version has a SHA-256 pin
            System.exit(Util.Pins.check());
        }
        if (args[0].equals("--classify")) {   // --classify JAR_OR_DIR ... [--game GAMEDIR]: which side a jar in mods/ goes to
            ForgeInMods.cli(Arrays.asList(args).subList(1, args.length));
            return;
        }
        if (args[0].equals("--check-world")) {   // --check-world WORLD [--game GAMEDIR] [--quick] [--out FILE]: offline, changes nothing
            System.exit(alloy.world.WorldCheck.cli(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args[0].equals("--report")) {   // --report [--game GAMEDIR] [--out FILE] [--print]: crash report bundle for testers, personal data removed
            System.exit(ReportBundle.cli(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args[0].equals("--restore-disabled") || args[0].equals("--disable-mod") || args[0].equals("--recover-check")) {
            System.exit(CrashRecovery.cli(args));   // crash recovery: put switched-off mods back / switch one off / show the offer
        }
        if (args[0].equals("--server-start")) {   // in a server folder: --prep, then the server (hosts, panels, run-server)
            int rc;
            try { rc = ServerStart.run(Arrays.copyOfRange(args, 1, args.length)); }
            catch (Exception e) {
                System.err.println("[alloy] ERROR: " + e.getMessage());
                if (System.getProperty("alloy.debug") != null) e.printStackTrace();
                rc = 1;
            }
            System.exit(rc);
        }
        List<String> argList = new ArrayList<>(Arrays.asList(args));
        devScripts = argList.remove("--dev-scripts");   // dev only: also write the offline run-client test script
        args = argList.toArray(new String[0]);

        Map<String, String> o = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--modpack") && i + 1 < args.length) {   // may be given more than once (a Forge pack and a Fabric pack)
                o.merge("modpack", args[++i], (x, y) -> x + "\n" + y);
                continue;
            }
            if (!a.startsWith("--")) { usage(); System.exit(2); }
            String k = a.substring(2);
            if (k.equals("eula") || k.equals("no-assets") || k.equals("force") || k.equals("lean") || k.equals("no-profile")
                    || k.equals("check") || k.equals("fabric-instance")) o.put(k, "1");
            else if (i + 1 < args.length) o.put(k, args[++i]);
            else { usage(); System.exit(2); }
        }
        try {
            // Minecraft version: --mc, else the one a game dir was installed for, else the default (26.1.2)
            if (o.containsKey("mc")) Installer.select(o.get("mc"));
            else if (o.containsKey("doctor")) Installer.selectFor(Path.of(o.get("doctor")));
            else if (o.containsKey("lock")) Installer.selectFor(Path.of(o.get("lock")));
            else if (!o.containsKey("prep")) Installer.select(null);   // --prep reads the game dir's version itself
            if (o.containsKey("doctor")) {
                java.util.List<String> a = new java.util.ArrayList<>(java.util.List.of(o.get("doctor")));
                String dside = o.containsKey("side") ? o.get("side") : installedSide(Path.of(o.get("doctor")));   // a server folder is checked as a server
                if (dside != null) a.addAll(java.util.List.of("--side", dside));
                if (o.containsKey("json")) a.addAll(java.util.List.of("--json", o.get("json")));
                System.exit(alloy.ideas.ModDoctor.cli(a.toArray(new String[0])));
            }
            if (o.containsKey("lock")) {   // --lock GAMEDIR [--out FILE]   |   --lock GAMEDIR --verify LOCKFILE
                java.util.List<String> a = new java.util.ArrayList<>();
                if (o.containsKey("verify")) a.addAll(java.util.List.of("verify", o.get("lock"), o.get("verify")));
                else { a.addAll(java.util.List.of("lock", o.get("lock"))); if (o.containsKey("out")) a.addAll(java.util.List.of("-o", o.get("out"))); }
                System.exit(alloy.ideas.ModLock.cli(a.toArray(new String[0])));
            }
            if (o.containsKey("fit")) { Fit.dev(o); return; }   // dev: static fit of one jar
            if (launcherModes(o)) return;
            if (o.containsKey("modpack")) { Modpack.cli(o); return; }
            if (o.containsKey("prep")) {
                CrashRecovery.atLaunch(Path.of(o.get("prep")));   // the last start crashed: offer to start without the mod behind it
                Prep.run(Path.of(o.get("prep")), o.containsKey("force"));
                return;
            }
            run(o);
        } catch (Modpack.Refused e) {
            System.err.println("[alloy] modpack: " + e.getMessage());
            System.exit(2);
        } catch (Prep.Blocked e) {
            System.err.println("[alloy] " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("[alloy] ERROR: " + e.getMessage());
            if (System.getProperty("alloy.debug") != null) e.printStackTrace();
            System.exit(1);
        }
    }

    /** launcher format (docs/launchers.md): profile output, official-launcher install, processor runner, launch-time prep. */
    static boolean launcherModes(Map<String, String> o) throws Exception {
        if (o.containsKey("launcher-profile")) {   // --launcher-profile OUT --launch-jar alloy-launch.jar [--alloy-version V] [--maven-url URL] [--cache DIR] [--time ISO]
            Path out = Path.of(o.get("launcher-profile"));
            if (o.containsKey("maven-url") && !o.get("maven-url").startsWith("https://"))
                throw new java.io.IOException("--maven-url must be an https:// address (launchers download Alloy's jars from it): " + o.get("maven-url"));
            Installer in = new Installer("client", out.resolve("work"), o.containsKey("cache") ? Path.of(o.get("cache")) : out.resolve("cache"));
            LauncherProfile lp = new LauncherProfile(in, out, o.getOrDefault("alloy-version", ALLOY_VERSION),
                    o.getOrDefault("maven-url", "https://alloy-maven.invalid/"));
            lp.launchJar = o.containsKey("launch-jar") ? Path.of(o.get("launch-jar")) : Util.selfJar().resolveSibling("alloy-launch.jar");
            lp.time = o.get("time");
            lp.build();
            return true;
        }
        if (o.containsKey("install-official")) {
            if (o.get("profile-dir") == null) { usage(); System.exit(2); }
            OfficialInstall.run(Path.of(o.get("install-official")), Path.of(o.get("profile-dir")),
                    o.containsKey("game-dir") ? Path.of(o.get("game-dir")) : null, !o.containsKey("no-profile"));
            return true;
        }
        if (o.containsKey("run-processors")) {   // --run-processors install_profile.json --libraries DIR --minecraft-jar JAR [--root DIR]
            var prof = ProfileRunner.load(Path.of(o.get("run-processors")));
            Path lib = Path.of(o.get("libraries"));
            ProfileRunner r = new ProfileRunner(prof, "client", lib, Path.of(o.get("minecraft-jar")), Path.of(o.getOrDefault("root", lib.toString())), null);
            Util.log("libraries: " + r.libraries() + " downloaded");
            int[] n = r.processors();
            Util.log("processors: " + n[0] + " ran, " + n[1] + " up to date");
            return true;
        }
        if (o.containsKey("launch-prep")) {   // called by alloy.launch.AlloyLaunch inside the game JVM
            LaunchPrep.run(Path.of(o.get("launch-prep")), Path.of(o.get("libraries")), Path.of(o.get("profile")),
                    o.containsKey("minecraft-jar") ? Path.of(o.get("minecraft-jar")) : null);
            return true;
        }
        return false;
    }

    static final String ALLOY_VERSION = "0.5.4";   // the release number; bridge/res*/fabric.mod.json carry it + "-beta" (test_release_version.py)
    static boolean devScripts;

    static void run(Map<String, String> o) throws Exception {
        String side = o.get("side"), dir = o.get("dir");
        if (side == null || dir == null || !(side.equals("client") || side.equals("server"))) { usage(); System.exit(2); }
        if (o.containsKey("mc")) Installer.select(o.get("mc"));
        Installer in = new Installer(side, Path.of(dir), o.containsKey("cache") ? Path.of(o.get("cache")) : null);
        in.devScripts = devScripts || o.containsKey("dev-scripts");
        in.eula = o.containsKey("eula");
        in.noAssets = o.containsKey("no-assets");
        in.fabricInstance = o.containsKey("fabric-instance");
        in.lean = o.containsKey("lean");
        if (o.containsKey("mc-dir")) in.mcDir = Path.of(o.get("mc-dir"));
        if (o.containsKey("java")) {
            in.java = Util.javaExe(Path.of(o.get("java")), Util.WINDOWS);   // a JAVA_HOME, java itself, or a macOS .jdk bundle
        }
        in.install(jarsIn(o.get("forge-mods")), jarsIn(o.get("fabric-mods")));
    }

    /** the side a game dir was installed for (.alloy/prep.properties "side"), or null */
    static String installedSide(Path game) {
        Path pf = game.resolve(".alloy/prep.properties");
        if (!Files.isRegularFile(pf)) return null;
        Properties p = new Properties();
        try (var r = Files.newBufferedReader(pf)) { p.load(r); } catch (java.io.IOException e) { return null; }
        String s = p.getProperty("side");
        return "server".equals(s) || "client".equals(s) ? s : null;
    }

    static List<Path> jarsIn(String dir) throws java.io.IOException {
        return dir == null ? List.of() : Util.jars(Path.of(dir));
    }

    static boolean isHelp(String a) {
        return a.equals("--help") || a.equals("-help") || a.equals("-h") || a.equals("-?") || a.equals("/?");
    }

    static void usage() {
        System.err.println("""
                Alloy installer
                  --side client|server --dir TARGET [--forge-mods DIR] [--fabric-mods DIR] [--cache DIR]
                  [--java JAVA_HOME_OR_EXE] [--eula] [--no-assets]
                  [--mc-dir MINECRAFT_FOLDER]  (client) also add a profile to the official Minecraft Launcher there
                  --doctor GAMEDIR [--side client|server] [--json FILE]   check mods/ and forgemods/ in plain words (exit 2 = blockers)
                  --lock GAMEDIR [--out FILE] | --lock GAMEDIR --verify LOCKFILE   write / verify a mod lock file
                  --check-world WORLD [--game GAMEDIR] [--quick] [--out FILE]
                                               read a world offline (level.dat, players, data, regions) and list content and
                                               mod data of mods that GAMEDIR's mods/ and forgemods/ do not have (default
                                               GAMEDIR: the folder above saves/). Changes nothing. Exit 2 = content would be lost
                  --report [--game GAMEDIR] [--out FILE] [--print]
                                               write a crash report zip for whoever helps you: logs, newest crash report,
                                               hs_err files, mod list with SHA-256, Alloy/Java/OS/RAM facts, Mod Doctor and
                                               the diagnosis. The login token, account ids, player and user names, e-mails and
                                               IPs are removed; launcher account files are never read. Default GAMEDIR: here
                  --lean (with --side/--dir)   leave no Minecraft code in the install; --prep downloads it on the first start
                  --server-start [SERVER ARGS] in a server folder (java -jar .alloy/alloy-installer.jar --server-start nogui):
                                               --prep, then start the server on this Java with this JVM's options.
                                               Same as java -jar server.jar (the folder's server.jar, for hosts and
                                               panels): no Alloy arguments are needed in an Alloy server folder
                  --find-java [--mc VERSION]   list the Javas found on this PC and the one Alloy would use
                  --prep GAMEDIR [--force]     re-prepare after Forge mods were added/removed in GAMEDIR/forgemods
                                               (Forge mods in GAMEDIR/mods are found too, see config/alloy-sides.properties)
                  --restore-disabled GAMEDIR [MOD]   put back the mods crash recovery switched off (.alloy/disabled/)
                  --disable-mod GAMEDIR MOD    switch a mod off (and the mods that need it), reversible; the same as
                                               -Dalloy.recover=disable:MOD on the start command
                  --recover-check GAMEDIR      show what crash recovery would offer for the last failed start
                  --modpack PACK [--modpack PACK2] --dir TARGET [--side client|server] [--pack-mods DIR] [--check]
                                               import a Modrinth .mrpack or CurseForge pack zip (or a Forge and a Fabric pack
                                               together): Forge jars to forgemods/, Fabric jars to mods/, overrides copied.
                                               --pack-mods: jars you downloaded by hand. --check: only check and list, install nothing
                  --classify JAR_OR_DIR ... [--game GAMEDIR]   print which side (Forge, Fabric, none) each jar goes to
                  --launcher-profile OUT [--alloy-version V] [--maven-url URL] [--launch-jar JAR]
                                               write install_profile.json + version.json for launchers (PACK-MAKERS.md)
                  --install-official MCDIR --profile-dir OUT [--game-dir DIR] [--no-profile]
                                               install that profile into an official Minecraft Launcher folder
                  --run-processors install_profile.json --libraries DIR --minecraft-jar JAR   run its client processors
                  --mc VERSION                 Minecraft version to install or check (with --side, --doctor, --lock,
                                               --launcher-profile). Default 26.1.2. A game dir keeps its version for --prep
                  --fabric-instance (with --side client --dir INSTANCE)   Alloy inside a plain Fabric Loader instance of
                                               another launcher (INSTALL.md): writes .alloy/fabric-instance.args,
                                               the marker and .alloy/alloy-agent.jar, no Prism zip; start the instance with
                                               the Java argument -javaagent:.alloy/alloy-agent.jar
                  --dev-scripts (with --side client)   also write run-client.cmd/.sh: an offline test start for Alloy
                                               developers, with no account check. Players start Alloy from Prism or MultiMC
                                               (import prism/*.zip) or from the Minecraft Launcher (--mc-dir)
                  --lean with --mc-dir         --lean is ignored: the Minecraft Launcher has no pre-launch step, so the
                                               game is prepared during the install (the Prism zip is still written)
                  (no arguments)               open the GUI
                The installer needs Java 25 or newer (an older Java relaunches with a JDK 25 it finds, or offers a download).
                The game needs: Minecraft 26.x Java 25 or newer; 1.21.1, 1.21.4, 1.21.8 and 1.21.11 Java 21 or newer;
                1.20.1, 1.20.2 and 1.20.4 Java 17 or newer (Alloy tests 1.20.x and 1.21.x on Java 21).""");
        System.err.println("Minecraft versions in this installer: " + String.join(", ", Installer.versions()) + " (first = default)");
    }
}
