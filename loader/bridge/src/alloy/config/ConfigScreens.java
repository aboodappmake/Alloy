package alloy.config;

import alloy.Bridge;
import alloy.Fml;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * One place that knows how to open a mod's config screen, for Forge mods and Fabric mods alike.
 *
 *  - Forge: {@code ConfigScreenHandler.getScreenFactoryFor(IModInfo)}, the same record API on Forge 47 to 64.
 *  - Fabric: Mod Menu's public static {@code ModMenu.getConfigScreen(String, Screen)}, looked up by reflection so Mod Menu stays
 *    optional. {@code hasConfigScreen(String)} (Mod Menu for 1.21 and later) answers "has a screen" without building it; on
 *    1.20.x Mod Menu's factory map says "maybe" and the click decides.
 *  - Neither: the mod's config files (Forge's ConfigTracker, or config/&lt;id&gt;* by name), opened with the system's editor.
 *
 * Nothing here builds a screen before the player clicks (Connector Extras issue 36), and Alloy never claims the modmenu id
 * (Connector Extras issue 82). Opening goes through {@link SafeOpen}, which catches a mod's broken screen.
 *
 * Every Forge call is reflective (ModList and ConfigTracker are instance methods on Forge 47 to 61 and static on 64), so this
 * source compiles for every bridge. Off on Forge 64 (Minecraft 26.x) for now unless -Dalloy.configscreens=on; -Dalloy.configscreens=off
 * turns it off everywhere (the chooser and pause menu then behave as before).
 */
public final class ConfigScreens {
    private ConfigScreens() {}

    public enum Side { FORGE, FABRIC }

    /** whether a mod has a config screen: YES (known without building it), MAYBE (only a click can tell, Mod Menu on 1.20.x), NO */
    public enum Has { YES, MAYBE, NO }

    public record Mod(String id, String name, Side side) {
        public String label() { return name + " (" + (side == Side.FORGE ? "Forge" : "Fabric") + ")"; }
    }

    private static final ClassLoader CL = ConfigScreens.class.getClassLoader();
    /** Forge mods whose config button Alloy filled with the config file screen (they have no screen of their own) */
    static final Set<String> FILE_FALLBACK = new LinkedHashSet<>();

    public static boolean enabled() {
        String p = System.getProperty("alloy.configscreens", "");
        if (p.equals("off") || p.equals("false")) return false;
        if (p.equals("on") || p.equals("true")) return true;
        return !Fml.FAMILY.equals("fml64");
    }

    /**
     * Forge's own Config buttons ({@link ForgeFactories}: crash safe factories, SpecScreen or the config file screen for
     * mods without one). On wherever the layer is on, and on Forge 64 (26.x) too, where the pause menu route and the Alloy
     * list stay off by default; -Dalloy.configscreens=off turns it off everywhere.
     */
    public static boolean forgeButtons() {
        String p = System.getProperty("alloy.configscreens", "");
        if (p.equals("off") || p.equals("false")) return false;
        return enabled() || Fml.FAMILY.equals("fml64");
    }

    // ------------------------------------------------------------------------------------------------------------ listing

