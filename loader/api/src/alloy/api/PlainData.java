package alloy.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "plain Java types" rule that keeps side isolation intact: everything that crosses between a Fabric mod and a
 * Forge mod (event data, channel messages) is copied into JDK types only. No Minecraft, Fabric or Forge object, and no
 * mod class, ever crosses.
 *
 * <p>Allowed values: {@code null}, {@link String}, {@link Boolean}, {@link Byte}, {@link Short}, {@link Integer},
 * {@link Long}, {@link Float}, {@link Double}, {@code byte[]}, {@link List} (or any {@link Collection}) of allowed
 * values, and {@link Map} with {@link String} keys and allowed values. Nesting depth is limited to {@value #MAX_DEPTH}.
 */
public final class PlainData {
    private PlainData() {}

    public static final int MAX_DEPTH = 32;

    /** True when {@code value} (deeply) uses only allowed types. */
    public static boolean isPlain(Object value) {
        try {
            copy(value, 0);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Deep, unmodifiable copy of a data map. {@code null} gives an empty map.
     *
     * @throws IllegalArgumentException naming the first key whose value is not a plain type
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> copyMap(Map<String, ?> data) {
        if (data == null) return Map.of();
        return (Map<String, Object>) copy(data, 0);
    }

    /** Deep, unmodifiable copy of one allowed value (byte arrays are cloned). */
    public static Object copy(Object value) {
        return copy(value, 0);
    }

    private static Object copy(Object v, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Alloy: data nested deeper than " + MAX_DEPTH);
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Integer || v instanceof Long
                || v instanceof Double || v instanceof Float || v instanceof Short || v instanceof Byte) return v;
        if (v instanceof byte[] b) return b.clone();
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>(Math.max(4, m.size() * 2));
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String k))
                    throw new IllegalArgumentException("Alloy: map keys must be Strings, got " + typeName(e.getKey()));
                try {
                    out.put(k, copy(e.getValue(), depth + 1));
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException(ex.getMessage() + " (key \"" + k + "\")", ex);
                }
            }
            return Collections.unmodifiableMap(out);
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            for (Object o : c) out.add(copy(o, depth + 1));
            return Collections.unmodifiableList(out);
        }
        throw new IllegalArgumentException("Alloy: value of type " + typeName(v)
                + " is not a plain type (use String, Boolean, numbers, byte[], List or Map<String, ?>)");
    }

    private static String typeName(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }
}
