package alloy.api;

import alloy.api.spi.AlloyBackend;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A named message channel ("modid:name"), from {@link Alloy#channel(String)}.
 *
 * <p><b>In the same game</b> ({@link #send}): the message goes synchronously to every listener of this channel that
 * belongs to a <i>different</i> mod (a mod never receives its own local messages), on either side.
 *
 * <p><b>Over the network</b> ({@link #sendToServer}, {@link #sendToPlayer}, {@link #sendToAllPlayers}): Alloy
 * carries it on its own single payload channel ("alloyloader:api"); on arrival it goes to every listener of this channel
 * on the other machine, on its main thread, with {@link Message#remote()} = true. A message only goes to a peer that
 * runs Alloy; otherwise it is dropped and the send reports false / 0. Treat data from clients as untrusted.
 * Size limits: about 32 KB from a client, 1 MB from the server.
 *
 * <p>Payloads: a map of plain types (see {@link PlainData}, copied on send) or a byte array (copied).
 * On plain Fabric / plain Forge nothing is delivered and every send reports 0 / false.
 */
public final class Channel {
    private final String name;
    private final Class<?> owner;

    Channel(String name, Class<?> owner) {
        this.name = name;
        this.owner = owner;
    }

    public String name() { return name; }

    /** Listen for messages on this channel (local and network). */
    public Subscription onMessage(Consumer<Message> listener) {
        Objects.requireNonNull(listener, "listener");
        AlloyBackend b = Alloy.backend();
        return b == null ? Subscription.NONE : b.onMessage(name, listener, owner);
    }

    /** Send to listeners of other mods in this game. Returns how many listeners were called. */
    public int send(Map<String, ?> data) {
        Map<String, Object> d = PlainData.copyMap(data);
        AlloyBackend b = Alloy.backend();
        return b == null ? 0 : b.sendLocal(name, d, null, owner);
    }

    /** Bytes variant of {@link #send(Map)}. */
    public int send(byte[] bytes) {
        byte[] c = Objects.requireNonNull(bytes, "bytes").clone();
        AlloyBackend b = Alloy.backend();
        return b == null ? 0 : b.sendLocal(name, null, c, owner);
    }

    /** True when Alloy's network channel is registered in this game (not whether a peer is connected). */
    public boolean isNetworkAvailable() {
        AlloyBackend b = Alloy.backend();
        return b != null && b.networkAvailable();
    }

    /** Client only: send to the server. False when not connected or the server does not run Alloy. */
    public boolean sendToServer(Map<String, ?> data) {
        Map<String, Object> d = PlainData.copyMap(data);
        AlloyBackend b = Alloy.backend();
        return b != null && b.sendToServer(name, d, null, owner);
    }

    public boolean sendToServer(byte[] bytes) {
        byte[] c = Objects.requireNonNull(bytes, "bytes").clone();
        AlloyBackend b = Alloy.backend();
        return b != null && b.sendToServer(name, null, c, owner);
    }

    /** Server only: send to one player (UUID string). False when not online or the client does not run Alloy. */
    public boolean sendToPlayer(String playerUuid, Map<String, ?> data) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Map<String, Object> d = PlainData.copyMap(data);
        AlloyBackend b = Alloy.backend();
        return b != null && b.sendToPlayers(name, playerUuid, d, null, owner) > 0;
    }

    public boolean sendToPlayer(String playerUuid, byte[] bytes) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        byte[] c = Objects.requireNonNull(bytes, "bytes").clone();
        AlloyBackend b = Alloy.backend();
        return b != null && b.sendToPlayers(name, playerUuid, null, c, owner) > 0;
    }

    /** Server only: send to every connected Alloy client. Returns how many players it was sent to. */
    public int sendToAllPlayers(Map<String, ?> data) {
        Map<String, Object> d = PlainData.copyMap(data);
        AlloyBackend b = Alloy.backend();
        return b == null ? 0 : b.sendToPlayers(name, null, d, null, owner);
    }

    public int sendToAllPlayers(byte[] bytes) {
        byte[] c = Objects.requireNonNull(bytes, "bytes").clone();
        AlloyBackend b = Alloy.backend();
        return b == null ? 0 : b.sendToPlayers(name, null, null, c, owner);
    }

    @Override
    public String toString() { return "Channel[" + name + "]"; }
}
