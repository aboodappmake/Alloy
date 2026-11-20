package alloy.api;

import java.util.Map;
import java.util.Objects;

/**
 * One message on a {@link Channel}. It carries either a data map or raw bytes ({@link #isBytes()}).
 *
 * @param channel    channel name, "modid:name"
 * @param data       unmodifiable plain-type map (empty for a bytes message)
 * @param bytes      raw payload of a bytes message, else null; {@link #bytes()} returns a copy
 * @param fromSide   side of the sending mod (as the sender reported it, for a network message)
 * @param fromMod    mod id of the sender, null when unknown
 * @param remote     true when it came over the network (from the server, or from a client)
 * @param clientSide true when it was delivered on the logical client (a network message from the server).
 *                   In singleplayer both logical sides run in one game and share listeners: check this flag.
 * @param player     UUID string of the sending player, for a network message received on the server; else null
 */
public record Message(String channel, Map<String, Object> data, byte[] bytes, Side fromSide, String fromMod,
                      boolean remote, boolean clientSide, String player) {
    public Message {
        Objects.requireNonNull(channel, "channel");
        data = data == null ? Map.of() : data;
        fromSide = fromSide == null ? Side.UNKNOWN : fromSide;
    }

    public boolean isBytes() { return bytes != null; }

    @Override
    public byte[] bytes() { return bytes == null ? null : bytes.clone(); }

    public Object get(String key) { return data.get(key); }

    public String getString(String key, String def) { return data.get(key) instanceof String s ? s : def; }

    public int getInt(String key, int def) { return data.get(key) instanceof Number n ? n.intValue() : def; }

    public long getLong(String key, long def) { return data.get(key) instanceof Number n ? n.longValue() : def; }

    public double getDouble(String key, double def) { return data.get(key) instanceof Number n ? n.doubleValue() : def; }

    public boolean getBoolean(String key, boolean def) { return data.get(key) instanceof Boolean b ? b : def; }

    @Override
    public String toString() {
        return "Message[" + channel + (isBytes() ? ", " + bytes.length + " bytes" : ", " + data) + ", from " + fromSide + "/" + fromMod
                + (remote ? ", remote" + (clientSide ? " (client)" : " (server)") + (player != null ? ", player " + player : "") : "") + "]";
    }
}
