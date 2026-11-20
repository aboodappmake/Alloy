package alloy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.List;

/**
 * The few FML calls whose shape differs between the Forge families Alloy bridges. Everything is reflective, so the
 * same bridge source compiles and runs against Forge 64 (MC 26.1.x) and Forge 47 (MC 1.20.1).
 *
 *  - launch handler class: 64 ForgeProdLaunchHandler$Client/$Server, 47 ForgeClientLaunchHandler/ForgeServerLaunchHandler
 *  - CommonLaunchHandler.getMinecraftPaths(): 64 List&lt;Path&gt;, 47 record LocatedPaths (component minecraftPaths)
 *  - LoadingModList.getModFiles/getMods/getErrors/getBrokenFiles: 64 static, 47 instance methods on LoadingModList.get()
 *  - ModLoader.getWarnings: 64 static, 47 instance method on ModLoader.get()
 *
 * Forge 49 (MC 1.20.4, "fml49") is a mix: launch handler, getMinecraftPaths and JarVersionLookupHandler.getInfo as on 64;
 * instance LoadingModList/ModLoader, runtime access transformer and dist cleaner fields in FMLLoader, eventbus 6, SRG names
 * and JS coremods as on 47. FMLLoader has no eventBus field and versionInfo is final (VersionInfo.detect()).
 * Forge 48 (1.20.2) is 47 with the new launch handler; Forge 50 to 55 are 49 with Mojang names; Forge 56 to 61 are 49 with
 * eventbus 7 (61 also without coremods). Table: loader/docs/families.md.
 */
public final class Fml {
    private Fml() {}

    /**
     * The FML family, picked from features present at runtime (never from a field that several families share):
     *  - fml47: no ForgeProdLaunchHandler (Forge 47, MC 1.20.1)
     *  - fml48: ForgeProdLaunchHandler, but JarVersionLookupHandler has no getInfo(Class) (Forge 48, MC 1.20.2)
     *  - fml49: getInfo, eventbus 6 (no api.bus.BusGroup) (Forge 49 to 55, MC 1.20.4 to 1.21.5; Mojang names from 50 on)
     *  - fml56: eventbus 7 (BusGroup) and still JS coremods (FMLLoader.getCoreModProvider) (Forge 56 to 60, MC 1.21.6 to 1.21.10)
     *  - fml61: eventbus 7, no coremods, LoadingModList still a class (Forge 61, MC 1.21.11)
     *  - fml64: eventbus 7, LoadingModList is an interface of static methods (Forge 64 and up, MC 26.x)
     */
    public static final String FAMILY = detect();

    static String detect() {
        if (!exists("net.minecraftforge.fml.loading.targets.ForgeProdLaunchHandler")) return "fml47";
        if (!hasMethod("net.minecraftforge.fml.loading.JarVersionLookupHandler", "getInfo")) return "fml48";
        if (!exists("net.minecraftforge.eventbus.api.bus.BusGroup")) return "fml49";
        if (hasMethod("net.minecraftforge.fml.loading.FMLLoader", "getCoreModProvider")) return "fml56";
        if (!isInterface("net.minecraftforge.fml.loading.LoadingModList")) return "fml61";
        return "fml64";
    }

    public static boolean is47() { return FAMILY.equals("fml47"); }

    public static boolean is48() { return FAMILY.equals("fml48"); }

    public static boolean is49() { return FAMILY.equals("fml49"); }

    public static boolean is56() { return FAMILY.equals("fml56"); }

    public static boolean is61() { return FAMILY.equals("fml61"); }

    /** The bridge gives FML Forge's AccessTransformerService and dist cleaner (FMLLoader fields): every family that runs
     *  through launch120.py (no PrepGame). Forge 64 gets its ATs ahead of time from tools/PrepGame. */
    public static boolean runtimeAT() { return !FAMILY.equals("fml64"); }

    /** eventbus 6 (47, 48, 49): ModLauncher plugin "eventbus" with ASM listener classes. */
    public static boolean eventbus6() { return is47() || is48() || is49(); }

    /** JS coremods (coremods jar, forgespi ICoreModProvider) run as ModLauncher transformers (47 to 60). */
    public static boolean jsCoremods() { return is47() || is48() || is49() || is56(); }

    /** VersionInfo comes from launch arguments and FMLLoader has an eventBus field (47, 48; 49 and up read
     *  /forge_version.json into a final field). FML's locators also want their ModLauncher arguments map there. */
    public static boolean versionFromArgs() { return is47() || is48(); }

    static boolean hasMethod(String cls, String method) {
        try {
            for (Method m : Class.forName(cls, false, Fml.class.getClassLoader()).getDeclaredMethods())
                if (m.getName().equals(method)) return true;
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isInterface(String cls) {
        try {
            return Class.forName(cls, false, Fml.class.getClassLoader()).isInterface();
        } catch (Throwable t) {
            return true;
        }
    }

    static boolean hasField(String cls, String field) {
        try {
            Class.forName(cls, false, Fml.class.getClassLoader()).getDeclaredField(field);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean exists(String n) {
        try {
            Class.forName(n, false, Fml.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String launchHandlerClass(boolean client) {
        if (is47()) return "net.minecraftforge.fml.loading.targets.Forge" + (client ? "Client" : "Server") + "LaunchHandler";
        return "net.minecraftforge.fml.loading.targets.ForgeProdLaunchHandler$" + (client ? "Client" : "Server");
    }

    /** First entry of the launch handler's Minecraft paths: the game jar. */
    public static Path gameJar(Object handler) throws Exception {
        Object r = handler.getClass().getMethod("getMinecraftPaths").invoke(handler);
        if (!(r instanceof List<?>)) r = r.getClass().getMethod("minecraftPaths").invoke(r);   // 47: LocatedPaths
        return Path.of(((List<?>) r).get(0).toString()).toAbsolutePath();
    }

    @SuppressWarnings("unchecked")
    public static List<net.minecraftforge.fml.loading.moddiscovery.ModFileInfo> modFiles() { return (List<net.minecraftforge.fml.loading.moddiscovery.ModFileInfo>) lml("getModFiles"); }

    public static List<?> mods() { return (List<?>) lml("getMods"); }

    public static List<?> errors() { return (List<?>) lml("getErrors"); }

    public static List<?> brokenFiles() { return (List<?>) lml("getBrokenFiles"); }

    /** ModLoader's warnings (List of ModLoadingWarning). Throws when ModLoader is not there or not set up yet. */
    public static List<?> modLoaderWarnings() {
        try {
            Class<?> c = Class.forName("net.minecraftforge.fml.ModLoader", false, Fml.class.getClassLoader());
            Method m = c.getMethod("getWarnings");
            Object self = Modifier.isStatic(m.getModifiers()) ? null : c.getMethod("get").invoke(null);
            Object r = m.invoke(self);
            return r == null ? List.of() : (List<?>) r;
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException("ModLoader.getWarnings failed", e.getCause());
        } catch (Exception e) {
            throw new RuntimeException("ModLoader.getWarnings not found (" + FAMILY + ")", e);
        }
    }

    static Object lml(String name) {
        try {
            Class<?> c = net.minecraftforge.fml.loading.LoadingModList.class;
            Method m = c.getMethod(name);
            Object self = Modifier.isStatic(m.getModifiers()) ? null : c.getMethod("get").invoke(null);
            Object r = m.invoke(self);
            return r == null ? List.of() : r;
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException("LoadingModList." + name + " failed", e.getCause());
        } catch (Exception e) {
            throw new RuntimeException("LoadingModList." + name + " not found (" + FAMILY + ")", e);
        }
    }
}
