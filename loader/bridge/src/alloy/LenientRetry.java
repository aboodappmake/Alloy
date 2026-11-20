package alloy;

import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Lenient mode, one class at a time. Mixin applies every mixin of a target class in one pass. When one of them fails
 * (and the error handler says WARN), Mixin gives back the class bytes untouched, so the other mods' mixins on that class
 * are gone too: in VORTEX Foundations one bad Entity Model Features injector took Supplementaries' interface off ModelPart,
 * one Fabrication injector took Passable Foliage's off Block, and the game crashed far away with a ClassCastException.
 *
 * Here the class is transformed again without the failed mixin (it is taken out of its config's target list), until it
 * transforms or nothing more can be taken out. So one broken mixin costs one feature, not every mixin on that class.
 * ConflictReport.onApplyError tells us which mixin failed through {@link #record}.
 */
final class LenientRetry {
    static final int MAX_ROUNDS = 8;

    record Failure(String target, Object mixin) {}

    /** One frame per class being transformed on this thread (a transform can load, and so transform, other classes). */
    private static final ThreadLocal<ArrayDeque<List<Failure>>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);

    interface Pass { byte[] run(); }
    /** Takes the mixin out of the target's list; returns a short name for the log, or null when nothing was taken out. */
    interface Drop { String drop(Object mixin, String target); }
    /** The mixin named by a critical injection failure on target (message "in cfg:Mixin from mod id"), or null. */
    interface Finder { Object find(String target, String message, Throwable error); }

    /** Loads every class the retry uses (the wrapper calls it before its first transform). */
    static void warm() {
        FRAMES.get().clear();
        new Failure("", null).target();
        new ArrayList<String>().add("");
        for (Class<?> c : new Class<?>[]{Pass.class, Drop.class, Finder.class, IMixinInfo.class, java.lang.reflect.Field.class}) c.getName();
        injectionError(new RuntimeException());
        sameClass("a", "a");
    }

    /** Called by the error handler: the mixin failed to apply to target. */
    static void record(String target, Object mixin) {
        ArrayDeque<List<Failure>> d = FRAMES.get();
        if (!d.isEmpty()) d.peek().add(new Failure(target, mixin));
    }

    /**
     * Runs pass; when it gave back the input unchanged and a mixin failed on this very class, drops that mixin and runs
     * again. A pass that returned new bytes is kept as it is (Mixin went through: a failure there was only a suppressed one).
     */
    static byte[] transform(String name, byte[] in, Pass pass, Drop drop, Consumer<String> log) {
        return transform(name, in, pass, drop, null, log);
    }

    /**
     * Same, and a critical injection failure (InjectionError: a require/allow count not met, "Critical injection failure
     * ... (0/1) succeeded"), which Mixin throws past every error handler, counts as a failure of the mixin finder names.
     * Mixin then gives back nothing, so the class is transformed again without that mixin.
     */
    static byte[] transform(String name, byte[] in, Pass pass, Drop drop, Finder finder, Consumer<String> log) {
        List<String> droppedAll = new ArrayList<>();
        for (int round = 0; ; round++) {
            ArrayDeque<List<Failure>> d = FRAMES.get();
            List<Failure> failed = new ArrayList<>();
            d.push(failed);
            byte[] out;
            try {
                out = pass.run();
            } catch (RuntimeException | Error t) {
                Throwable ie = injectionError(t);
                Object mi = ie != null && finder != null && in != null ? finder.find(name, String.valueOf(ie.getMessage()), ie) : null;
                if (mi == null) throw t;
                if (log != null) log.accept("Alloy lenient: critical injection failure on " + name + ": " + ie.getMessage());
                failed.add(new Failure(name, mi));
                out = in;
            } finally {
                d.pop();
            }
            if (round > 0 && out != in && log != null)
                log.accept("Alloy lenient: " + name + " transformed without " + droppedAll + "; the other mixins on it are applied");
            if (in == null || out != in || round >= MAX_ROUNDS) return out;
            List<String> dropped = new ArrayList<>();
            for (Failure f : failed) {
                if (!sameClass(f.target(), name)) continue;
                String s = drop.drop(f.mixin(), name);
                if (s != null) dropped.add(s);
            }
            if (dropped.isEmpty()) return out;
            droppedAll.addAll(dropped);
            if (log != null) log.accept("Alloy lenient: dropped " + dropped + " from " + name + ", applying the other mixins on it again");
        }
    }

    /** The InjectionError in t's cause chain (Mixin wraps it in MixinTransformerError), or null. */
    static Throwable injectionError(Throwable t) {
        for (int i = 0; t != null && i < 10; t = t.getCause(), i++)
            if (t.getClass().getName().equals("org.spongepowered.asm.mixin.injection.throwables.InjectionError")) return t;
        return null;
    }

    /**
     * The MixinInfo whose name (Mixin prints "config:Mixin from mod id") is in the message, among the mixins the
     * transformer's configs map to target. transformer is Mixin's MixinTransformer (fields processor, configs).
     */
    @SuppressWarnings("unchecked")
    static Object findMixin(Object transformer, String target, String message) {
        try {
            Object proc = field(transformer, "processor");
            List<?> configs = (List<?>) field(proc, "configs");
            Object best = null;
            int bestLen = -1;
            for (Object cfg : configs) {
                Map<String, List<?>> m = (Map<String, List<?>>) field(cfg, "mixinMapping");
                for (String k : new String[]{target.replace('/', '.'), target.replace('.', '/')}) {
                    List<?> l = m.get(k);
                    if (l == null) continue;
                    for (Object mi : l) {
                        String id = String.valueOf(mi);
                        String shortId = mi instanceof IMixinInfo info ? info.getConfig().getName() + ":" + info.getName() : id;
                        int len = message.contains(id) ? id.length() : message.contains(shortId + " ") ? shortId.length() : -1;
                        if (len > bestLen) { best = mi; bestLen = len; }
                    }
                }
            }
            return best;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    static boolean sameClass(String a, String b) {
        return a != null && b != null && a.replace('/', '.').equals(b.replace('/', '.'));
    }

    private static java.lang.reflect.Field mappingField;

    /** Removes mixin from MixinConfig.mixinMapping[target] (Mixin 0.8: a Map of target class name to its MixinInfo list). */
    @SuppressWarnings("unchecked")
    static String dropFromConfig(Object mixin, String target) {
        if (!(mixin instanceof IMixinInfo mi) || mi.getConfig() == null) return null;
        Object cfg = mi.getConfig();
        try {
            java.lang.reflect.Field f = mappingField;
            if (f == null || !f.getDeclaringClass().isInstance(cfg)) {
                f = null;
                for (Class<?> c = cfg.getClass(); c != null && f == null; c = c.getSuperclass()) {
                    try { f = c.getDeclaredField("mixinMapping"); } catch (NoSuchFieldException ignored) { }
                }
                if (f == null) return null;
                f.setAccessible(true);
                mappingField = f;
            }
            Map<String, List<?>> m = (Map<String, List<?>>) f.get(cfg);
            boolean hit = false;
            for (String k : new String[]{target.replace('/', '.'), target.replace('.', '/')}) {
                List<?> l = m.get(k);
                if (l != null) hit |= l.remove(mi);
            }
            return hit ? mi.getClassName() + " (" + mi.getConfig().getName() + ")" : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
