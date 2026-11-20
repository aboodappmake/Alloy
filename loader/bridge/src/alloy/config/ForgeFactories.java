package alloy.config;

import alloy.Bridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Forge's own mod list (and Catalogue, which uses the same ConfigScreenHandler) gets two things, once, when the title or
 * pause screen first opens (after every mod's loading, including Configured's FMLLoadCompleteEvent step):
 *
 *  - every registered ConfigScreenFactory is wrapped with {@link SafeOpen#guard}: a factory that throws or returns null
 *    shows a short message instead of crashing the game;
 *  - a Forge mod with config files (a ModConfig in ConfigTracker) and no factory gets one, so its grey Config button works:
 *    on Forge 64 (Minecraft 26.x) {@link SpecScreen}, an editor for its ForgeConfigSpec values, when the 26.x bridge has it
 *    (found by name, the 1.x bridges do not carry it; -Dalloy.configscreens.spec=false turns it off); otherwise
 *    {@link ConfigMessageScreen#filesOnly} ("Open config file" / "Open config folder").
 *    Skipped when Configured is loaded (it builds real screens for those mods) or with -Dalloy.configscreens.forgefallback=false.
 *
 * Only the public ModContainer.registerExtensionPoint is used. No screen is built here (Connector Extras issue 36).
 */
public final class ForgeFactories {
    private ForgeFactories() {}

    private static boolean done;
    static int wrapped, fallbacks, specs;

    /** alloy.config.SpecScreen's has(String) and create(Screen, String, String), or null (1.x bridges, or switched off) */
    static Method[] specScreen() {
        if ("false".equals(System.getProperty("alloy.configscreens.spec"))) return null;
        try {
            Class<?> c = Class.forName("alloy.config.SpecScreen", true, ForgeFactories.class.getClassLoader());
            return new Method[] { c.getMethod("has", String.class), c.getMethod("create", Screen.class, String.class, String.class) };
        } catch (Throwable t) {
            return null;
        }
    }

    public static synchronized void install() {
        if (done || !ConfigScreens.forgeButtons() || !ConfigScreens.forgeLoaded()) return;
        done = true;
        try {
            ClassLoader cl = ForgeFactories.class.getClassLoader();
            Class<?> cf = Class.forName("net.minecraftforge.client.ConfigScreenHandler$ConfigScreenFactory", true, cl);
            Constructor<?> make = cf.getConstructor(BiFunction.class);
            Method fnOf = cf.getMethod("screenFunction");
            boolean fallback = !"false".equals(System.getProperty("alloy.configscreens.forgefallback"))
                    && !ConfigScreens.forgeModLoaded("configured");
            var files = ConfigScreens.forgeConfigFiles();
            Method[] spec = fallback ? specScreen() : null;
            for (Object info : ConfigScreens.forgeMods()) {
                String id = ConfigScreens.str(info, "getModId");
                if (id == null || id.equals("minecraft")) continue;
                Optional<?> c = ConfigScreens.container(id);
                if (c.isEmpty()) continue;
                Object container = c.get();
                String name = ConfigScreens.str(info, "getDisplayName");
                ConfigScreens.Mod m = new ConfigScreens.Mod(id, name == null || name.isBlank() ? id : name, ConfigScreens.Side.FORGE);
                Optional<?> have = (Optional<?>) container.getClass().getMethod("getCustomExtension", Class.class).invoke(container, cf);
                BiFunction<Minecraft, Screen, Screen> fn;
                if (have.isPresent()) {
                    @SuppressWarnings("unchecked")
                    BiFunction<Minecraft, Screen, Screen> own = (BiFunction<Minecraft, Screen, Screen>) fnOf.invoke(have.get());
                    if (own == null) continue;
                    fn = SafeOpen.guard(m, own);
                    wrapped++;
                } else if (spec != null && Boolean.TRUE.equals(spec[0].invoke(null, id))) {
                    Method create = spec[1];
                    String shown = m.name();
                    fn = SafeOpen.guard(m, (mc, parent) -> {
                        try { return (Screen) create.invoke(null, parent, id, shown); }
                        catch (java.lang.reflect.InvocationTargetException e) {
                            if (e.getCause() instanceof RuntimeException r) throw r;
                            if (e.getCause() instanceof Error er) throw er;
                            throw new IllegalStateException(e.getCause());
                        }
                        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                    });
                    specs++;
                } else if (fallback && !files.getOrDefault(id, List.of()).isEmpty()) {
                    fn = (mc, parent) -> ConfigMessageScreen.filesOnly(parent, m);
                    ConfigScreens.FILE_FALLBACK.add(id);
                    fallbacks++;
                } else continue;
                Object rec = make.newInstance(fn);
                Supplier<Object> sup = () -> rec;
                container.getClass().getMethod("registerExtensionPoint", Class.class, Supplier.class).invoke(container, cf, sup);
            }
            Bridge.LOG.info("Alloy config: Forge config buttons: {} factories made crash safe, {} mods given a spec editor (SpecScreen), {} mods given an open-config-file screen{}",
                    wrapped, specs, fallbacks, fallback ? "" : " (fallback off)");
        } catch (Throwable t) {
            Bridge.LOG.warn("Alloy config: Forge config factories not touched: {}", t.toString());
        }
    }
}
