package alloy.api.impl;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.EventNetworkChannel;

/** Client-only half of ApiNetwork (never loaded on a dedicated server). */
final class ApiNetworkClient {
    private ApiNetworkClient() {}

    static boolean send(EventNetworkChannel ch, byte[] frame) {
        ClientPacketListener l = Minecraft.getInstance().getConnection();
        if (l == null) return false;
        Connection c = l.getConnection();
        if (c == null || !ch.isRemotePresent(c)) return false;
        ch.send(new FriendlyByteBuf(Unpooled.wrappedBuffer(frame)), c);
        return true;
    }
}
