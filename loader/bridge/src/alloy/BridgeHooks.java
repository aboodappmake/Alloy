package alloy;

import cpw.mods.modlauncher.api.IModuleLayerManager;

import java.nio.file.Path;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/**
 * Static hooks that tools/PrepFml bakes into copies of Forge's loader jars (fmlloader, fmlcore, javafmllanguage,
 * mclanguage, modlauncher). Baking them in, instead of mixing into those classes at runtime, means they hold no
 * matter how early a Forge mod's mixin plugin touches FML (some do during Mixin startup, before any mixin exists).
 * Every method here has the exact shape of the call it replaces (receiver first for instance calls).
 */
public final class BridgeHooks {
    private BridgeHooks() {}

    /** ServiceLoader.load(ModuleLayer, Class): services are on Knot's classpath, not in a layer. */
    public static <S> ServiceLoader<S> load(ModuleLayer layer, Class<S> c) {
        return ServiceLoader.load(c, new ForgeServices(Isolation.forgeLoader()));
    }

    /**
     * The loader FML's service lookups use: hides META-INF/services files of Fabric mods (mods/, .fabric/). Real Forge never
     * reads those jars. A multi-loader jar loaded as a Fabric mod can still list a Forge service built for another Forge
     * (MineDave's Preloading Tricks lists an IModLocator that extends a class Forge 64 no longer has), and FML's discovery
     * then stopped at start with NoClassDefFoundError.
     */
    static final class ForgeServices extends ClassLoader {
        static { ClassLoader.registerAsParallelCapable(); }
        ForgeServices(ClassLoader parent) { super("alloy-forge-services", parent); }

        @Override public java.util.Enumeration<java.net.URL> getResources(String name) throws java.io.IOException {
            java.util.Enumeration<java.net.URL> all = getParent().getResources(name);
            if (!name.startsWith("META-INF/services/")) return all;
            java.util.List<java.net.URL> keep = new java.util.ArrayList<>();
            while (all.hasMoreElements()) {
                java.net.URL u = all.nextElement();
                Path jar = jarOf(u);
                if (jar != null && fabricJar(jar)) {
                    String n = String.valueOf(jar.getFileName());
                    if (skippedLogged.add("svc:" + n + name))
                        Bridge.LOG.info("Alloy: {} is a Fabric mod; Forge does not read its {} (as on real Forge)", n, name.substring(18));
                    continue;
                }
                keep.add(u);
            }
            return java.util.Collections.enumeration(keep);
        }

