package alloy.shim;

import alloy.fabinst.ArgFile;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Fabric instance mode inside Fabric Loader: Alloy's files dropped into a plain Fabric Loader instance
 * of a third party launcher. Runs from ForgeModsAdapter's constructor, the earliest point a mod's code runs (Fabric
 * instantiates language adapters before Mixin starts and before it adds the game jars to Knot).
 *
 * Off unless the game dir has .alloy/fabric-instance.properties (and -Dalloy.fabricInstance is not false). Then:
 *   1. the Forge runtime jars of .alloy/client.args that the JVM's class path lacks are added to Knot and java.class.path
 *      (the same thing the launch file's class path gives, only later: Fabric would have made them misc game libraries);
 *   2. JVM properties of client.args that are not set and still matter this late (alloy.*, user.language/region) are set;
 *   3. when fabric.gameJarPath is not set (no Java argument and no agent), the vanilla client jar Fabric picked is replaced
 *      by the hybrid game jar in Fabric's game provider ("swap"; -Dalloy.fabricInstance.swap=false or swap=false in the
 *      marker turns it off). This reads private fields of Fabric Loader 0.20.1 and is refused on any other version.
 * Step 1 also installs the jars' file system providers (Forge's union file system), see installFileSystems.
 * With the agent (alloy-agent.jar) on 26.x this class still does step 1 (the agent only runs the prep and sets the
 * properties); on 1.x the agent already put everything in the system class loader, so nothing is missing here.
 */
final class FabricInstance {
    static final String SWAP_LOADER = "0.20.1";

    private FabricInstance() { }

    static void apply(Path game) throws Exception {
        if (!ArgFile.on(game)) return;
        Properties marker = new Properties();
        try (InputStream in = Files.newInputStream(game.resolve(ArgFile.MARKER))) { marker.load(in); }
        List<String> tok = ArgFile.read(game);
        boolean agent = Boolean.getBoolean("alloy.fabricInstance.agent");
        // 2. late properties (the agent sets them all before Fabric; here only what is read after this point)
        for (Map.Entry<String, String> e : ArgFile.properties(tok).entrySet()) {
            String k = e.getKey();
            if (System.getProperty(k) != null) continue;
            if (k.startsWith("alloy.") || k.equals("user.language") || k.equals("user.region")) System.setProperty(k, e.getValue());
        }
        Locale.setDefault(new Locale(System.getProperty("user.language", "en"), System.getProperty("user.region", "US")));
        // 3. game jar swap
        String gjp = System.getProperty("fabric.gameJarPath");
        String swapOff = System.getProperty("alloy.fabricInstance.swap", marker.getProperty("swap", "true"));
        String swapped = null;
        if (gjp == null) {
            if ("false".equalsIgnoreCase(swapOff))
                throw new IllegalStateException("Alloy: this Fabric instance has no -Dfabric.gameJarPath and the game jar swap is off; "
                        + "add -javaagent:.alloy/alloy-agent.jar to the instance's Java arguments");
            String hybrid = ArgFile.properties(tok).get("fabric.gameJarPath");
            if (hybrid == null) throw new IllegalStateException("Alloy: .alloy/client.args names no fabric.gameJarPath");
            swapped = swapGameJar(game.resolve(hybrid).toAbsolutePath().normalize());
        }
        // 1. Forge runtime on Knot's class path
        String jcp = System.getProperty("java.class.path", "");
        List<Path> add = ArgFile.missing(ArgFile.classpath(tok), jcp, game);
        StringBuilder cp = new StringBuilder(jcp);
        int n = 0;
        for (Path p : add) {
            if (!Files.exists(p)) throw new IllegalStateException("Alloy: " + p + " from .alloy/client.args is missing; run the Alloy prep again");
            FabricLauncherBase.getLauncher().addToClassPath(p);
            cp.append(File.pathSeparator).append(p);
            n++;
        }
        System.setProperty("java.class.path", cp.toString());
        int fs = add.isEmpty() ? 0 : installFileSystems(add);
        System.out.println("[Alloy] Fabric instance mode: " + n + " Forge runtime jar(s) added to Knot, " + fs + " file system provider(s) installed"
                + (swapped != null ? "; game jar swapped: " + swapped : "; game jar from fabric.gameJarPath (" + (agent ? "agent" : "Java argument") + ")"));
    }

