package alloy.api.impl;

import alloy.api.Message;
import alloy.api.Side;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.commons.lang3.tuple.Pair;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client/server transport for API channels: ONE Alloy-owned Forge payload channel, "alloyloader:api", optional on
 * both ends (vanilla, pure Fabric and pure Forge peers still connect; they just never get API messages).
 *
 * Forge 47 / Minecraft 1.20.1 version (bridge/src47 replaces bridge/src in the Forge 47 build). Forge 47 has the older
 * networking: NetworkRegistry.newEventChannel (version string, accept-missing predicates) instead of ChannelBuilder,
 * NetworkEvent with a Context supplier instead of CustomPayloadEvent, and packets built with NetworkDirection.buildPacket.
 * Same channel name, protocol version, frame layout and size limits as the 26.1 source.
 *
 * Frame: utf channel, utf sender mod ("" = unknown), byte sender side, boolean isBytes, byte[] payload
 * (PlainCodec map or raw bytes). Received frames are decoded on the network thread and delivered on the main thread.
 */
final class ApiNetwork {
    private ApiNetwork() {}

    static final String CHANNEL = "alloyloader:api";
    static final int PROTOCOL = 1;
    /** vanilla caps serverbound custom payloads at 32767 bytes; leave room for the channel id */
    static final int MAX_TO_SERVER = 32_000;
    static final int MAX_TO_CLIENT = 1_000_000;

    private static final ResourceLocation NAME = new ResourceLocation("alloyloader", "api");
    private static volatile EventNetworkChannel channel;

    /**
     * Registers the channel. ApiHooks runs this from the bridge's Fabric "main" entrypoint, which on Alloy runs after
     * Forge's mod loading, i.e. after NetworkRegistry.lock(). The lock only guards against channels added once
     * connections exist; there are none yet at this point, so the lock flag is lifted for this one registration and set back.
     */
    static synchronized void init() {
        if (channel != null) return;
        Field lock = null;
        boolean wasLocked = false;
        try {
            try {
                lock = NetworkRegistry.class.getDeclaredField("lock");
                lock.setAccessible(true);
                wasLocked = lock.getBoolean(null);
                if (wasLocked) lock.setBoolean(null, false);
            } catch (ReflectiveOperationException | RuntimeException e) {
                lock = null;   // no such field in this Forge: register normally
            }
            String version = Integer.toString(PROTOCOL);
            EventNetworkChannel ch = NetworkRegistry.newEventChannel(NAME, () -> version,
                    NetworkRegistry.acceptMissingOr(version), NetworkRegistry.acceptMissingOr(version));
            ch.addListener(ApiNetwork::onServerPayload);
            ch.addListener(ApiNetwork::onClientPayload);
            channel = ch;
            ApiBackend.LOG.info("Alloy API: network channel {} v{} registered{} (Forge 47 networking)", CHANNEL, PROTOCOL,
                    wasLocked ? " (after Forge's network lock)" : "");
        } catch (Throwable t) {
            ApiBackend.LOG.warn("Alloy API: could not register network channel {}; API network messages are off (local ones still work)", CHANNEL, t);
        } finally {
            if (lock != null && wasLocked) {
                try { lock.setBoolean(null, true); } catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
        }
    }

    static boolean available() { return channel != null; }

    static byte[] frame(String name, Map<String, Object> data, byte[] bytes, Owner from) {
        FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.buffer());
        try {
            b.writeUtf(name);
            b.writeUtf(from.mod() == null ? "" : from.mod());
            b.writeByte(from.side().ordinal());
            b.writeBoolean(bytes != null);
            b.writeByteArray(bytes != null ? bytes : PlainCodec.encode(data));
            byte[] out = new byte[b.readableBytes()];
            b.readBytes(out);
            return out;
        } finally {
            b.release();
        }
    }

    private static void checkSize(byte[] f, int max) {
        if (f.length > max) throw new IllegalArgumentException("Alloy: message is " + f.length + " bytes, the limit this way is " + max);
    }

    /** A vanilla custom payload packet on the channel, in the given direction (index MIN_VALUE: no index byte, like EventNetworkChannel). */
    static net.minecraft.network.protocol.Packet<?> packet(NetworkDirection dir, byte[] frame) {
        return dir.buildPacket(Pair.of(new FriendlyByteBuf(Unpooled.wrappedBuffer(frame)), Integer.MIN_VALUE), NAME).getThis();
    }

    static boolean sendToServer(String name, Map<String, Object> data, byte[] bytes, Owner from) {
        EventNetworkChannel ch = channel;
        if (ch == null || FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) return false;
        byte[] f = frame(name, data, bytes, from);
        checkSize(f, MAX_TO_SERVER);
        return ApiNetworkClient.send(ch, f);
    }

    static int sendToPlayers(String name, String player, Map<String, Object> data, byte[] bytes, Owner from) {
        EventNetworkChannel ch = channel;
        if (ch == null) return 0;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return 0;
        List<ServerPlayer> targets;
        if (player == null) targets = List.copyOf(server.getPlayerList().getPlayers());
        else {
            UUID id;
            try {
                id = UUID.fromString(player);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Alloy: \"" + player + "\" is not a player UUID", e);
            }
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            targets = p == null ? List.of() : List.of(p);
        }
        if (targets.isEmpty()) return 0;
        byte[] f = frame(name, data, bytes, from);
        checkSize(f, MAX_TO_CLIENT);
        int n = 0;
        for (ServerPlayer p : targets) {
            if (p.connection == null) continue;
            Connection c = p.connection.connection;
            if (c == null || !ch.isRemotePresent(c)) continue;
            c.send(packet(NetworkDirection.PLAY_TO_CLIENT, f));
            n++;
        }
        return n;
    }

    private static void onServerPayload(NetworkEvent.ServerCustomPayloadEvent e) { onPayload(e, false); }

    private static void onClientPayload(NetworkEvent.ClientCustomPayloadEvent e) { onPayload(e, true); }

    private static void onPayload(NetworkEvent e, boolean client) {
        NetworkEvent.Context ctx = e.getSource().get();
        FriendlyByteBuf buf = e.getPayload();
        if (buf == null) return;
        ctx.setPacketHandled(true);
        Message m;
        try {
            String name = buf.readUtf(256);
            String mod = buf.readUtf(256);
            int side = buf.readByte();
            boolean isBytes = buf.readBoolean();
            byte[] payload = buf.readByteArray(client ? MAX_TO_CLIENT : MAX_TO_SERVER);
            Side s = side >= 0 && side < Side.values().length ? Side.values()[side] : Side.UNKNOWN;
            ServerPlayer sender = client ? null : ctx.getSender();
            m = new Message(name, isBytes ? Map.of() : PlainCodec.decode(payload), isBytes ? payload : null, s,
                    mod.isEmpty() ? null : mod, true, client, sender == null ? null : sender.getStringUUID());
        } catch (RuntimeException ex) {
            ApiBackend.LOG.warn("Alloy API: dropped a malformed {} payload ({}): {}", CHANNEL, client ? "from the server" : "from a client", ex.toString());
            return;
        }
        ctx.enqueueWork(() -> ApiBackend.get().hub.deliverRemote(m));
    }
}
