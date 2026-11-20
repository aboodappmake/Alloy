package alloy.api;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Fallbacks for plain Fabric / plain Forge (no Alloy), by reflection only, so the API jar links to neither. */
final class Platform {
    private Platform() {}

    private static volatile Side side;

    static Side side() {
        Side s = side;
        if (s == null) side = s = has("net.fabricmc.loader.api.FabricLoader") ? Side.FABRIC
                : has("net.minecraftforge.fml.ModList") || has("net.minecraftforge.fml.loading.FMLLoader") ? Side.FORGE : Side.UNKNOWN;
        return s;
    }

    private static boolean has(String name) {
        for (ClassLoader l : new ClassLoader[]{Platform.class.getClassLoader(), Thread.currentThread().getContextClassLoader()}) {
            try {
                Class.forName(name, false, l);
                return true;
            } catch (ClassNotFoundException | LinkageError ignored) {
            }
        }
        return false;
    }

    private static Class<?> cls(String name) throws ClassNotFoundException {
        try {
            return Class.forName(name, false, Platform.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return Class.forName(name, false, Thread.currentThread().getContextClassLoader());
        }
    }

    static List<ModInfo> mods() {
        List<ModInfo> out = new ArrayList<>();
        try {
            switch (side()) {
                case FABRIC -> {
                    Object loader = cls("net.fabricmc.loader.api.FabricLoader").getMethod("getInstance").invoke(null);
                    for (Object mc : (Collection<?>) loader.getClass().getMethod("getAllMods").invoke(loader)) {
                        Object meta = call(mc, "getMetadata");
                        Object ver = call(meta, "getVersion");
                        out.add(new ModInfo((String) call(meta, "getId"), (String) call(meta, "getName"),
                                (String) call(ver, "getFriendlyString"), Side.FABRIC));
                    }
                }
                case FORGE -> {
                    Object list = cls("net.minecraftforge.fml.ModList").getMethod("get").invoke(null);
                    if (list == null) return out;
                    for (Object mi : (Collection<?>) call(list, "getMods"))
                        out.add(new ModInfo((String) call(mi, "getModId"), (String) call(mi, "getDisplayName"),
                                String.valueOf(call(mi, "getVersion")), Side.FORGE));
                }
                default -> { }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
        return out;
    }

    private static Object call(Object o, String name) throws ReflectiveOperationException {
        Method m = findPublic(o.getClass(), name);
        return m.invoke(o);
    }

    /** a public method reachable through a public interface/superclass (implementation classes may be hidden) */
    private static Method findPublic(Class<?> c, String name) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Class<?> i : k.getInterfaces()) {
                try { return i.getMethod(name); } catch (NoSuchMethodException ignored) { }
            }
            if (java.lang.reflect.Modifier.isPublic(k.getModifiers())) {
                try { return k.getMethod(name); } catch (NoSuchMethodException ignored) { }
            }
        }
        return c.getMethod(name);
    }
}