    /**
     * FileSystemProvider.installedProviders() only looks in the system class loader, which a mod cannot extend, so Forge's
     * union file system (securemodules) and the jar-in-jar ones would be missing ("Couldn't find UnionFileSystemProvider").
     * The providers named in the added jars' META-INF/services are made from Knot and appended to the JDK's cached list
     * (sun.misc.Unsafe; the vanilla JVM arguments allow it with --sun-misc-unsafe-memory-access=allow).
     */
    static int installFileSystems(List<Path> jars) throws Exception {
        List<java.nio.file.spi.FileSystemProvider> have = java.nio.file.spi.FileSystemProvider.installedProviders();
        java.util.Set<String> schemes = new java.util.HashSet<>();
        for (java.nio.file.spi.FileSystemProvider p : have) schemes.add(p.getScheme());
        List<java.nio.file.spi.FileSystemProvider> add = new java.util.ArrayList<>();
        ClassLoader knot = FabricLauncherBase.getLauncher().getTargetClassLoader();
        for (Path j : jars) {
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(j.toFile())) {
                java.util.zip.ZipEntry e = z.getEntry("META-INF/services/java.nio.file.spi.FileSystemProvider");
                if (e == null) continue;
                String text;
                try (InputStream in = z.getInputStream(e)) { text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
                for (String line : text.split("\\R")) {
                    String c = line.replaceAll("#.*", "").trim();
                    if (c.isEmpty()) continue;
                    java.nio.file.spi.FileSystemProvider p = (java.nio.file.spi.FileSystemProvider) Class.forName(c, true, knot).getDeclaredConstructor().newInstance();
                    if (schemes.add(p.getScheme())) add.add(p);
                }
            }
        }
        if (add.isEmpty()) return 0;
        List<java.nio.file.spi.FileSystemProvider> all = new java.util.ArrayList<>(have);
        all.addAll(add);
        Field f = java.nio.file.spi.FileSystemProvider.class.getDeclaredField("installedProviders");
        sun.misc.Unsafe u = unsafe();
        @SuppressWarnings("deprecation") Object base = u.staticFieldBase(f);
        @SuppressWarnings("deprecation") long off = u.staticFieldOffset(f);
        u.putObjectVolatile(base, off, java.util.Collections.unmodifiableList(all));
        return add.size();
    }

    static sun.misc.Unsafe unsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    /** Replaces the vanilla client jar in Fabric's MinecraftGameProvider (and java.class.path) by the hybrid jar. */
    static String swapGameJar(Path hybrid) throws Exception {
        String v = (String) FabricLoaderImpl.class.getField("VERSION").get(null);   // not the constant: javac would inline it
        if (!SWAP_LOADER.equals(v))
            throw new IllegalStateException("Alloy: the in-process game jar swap needs Fabric Loader " + SWAP_LOADER + ", this instance has " + v
                    + "; add -javaagent:.alloy/alloy-agent.jar to the instance's Java arguments or set the instance's loader to " + SWAP_LOADER);
        if (!Files.isRegularFile(hybrid)) throw new IllegalStateException("Alloy: " + hybrid + " is missing; run the Alloy prep again");
        Object provider = FabricLoaderImpl.INSTANCE.getGameProvider();
        Class<?> pc = provider.getClass();
        if (!pc.getName().equals("net.fabricmc.loader.impl.game.minecraft.MinecraftGameProvider"))
            throw new IllegalStateException("Alloy: unexpected game provider " + pc.getName());
        @SuppressWarnings("unchecked")
        List<Path> jars = (List<Path>) field(pc, "gameJars").get(provider);
        if (jars.size() != 1) throw new IllegalStateException("Alloy: expected one game jar, Fabric has " + jars);
        Path vanilla = jars.get(0);
        jars.set(0, hybrid);
        // Fabric patched the entry classes (Main, Minecraft) from the vanilla bytes; build them again from the hybrid jar,
        // or Fabric's copies would hide Forge's patches of those classes
        Object transformer = field(pc, "transformer").get(provider);
        Class<?> tc = transformer.getClass();
        field(tc, "entrypointsLocated").setBoolean(transformer, false);
        field(tc, "patchedClasses").set(transformer, null);
        tc.getMethod("locateEntrypoints", net.fabricmc.loader.impl.launch.FabricLauncher.class, List.class)
                .invoke(transformer, FabricLauncherBase.getLauncher(), jars);
        String abs = vanilla.toAbsolutePath().normalize().toString();
        StringBuilder cp = new StringBuilder();
        for (String s : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (s.isBlank()) continue;
            boolean same;
            try { same = Path.of(s).toAbsolutePath().normalize().toString().equals(abs); } catch (Exception e) { same = false; }
            if (cp.length() > 0) cp.append(File.pathSeparator);
            cp.append(same ? hybrid.toString() : s);
        }
        System.setProperty("java.class.path", cp.toString());
        System.setProperty("fabric.gameJarPath", hybrid.toString());   // read by Alloy's own code (FML's minecraft locator)
        return vanilla.getFileName() + " -> " + hybrid.getFileName();
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
