package alloy.api;

import java.util.Map;
import java.util.Objects;

/**
 * One cross-side event as a listener receives it.
 *
 * @param name     event name, "namespace:path" (built-ins use the "alloy" namespace, see {@link CrossEvents})
 * @param data     unmodifiable plain-type data (see {@link PlainData})
 * @param fromSide side of the mod that posted it ({@link Side#UNKNOWN} for Alloy's built-ins)
 * @param fromMod  mod id of the poster ("alloyloader" for built-ins, null when it could not be told)
 */
public record CrossEvent(String name, Map<String, Object> data, Side fromSide, String fromMod) {
    public CrossEvent {
        Objects.requireNonNull(name, "name");
        data = data == null ? Map.of() : data;
        fromSide = fromSide == null ? Side.UNKNOWN : fromSide;
    }

    /** True for events fired by Alloy itself. */
    public boolean isBuiltIn() { return "alloyloader".equals(fromMod) && name.startsWith("alloy:"); }

    public Object get(String key) { return data.get(key); }

    /** String value, or {@code def} when missing or not a String. */
    public String getString(String key, String def) { return data.get(key) instanceof String s ? s : def; }

    /** int value of any number, or {@code def}. */
    public int getInt(String key, int def) { return data.get(key) instanceof Number n ? n.intValue() : def; }

    /** long value of any number, or {@code def}. */
    public long getLong(String key, long def) { return data.get(key) instanceof Number n ? n.longValue() : def; }

    /** double value of any number, or {@code def}. */
    public double getDouble(String key, double def) { return data.get(key) instanceof Number n ? n.doubleValue() : def; }

    public boolean getBoolean(String key, boolean def) { return data.get(key) instanceof Boolean b ? b : def; }
}