    /** every Forge and Fabric mod that has a config screen or a config file, Forge first, each side by name */
    public static List<Mod> mods() {
        List<Mod> out = new ArrayList<>();
        try {
            for (Object info : forgeMods()) {
                String id = str(info, "getModId"), name = str(info, "getDisplayName");
                if (id == null || id.equals("minecraft") || id.equals("forge")) continue;
                Mod m = new Mod(id, name == null || name.isBlank() ? id : name, Side.FORGE);
                if (has(m) != Has.NO || !files(m).isEmpty()) out.add(m);
            }
        } catch (Throwable t) { Bridge.LOG.warn("Alloy config: cannot list Forge mods: {}", t.toString()); }
        List<Mod> fab = new ArrayList<>();
        for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
            var md = c.getMetadata();
            String id = md.getId();
            if (skipFabric(c)) continue;
            Mod m = new Mod(id, md.getName() == null || md.getName().isBlank() ? id : md.getName(), Side.FABRIC);
            if (has(m) != Has.NO || !files(m).isEmpty()) fab.add(m);
        }
        Comparator<Mod> byName = Comparator.comparing(m -> m.name().toLowerCase(Locale.ROOT));
        out.sort(byName);
        fab.sort(byName);
        out.addAll(fab);
        return out;
    }

    static boolean skipFabric(ModContainer c) {
        var md = c.getMetadata();
        String id = md.getId();
        if (Set.of("minecraft", "java", "fabricloader", "mixinextras", "alloyloader").contains(id)) return true;
        if (md.containsCustomValue("alloy:forge-stub")) return true;   // Alloy's placeholder for a Forge mod (Prep.forgeStubs)
        try { if (c.getContainingMod().isPresent()) return true; } catch (Throwable ignored) { }   // jar-in-jar child (Fabric API modules)
        return false;
    }

    // ------------------------------------------------------------------------------------------------------------ screens

    /** does the mod have a screen? Never builds one. */
    public static Has has(Mod m) {
        try {
            if (m.side() == Side.FORGE) {
                if (FILE_FALLBACK.contains(m.id())) return Has.NO;
                return forgeFactory(m.id()).isPresent() ? Has.YES : Has.NO;
            }
            return modMenuHas(m.id());
        } catch (Throwable t) {
            Bridge.LOG.debug("Alloy config: has({}) failed: {}", m.id(), t.toString());
            return Has.NO;
        }
    }

    /** builds the mod's config screen (null: the mod has none). May throw whatever the mod's code throws. */
    public static Screen build(Mod m, Screen parent) throws Exception {
        if (m.side() == Side.FORGE) {
            if (FILE_FALLBACK.contains(m.id())) return null;
            Optional<BiFunction<Minecraft, Screen, Screen>> f = forgeFactory(m.id());
            return f.isPresent() ? f.get().apply(Minecraft.getInstance(), parent) : null;
        }
        Method get = modMenu("getConfigScreen", String.class, Screen.class);
        if (get == null) return null;
        try {
            return (Screen) get.invoke(null, m.id(), parent);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception x) throw x;
            if (c instanceof Error er) throw er;
            throw e;
        }
    }

    // ------------------------------------------------------------------------------------------------------------ Mod Menu

    public static boolean modMenuLoaded() { return FabricLoader.getInstance().isModLoaded("modmenu") && modMenuClass() != null; }

    static Class<?> modMenuClass() {
        try { return Class.forName("com.terraformersmc.modmenu.ModMenu", false, CL); } catch (Throwable t) { return null; }
    }

    static Method modMenu(String name, Class<?>... args) {
        Class<?> c = modMenuClass();
        if (c == null) return null;
        try { return c.getMethod(name, args); } catch (NoSuchMethodException e) { return null; }
    }

    static Has modMenuHas(String id) throws Exception {
        if (!modMenuLoaded()) return Has.NO;
        Method has = modMenu("hasConfigScreen", String.class);   // Mod Menu 11 and later (Minecraft 1.21+)
        if (has != null) return Boolean.TRUE.equals(has.invoke(null, id)) ? Has.YES : Has.NO;
        // Mod Menu 7 to 9 (1.20.x): the private factory map plus the not yet folded provider maps; only a click can tell
        // whether the factory returns a screen (the default factory returns null)
        Class<?> c = modMenuClass();
        if (mapHas(c, "configScreenFactories", id)) return Has.MAYBE;
        try {
            Field f = c.getDeclaredField("delayedScreenFactoryProviders");
            f.setAccessible(true);
            if (f.get(null) instanceof Collection<?> l)
                for (Object o : l) if (o instanceof Map<?, ?> mp && mp.containsKey(id)) return Has.MAYBE;
        } catch (NoSuchFieldException ignored) { }
        return Has.NO;
    }

    static boolean mapHas(Class<?> c, String field, String id) {
        try {
            Field f = c.getDeclaredField(field);
            f.setAccessible(true);
            return f.get(null) instanceof Map<?, ?> mp && mp.containsKey(id);
        } catch (Throwable t) { return false; }
    }

    // ------------------------------------------------------------------------------------------------------------ Forge

    /** Forge's mod list screen (net.minecraftforge.client.gui.ModListScreen), or null when it is not there */
    public static Screen forgeModList(Screen parent) {
        try {
            return (Screen) Class.forName("net.minecraftforge.client.gui.ModListScreen", true, CL).getConstructor(Screen.class).newInstance(parent);
        } catch (Throwable t) {
            Bridge.LOG.warn("Alloy config: cannot open Forge's mod list: {}", t.toString());
            return null;
        }
    }

    public static boolean forgeLoaded() {
        try { Class.forName("net.minecraftforge.client.ConfigScreenHandler", false, CL); return true; } catch (Throwable t) { return false; }
    }

    static Object call(String cls, String method, Object... args) throws Exception {
        Class<?> c = Class.forName(cls, true, CL);
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(method) || m.getParameterCount() != args.length) continue;
            Object self = Modifier.isStatic(m.getModifiers()) ? null : c.getMethod("get").invoke(null);   // 47 to 61: ModList.get()
            return m.invoke(self, args);
        }
        throw new NoSuchMethodException(cls + "." + method);
    }

    static List<?> forgeMods() throws Exception {
        if (!forgeLoaded()) return List.of();
        return (List<?>) call("net.minecraftforge.fml.ModList", "getMods");
    }

    static Optional<?> container(String id) throws Exception {
        return (Optional<?>) call("net.minecraftforge.fml.ModList", "getModContainerById", id);
    }

    static boolean forgeModLoaded(String id) {
        try { return forgeLoaded() && container(id).isPresent(); } catch (Throwable t) { return false; }
    }

    @SuppressWarnings("unchecked")
    static Optional<BiFunction<Minecraft, Screen, Screen>> forgeFactory(String id) throws Exception {
        Optional<?> c = container(id);
        if (c.isEmpty()) return Optional.empty();
        Object info = c.get().getClass().getMethod("getModInfo").invoke(c.get());
        Class<?> h = Class.forName("net.minecraftforge.client.ConfigScreenHandler", true, CL);
        for (Method m : h.getMethods())
            if (m.getName().equals("getScreenFactoryFor") && m.getParameterCount() == 1)
                return (Optional<BiFunction<Minecraft, Screen, Screen>>) m.invoke(null, info);
        return Optional.empty();
    }

    static String str(Object o, String getter) {
        try { Object r = o.getClass().getMethod(getter).invoke(o); return r == null ? null : r.toString(); } catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------------------------------------------------------ files

    /** the mod's config files and folders: Forge's ModConfig paths (Forge mods), then config/&lt;id&gt;* by name */
    public static List<Path> files(Mod m) {
        Set<Path> out = new LinkedHashSet<>();
        if (m.side() == Side.FORGE) out.addAll(forgeConfigFiles().getOrDefault(m.id(), List.of()));
        for (Path p : ConfigFiles.find(FabricLoader.getInstance().getConfigDir(), m.id()))   // same form as Forge's paths, or a file shows twice
            out.add(p.toAbsolutePath().normalize());
        out.removeIf(p -> !Files.exists(p));
        return new ArrayList<>(out);
    }

    /** Forge mod id -> config file paths, from ConfigTracker.configSets() (INSTANCE on Forge 47 to 61, static on 64) */
    static Map<String, List<Path>> forgeConfigFiles() {
        Map<String, List<Path>> out = new HashMap<>();
        if (!forgeLoaded()) return out;
        try {
            Class<?> t = Class.forName("net.minecraftforge.fml.config.ConfigTracker", true, CL);
            Method sets = t.getMethod("configSets");
            Object self = null;
            if (!Modifier.isStatic(sets.getModifiers())) self = t.getField("INSTANCE").get(null);
            if (!(sets.invoke(self) instanceof Map<?, ?> byType)) return out;
            for (Object set : byType.values()) {
                if (!(set instanceof Collection<?> configs)) continue;
                for (Object mc : configs) {
                    String id = str(mc, "getModId");
                    Path p = null;
                    try { if (mc.getClass().getMethod("getFullPath").invoke(mc) instanceof Path fp) p = fp; } catch (Throwable ignored) { }
                    if (p == null) {
                        String fn = str(mc, "getFileName");
                        if (fn != null) p = FabricLoader.getInstance().getConfigDir().resolve(fn);
                    }
                    if (id != null && p != null) out.computeIfAbsent(id, k -> new ArrayList<>()).add(p.toAbsolutePath().normalize());
                }
            }
        } catch (Throwable e) {
            Bridge.LOG.debug("Alloy config: ConfigTracker not readable: {}", e.toString());
        }
        return out;
    }

    /**
     * Opens a file or folder with the system's handler (Minecraft's Util.getPlatform().openFile / openPath, whichever this
     * version has). -Dalloy.configscreens.open=log only logs it (test runs). True when the call went out.
     */
    public static boolean openFile(Path p) {
        if ("log".equals(System.getProperty("alloy.configscreens.open"))) {
            Bridge.LOG.info("Alloy config: open {} (logged only, -Dalloy.configscreens.open=log)", p);
            return true;
        }
        try {
            Class<?> util = Class.forName("net.minecraft.Util", true, CL);
            Object os = util.getMethod("getPlatform").invoke(null);
            for (String n : new String[] { "openPath", "openFile" }) {
                for (Method m : os.getClass().getMethods()) {
                    if (!m.getName().equals(n) || m.getParameterCount() != 1) continue;
                    Class<?> a = m.getParameterTypes()[0];
                    if (a == Path.class) { m.invoke(os, p); return true; }
                    if (a == File.class) { m.invoke(os, p.toFile()); return true; }
                }
            }
        } catch (Throwable t) {
            Bridge.LOG.warn("Alloy config: cannot open {}: {}", p, t.toString());
        }
        return false;
    }
}
