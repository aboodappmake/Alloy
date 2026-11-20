package alloy.agent;

import alloy.fabinst.ArgFile;

import java.io.File;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * alloy-agent.jar: Fabric instance mode with one Java argument, {@code -javaagent:.alloy/alloy-agent.jar}. A Java agent's premain runs before Fabric Loader's main, so it can do what Alloy's own launch
 * files do, without touching any Fabric internals:
 *   1. run the Alloy prep (java -jar .alloy/alloy-installer.jar --prep GAMEDIR) in a child JVM of the same Java, like
 *      Prism's pre-launch command; a failed prep stops the start with its exit code;
 *   2. set the JVM properties of .alloy/client.args that are not set yet (fabric.gameJarPath, the Log4j context selector,
 *      alloy.*, fabric.systemLibraries, ...), never the launcher's natives folders;
 *   3. Minecraft 1.x: append the jars of client.args' class path that this JVM lacks to the system class loader and to
 *      java.class.path, so Fabric sees them exactly like jars on the launch file's class path (26.x: the forgemods language
 *      adapter adds them to Knot instead, see alloy.shim.FabricInstance; marker key systemLoader=true forces this step);
 *   4. apply client.args' --add-opens / --add-exports (Minecraft 1.20.1) to the unnamed module of the system class loader;
 *   5. Minecraft 1.x (client.args has alloy.mcVersion): point fabric.mappingPath at a file that does not exist, so Fabric loads
 *      no Intermediary mappings and runs in Mojang names like Alloy's own 1.20.1 launch.
 * It does nothing (one log line) without .alloy/fabric-instance.properties in the game dir, with -Dalloy.fabricInstance=false,
 * or when fabric.gameJarPath is already set (an Alloy launch file is in use). The game dir is the working directory, or the
 * --gameDir game argument when the working directory has no marker. Marker keys: prep=false skips step 1.
 */
public final class Premain {
    static final String TAG = "[Alloy agent] ";
    static final String NO_MAPPINGS = ".alloy/no-mappings.tiny";

    private Premain() { }

