package alloy.api.impl;

import alloy.api.CrossEvent;
import alloy.api.Message;
import alloy.api.ModInfo;
import alloy.api.Side;
import alloy.api.Subscription;
import alloy.api.spi.AlloyBackend;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Alloy's implementation of the Alloy API. The API jar finds it by name (alloy.api.Alloy.IMPL) through
 * Knot, which defines both the API classes and the bridge, so no registration call is needed for the API to report
 * "Alloy present". Built-in events and the network channel come from {@link ApiHooks}.
 */
public final class ApiBackend implements AlloyBackend {
    static final Logger LOG = LoggerFactory.getLogger("Alloy API");
    private static final ApiBackend INSTANCE = new ApiBackend();

    final Hub hub = new Hub();
    private volatile String version;

    private ApiBackend() {}

    /** Called reflectively by alloy.api.Alloy. */
    public static AlloyBackend instance() { return INSTANCE; }

    static ApiBackend get() { return INSTANCE; }

    @Override public int apiLevel() { return 1; }

    @Override
    public String version() {
        String v = version;
        if (v == null) {
            v = System.getProperty("alloy.version");
            if (v == null) v = FabricLoader.getInstance().getModContainer("alloyloader")
                    .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
            version = v;
        }
        return v;
    }

    @Override public Side sideOf(Class<?> caller) { return Owners.of(caller).side(); }

    @Override public String modOf(Class<?> caller) { return Owners.of(caller).mod(); }

    @Override public List<ModInfo> mods() { return Owners.mods(); }

    @Override
    public Subscription listen(String event, Consumer<CrossEvent> listener, Class<?> caller) {
        return hub.listenEvent(event, listener, Owners.of(caller));
    }

    @Override
    public int post(String event, Map<String, Object> data, Class<?> caller) {
        return hub.postEvent(event, data, Owners.of(caller));
    }

    @Override
    public Subscription onMessage(String channel, Consumer<Message> listener, Class<?> caller) {
        return hub.listenChannel(channel, listener, Owners.of(caller));
    }

    @Override
    public int sendLocal(String channel, Map<String, Object> data, byte[] bytes, Class<?> caller) {
        Owner from = Owners.of(caller);
        return hub.sendLocal(new Message(channel, data, bytes, from.side(), from.mod(), false, false, null), from);
    }

    @Override public boolean networkAvailable() { return ApiNetwork.available(); }

    @Override
    public boolean sendToServer(String channel, Map<String, Object> data, byte[] bytes, Class<?> caller) {
        return ApiNetwork.sendToServer(channel, data, bytes, Owners.of(caller));
    }

    @Override
    public int sendToPlayers(String channel, String player, Map<String, Object> data, byte[] bytes, Class<?> caller) {
        return ApiNetwork.sendToPlayers(channel, player, data, bytes, Owners.of(caller));
    }
}
