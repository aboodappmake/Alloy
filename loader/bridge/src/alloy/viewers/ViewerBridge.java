package alloy.viewers;

import alloy.Bridge;
import alloy.Isolation;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.forgespi.language.ModFileScanData;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Recipe viewer bridge, Forge plugins into the Fabric viewer (26.1.2: only Fabric JEI and Fabric REI exist; no Forge
 * build of either viewer is published for it). The mixins in alloy.compat.viewers only call the static methods here; this class touches the
 * viewers by reflection, so the bridge needs no viewer jar at compile time and stays inert when no viewer is installed.
 *
 * <p>Forge plugins are found the way Forge's own viewer builds find them: in FML's scan data of the Forge mod files
 * (annotation {@code @mezz.jei.api.JeiPlugin}, {@code @me.shedaniel.rei.forge.REIPluginCommon} / {@code REIPluginClient} /
 * {@code REIPluginDedicatedServer} / legacy {@code REIPlugin}). Classes are loaded through Isolation's Forge view, so the
 * usual side rules apply. The viewer API packages are shared by Isolation's cross-API list, which is what lets a Forge
 * class implement {@code mezz.jei.api.IModPlugin} from the Fabric JEI jar.
 *
 * <p>Every plugin is created in its own try/catch: one broken plugin is logged with its mod id and skipped.
 * Log lines start with "Alloy viewers:"; {@link #summary()} lists everything that was bridged (also used by the selftest).
 */
public final class ViewerBridge {
    private ViewerBridge() {}

    static final String JEI_PLUGIN = "Lmezz/jei/api/JeiPlugin;";
    static final String REI_COMMON = "Lme/shedaniel/rei/forge/REIPluginCommon;";
    static final String REI_CLIENT = "Lme/shedaniel/rei/forge/REIPluginClient;";
    static final String REI_SERVER = "Lme/shedaniel/rei/forge/REIPluginDedicatedServer;";
    static final String REI_LEGACY = "Lme/shedaniel/rei/forge/REIPlugin;";

    /** One Forge class carrying a viewer plugin annotation. */
    record Found(String className, String modId, String annotation, Map<String, Object> data) {}

    private static final List<String> BRIDGED = new CopyOnWriteArrayList<>();

    /** Everything bridged so far, one entry per plugin: "JEI tiab:main (tiab)", "REI common PaladinFurnitureModREIPlugin [pfm]". */
    public static List<String> summary() { return List.copyOf(BRIDGED); }

    // ------------------------------------------------------------------------------------------------ ModList.isLoaded

    /** Viewer ids a Forge mod may ask ModList.isLoaded about; -Dalloy.viewers.forgeIsLoaded=false turns the answer off. */
    static final Set<String> VIEWER_IDS = Set.of("jei", "roughlyenoughitems", "emi");

    /** true when {@code id} is a recipe viewer that is installed as a Fabric mod (called from ModList.isLoaded). */
    public static boolean fabricViewer(String id) {
        return alloy.presence.Presence.viewerBridged(id);   // one id list and switch, in alloy.presence (logged there)
    }

    // ------------------------------------------------------------------------------------------------ scan data

    /** Forge classes annotated with one of {@code descriptors}, in mod load order, de-duplicated by class name. */
    static List<Found> forgeAnnotated(Set<String> descriptors) {
        Map<String, Found> out = new LinkedHashMap<>();
        for (Map.Entry<String, ModFileScanData> e : forgeScanData().entrySet()) {
            try {
                for (ModFileScanData.AnnotationData a : e.getValue().getAnnotations()) {
                    String desc = a.annotationType().getDescriptor();
                    if (!descriptors.contains(desc)) continue;
                    String cls = a.clazz().getClassName();
                    out.putIfAbsent(cls, new Found(cls, e.getKey(), desc, a.annotationData() == null ? Map.of() : a.annotationData()));
                }
            } catch (Throwable t) {
                Bridge.LOG.warn("Alloy viewers: cannot read scan data of {}: {}", e.getKey(), t.toString());
            }
        }
        return new ArrayList<>(out.values());
    }

    /** mod id -> scan data of every Forge mod file (not Forge itself, not Minecraft). */
    static Map<String, ModFileScanData> forgeScanData() {
        Map<String, ModFileScanData> out = new LinkedHashMap<>();
        try {
            for (ModFileInfo mfi : LoadingModList.getModFiles()) {
                List<String> ids = mfi.getMods().stream().map(m -> m.getModId()).toList();
                if (ids.isEmpty() || ids.contains("minecraft") || ids.contains("forge")) continue;
                ModFileScanData sd = mfi.getFile().getScanResult();
                if (sd != null) out.put(ids.get(0), sd);
            }
        } catch (Throwable t) {
            Bridge.LOG.warn("Alloy viewers: Forge scan data not available yet: {}", t.toString());
        }
        return out;
    }

    /** Has FML constructed this Forge mod (its @Mod instance exists)? false while ModList is not built yet. */
    static boolean constructed(String modId) {
        try {
            return ModList.getModContainerById(modId).map(ModContainer::getMod).isPresent();
        } catch (Throwable t) {
            return false;
        }
    }

    static Class<?> forgeClass(String name, boolean init) throws ClassNotFoundException {
        return Class.forName(name, init, Isolation.forgeLoader());
    }

    static Class<?> knotClass(String name) {
        try {
            return Class.forName(name, false, ViewerBridge.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    static Object construct(Found f) {
        try {
            var c = forgeClass(f.className(), true).getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
            Bridge.LOG.error("Alloy viewers: cannot create plugin {} of Forge mod {}: {}", f.className(), f.modId(), cause.toString(), cause);
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------ JEI

    /** "auto" (default): add Forge plugins in FabricPluginFinder when their mods are constructed, else late in JeiStarter.start. */
    static final String JEI_MODE = System.getProperty("alloy.viewers.jeiLate", "auto");
    private static volatile List<Found> jeiPending;
    private static final Set<Object> jeiStartersDone = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Called at RETURN of Fabric JEI's {@code FabricPluginFinder.getModPlugins()} with the (mutable) result list.
     * Timing probe: logs whether FML has constructed the plugins' mods yet. If it has (and late mode is not forced), the
     * plugins are created and appended here; otherwise they wait for {@link #jeiLateAdd(Object)}.
     */
    public static void jeiFinder(List<Object> plugins) {
        try {
            List<Found> found = forgeAnnotated(Set.of(JEI_PLUGIN));
            if (found.isEmpty()) { Bridge.LOG.info("Alloy viewers: JEI plugin finder ran, no Forge @JeiPlugin classes"); return; }
            int built = 0;
            Set<String> mods = new HashSet<>();
            for (Found f : found) if (mods.add(f.modId()) && constructed(f.modId())) built++;
            boolean late = JEI_MODE.equals("true") || (!JEI_MODE.equals("false") && built < mods.size());
            Bridge.LOG.info("Alloy viewers: JEI plugin finder ran on thread {}; Forge mods with @JeiPlugin constructed: {} of {} -> {}",
                    Thread.currentThread().getName(), built, mods.size(), late ? "adding them late, in JeiStarter.start" : "adding them now");
            if (late) { jeiPending = found; return; }
            List<String> names = addJei(plugins, found);
            Bridge.LOG.info("Alloy viewers: JEI got {} Forge plugin(s): {}", names.size(), names);
        } catch (Throwable t) {
            Bridge.LOG.error("Alloy viewers: JEI bridge failed", t);
        }
    }

    /** Appends the Forge JEI plugins in {@code found} to {@code plugins}; returns their descriptions. */
    private static List<String> addJei(List<Object> plugins, List<Found> found) {
        Class<?> api = knotClass("mezz.jei.api.IModPlugin");
        Set<String> have = new HashSet<>();
        for (Object p : plugins) have.add(p.getClass().getName());
        List<String> names = new ArrayList<>();
        for (Found f : found) {
            if (have.contains(f.className())) continue;
            Object p = construct(f);
            if (p == null) continue;
            if (api != null && !api.isInstance(p)) {
                Bridge.LOG.error("Alloy viewers: {} of Forge mod {} has @JeiPlugin but is not an IModPlugin (isolation?)", f.className(), f.modId());
                continue;
            }
            plugins.add(p);
            String n = jeiUid(p) + " (" + f.modId() + ")";
            names.add(n);
            BRIDGED.add("JEI " + n);
        }
        return names;
    }

    static String jeiUid(Object plugin) {
        try {
            return String.valueOf(plugin.getClass().getMethod("getPluginUid").invoke(plugin));
        } catch (Throwable t) {
            return plugin.getClass().getName();
        }
    }

    /**
     * Called at HEAD of {@code JeiStarter.start()}. Once per starter, appends the Forge plugins the finder left pending
     * (their mods are constructed by now: start() runs after resource loading and world join), then does what JEI's
     * constructor does for its own plugins: drop crashing uids, re-sort (vanilla first, JEI internal last), and hand
     * them the config manager. Connector Extras 26.1's JeiStarterMixin does the same for Fabric plugins on NeoForge.
     */
    public static void jeiLateAdd(Object starter) {
        List<Found> found = jeiPending;
        if (found == null) return;
        synchronized (jeiStartersDone) {
            if (!jeiStartersDone.add(starter)) return;
        }
        try {
            Class<?> sc = starter.getClass();
            @SuppressWarnings("unchecked") List<Object> plugins = (List<Object>) field(sc, "plugins").get(starter);
            int before = plugins.size();
            List<String> names = addJei(plugins, found);
            List<Object> added = new ArrayList<>(plugins.subList(before, plugins.size()));
            Class<?> helper = knotClass("mezz.jei.library.load.PluginHelper");
            if (helper != null) {
                helper.getMethod("removePluginsWithCrashingUids", List.class).invoke(null, plugins);
                Object vanilla = field(sc, "vanillaPlugin").get(starter);
                Object internal = null;
                for (Object p : plugins) if (p.getClass().getName().equals("mezz.jei.library.plugins.jei.JeiInternalPlugin")) internal = p;
                for (Method m : helper.getMethods())
                    if (m.getName().equals("sortPlugins") && m.getParameterCount() == 3) { m.invoke(null, plugins, vanilla, internal); break; }
            }
            Object cfg = field(sc, "configManagerForPlugins").get(starter);
            for (Object p : added) {
                if (!plugins.contains(p)) continue;
                try {
                    for (Method m : p.getClass().getMethods())
                        if (m.getName().equals("onConfigManagerAvailable") && m.getParameterCount() == 1) { m.invoke(p, cfg); break; }
                } catch (Throwable t) {
                    Bridge.LOG.error("Alloy viewers: {} onConfigManagerAvailable failed: {}", p.getClass().getName(), t.toString());
                }
            }
            Bridge.LOG.info("Alloy viewers: JEI got {} Forge plugin(s) late: {}", names.size(), names);
        } catch (Throwable t) {
            Bridge.LOG.error("Alloy viewers: JEI late add failed", t);
        }
    }

    static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    // ------------------------------------------------------------------------------------------------ REI

    /** Called at TAIL of Fabric REI's {@code PluginDetectorImpl.detectCommonPlugins()}. */
    public static void reiCommon() {
        try {
            boolean server = FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER;
            Set<String> want = new HashSet<>(List.of(REI_COMMON, REI_LEGACY));
            if (server) want.add(REI_SERVER);
            List<String> names = reiRegister(forgeAnnotated(want), "me.shedaniel.rei.api.common.plugins.REICommonPlugin", "getInstance", "common");
            Bridge.LOG.info("Alloy viewers: REI got {} Forge common plugin(s): {}", names.size(), names);
        } catch (Throwable t) {
            Bridge.LOG.error("Alloy viewers: REI common bridge failed", t);
        }
    }

    /** Wraps the Runnable that Fabric REI's {@code detectClientPlugins()} supplier returns, so Forge client plugins register right after. */
    public static java.util.function.Supplier<Runnable> reiClientSupplier(java.util.function.Supplier<Runnable> original) {
        return () -> {
            Runnable r = original == null ? null : original.get();
            return () -> {
                if (r != null) r.run();
                reiClient();
            };
        };
    }

    static void reiClient() {
        try {
            List<String> names = reiRegister(forgeAnnotated(Set.of(REI_CLIENT, REI_LEGACY)), "me.shedaniel.rei.api.client.plugins.REIClientPlugin", "getClientInstance", "client");
            Bridge.LOG.info("Alloy viewers: REI got {} Forge client plugin(s): {}", names.size(), names);
        } catch (Throwable t) {
            Bridge.LOG.error("Alloy viewers: REI client bridge failed", t);
        }
    }

    /**
     * Registers each annotated Forge class that implements {@code pluginType} through
     * {@code PluginManager.<instance>().view().registerPlugin(provider)}. The provider is a proxy for REI's
     * {@code REIPluginProvider}; it creates the plugin the first time REI asks for it (REI does that during its reload,
     * long after Forge mod construction), so no Forge plugin code runs at detection time.
     */
    private static List<String> reiRegister(List<Found> found, String pluginType, String instance, String kind) throws ReflectiveOperationException {
        List<String> names = new ArrayList<>();
        if (found.isEmpty()) return names;
        Class<?> type = knotClass(pluginType), provider = knotClass("me.shedaniel.rei.api.common.plugins.REIPluginProvider"),
                manager = knotClass("me.shedaniel.rei.api.common.plugins.PluginManager");
        if (type == null || provider == null || manager == null) return names;
        Class<?> viewType = knotClass("me.shedaniel.rei.api.common.plugins.PluginView");
        if (viewType == null) return names;
        Object view = manager.getMethod("view").invoke(manager.getMethod(instance).invoke(null));   // interface methods: impl classes may not be public
        Method register = viewType.getMethod("registerPlugin", provider);
        for (Found f : found) {
            Class<?> c;
            try {
                c = forgeClass(f.className(), false);
            } catch (Throwable t) {
                Bridge.LOG.error("Alloy viewers: cannot load REI plugin {} of Forge mod {}: {}", f.className(), f.modId(), t.toString());
                continue;
            }
            if (!type.isAssignableFrom(c)) continue;   // a legacy @REIPlugin of the other kind, or the other side's plugin
            String name = c.getSimpleName() + " [" + f.modId() + "]";
            Object proxy = Proxy.newProxyInstance(provider.getClassLoader(), new Class<?>[]{provider}, new ReiProvider(f, type, name));
            register.invoke(view, proxy);
            names.add(name);
            BRIDGED.add("REI " + kind + " " + name);
        }
        return names;
    }

    private static final class ReiProvider implements InvocationHandler {
        final Found f; final Class<?> type; final String name;
        volatile Collection<Object> plugins;
        ReiProvider(Found f, Class<?> type, String name) { this.f = f; this.type = type; this.name = name; }

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) {
            switch (m.getName()) {
                case "getPluginProviderName": return name;
                case "getPluginProviderClass": return type;
                case "provide": {
                    Collection<Object> p = plugins;
                    if (p == null) {
                        Object o = construct(f);
                        plugins = p = o == null ? List.of() : List.of(o);
                        if (o != null) Bridge.LOG.info("Alloy viewers: REI created Forge plugin {} on thread {}", name, Thread.currentThread().getName());
                    }
                    return p;
                }
                case "toString": return "Alloy Forge REI plugin provider " + name;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return args != null && args.length == 1 && proxy == args[0];
                default: throw new UnsupportedOperationException(m.toString());
            }
        }
    }
}
