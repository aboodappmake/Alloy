package alloy.installer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static alloy.installer.Util.*;

/**
 * Launch-time step of the launcher format (called by alloy.launch.AlloyLaunch in the game JVM, before Fabric starts):
 *   --launch-prep GAMEDIR --libraries LIBDIR --profile install_profile.json [--minecraft-jar JAR]
 *
 * 1. install check: Forge's patched client jar and Alloy's patched loader jars are in LIBDIR. When a launcher did not
 *    run the install processors (Prism, or a broken install) they are run here, once (needs the vanilla client jar).
 * 2. GAMEDIR/mods/alloy-bridge.jar is this installer's bridge (copied when missing or different).
 * 3. GAMEDIR/.alloy/prep.properties points alloy-prep at the files in LIBDIR (rewritten only when it changes,
 *    so the prep fast path stays valid).
 * 4. alloy-prep (Prep.run): hybrid game jar for this instance's forgemods/, Fabric mod fitting, Mod Doctor.
 * The game jar ends up in GAMEDIR/.alloy/hybrid_client_game.jar; the wrapper passes it to Fabric (fabric.gameJarPath).
 */
final class LaunchPrep {
    private LaunchPrep() { }

    static final String GAME_JAR = ".alloy/hybrid_client_game.jar";

    static void run(Path game, Path libDir, Path profileFile, Path mcJar) throws Exception {
        // this runs in the game JVM: Mod Doctor's memory check reads the real -Xmx from here
        System.setProperty("alloy.doctor.heapMb", String.valueOf(Runtime.getRuntime().maxMemory() >> 20));
        game = game.toAbsolutePath().normalize();
        libDir = libDir.toAbsolutePath().normalize();
        var prof = ProfileRunner.load(profileFile);
        var alloy = Json.obj(prof.get("alloy"));
        if (alloy == null) throw new IOException(profileFile + " is not an Alloy install profile");
        if (Installer.supports((String) alloy.get("minecraft"))) Installer.select((String) alloy.get("minecraft"));
        if (!Installer.FV.equals(alloy.get("forge")) || !Installer.MC.equals(alloy.get("minecraft")))
            throw new IOException("this alloy-installer is for Minecraft " + Installer.MC + " / Forge " + Installer.FV + ", the profile is for "
                    + alloy.get("minecraft") + " / " + alloy.get("forge"));
        Path forge = libDir.resolve(Maven.path((String) alloy.get("forgePatched")));
        Path universal = libDir.resolve(Maven.path((String) alloy.get("forgeUniversal")));

        // 1. install check
        List<Path> need = new ArrayList<>(List.of(forge));
        for (Object c : Json.arr(alloy.get("patchedLibraries"))) need.add(libDir.resolve(Maven.path((String) c)));
        if (!need.stream().allMatch(Files::isRegularFile)) {
            if (mcJar == null || !Files.isRegularFile(mcJar)) throw new IOException("Alloy is not installed in " + libDir + " and the vanilla client jar was not found; pass -Dalloy.minecraftJar=PATH");
            log("first start: installing Alloy into " + libDir + " (Forge patches, Alloy loader jars; once)");
            ProfileRunner r = new ProfileRunner(prof, "client", libDir, mcJar, game, null);
            r.skipKnown = true;
            r.libraries();
            int[] n = r.processors();
            log("install processors: " + n[0] + " ran, " + n[1] + " up to date");
            for (Path p : need) if (!Files.isRegularFile(p)) throw new IOException("install did not produce " + p);
        }
        if (mcJar == null || !Files.isRegularFile(mcJar)) throw new IOException("vanilla client jar not found; pass -Dalloy.minecraftJar=PATH");

        // 2. bridge mod
        Path mods = game.resolve("mods");
        Files.createDirectories(mods);
        Files.createDirectories(game.resolve("forgemods/.nested"));
        byte[] bridge = Installer.bridgeJar();
        Path bj = mods.resolve("alloy-bridge.jar");
        if (!(Files.exists(bj) && Arrays.equals(Files.readAllBytes(bj), bridge))) {
            Files.write(bj, bridge);
            log("mods/alloy-bridge.jar updated");
        }

        // 3. prep config
        List<String> tr = new ArrayList<>();
        for (Object c : Json.arr(alloy.get("transformers"))) tr.add(abs(libDir.resolve(Maven.path((String) c))));
        Properties p = new Properties();
        p.setProperty("side", "client");
        p.setProperty("vanilla", abs(mcJar));
        p.setProperty("forge", abs(forge));
        p.setProperty("universal", abs(universal));
        p.setProperty("transformers", String.join(";", tr));
        p.setProperty("gameJar", GAME_JAR);
        p.setProperty("cache", ".alloy/cache");
        p.setProperty("launcher", "1");   // made by the launch wrapper: no run scripts, no bootstrap downloads
        Path work = game.resolve(".alloy");
        Files.createDirectories(work);
        Path cfg = work.resolve("prep.properties");
        StringWriter sw = new StringWriter();
        for (String k : new TreeSet<>(p.stringPropertyNames())) sw.append(k).append('=').append(esc(p.getProperty(k))).append('\n');
        String text = "# alloy-prep configuration (generated by the launch wrapper)\n" + sw;
        if (!(Files.exists(cfg) && Files.readString(cfg).equals(text))) Files.writeString(cfg, text, StandardCharsets.UTF_8);

        // 4. crash recovery (the last start crashed: offer to start without the mod behind it), then alloy-prep
        System.setProperty("alloy.recover.inGame", "true");
        CrashRecovery.atLaunch(game);
        Prep.run(game, false);
    }

    static String abs(Path p) { return p.toAbsolutePath().normalize().toString().replace('\\', '/'); }

    /** java.util.Properties escaping for values (':' '=' and '\' would be misread otherwise). */
    static String esc(String v) {
        StringBuilder b = new StringBuilder();
        for (char c : v.toCharArray()) {
            if (c == '\\' || c == ':' || c == '=' || c == '#' || c == '!') b.append('\\');
            b.append(c);
        }
        return b.toString();
    }
}
