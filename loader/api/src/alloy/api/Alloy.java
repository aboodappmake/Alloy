package alloy.api;

import alloy.api.spi.AlloyBackend;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Entry point of the Alloy API: lets a Fabric mod and a Forge mod in the same Alloy game find and talk to each
 * other, using plain Java types only (so Alloy's side isolation still holds).
 *
 * <p>Safe everywhere: on plain Fabric or plain Forge (no Alloy) every call works and reports "not Alloy":
 * {@link #isPresent()} is false, events and messages are never delivered, {@link #isModLoaded(String)} asks the
 * running loader only.
 */
public final class Alloy {
    private Alloy() {}

    /** Version of this API jar. */
    public static final String API_VERSION = "1.0.0";
    /** API level of this jar; an Alloy backend reports its own with {@link AlloyBackend#apiLevel()}. */
    public static final int API_LEVEL = 1;

    static final String IMPL = "alloy.api.impl.ApiBackend";
    static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private static final class Holder {
        static final AlloyBackend BACKEND = load();
    }

    private static AlloyBackend load() {
        if (Boolean.getBoolean("alloy.api.disable")) return null;
        ClassLoader l = Alloy.class.getClassLoader();
        Class<?> c;
        try {
            c = Class.forName(IMPL, true, l);
        } catch (ClassNotFoundException | LinkageError notAlloy) {
            return null;   // plain Fabric / plain Forge
        }
        try {
            Method m = c.getMethod("instance");
            return (AlloyBackend) m.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            System.err.println("[Alloy API] Alloy found, but its API backend failed to start; the API reports \"not Alloy\": " + e);
            e.printStackTrace();
            return null;
        }
    }

    /** The Alloy backend, or null when not running on Alloy. Internal. */
    static AlloyBackend backend() { return Holder.BACKEND; }

    /** First class on the stack outside the API's own packages: the calling mod's class. */
    static Class<?> caller() {
        return WALKER.walk(s -> s.map(StackWalker.StackFrame::getDeclaringClass)
                .filter(c -> !c.getName().startsWith("alloy.api."))
                .findFirst().orElse(Alloy.class));
    }

    static String checkName(String what, String name) {
        Objects.requireNonNull(name, what);
        if (!ID.matcher(name).matches())
            throw new IllegalArgumentException("Alloy: " + what + " must look like \"modid:name\" (lower case a-z 0-9 _ . - /), got \"" + name + "\"");
        return name;
    }

    // -------------------------------------------------------------------------------------------------------------- 1

    /** True when the game runs on Alloy (Fabric and Forge mods together). */
    public static boolean isPresent() { return backend() != null; }

    /** Alloy's version, empty when not on Alloy. */
    public static Optional<String> version() {
        AlloyBackend b = backend();
        return b == null ? Optional.empty() : Optional.ofNullable(b.version());
    }

    /** API level of the running Alloy backend, 0 when not on Alloy. */
    public static int backendApiLevel() {
        AlloyBackend b = backend();
        return b == null ? 0 : b.apiLevel();
    }

    /**
     * Which side the calling mod lives on. On Alloy: where its jar was loaded from. On plain Fabric / plain Forge:
     * that platform. {@link Side#UNKNOWN} when the caller is not a mod (or nothing could be told).
     */
    public static Side side() {
        AlloyBackend b = backend();
        return b != null ? b.sideOf(caller()) : Platform.side();
    }

    /** Side of the mod that owns {@code cls} (same rules as {@link #side()}). */
    public static Side sideOf(Class<?> cls) {
        Objects.requireNonNull(cls, "cls");
        AlloyBackend b = backend();
        return b != null ? b.sideOf(cls) : Platform.side();
    }

    // -------------------------------------------------------------------------------------------------------------- 2

    /** True when a mod with this id is loaded on either side. */
    public static boolean isModLoaded(String id) {
        return !mods(id).isEmpty();
    }

    /** True when a mod with this id is loaded on the given side. */
    public static boolean isModLoaded(String id, Side side) {
        for (ModInfo m : mods(id)) if (m.side() == side) return true;
        return false;
    }

    /** The mod with this id; when both sides have one, the caller's own side wins. */
    public static Optional<ModInfo> mod(String id) {
        List<ModInfo> l = mods(id);
        if (l.isEmpty()) return Optional.empty();
        if (l.size() > 1) {
            Side mine = side();
            for (ModInfo m : l) if (m.side() == mine) return Optional.of(m);
        }
        return Optional.of(l.get(0));
    }

    /** Every loaded mod with this id (at most one per side). */
    public static List<ModInfo> mods(String id) {
        Objects.requireNonNull(id, "id");
        List<ModInfo> out = new ArrayList<>(2);
        for (ModInfo m : mods()) if (m.id().equals(id)) out.add(m);
        return out;
    }

    /** Every loaded mod on both sides (on plain Fabric/Forge: that loader's mods). */
    public static List<ModInfo> mods() {
        AlloyBackend b = backend();
        return b != null ? b.mods() : Platform.mods();
    }

    // -------------------------------------------------------------------------------------------------------------- 4

    /**
     * A message channel, named "modid:name". Fabric and Forge mods that use the same name talk to each other. The
     * handle remembers the calling mod, so get it from your own mod's code.
     */
    public static Channel channel(String name) {
        return new Channel(checkName("channel name", name), caller());
    }
}