        private static Path jarOf(java.net.URL u) {
            try {
                String s = u.toString();
                if (s.startsWith("jar:")) s = s.substring(4, s.indexOf("!/") > 0 ? s.indexOf("!/") : s.length());
                if (!s.startsWith("file:")) return null;
                return Path.of(java.net.URI.create(s));
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** ModuleLayer.findModule(String): every Forge/mod class is in Knot's unnamed module. */
    public static Optional<Module> findModule(ModuleLayer layer, String name) {
        return Optional.of(BridgeLayers.module());
    }

    /**
     * Class.forName(Module, String): load through Knot. Like the JDK method it does NOT initialize the class (a mod's
     * static initializer must not run before FML has built ModList) and returns null if the class is missing.
     */
    public static Class<?> forName(Module m, String name) {
        try {
            return Class.forName(name, false, Isolation.forgeLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** FMLModContainer.openModules: JPMS Add-Opens/Add-Exports have nothing to act on in the unnamed module. */
    public static void noOpenModules(ModuleLayer layer, Module self, cpw.mods.jarhandling.SecureJar jar) {
    }

    /** Launcher.findLayerManager() body. */
    public static Optional<IModuleLayerManager> layers() {
        return Optional.of(BridgeLayers.INSTANCE);
    }

    /** ModsFolderLocator(): FML reads forgemods/, Fabric reads mods/. Replaces FMLPaths.MODSDIR.get(). */
    public static Path forgeModsDir(net.minecraftforge.fml.loading.FMLPaths p) {
        return net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve("forgemods");
    }

    //#if versioninfo   (launch120.py keeps this block for Forge 49 only: Forge 47 has no JarVersionLookupHandler.Info)
    /**
     * JarVersionLookupHandler.getInfo(Class) prefix: ModLauncher fills java.lang.Package from per-package manifest
     * sections, Knot does not. Replaces the whole method body; falls back to getInfo(Package) like the original.
     */
    public static net.minecraftforge.fml.loading.JarVersionLookupHandler.Info versionInfo(Class<?> clazz) {
        var fromPkg = net.minecraftforge.fml.loading.JarVersionLookupHandler.getInfo(clazz.getPackage());
        if (clazz.getPackage() != null && clazz.getPackage().getImplementationVersion() != null) return fromPkg;
        try {
            var src = clazz.getProtectionDomain().getCodeSource();
            if (src == null) return fromPkg;
            Path p = Path.of(src.getLocation().toURI());
            if (!p.toString().endsWith(".jar")) return fromPkg;
            try (JarFile jf = new JarFile(p.toFile())) {
                var mf = jf.getManifest();
                if (mf == null) return fromPkg;
                Attributes sec = mf.getAttributes(clazz.getPackageName().replace('.', '/') + "/"), main = mf.getMainAttributes();
                return new net.minecraftforge.fml.loading.JarVersionLookupHandler.Info(
                        new net.minecraftforge.fml.loading.JarVersionLookupHandler.Version(get(sec, main, "Specification-Title"), get(sec, main, "Specification-Vendor"), get(sec, main, "Specification-Version")),
                        new net.minecraftforge.fml.loading.JarVersionLookupHandler.Version(get(sec, main, "Implementation-Title"), get(sec, main, "Implementation-Vendor"), get(sec, main, "Implementation-Version")));
            }
        } catch (Exception e) {
            return fromPkg;
        }
    }

    //#endif

    /** Forge 47 JarVersionLookupHandler.getImplementationVersion(Class): same idea as versionInfo, one attribute. */
    public static Optional<String> implVersion(Class<?> c) { return attr(c, "Implementation-Version"); }

    /** Forge 47 JarVersionLookupHandler.getImplementationTitle(Class). */
    public static Optional<String> implTitle(Class<?> c) { return attr(c, "Implementation-Title"); }

    /** Forge 47 JarVersionLookupHandler.getSpecificationVersion(Class). */
    public static Optional<String> specVersion(Class<?> c) { return attr(c, "Specification-Version"); }

    private static Optional<String> attr(Class<?> clazz, String key) {
        Package pkg = clazz.getPackage();
        if (pkg != null) {
            String v = key.equals("Implementation-Version") ? pkg.getImplementationVersion()
                    : key.equals("Implementation-Title") ? pkg.getImplementationTitle() : pkg.getSpecificationVersion();
            if (v != null) return Optional.of(v);
        }
        try {
            var src = clazz.getProtectionDomain().getCodeSource();
            if (src == null) return Optional.empty();
            Path p = Path.of(src.getLocation().toURI());
            if (!p.toString().endsWith(".jar")) return Optional.empty();
            try (JarFile jf = new JarFile(p.toFile())) {
                var mf = jf.getManifest();
                if (mf == null) return Optional.empty();
                var v = get(mf.getAttributes(clazz.getPackageName().replace('.', '/') + "/"), mf.getMainAttributes(), key);
                // Forge 47.4.29's javafmllanguage jar has no version for its own package (only for net/minecraftforge/fml/loading/).
                // For a language provider without one, answer what FML itself uses for an exploded provider: the Forge major version.
                if (v.isEmpty() && key.equals("Implementation-Version") && "LANGPROVIDER".equals(mf.getMainAttributes().getValue("FMLModType")))
                    v = forgeMajor();
                return v;
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<String> forgeMajor() {
        try {
            Object vi = net.minecraftforge.fml.loading.FMLLoader.class.getMethod("versionInfo").invoke(null);
            String fv = (String) vi.getClass().getMethod("forgeVersion").invoke(vi);
            return Optional.of(fv.split("[.]")[0]);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static volatile java.util.Map<String, String> srgNames;

    /**
     * Forge 47 Launcher.findNameMapping(target) body: FML's srg -> runtime name function (ObfuscationReflectionHelper,
     * JS coremods' ASMAPI.mapMethod/mapField, the access transformer name handler). SRG ids are unique tokens, so the
     * domain is not needed. -Dalloy.srgNames=FILE (lines "srg name", tab or space separated) gives the names of a
     * Mojang-named game; without it names stay as they are (game and Forge both in SRG).
     */
    public static Optional<java.util.function.BiFunction<cpw.mods.modlauncher.api.INameMappingService.Domain, String, String>> nameMapping(
            cpw.mods.modlauncher.Launcher self, String target) {
        java.util.Map<String, String> m = srgNames;
        if (m == null) {
            synchronized (BridgeHooks.class) {
                if ((m = srgNames) == null) srgNames = m = readSrgNames(System.getProperty("alloy.srgNames"));
            }
        }
        final java.util.Map<String, String> map = m;
        return Optional.of((domain, name) -> name == null ? null : map.getOrDefault(name, name));
    }

    private static java.util.Map<String, String> readSrgNames(String file) {
        if ((file == null || file.isBlank()) && "mcp".equals(System.getProperty("alloy.naming"))) return srg2offCsv();
        if (file == null || file.isBlank()) return java.util.Map.of();
        var out = new java.util.HashMap<String, String>();
        try {
            for (String line : java.nio.file.Files.readAllLines(Path.of(file))) {
                String[] p = line.trim().split("[\\t ]+");
                if (p.length >= 2 && !p[0].startsWith("#")) out.put(p[0], p[1]);
            }
            Bridge.LOG.info("Alloy: {} srg -> runtime names from {}", out.size(), file);
        } catch (Exception e) {
            Bridge.LOG.error("Alloy: cannot read srg names from {}", file, e);
        }
        return out;
    }

    /**
     * Forge 50 to 61 run in Mojang names but still write their access transformers (and some coremods) in SRG names.
     * Real Forge translates them with MCPNamingService, which reads fields.csv and methods.csv (searge,name,...) from
     * the class path: the installer's mcp_config srg2off jar, one of Forge's libraries. Same files here.
     */
    private static java.util.Map<String, String> srg2offCsv() {
        var out = new java.util.HashMap<String, String>();
        for (String f : new String[]{"fields.csv", "methods.csv"}) {
            var url = BridgeHooks.class.getClassLoader().getResource(f);
            if (url == null) continue;
            try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(url.openStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                r.readLine();   // header
                for (String line; (line = r.readLine()) != null; ) {
                    String[] p = line.split(",", 3);
                    if (p.length >= 2) out.put(p[0], p[1]);
                }
            } catch (Exception e) {
                Bridge.LOG.error("Alloy: cannot read srg names from {}", url, e);
            }
        }
        Bridge.LOG.info("Alloy: {} srg -> runtime names from the class path's fields.csv/methods.csv (Forge's srg2off mappings)", out.size());
        return out;
    }

    private static Optional<String> get(Attributes sec, Attributes main, String k) {
        String v = sec != null ? sec.getValue(k) : null;
        return Optional.ofNullable(v != null ? v : main.getValue(k));
    }

    /**
     * ModDiscoverer -> IDependencyLocator.scanMods: drop jar-in-jar copies of Forge's MixinExtras (Fabric Loader
     * provides MixinExtras; a second copy as a Forge "mod" cannot load and would fail mod construction).
     */
    public static java.util.List<net.minecraftforge.forgespi.locating.IModFile> scanDependencies(
            net.minecraftforge.forgespi.locating.IDependencyLocator locator, Iterable<net.minecraftforge.forgespi.locating.IModFile> loaded) {
        var out = new java.util.ArrayList<>(locator.scanMods(loaded));
        out.removeIf(f -> {
            if (f == null) return true;                       // JarJar can return null for an unselectable entry
            String n = f.getFileName().toLowerCase(java.util.Locale.ROOT);
            n = n.substring(n.lastIndexOf('/') + 1);          // nested jars are reported as "/name.jar"
            return n.startsWith("mixinextras");
        });
        return out;
    }

    private static volatile java.util.Set<Path> fabricJars;
    private static final java.util.Set<String> skippedLogged = java.util.concurrent.ConcurrentHashMap.newKeySet();

    //#if serviceprovider   (launch120.py keeps this block only where ModDirTransformerDiscoverer.isServiceProvider(Path) exists and ClasspathLocator calls it: Forge 49+; 47 and 48 have no such method)
    /**
     * ClasspathLocator.scanMods -> ModDirTransformerDiscoverer.isServiceProvider(path): also true for a jar that Fabric
     * Loader already loads as a Fabric mod. Forge's classpath locator reads every META-INF/mods.toml on Knot's classpath,
     * so without this it also picks up Fabric mods from mods/ that carry a mods.toml (multi-loader jars, and nested jars
     * Fabric Loader extracted). On real Forge those jars are never seen by Forge. A multi-loader jar then loads on both
     * sides, and a broken one (Fabulously Optimized's MixinTrace has an unfilled template mods.toml) stops the game.
     */
    public static boolean classpathSkip(Path p) {
        if (net.minecraftforge.fml.loading.ModDirTransformerDiscoverer.isServiceProvider(p)) return true;
        if (!fabricJar(p)) return false;
        String n = String.valueOf(p.getFileName());
        if (skippedLogged.add(n)) Bridge.LOG.info("Alloy: {} is a Fabric mod (mods/); Forge's classpath scan skips it, as real Forge never sees it", n);
        return true;
    }
    //#endif

    /** a jar Fabric Loader loads as a Fabric mod (its own jars, mods/, nested jars in .fabric/) */
    static boolean fabricJar(Path p) {
        java.util.Set<Path> own = fabricJars;
        if (own == null) fabricJars = own = fabricModPaths();
        Path n0 = norm(p);
        return own.contains(n0) || underFabricDirs(n0);
    }

    private static java.util.Set<Path> fabricModPaths() {
        java.util.Set<Path> out = new java.util.HashSet<>();
        try {
            for (var mc : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
                if ("builtin".equals(mc.getMetadata().getType())) continue;   // minecraft, java, fabricloader
                try {
                    for (Path q : mc.getOrigin().getPaths()) out.add(norm(q));
                } catch (UnsupportedOperationException e) {
                    // nested origin without a path of its own: nothing to skip
                }
            }
        } catch (Throwable t) {
            Bridge.LOG.warn("Alloy: could not list Fabric mod jars for Forge's classpath scan: {}", t.toString());
        }
        return out;
    }

    private static volatile Path[] fabricDirs;

    /** the game's mods/ (Fabric side; Forge jars dropped there are mirrored into forgemods/) and .fabric/ (nested jars Fabric Loader extracted) */
    private static boolean underFabricDirs(Path p) {
        Path[] d = fabricDirs;
        if (d == null) {
            try {
                Path g = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
                d = new Path[] {norm(g.resolve("mods")), norm(g.resolve(".fabric"))};
            } catch (Throwable t) {
                d = new Path[0];
            }
            fabricDirs = d;
        }
        for (Path x : d) if (p.startsWith(x)) return true;
        return false;
    }

    private static Path norm(Path p) {
        try { return p.toRealPath(); } catch (Exception e) { return p.toAbsolutePath().normalize(); }
    }

    private static final java.util.Set<String> keptFabric = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * ClasspathLocator.getUrls -> ClassLoader.getResources(name) (META-INF/mods.toml and META-INF/MANIFEST.MF). FML's
     * ClasspathLocator reads every jar on its class loader, which under Alloy is Knot, so a multi-loader jar in mods/
     * (fabric.mod.json + mods.toml, e.g. Rhino) was loaded by Fabric Loader AND as a Forge mod. Alloy's rule is one jar,
     * one side (ForgeInMods: a multi-loader jar in mods/ is Fabric; set it to forge to move it to forgemods/), so drop
     * the jars Fabric Loader loaded as mods. Forge mods (forgemods/, Forge's own jars) are not Fabric mods and stay.
     */
    public static java.util.Enumeration<java.net.URL> classpathResources(ClassLoader cl, String name) throws java.io.IOException {
        var urls = java.util.Collections.list(cl.getResources(name));
        try {
            java.util.Set<Path> fabric = new java.util.HashSet<>();
            for (var mod : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
                if ("builtin".equals(mod.getMetadata().getType())) continue;   // minecraft, java: the game jar has its own locator
                if (mod.getOrigin().getKind() != net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH) continue;
                for (Path p : mod.getOrigin().getPaths()) fabric.add(p.toAbsolutePath().normalize());
            }
            var dropped = new java.util.ArrayList<String>();
            urls.removeIf(u -> {
                Path p = jarOf(u);
                if (p == null || !fabric.contains(p)) return false;
                if (name.equals("META-INF/mods.toml") && keptFabric.add(p.getFileName().toString())) dropped.add(p.getFileName().toString());
                return true;
            });
            if (!dropped.isEmpty())
                Bridge.LOG.info("Alloy: {} Fabric mod jar(s) also carry Forge metadata; loaded by Fabric only, as Alloy puts a multi-loader jar in mods/ on the Fabric side: {}",
                        dropped.size(), dropped);
        } catch (Throwable t) {
            Bridge.LOG.warn("Alloy: could not filter Fabric mod jars out of Forge's classpath scan: {}", t.toString());
        }
        return java.util.Collections.enumeration(urls);
    }

    /** jar:file:/x.jar!/entry -> x.jar (null for directories and other URL kinds) */
    private static Path jarOf(java.net.URL u) {
        try {
            if (!"jar".equalsIgnoreCase(u.getProtocol())) return null;
            String f = u.getFile();
            int i = f.indexOf("!/");
            return i < 0 ? null : Path.of(java.net.URI.create(f.substring(0, i))).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static volatile boolean movedWarned;

    /**
     * night-config's ConfigWriter.write(..., WritingMode.REPLACE_ATOMIC) ends with Files.move(tmp, file, ATOMIC_MOVE, ...). On Windows
     * that move fails with AccessDeniedException while another handle has the config open (Forge's config tracker / file watcher
     * reading it at the same moment). Plain Forge 64 + Forge Config API Port 26.1.5 crash there too (TslatEntityStatus, Open Parties
     * and Claims). ForgeLaunchPlugins redirects that one call here: retry briefly, then write the new content into the existing
     * file (Java opens files with share-read/share-write, so this works while a reader holds it) and drop the temp file.
     */
    public static java.nio.file.Path configMove(java.nio.file.Path src, java.nio.file.Path dst, java.nio.file.CopyOption... opts) throws java.io.IOException {
        java.nio.file.FileSystemException last = null;
        for (int i = 0; i < 8; i++) {
            try {
                return java.nio.file.Files.move(src, dst, opts);
            } catch (java.nio.file.AccessDeniedException e) {
                last = e;
                try { Thread.sleep(25L * (i + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        try {
            java.nio.file.Files.write(dst, java.nio.file.Files.readAllBytes(src));
            java.nio.file.Files.deleteIfExists(src);
            if (!movedWarned) {
                movedWarned = true;
                Bridge.LOG.warn("Alloy: config {} was locked by another reader; wrote it in place instead of an atomic replace (Windows file lock, also seen on plain Forge)", dst.getFileName());
            }
            return dst;
        } catch (java.io.IOException e) {
            if (last != null) e.addSuppressed(last);
            throw e;
        }
    }
}
