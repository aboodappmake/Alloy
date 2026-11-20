package alloy.launch;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * Real entry point, called by {@link AlloyLaunch} once the Java version is known to be new enough.
 * Main class of the Alloy launcher version (version.json "mainClass"), the way ForgeWrapper is for Forge in Prism.
 * Runs in the game JVM, before Fabric:
 *   1. finds the libraries dir (-Dalloy.librariesDir, else from this jar's maven path), the Alloy installer jar in it,
 *      the game dir (--gameDir) and the vanilla client jar (-Dalloy.minecraftJar, else the class path entry that
 *      holds net/minecraft/client/main/Main.class),
 *   2. runs  alloy-installer --launch-prep  in an isolated class loader (install processors if the launcher did not
 *      run them, bridge mod, alloy-prep for this instance's Forge mods; ~0.3 s when nothing changed),
 *   3. sets fabric.gameJarPath to the instance's hybrid game jar and calls Fabric's KnotClient.main with the same args.
 * -Dalloy.skipPrep=true skips step 2 (debugging only).
 */
public final class AlloyLaunchMain {
    private AlloyLaunchMain() { }

    public static void main(String[] args) throws Throwable {
        Properties meta = new Properties();
        try (InputStream in = AlloyLaunchMain.class.getResourceAsStream("alloy-launch.properties")) {
            if (in == null) throw new IllegalStateException("alloy-launch.properties missing: this alloy-launch jar was not built by --launcher-profile");
            meta.load(in);
        }
        Path game = Path.of(arg(args, "--gameDir", System.getProperty("user.dir"))).toAbsolutePath().normalize();
        Path libs = librariesDir();
        Path installer = libs.resolve(mavenPath(meta.getProperty("installer")));
        if (!Files.isRegularFile(installer)) throw new IllegalStateException("Alloy installer jar missing: " + installer + " (reinstall Alloy in the launcher)");
        Path mc = minecraftJar();
        Path profile = game.resolve(".alloy/install_profile.json");
        byte[] pb;
        try (InputStream in = AlloyLaunchMain.class.getResourceAsStream("install_profile.json")) { pb = in.readAllBytes(); }
        Files.createDirectories(profile.getParent());
        if (!(Files.exists(profile) && Arrays.equals(Files.readAllBytes(profile), pb))) Files.write(profile, pb);

        if (!Boolean.getBoolean("alloy.skipPrep")) {
            List<String> a = new ArrayList<>(List.of("--launch-prep", game.toString(), "--libraries", libs.toString(), "--profile", profile.toString()));
            if (mc != null) a.addAll(List.of("--minecraft-jar", mc.toString()));
            ClassLoader old = Thread.currentThread().getContextClassLoader();
            try (URLClassLoader cl = new URLClassLoader(new URL[]{installer.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                Thread.currentThread().setContextClassLoader(cl);
                Class.forName("alloy.installer.Main", true, cl).getMethod("main", String[].class).invoke(null, (Object) a.toArray(new String[0]));
            } catch (InvocationTargetException e) {
                throw e.getCause();
            } finally {
                Thread.currentThread().setContextClassLoader(old);
            }
        }
        if (System.getProperty("fabric.gameJarPath") == null)
            System.setProperty("fabric.gameJarPath", game.resolve(".alloy/hybrid_client_game.jar").toString());
        if (System.getProperty("alloy.version") == null) System.setProperty("alloy.version", meta.getProperty("alloyVersion"));
        try {
            Class.forName("net.fabricmc.loader.impl.launch.knot.KnotClient").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    static String arg(String[] a, String name, String def) {
        for (int i = 0; i + 1 < a.length; i++) if (a[i].equals(name)) return a[i + 1];
        return def;
    }

    static String mavenPath(String coord) {
        String[] p = coord.split(":");
        return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + p[1] + "-" + p[2] + (p.length > 3 ? "-" + p[3] : "") + ".jar";
    }

    /** -Dalloy.librariesDir (official launcher / Modrinth fill ${library_directory}), else 5 levels above this jar. */
    static Path librariesDir() throws Exception {
        String p = System.getProperty("alloy.librariesDir");
        if (p != null && !p.isBlank() && !p.contains("${") && Files.isDirectory(Path.of(p))) return Path.of(p).toAbsolutePath().normalize();
        Path self = Path.of(AlloyLaunchMain.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
        // <libs>/net/alloy/alloy-launch/<ver>/alloy-launch-<ver>.jar
        Path d = self;
        for (int i = 0; i < 5 && d != null; i++) d = d.getParent();
        if (d == null || !Files.isDirectory(d)) throw new IllegalStateException("cannot find the libraries folder from " + self + "; set -Dalloy.librariesDir");
        return d;
    }

    static Path minecraftJar() {
        String p = System.getProperty("alloy.minecraftJar");
        if (p != null && !p.isBlank() && Files.isRegularFile(Path.of(p))) return Path.of(p);
        for (String e : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!e.endsWith(".jar") || e.contains("hybrid_client_game")) continue;
            try (ZipFile z = new ZipFile(e)) {
                if (z.getEntry("net/minecraft/client/main/Main.class") != null && z.getEntry("version.json") != null) return Path.of(e);
            } catch (Exception ignored) { }
        }
        return null;
    }
}
