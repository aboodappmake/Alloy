package alloy.api;

import alloy.api.spi.AlloyBackend;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A small event bus shared by both sides. Any mod can post an event ("namespace:path" + a plain-type data map) and
 * any mod on either side can listen. Listeners run synchronously on the posting thread, in registration order; a
 * listener that throws is logged and skipped, the others still run. Every listener (also the poster's own) is called.
 *
 * <p>Built-in events, fired by Alloy on the logical server thread (names in the reserved "alloy" namespace,
 * which mods cannot post to):
 * <ul>
 *   <li>{@link #PLAYER_JOIN} / {@link #PLAYER_LEAVE}: {@code uuid} (String), {@code name} (String)</li>
 *   <li>{@link #SERVER_STARTED} / {@link #SERVER_STOPPING}: {@code dedicated} (Boolean)</li>
 *   <li>{@link #BLOCK_BROKEN}: {@code x}, {@code y}, {@code z} (Integer), {@code dimension} (String, e.g.
 *       "minecraft:overworld"), {@code state_id} (Integer, Minecraft's block-state id), {@code block} (String registry
 *       id, e.g. "minecraft:stone"), {@code player} (String UUID). Fired once the break is final: after every Forge
 *       listener, and only when nobody (Forge or Fabric) cancelled it.</li>
 * </ul>
 * On plain Fabric / plain Forge nothing is ever delivered: {@link #listen} returns {@link Subscription#NONE} and
 * {@link #post} returns 0.
 */
public final class CrossEvents {
    private CrossEvents() {}

    public static final String PLAYER_JOIN = "alloy:player_join";
    public static final String PLAYER_LEAVE = "alloy:player_leave";
    public static final String SERVER_STARTED = "alloy:server_started";
    public static final String SERVER_STOPPING = "alloy:server_stopping";
    public static final String BLOCK_BROKEN = "alloy:block_broken";

    /** Listen for an event by name. Close the returned subscription to stop. */
    public static Subscription listen(String event, Consumer<CrossEvent> listener) {
        Alloy.checkName("event name", event);
        Objects.requireNonNull(listener, "listener");
        AlloyBackend b = Alloy.backend();
        return b == null ? Subscription.NONE : b.listen(event, listener, Alloy.caller());
    }

    /**
     * Post an event to every listener on both sides.
     *
     * @param data plain types only (see {@link PlainData}); copied, so later changes to it are not seen
     * @return number of listeners called
     * @throws IllegalArgumentException for a bad name, a "alloy:" name, or a non-plain value
     */
    public static int post(String event, Map<String, ?> data) {
        Alloy.checkName("event name", event);
        if (event.startsWith("alloy:"))
            throw new IllegalArgumentException("Alloy: the \"alloy\" event namespace is reserved for built-in events");
        Map<String, Object> copy = PlainData.copyMap(data);
        AlloyBackend b = Alloy.backend();
        return b == null ? 0 : b.post(event, copy, Alloy.caller());
    }
}