    public static void premain(String args, Instrumentation inst) {
        try {
            run(inst);
        } catch (Exit e) {
            System.err.println(TAG + e.getMessage());
            System.exit(e.code);
        } catch (Throwable t) {
            System.err.println(TAG + "failed: " + t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    static final class Exit extends RuntimeException {
        final int code;
        Exit(String m, int code) { super(m); this.code = code; }
    }

    /** -Dalloy.agent.mode=prep: written by the official launcher profile (LauncherExport); only step 1, nothing else */
    public static final String LAUNCHER_MARKER = ".alloy/launcher-agent.properties";

    static void run(Instrumentation inst) throws Exception {
        if ("prep".equals(System.getProperty("alloy.agent.mode"))) { launcherPrep(); return; }
        Path self = selfJar();
        Path game = gameDir(Path.of("").toAbsolutePath(), System.getProperty("sun.java.command", ""), self);
        if (game == null || "false".equalsIgnoreCase(System.getProperty("alloy.fabricInstance", ""))) {
            System.out.println(TAG + "no " + ArgFile.MARKER + " here, nothing to do");
            return;
        }
        if (System.getProperty("fabric.gameJarPath") != null) { System.out.println(TAG + "fabric.gameJarPath is set already, nothing to do"); return; }
        long t0 = System.nanoTime();
        boolean installed = false;
        if (!Files.isRegularFile(game.resolve(".alloy").resolve("prep.properties"))) { install(game, self); installed = true; }
        if (!ArgFile.on(game)) throw new Exit("the Alloy install did not write " + ArgFile.MARKER, 1);
        Properties marker = new Properties();
        try (InputStream in = Files.newInputStream(game.resolve(ArgFile.MARKER))) { marker.load(in); }
        if (!installed && !"false".equalsIgnoreCase(System.getProperty("alloy.agent.prep", marker.getProperty("prep", "true")))) prep(game);
        long t1 = System.nanoTime();
        List<String> tok = ArgFile.read(game);
        Map<String, String> set = plan(ArgFile.properties(tok), game, k -> System.getProperty(k) != null);
        for (Map.Entry<String, String> e : set.entrySet()) System.setProperty(e.getKey(), e.getValue());
        System.setProperty("alloy.fabricInstance.agent", "true");
        String mc = ArgFile.properties(tok).get("alloy.mcVersion");
        boolean system = (mc != null && mc.startsWith("1.")) || "true".equalsIgnoreCase(marker.getProperty("systemLoader", ""));
        List<Path> add = List.of();
        if (system) {   // 1.x: securejarhandler must be in the system class loader (fabric.systemLibraries), so all of them go there
            String jcp = System.getProperty("java.class.path", "");
            add = ArgFile.missing(ArgFile.classpath(tok), jcp, game);
            StringBuilder cp = new StringBuilder(jcp);
            for (Path p : add) {
                if (!Files.isRegularFile(p)) throw new Exit(p + " from .alloy/client.args is missing; run the Alloy prep again", 1);
                inst.appendToSystemClassLoaderSearch(new JarFile(p.toFile()));
                cp.append(File.pathSeparator).append(p);
            }
            System.setProperty("java.class.path", cp.toString());
        }
        // 26.x: the Forge runtime is left to the forgemods language adapter (alloy.shim.FabricInstance adds it to Knot). In the
        // system class loader, ModLauncher would start a second Log4j next to Fabric's copy in Knot (its
        // log4j2.component.properties selector, then ClassCastExceptions for every Log4j plugin).
        int mods = openModules(inst, ArgFile.moduleFlags(tok));
        System.out.printf("%sFabric instance mode: prep %d ms, %d propert%s set, %s, %d module flag(s), game jar %s%n",
                TAG, (t1 - t0) / 1_000_000, set.size(), set.size() == 1 ? "y" : "ies",
                system ? add.size() + " jar(s) added to the class path" : "Forge runtime left to the language adapter", mods, System.getProperty("fabric.gameJarPath"));
    }

    /** The game dir: cwd when it has the marker, else the --gameDir value of the launch command, else null. */
    public static Path gameDir(Path cwd, String command) {
        return gameDir(cwd, command, null);
    }

    /**
     * Like gameDir(cwd, command), and also the game dir the agent jar itself sits in (GAME/.alloy/alloy-agent.jar) when that
     * is the cwd or the --gameDir: a fresh instance whose only Alloy file is the agent. A shared agent elsewhere needs the marker.
     */
    public static Path gameDir(Path cwd, String command, Path self) {
        if (Files.isRegularFile(cwd.resolve(ArgFile.MARKER))) return cwd;
        Path gd = null;
        List<String> t = ArgFile.tokens(command);
        for (int i = 0; i + 1 < t.size(); i++) if (t.get(i).equals("--gameDir")) { gd = Path.of(t.get(i + 1)).toAbsolutePath().normalize(); break; }
        if (gd != null && Files.isRegularFile(gd.resolve(ArgFile.MARKER))) return gd;
        if (self != null && self.getParent() != null && self.getParent().getFileName() != null
                && self.getParent().getFileName().toString().equals(".alloy") && self.getParent().getParent() != null) {
            Path home = self.getParent().getParent().toAbsolutePath().normalize();
            if (home.equals(cwd.toAbsolutePath().normalize()) || home.equals(gd)) return home;
        }
        return null;
    }

    static Path selfJar() {
        try {
            return Path.of(Premain.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    /** The Minecraft version of the vanilla client jar on a class path (its version.json "id"), or null. */
    public static String mcVersion(String classpath) {
        for (String s : classpath.split(File.pathSeparator)) {
            if (!s.endsWith(".jar")) continue;
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(s)) {
                if (z.getEntry("net/minecraft/client/main/Main.class") == null) continue;
                java.util.zip.ZipEntry e = z.getEntry("version.json");
                if (e == null) continue;
                String j;
                try (InputStream in = z.getInputStream(e)) { j = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(j);
                if (m.find()) return m.group(1);
            } catch (Exception ignored) {   // not a jar we can read
            }
        }
        return null;
    }

    /**
     * First start in a fresh Fabric instance: run the Alloy installer into it (--fabric-instance: downloads Mojang's and Forge's
     * files with the installer's pins, writes .alloy/, the bridge and the first prep; the launcher keeps the assets). The
     * installer is .alloy/alloy-installer.jar, or the copy this agent jar carries (/alloy-installer.jar, the full agent).
     */
    static void install(Path game, Path self) throws Exception {
        Path alloy = game.resolve(".alloy");
        Path installer = alloy.resolve("alloy-installer.jar");
        if (!Files.isRegularFile(installer)) {
            try (InputStream in = Premain.class.getResourceAsStream("/alloy-installer.jar")) {
                if (in == null) throw new Exit("first start: no " + installer + " and this agent carries none (use the full alloy-agent.jar)", 1);
                Files.createDirectories(alloy);
                Path tmp = alloy.resolve("alloy-installer.jar.tmp");
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, installer, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        String mc = mcVersion(System.getProperty("java.class.path", ""));
        if (mc == null) throw new Exit("first start: no Minecraft client jar on the class path, cannot tell the Minecraft version", 1);
        System.out.println(TAG + "first start: installing Alloy for Minecraft " + mc + " into " + game + " (Mojang and Forge files are downloaded once)");
        String java = Path.of(System.getProperty("java.home"), "bin", File.separatorChar == '\\' ? "java.exe" : "java").toString();
        List<String> cmd = new ArrayList<>(List.of(java, "-Duser.language=en", "-Duser.region=US", "-jar", installer.toString(),
                "--side", "client", "--dir", game.toString(), "--mc", mc, "--cache", alloy.resolve("cache").toString(),
                "--java", java, "--no-assets", "--fabric-instance"));
        Process p = new ProcessBuilder(cmd).directory(game.toFile()).inheritIO().start();
        int rc = p.waitFor();
        if (rc != 0) throw new Exit("the Alloy install stopped the start (exit code " + rc + "); see the lines above", rc);
    }

    public interface IsSet { boolean test(String key); }

    /**
     * The properties to set: client.args' ones the JVM does not have yet, fabric.gameJarPath made absolute (Fabric resolves
     * it against the working directory, which is not always the game dir), and on 1.x a missing fabric.mappingPath.
     * 26.x: no Log4j context selector. In a Fabric instance Log4j first starts inside Knot there, the selector class then
     * comes from Knot and Log4j prints a ClassCastException plus ~55 error lines (falling back to the default selector);
     * without the property the 26.1.2 self-test result and the error count are the same as with Alloy's own launch file.
     */
    public static Map<String, String> plan(Map<String, String> props, Path game, IsSet isSet) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        String mc = props.get("alloy.mcVersion");
        boolean v1 = mc != null && mc.startsWith("1.");
        for (Map.Entry<String, String> e : props.entrySet()) {
            String k = e.getKey(), v = e.getValue();
            if (isSet.test(k)) continue;
            if (k.equals("log4j2.contextSelector") && !v1) continue;
            if (k.equals("fabric.gameJarPath") || k.equals("fabric.systemLibraries") || k.equals("alloy.srgNames")) v = absolute(game, v);
            out.put(k, v);
        }
        if (v1 && !isSet.test("fabric.mappingPath"))
            out.put("fabric.mappingPath", game.resolve(NO_MAPPINGS).toAbsolutePath().normalize().toString());
        return out;
    }

    static String absolute(Path game, String list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list.split(File.pathSeparator)) {
            if (s.isEmpty()) continue;
            Path p = Path.of(s);
            if (sb.length() > 0) sb.append(File.pathSeparator);
            sb.append((p.isAbsolute() ? p : game.resolve(p)).toAbsolutePath().normalize());
        }
        return sb.toString();
    }

    /**
     * Official Minecraft Launcher (no pre-launch hook): the Alloy profile's JVM arguments are complete already, so the agent
     * only runs the prep for the game dir (the launcher's working directory, or --gameDir) before Fabric starts.
     * -Dalloy.agent.prep=false skips it.
     */
    static void launcherPrep() throws Exception {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path game = launcherGameDir(cwd, System.getProperty("sun.java.command", ""));
        if (game == null) { System.out.println(TAG + "no " + LAUNCHER_MARKER + " in " + cwd + " or --gameDir, no prep"); return; }
        if ("false".equalsIgnoreCase(System.getProperty("alloy.agent.prep", ""))) { System.out.println(TAG + "prep skipped (-Dalloy.agent.prep=false)"); return; }
        long t0 = System.nanoTime();
        prep(game);
        System.out.printf("%sofficial launcher: prep %d ms for %s%n", TAG, (System.nanoTime() - t0) / 1_000_000, game);
    }

    /** the --gameDir of the launch command when it has the launcher marker, else cwd when it has it, else null */
    public static Path launcherGameDir(Path cwd, String command) {
        List<String> t = ArgFile.tokens(command);
        for (int i = 0; i + 1 < t.size(); i++) {
            if (!t.get(i).equals("--gameDir")) continue;
            Path gd = Path.of(t.get(i + 1)).toAbsolutePath().normalize();
            if (Files.isRegularFile(gd.resolve(LAUNCHER_MARKER))) return gd;
            break;
        }
        return Files.isRegularFile(cwd.resolve(LAUNCHER_MARKER)) ? cwd.toAbsolutePath().normalize() : null;
    }

    static void prep(Path game) throws Exception {
        Path installer = game.resolve(".alloy").resolve("alloy-installer.jar");
        if (!Files.isRegularFile(installer)) throw new Exit(installer + " is missing", 1);
        String java = Path.of(System.getProperty("java.home"), "bin", File.separatorChar == '\\' ? "java.exe" : "java").toString();
        List<String> cmd = new ArrayList<>(List.of(java, "-Duser.language=en", "-Duser.region=US", "-jar", installer.toString(), "--prep", game.toString()));
        Process p = new ProcessBuilder(cmd).directory(game.toFile()).inheritIO().start();
        int rc = p.waitFor();
        if (rc != 0) throw new Exit("the Alloy prep stopped the start (exit code " + rc + "); see the lines above", rc);
    }

    /** --add-opens/--add-exports module/package=target, applied to the system class loader's unnamed module. */
    static int openModules(Instrumentation inst, List<String[]> flags) {
        Module unnamed = ClassLoader.getSystemClassLoader().getUnnamedModule();
        int n = 0;
        for (String[] f : flags) {
            String v = f[1];
            int slash = v.indexOf('/'), eq = v.indexOf('=');
            if (slash < 0 || eq < slash) continue;
            Module m = ModuleLayer.boot().findModule(v.substring(0, slash)).orElse(null);
            if (m == null) continue;
            Map<String, Set<Module>> one = new HashMap<>();
            one.put(v.substring(slash + 1, eq), Set.of(unnamed));
            boolean opens = f[0].equals("--add-opens");
            inst.redefineModule(m, Set.of(), opens ? Map.of() : one, opens ? one : Map.of(), Set.of(), Map.of());
            n++;
        }
        return n;
    }
}
