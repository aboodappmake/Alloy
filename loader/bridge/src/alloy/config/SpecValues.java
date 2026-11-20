package alloy.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The value side of {@link SpecScreen}, without any Minecraft class (unit tested against Forge 64's ForgeConfigSpec,
 * loader/tests/test_configscreens.py): which values a spec has, what kind each is, how a typed text or a click turns into
 * a value the spec accepts. 26.x only, like SpecScreen.
 */
final class SpecValues {
    private SpecValues() {}

    enum Kind { BOOL, INT, LONG, DOUBLE, FLOAT, ENUM, STRING, READ_ONLY }

    /** one config value: its path in the spec, the mod's ConfigValue and the spec entry (comment, range, rule) */
    record Value(List<String> path, ForgeConfigSpec.ConfigValue<Object> value, ForgeConfigSpec.ValueSpec spec) {}

    /** every ConfigValue under values (sections walked in the spec's order), with its ValueSpec from specs */
    @SuppressWarnings("unchecked")
    static List<Value> walk(UnmodifiableConfig values, UnmodifiableConfig specs) {
        List<Value> out = new ArrayList<>();
        walk(values, specs, new ArrayList<>(), out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void walk(UnmodifiableConfig values, UnmodifiableConfig specs, List<String> prefix, List<Value> out) {
        for (Map.Entry<String, Object> e : values.valueMap().entrySet()) {
            List<String> p = new ArrayList<>(prefix);
            p.add(e.getKey());
            Object v = e.getValue();
            if (v instanceof UnmodifiableConfig sub) walk(sub, specs, p, out);
            else if (v instanceof ForgeConfigSpec.ConfigValue<?> cv && specs.get(p) instanceof ForgeConfigSpec.ValueSpec vs)
                out.add(new Value(List.copyOf(p), (ForgeConfigSpec.ConfigValue<Object>) cv, vs));
        }
    }

    static Kind kind(Object v) {
        if (v instanceof Boolean) return Kind.BOOL;
        if (v instanceof Integer) return Kind.INT;
        if (v instanceof Long) return Kind.LONG;
        if (v instanceof Double) return Kind.DOUBLE;
        if (v instanceof Float) return Kind.FLOAT;
        if (v instanceof Enum<?>) return Kind.ENUM;
        if (v instanceof String) return Kind.STRING;
        return Kind.READ_ONLY;   // lists and anything else: shown, not edited
    }

    /** typed text -> a value of that kind, or null when it does not parse or the spec rejects it (range, the mod's rule) */
    static Object parse(Kind k, ForgeConfigSpec.ValueSpec spec, String s) {
        Object v;
        try {
            v = switch (k) {
                case INT -> Integer.valueOf(s.trim());
                case LONG -> Long.valueOf(s.trim());
                case DOUBLE -> Double.valueOf(s.trim());
                case FLOAT -> Float.valueOf(s.trim());
                case STRING -> s;
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
        if (v instanceof Double d && (d.isNaN() || d.isInfinite())) return null;
        if (v instanceof Float f && (f.isNaN() || f.isInfinite())) return null;
        return v != null && accepts(spec, v) ? v : null;
    }

    static boolean accepts(ForgeConfigSpec.ValueSpec spec, Object v) {
        try { return spec.test(v); } catch (Throwable t) { return false; }
    }

    /** a click on a boolean or enum row: the other boolean, or the next enum constant the spec allows */
    static Object next(Kind k, ForgeConfigSpec.ValueSpec spec, Object now) {
        if (k == Kind.BOOL) return !(Boolean) now;
        if (k != Kind.ENUM) return now;
        Object[] all = ((Enum<?>) now).getDeclaringClass().getEnumConstants();
        int at = ((Enum<?>) now).ordinal();
        for (int i = 1; i < all.length; i++) {
            Object c = all[(at + i) % all.length];
            if (accepts(spec, c) || accepts(spec, ((Enum<?>) c).name())) return c;
        }
        return now;
    }

    static String show(Object v) {
        if (v instanceof Collection<?> c) return c.size() + (c.size() == 1 ? " entry" : " entries") + ": " + c;
        return String.valueOf(v);
    }
}
