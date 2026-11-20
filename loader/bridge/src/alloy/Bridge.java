package alloy;

import cpw.mods.modlauncher.Environment;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.TypesafeMap;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLConfig;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.fml.loading.targets.CommonLaunchHandler;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Alloy bridge: does the job of Forge's launcher (ModLauncher + FML's transformation service) from inside
 * Fabric Loader, before Minecraft's main runs. Everything after that is Forge's own code: the Forge-patched
 * Minecraft calls ServerModLoader.load(), which runs FML's real mod loading.
 *
 * What it replaces:
 *  - Launcher.INSTANCE: a stand-in with a real Environment + blackboard; findLayerManager() is mixed to return
 *    {@link BridgeLayers} (Fabric has no module layers; everything is in Knot's unnamed module).
 *  - FMLLoader's launch state (dist, naming, game path, launch handler) normally set by FMLServiceProvider.
 *  - Access transformers: applied ahead of time by tools/PrepGame, so FMLLoader.addAccessTransformer is a no-op.
 *  - Mod jars go on Knot's classpath; their MixinConfigs go to Fabric's Mixin.
 */
public final class Bridge implements PreLaunchEntrypoint {
    public static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("Alloy");

    private static volatile boolean started;
    private static Path gameDir;

    @Override
    public void onPreLaunch() {
        init(); // normally already done by EarlyInit during Mixin startup
        awaitScan();
        if (ConflictReport.ON) ConflictReport.auditAndExit(gameDir);
        if (DryBoot.ON) DryBoot.run(gameDir);   // -Dalloy.dryBoot=true: headless check, prints one result line and exits
    }

    /** Starts Forge's FML once. Called from EarlyInit (Mixin startup) and, as a fallback, from preLaunch. */
    public static synchronized void init() {
        // Mixin only logs a WARN when the early plugin throws, so a failure there must not be forgotten: keep it and
        // rethrow from preLaunch, where Fabric stops the game with the real cause instead of a later, misleading crash.
        if (failure != null) throw new RuntimeException("Alloy could not start Forge's FML (see the cause below)", failure);
        if (started) return;
        started = true;
        try {
            run();
        } catch (Throwable t) {
            failure = t;
            LOG.error("Alloy: starting Forge's FML failed", t);
            String why = report("onStartFailure", t);   // classify, write alloy-report.txt, server console block
            throw new RuntimeException("Alloy could not start Forge's FML: " + why + " (details: alloy-report.txt in the game folder)", t);
        }
    }

    static Throwable failure;

    /**
     * Fabric Loader's setGameDir makes gameDir absolute but derives configDir from the raw launch value, so a relative
     * --gameDir (Alloy's own .alloy/client.args has "--gameDir .") gives an absolute getGameDir() and a relative
     * getConfigDir(). A mod that relativizes one against the other then throws "'other' is different type of Path" on
     * Windows (Axiom 6.1.5 EditorUI.init: getGameDir().relativize(getConfigDir()/axiom/imgui.ini)). With an absolute
     * --gameDir (official launcher, Prism) both are absolute already and nothing changes.
     */
    static Path absoluteConfigDir(Path configDir) {
        return configDir == null || configDir.isAbsolute() ? configDir : configDir.toAbsolutePath().normalize();
    }

    private static void fixRelativeConfigDir() {
        try {
            Class<?> c = Class.forName("net.fabricmc.loader.impl.FabricLoaderImpl");
            Object loader = c.getField("INSTANCE").get(null);
            Field f = c.getDeclaredField("configDir");
            f.setAccessible(true);
            Path before = (Path) f.get(loader), after = absoluteConfigDir(before);
            if (after == before) return;
            f.set(loader, after);
            LOG.info("Alloy: Fabric config dir made absolute ({} -> {}), as the game dir already is", before, after);
        } catch (Throwable t) {
            LOG.warn("Alloy: could not check Fabric's config dir: {}", t.toString());
        }
    }

    private static void run() throws Throwable {
        fixRelativeConfigDir();
        Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath();
        gameDir = game;
        LOG.info("Alloy: starting Forge FML inside Fabric ({}; game dir {})", FabricLoader.getInstance().getEnvironmentType(), game);

        // 1. ModLauncher stand-in
        Launcher launcher = (Launcher) unsafe().allocateInstance(Launcher.class);
        set(launcher, "blackboard", new TypesafeMap());
        var envCtor = Environment.class.getDeclaredConstructor(Launcher.class);
        envCtor.setAccessible(true);
        set(launcher, "environment", envCtor.newInstance(launcher));
        Launcher.INSTANCE = launcher;

        // 2. FML launch state (what FMLServiceProvider/FMLLoader.onInitialLoad+setupLaunchHandler do under ModLauncher)
        FMLPaths.loadAbsolutePaths(game);
        ForgeLaunchPlugins.installHook();   // before FMLConfig.load(): night-config's ConfigWriter is loaded there (config-move redirect)
        FMLConfig.load();
        boolean client = FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT;
        Dist dist = client ? Dist.CLIENT : Dist.DEDICATED_SERVER;
        // Forge's early loading window would open a second GLFW window; Fabric/Minecraft own the window here,
        // so always use the dummy provider (a launch target without "client" selects it).
        net.minecraftforge.fml.loading.ImmediateWindowHandler.load("alloy_nowindow", new String[0]);
        var hc = Class.forName(Fml.launchHandlerClass(client));
        var hctor = hc.getDeclaredConstructor();
        hctor.setAccessible(true);
        CommonLaunchHandler handler = (CommonLaunchHandler) hctor.newInstance();
        fillLauncher(launcher, game, handler.name());
        excludeServiceJars(game);
        setStatic(FMLLoader.class, "dist", dist);
        String naming = System.getProperty("alloy.naming", handler.getNaming());   // Mojang-named game on SRG Forge: "mcp"
        setStatic(FMLLoader.class, "naming", naming);
        setStatic(FMLLoader.class, "gamePath", game);
        setStatic(FMLLoader.class, "production", true);
        setStatic(FMLLoader.class, "commonLaunchHandler", handler);
        setStatic(FMLLoader.class, "launchHandlerName", handler.name());
        setStatic(FMLLoader.class, "moduleLayerManager", BridgeLayers.INSTANCE);
        if (Fml.runtimeAT()) setup47(naming, dist);

        // what FMLServiceProvider.initialize + FMLEnvironment.setupInteropEnvironment do on the ModLauncher environment
        var env = launcher.environment();
        env.computePropertyIfAbsent(cpw.mods.modlauncher.api.IEnvironment.Keys.GAMEDIR.get(), k -> game);
        env.computePropertyIfAbsent(cpw.mods.modlauncher.api.IEnvironment.Keys.NAMING.get(), k -> naming);
        env.computePropertyIfAbsent(net.minecraftforge.forgespi.Environment.Keys.DIST.get(), k -> dist);
        env.computePropertyIfAbsent(net.minecraftforge.forgespi.Environment.Keys.MODFILEFACTORY.get(),
                k -> net.minecraftforge.fml.loading.moddiscovery.ModFile::new);
        net.minecraftforge.forgespi.Environment.build(env);

        // 3. FML's real mod discovery + validation + background scan
        preloadScanClasses();
        ForgeLaunchPlugins.beginScanWindow(Thread.currentThread());
        // Forge 47/48 locators read their ModLauncher arguments from this map (FMLServiceProvider passes them there); 49+ need none
        FMLLoader.beginModScan(Fml.versionFromArgs() ? Map.of("mavenRoots", java.util.List.of(), "mods", java.util.List.of(),
                "modLists", java.util.List.of(), "explodedTargets", java.util.List.of()) : Map.of());
        FMLLoader.completeScan(BridgeLayers.INSTANCE);
        endScanWindowWhenDone();
        if (Fml.jsCoremods()) coreMods47();
        ModTransformationServices.installForge();   // Forge 58.1.2+: Forge's own rewrites as a ModLauncher service
        // what CommonLaunchHandler.launchService does right before running the game: on the client this wires
        // Forge's window handoff (NoVizFallback). Server: not needed, and NoVizFallback needs GLFW.
        if (client) FMLLoader.beforeStart(ModuleLayer.boot());

        // 4. mod jars -> Knot classpath, MixinConfigs -> Fabric's Mixin
        Path gameJar = Fml.gameJar(FMLLoader.getLaunchHandler());
        int jars = 0, mixins = 0;
        for (ModFileInfo mfi : Fml.modFiles()) {
            Path p = mfi.getFile().getFilePath().toAbsolutePath();
            String ids = String.join(",", mfi.getMods().stream().map(m -> m.getModId()).toList());
            if (p.equals(gameJar) || ids.equals("minecraft") || ids.equals("forge")) { LOG.info("  {} (already on classpath)", ids); continue; }
            // launch.py put the jar on Knot's classpath and the generated alloy_modmixins mod declared its
            // MixinConfigs, so Fabric's Mixin loaded them at startup (configs added this late would be ignored)
            jars++;
            if (p.getFileSystem() != java.nio.file.FileSystems.getDefault()) continue;   // jar-in-jar: not a plain file
            // Mod files found by a mod's own locator (e.g. CustomSkinLoader unpacks a Common jar into its own folder)
            // were not on the launch classpath: add them to Knot now, or FML cannot load their @Mod class.
            if (!onClassPath(p)) {
                net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().addToClassPath(p);
                LOG.warn("Alloy: added {} to the classpath at runtime (found by a mod locator; its mixin configs, if any, are not applied)", p.getFileName());
            }
            try (JarFile jf = new JarFile(p.toFile())) {
                String cfgs = jf.getManifest() == null ? null : jf.getManifest().getMainAttributes().getValue("MixinConfigs");
                if (cfgs != null) mixins += cfgs.split(",").length;
            }
            LOG.info("  {} <- {} ", ids, p.getFileName());
        }
        LOG.info("Alloy: {} Forge mod jar(s) on the classpath, {} mixin config(s) declared at startup, {} mod(s) in the Forge mod list",
                jars, mixins, Fml.mods().size());
        // FML's ModLauncher launch plugins (capability tokens, enum extension, dist cleaner) on every loaded class
        Isolation.init(game);   // side ownership index; -Dalloy.isolation=off|warn|enforce (default off)
        ModTransformationServices.install();   // mods' own ModLauncher transformation services (e.g. CustomSkinLoader)
        ForgeLaunchPlugins.install(dist);
        report("onForgeScan", null);   // FML early errors/broken files + first report file (always written)
    }

    /**
     * alloy.report.AlloyReport, called by name: the report code is built only where it compiles (the Forge 47 build
     * leaves it out until it is ported). Without it the start failure is reported by its exception text.
     */
    static String report(String method, Throwable t) {
        try {
            Class<?> c = Class.forName("alloy.report.AlloyReport");
            if (t == null) { c.getMethod(method).invoke(null); return null; }
            return (String) c.getMethod(method, Throwable.class).invoke(null, t);
        } catch (ClassNotFoundException e) {
            if (t != null) return t.toString();
            LOG.info("Alloy: no report module in this build ({}); alloy-report.txt is not written", Fml.FAMILY);
            return null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error r) throw r;
            throw new RuntimeException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("alloy.report.AlloyReport." + method + " not callable", e);
        }
    }

    /**
     * Forge 47 only: the rest of what FMLLoader.onInitialLoad + setupLaunchHandler do under ModLauncher. FML 47 keeps
     * the launch plugins it talks to in static fields (Forge 64 does not), and finds the Minecraft and Forge jars
     * through VersionInfo (the --fml.* launch arguments; here -Dalloy.forgeVersion etc., set by launch120.py).
     */
    private static void setup47(String naming, Dist dist) throws Exception {
        String fv = System.getProperty("alloy.forgeVersion", "47.4.29"), mv = System.getProperty("alloy.mcVersion", "1.20.1"),
                mcp = System.getProperty("alloy.mcpVersion", "20230612.114412"), group = System.getProperty("alloy.forgeGroup", "net.minecraftforge");
        Object vi;
        if (Fml.versionFromArgs()) {
            vi = Class.forName("net.minecraftforge.fml.loading.VersionInfo")
                    .getConstructor(String.class, String.class, String.class, String.class).newInstance(fv, mv, mcp, group);
            setStatic(FMLLoader.class, "versionInfo", vi);
        } else {   // Forge 49: static final, read from /forge_version.json when FMLLoader loads
            vi = FMLLoader.class.getMethod("versionInfo").invoke(null);
        }
        var at = ForgeLaunchPlugins.plugin("accesstransformer");
        if (at == null) throw new IllegalStateException("Forge's AccessTransformerService (accesstransformers jar) is not on the class path");
        setStatic(FMLLoader.class, "accessTransformer", at);
        // ATs are written in SRG names; the runtime may be "srg" (same names) or "mcp" (Mojang names, via BridgeHooks.nameMapping)
        @SuppressWarnings("unchecked") var atNaming = (java.util.function.Consumer<Map.Entry<String, String>>) at.getExtension();
        atNaming.accept(Map.entry(naming, "srg"));
        var cleaner = ForgeLaunchPlugins.plugin("runtimedistcleaner");
        if (cleaner != null) setStatic(FMLLoader.class, "runtimeDistCleaner", cleaner);
        var bus = ForgeLaunchPlugins.plugin("eventbus");
        if (bus != null && Fml.versionFromArgs()) setStatic(FMLLoader.class, "eventBus", bus);   // 47, 48 only; Forge 49+ has no such field
        // by name: forgespi 8 (Forge 61, 64) has no ICoreModProvider and FMLLoader no coreModProvider field
        java.util.Optional<?> cm = java.util.Optional.empty();
        if (Fml.jsCoremods()) {
            cm = java.util.ServiceLoader.load(Class.forName("net.minecraftforge.forgespi.coremod.ICoreModProvider"), BridgeLayers.loader()).findFirst();
            if (cm.isEmpty()) throw new IllegalStateException("no ICoreModProvider (coremods jar) on the class path");
            setStatic(FMLLoader.class, "coreModProvider", cm.get());
        }
        LOG.info("Alloy: Forge {} setup: {}, naming {} (ATs srg -> {}), launch plugins accesstransformer{}{}, coremods {}", Fml.FAMILY.substring(3), vi, naming, naming,
                cleaner != null ? ", runtimedistcleaner" : "", bus != null ? ", eventbus" : "", cm.map(c -> c.getClass().getName()).orElse("none"));
    }

    /** Forge 47: JS coremods (Forge's own and mods' META-INF/coremods.json), collected by FML during the scan, as ModLauncher transformers. */
    private static void coreMods47() throws Exception {
        Object provider = FMLLoader.class.getMethod("getCoreModProvider").invoke(null);
        var ts = (java.util.List<?>) provider.getClass().getMethod("getCoreModTransformers").invoke(provider);
        ModTransformationServices.addTransformers("coremods", ts);
        int ats = 0;
        for (var mfi : Fml.modFiles()) {
            var file = mfi.getFile();
            // Forge 47 and 49+: List getAccessTransformers(); Forge 48: Optional<Path> getAccessTransformer()
            java.util.List<?> l;
            try {
                l = (java.util.List<?>) file.getClass().getMethod("getAccessTransformers").invoke(file);
            } catch (NoSuchMethodException e) {
                l = ((java.util.Optional<?>) file.getClass().getMethod("getAccessTransformer").invoke(file)).stream().toList();
            }
            ats += l.size();
            if (!l.isEmpty()) LOG.info("Alloy: access transformer(s) of {}: {}", file.getFileName(), l);
        }
        LOG.info("Alloy: {} access transformer file(s) offered to Forge's AccessTransformerService", ats);
    }

    static java.util.Set<Path> classPath;

    /**
     * FML scans mod files on worker threads (BackgroundScanHandler), and the first class those threads load (Scanner and
     * its ASM visitors) goes through Knot and so through Fabric's Mixin transformer, which takes the transformer lock.
     * A Forge mod whose Mixin config plugin asks for scan results while Mixin prepares configs (AllTheLeaks:
     * IssueManager.getAllowedMixins -> ModFile.getScanResult) then waits on main while holding that lock: deadlock, the
     * game hangs with no log line. Real Forge loads these classes in its boot layer, outside any transformer. Load them
     * here, before the scan starts.
     */
    private static void preloadScanClasses() {
        for (String n : new String[]{"net.minecraftforge.fml.loading.moddiscovery.Scanner",
                "net.minecraftforge.fml.loading.moddiscovery.ModClassVisitor", "net.minecraftforge.fml.loading.moddiscovery.ModFieldVisitor",
                "net.minecraftforge.fml.loading.moddiscovery.ModMethodVisitor", "net.minecraftforge.fml.loading.moddiscovery.ModAnnotationVisitor",
                "net.minecraftforge.fml.loading.moddiscovery.ModAnnotation", "net.minecraftforge.fml.loading.moddiscovery.ModAnnotation$EnumHolder",
                "net.minecraftforge.forgespi.language.ModFileScanData", "net.minecraftforge.forgespi.language.ModFileScanData$AnnotationData",
                "net.minecraftforge.forgespi.language.ModFileScanData$ClassData", "org.objectweb.asm.ClassReader", "org.objectweb.asm.Type"}) {
            try {
                Class.forName(n, true, Bridge.class.getClassLoader());
            } catch (Throwable ignored) {   // class not in this Forge version
            }
        }
    }

    private static volatile Thread scanWait;

    /**
     * Waits (outside Mixin's transformer lock) until the scan window is closed. Without this a slow start ran the game's
     * Main with the window still open: Bootstrap's worker thread then counted as a scan thread, so IdMapper missed its
     * access transformer (IllegalAccessError on IdMapper.tToId) and BuiltInRegistries missed Create's accessor mixin
     * (AssertionError), embeddium120 2026-11-14.
     */
    private static void awaitScan() {
        Thread t = scanWait;
        if (t == null || !t.isAlive()) return;
        long start = System.nanoTime();
        try { t.join(300_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (t.isAlive()) {
            LOG.warn("Alloy: FML's background scan still running after 300 s; starting the game anyway");
            ForgeLaunchPlugins.endScanWindow();
        } else LOG.info("Alloy: waited {} ms for FML's background scan before the game starts", (System.nanoTime() - start) / 1_000_000);
    }

    /** Ends ForgeLaunchPlugins' scan window once every mod file's background scan has finished (a daemon thread waits). */
    private static void endScanWindowWhenDone() {
        java.util.List<Object> files = new java.util.ArrayList<>();
        for (ModFileInfo mfi : Fml.modFiles()) files.add(mfi.getFile());
        Thread t = scanWait = new Thread(() -> {
            for (Object f : files) {
                try {
                    f.getClass().getMethod("getScanResult").invoke(f);
                } catch (Throwable ignored) {   // a failed scan is reported by FML itself
                }
            }
            ForgeLaunchPlugins.endScanWindow();
        }, "Alloy scan wait");
        t.setDaemon(true);
        t.start();
    }

    static boolean onClassPath(Path p) {
        if (classPath == null) {
            classPath = new java.util.HashSet<>();
            for (String s : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator))
                if (!s.isBlank()) try { classPath.add(Path.of(s).toAbsolutePath().normalize()); } catch (Exception ignored) { }
        }
        return classPath.contains(p.toAbsolutePath().normalize());
    }

    static sun.misc.Unsafe unsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    /**
     * Fills the Launcher fields that mods reflect into (Crash Assistant reads argumentHandler.args). Every field is
     * guarded on its own; what could not be filled stays null. nameMappingServiceHandler, launchService
     * and classLoader stay null on purpose (their constructors ServiceLoad ModLauncher services / need a TransformingClassLoader).
     */
    private static void fillLauncher(Launcher l, Path game, String launchTarget) {
        var filled = new java.util.ArrayList<String>();
        Object layers = null;
        cpw.mods.modlauncher.TransformStore store = null;
        try {
            var args = new java.util.ArrayList<String>();
            try { args.addAll(java.util.Arrays.asList(FabricLoader.getInstance().getLaunchArguments(false))); }
            catch (Throwable t) { LOG.warn("Alloy: no launch arguments from Fabric Loader: {}", t.toString()); }
            if (!args.contains("--gameDir")) { args.add("--gameDir"); args.add(game.toString()); }
            if (!args.contains("--launchTarget")) { args.add("--launchTarget"); args.add(launchTarget); }
            String[] a = args.toArray(new String[0]);
            var ah = new cpw.mods.modlauncher.ArgumentHandler();
            try {   // ModLauncher's own parser: fills args + optionSet, so getLaunchTarget() etc. work
                var m = ah.getClass().getDeclaredMethod("setArgs", String[].class);
                m.setAccessible(true);
                m.invoke(ah, (Object) a);
                filled.add("argumentHandler(parsed, " + a.length + " args)");
            } catch (Throwable t) {
                set(ah, "args", a);   // unparseable args (e.g. unknown Minecraft options): keep the raw array
                filled.add("argumentHandler(raw args only, " + a.length + " args; parser: " + t + ")");
            }
            set(l, "argumentHandler", ah);
        } catch (Throwable t) { LOG.warn("Alloy: Launcher.argumentHandler not filled", t); }
        try { store = new cpw.mods.modlauncher.TransformStore(); set(l, "transformStore", store); filled.add("transformStore"); }
        catch (Throwable t) { LOG.warn("Alloy: Launcher.transformStore not filled: {}", t.toString()); }
        try {
            var c = Class.forName("cpw.mods.modlauncher.ModuleLayerHandler").getDeclaredConstructor();
            c.setAccessible(true);
            layers = c.newInstance();
            set(l, "moduleLayerHandler", layers);
            filled.add("moduleLayerHandler");
        } catch (Throwable t) { layers = null; LOG.warn("Alloy: Launcher.moduleLayerHandler not filled: {}", t.toString()); }
        try {
            if (store != null && layers != null) {
                var c = Class.forName("cpw.mods.modlauncher.TransformationServicesHandler")
                        .getDeclaredConstructor(cpw.mods.modlauncher.TransformStore.class, layers.getClass());
                c.setAccessible(true);
                set(l, "transformationServicesHandler", c.newInstance(store, layers));
                filled.add("transformationServicesHandler");
            }
        } catch (Throwable t) { LOG.warn("Alloy: Launcher.transformationServicesHandler not filled: {}", t.toString()); }
        try {
            // launchPlugins without its constructor (that one ServiceLoads ModLauncher's plugins): a handler whose plugin map
            // starts empty. Mods that add a launch plugin by reflection put it there (Redirector in MMC4:
            // Launcher.INSTANCE.launchPlugins.plugins.put(...) in a static initializer); ForgeLaunchPlugins.install runs them.
            Object h = unsafe().allocateInstance(cpw.mods.modlauncher.LaunchPluginHandler.class);
            set(h, "plugins", new java.util.LinkedHashMap<String, cpw.mods.modlauncher.serviceapi.ILaunchPluginService>());
            set(l, "launchPlugins", h);
            filled.add("launchPlugins(empty, for mods that add a plugin)");
        } catch (Throwable t) { LOG.warn("Alloy: Launcher.launchPlugins not filled: {}", t.toString()); }
        LOG.info("Alloy: Launcher stand-in fields filled: {}", filled);
    }

    /** launch plugins that mods put into Launcher.INSTANCE.launchPlugins by reflection (see fillLauncher) */
    @SuppressWarnings("unchecked")
    static java.util.List<cpw.mods.modlauncher.serviceapi.ILaunchPluginService> addedLaunchPlugins() {
        try {
            Field lf = Launcher.class.getDeclaredField("launchPlugins");
            lf.setAccessible(true);
            Object h = lf.get(Launcher.INSTANCE);
            if (h == null) return java.util.List.of();
            Field pf = h.getClass().getDeclaredField("plugins");
            pf.setAccessible(true);
            var m = (java.util.Map<String, cpw.mods.modlauncher.serviceapi.ILaunchPluginService>) pf.get(h);
            return m == null ? java.util.List.of() : java.util.List.copyOf(m.values());
        } catch (Throwable t) {
            return java.util.List.of();
        }
    }

    /**
     * Real Forge never reads a mods-folder jar that provides a ModLauncher/FML service (ITransformationService, IModLocator,
     * IDependencyLocator) as a mod: ModDirTransformerDiscoverer lists such jars and ModsFolderLocator skips them
     * (ModDirTransformerDiscoverer.allExcluded). Those jars are wrappers whose real mod is jar-in-jar (Crash Assistant's
     * outer jar has a mods.toml for the same id as its nested mod but no @Mod class). Alloy never runs that discoverer
     * and scans forgemods/ instead of mods/, so fill its list from forgemods/ here.
     */
    /** forgemods jars that provide ModLauncher/FML services (loaded as services, not as mods); the error report reads it */
    public static final java.util.List<Path> SERVICE_JARS = new java.util.concurrent.CopyOnWriteArrayList<>();

    @SuppressWarnings("unchecked")
    private static void excludeServiceJars(Path game) {
        try {
            Path dir = game.resolve("forgemods");
            if (!java.nio.file.Files.isDirectory(dir)) return;
            Field ff = net.minecraftforge.fml.loading.ModDirTransformerDiscoverer.class.getDeclaredField("found");
            ff.setAccessible(true);
            var found = (java.util.List<cpw.mods.modlauncher.api.NamedPath>) ff.get(null);
            java.util.Set<String> services = Set.of("META-INF/services/cpw.mods.modlauncher.api.ITransformationService",
                    "META-INF/services/net.minecraftforge.forgespi.locating.IModLocator",
                    "META-INF/services/net.minecraftforge.forgespi.locating.IDependencyLocator");
            var names = new java.util.ArrayList<String>();
            try (var s = java.nio.file.Files.list(dir)) {
                for (Path p : s.filter(x -> x.toString().endsWith(".jar") && java.nio.file.Files.isRegularFile(x)).sorted().toList()) {
                    try (JarFile jf = new JarFile(p.toFile())) {
                        if (services.stream().noneMatch(n -> jf.getEntry(n) != null)) continue;
                        found.add(new cpw.mods.modlauncher.api.NamedPath(p.getFileName().toString(), p.toAbsolutePath()));
                        names.add(p.getFileName().toString());
                        SERVICE_JARS.add(p.toAbsolutePath());
                    } catch (Throwable t) { LOG.warn("Alloy: cannot inspect {} for ModLauncher services: {}", p.getFileName(), t.toString()); }
                }
            }
            LOG.info("Alloy: {} forgemods jar(s) provide ModLauncher/FML services and are not read as mods (as on Forge): {}", names.size(), names);
        } catch (Throwable t) { LOG.warn("Alloy: could not fill ModDirTransformerDiscoverer's exclusion list", t); }
    }

    static void set(Object o, String name, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    static void setStatic(Class<?> c, String name, Object v) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, v);
    }
}
