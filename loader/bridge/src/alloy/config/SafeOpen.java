package alloy.config;

import alloy.Bridge;
import alloy.Screens;
import alloy.report.AlloyReport;
import alloy.report.Entry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Opens a mod's config screen without letting a broken one take the game down (Connector Extras issue 36: a factory that
 * throws crashed Catalogue). The screen is built and shown inside a try; when the mod's code throws, or there is no screen,
 * the player stays where they were and sees a short {@link ConfigMessageScreen} (with the config file buttons when the mod
 * has files). A failure writes one WARN line to alloy-report.txt and is remembered for the session, so the next click shows
 * the message at once instead of running the broken code again.
 *
 * Covers what Alloy opens: the Alloy list ({@link AlloyModsScreen}) and Forge's own Config button, whose factories
 * {@link ForgeFactories} wraps with {@link #guard}. On Forge's list a crash while building the screen is caught in the
 * factory; a crash in the screen's init() is caught too, because the guard hands Forge a {@link ConfigLaunchScreen} that
 * opens the real screen through {@link #launch} on its first tick (Forge's own setScreen call never runs the mod's init()).
 */
public final class SafeOpen {
    private SafeOpen() {}

    /** mod key (side:id) -> what failed, for the rest of the session */
    static final Map<String, String> FAILED = new ConcurrentHashMap<>();

    static String key(ConfigScreens.Mod m) { return m.side() + ":" + m.id(); }

    public static boolean failed(ConfigScreens.Mod m) { return FAILED.containsKey(key(m)); }

    /** opens m's config screen over parent, or a message screen when there is none or it breaks */
    public static void open(ConfigScreens.Mod m, Screen parent) {
        String why = FAILED.get(key(m));
        if (why != null) { show(ConfigMessageScreen.failed(parent, m, why), parent); return; }
        Screen s;
        try {
            s = ConfigScreens.build(m, parent);
        } catch (Throwable t) {
            fail(m, "building the screen", t, parent);
            return;
        }
        if (s == null) { show(ConfigMessageScreen.noScreen(parent, m), parent); return; }
        if (s instanceof ConfigLaunchScreen l) s = l.target();   // a guarded Forge factory: open its screen here directly
        launch(m, s, parent);
    }

    /** shows a built config screen (its init() runs here) and catches what it throws; used by open and ConfigLaunchScreen */
    static void launch(ConfigScreens.Mod m, Screen s, Screen parent) {
        String why = FAILED.get(key(m));
        if (why != null) { show(ConfigMessageScreen.failed(parent, m, why), parent); return; }
        try {
            Screens.set(s);
            Bridge.LOG.info("Alloy config: opened the config screen of {} ({})", m.label(), s.getClass().getName());
        } catch (Throwable t) {
            fail(m, "opening the screen", t instanceof IllegalStateException && t.getCause() != null ? t.getCause() : t, parent);
        }
    }

    /** Alloy's message and launch screens are opened as they are; a mod's screen (and SpecScreen) goes through ConfigLaunchScreen */
    static boolean ours(Screen s) {
        return s instanceof ConfigMessageScreen || s instanceof ConfigLaunchScreen;
    }

    /**
     * A Forge ConfigScreenFactory function that catches what the mod's function throws (or a null screen) and returns the
     * message screen instead. The mod's screen itself comes back inside a {@link ConfigLaunchScreen}, so its init() runs in
     * {@link #launch} and a crash there is caught as well (-Dalloy.configscreens.launch=false returns the screen as before).
     * Used by {@link ForgeFactories} on Forge's own mod list.
     */
    public static BiFunction<Minecraft, Screen, Screen> guard(ConfigScreens.Mod m, BiFunction<Minecraft, Screen, Screen> fn) {
        return (mc, parent) -> {
            String why = FAILED.get(key(m));
            if (why != null) return ConfigMessageScreen.failed(parent, m, why);
            try {
                Screen s = fn.apply(mc, parent);
                if (s == null) return ConfigMessageScreen.noScreen(parent, m);
                return ours(s) || "false".equals(System.getProperty("alloy.configscreens.launch")) ? s : new ConfigLaunchScreen(m, s, parent);
            } catch (Throwable t) {
                return ConfigMessageScreen.failed(parent, m, record(m, "building the screen", t));
            }
        };
    }

    static void fail(ConfigScreens.Mod m, String phase, Throwable t, Screen parent) {
        show(ConfigMessageScreen.failed(parent, m, record(m, phase, t)), parent);
    }

    /** logs, writes the report line, remembers the failure; returns the short reason shown to the player */
    static String record(ConfigScreens.Mod m, String phase, Throwable t) {
        String why = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        if (why.length() > 160) why = why.substring(0, 157) + "...";
        FAILED.put(key(m), why);
        Bridge.LOG.warn("Alloy config: the config screen of {} failed while {}; the game goes on", m.label(), phase, t);
        try {
            StackTraceElement[] st = t.getStackTrace();
            String top = st.length > 0 ? st[0].toString() : "";
            AlloyReport.add(new Entry(Entry.Severity.WARN, m.side() == ConfigScreens.Side.FORGE ? "Forge" : "Fabric", m.label(), "",
                    "The config screen of " + m.name() + " failed to open",
                    "The mod's own config screen threw " + t.getClass().getName() + " while " + phase
                            + ". Alloy kept the game running and showed a message instead.",
                    "Edit the mod's config file, or report it to the mod's author.",
                    "mod " + m.id() + ", side " + m.side() + ", opener " + (m.side() == ConfigScreens.Side.FORGE ? "ConfigScreenHandler" : "ModMenu.getConfigScreen")
                            + ", " + t + (top.isEmpty() ? "" : " at " + top)));
            AlloyReport.flush();
        } catch (Throwable e) {
            Bridge.LOG.debug("Alloy config: report line not written: {}", e.toString());
        }
        return why;
    }

    static void show(Screen s, Screen parent) {
        try {
            Screens.set(s);
        } catch (Throwable t) {
            Bridge.LOG.warn("Alloy config: cannot show the message screen: {}", t.toString());
            try { Screens.set(parent); } catch (Throwable ignored) { }
        }
    }
}
