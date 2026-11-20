package alloy.api.spi;

import alloy.api.CrossEvent;
import alloy.api.Message;
import alloy.api.ModInfo;
import alloy.api.Side;
import alloy.api.Subscription;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * NOT for mods. The contract between the API jar and Alloy's implementation (alloy.api.impl.ApiBackend in the
 * Alloy bridge). All maps passed in are already plain-type, unmodifiable copies; {@code caller} is the first class
 * on the stack outside {@code alloy.api.*}, so the implementation can tell which mod and side is calling.
 *
 * <p>Compatibility rule: methods are only ever added (with defaults), never changed or removed.
 */
public interface AlloyBackend {
    /** API level this backend implements (1 = v1). */
    int apiLevel();

    /** Alloy version, e.g. "0.1.0". */
    String version();

    Side sideOf(Class<?> caller);

    /** mod id that owns {@code caller}, or null */
    String modOf(Class<?> caller);

    List<ModInfo> mods();

    Subscription listen(String event, Consumer<CrossEvent> listener, Class<?> caller);

    int post(String event, Map<String, Object> data, Class<?> caller);

    Subscription onMessage(String channel, Consumer<Message> listener, Class<?> caller);

    /** local delivery to the channel's listeners of other mods; data or bytes is null */
    int sendLocal(String channel, Map<String, Object> data, byte[] bytes, Class<?> caller);

    boolean networkAvailable();

    boolean sendToServer(String channel, Map<String, Object> data, byte[] bytes, Class<?> caller);

    /** {@code player} = UUID string, or null for every player; returns the number of players it was sent to */
    int sendToPlayers(String channel, String player, Map<String, Object> data, byte[] bytes, Class<?> caller);
}
