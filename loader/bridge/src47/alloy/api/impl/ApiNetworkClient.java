package alloy.api.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.event.EventNetworkChannel;

/** Client-only half of ApiNetwork (never loaded on a dedicated server). Forge 47 / Minecraft 1.20.1 version. */
final class ApiNetworkClient {
    private ApiNetworkClient() {}

    static boolean send(EventNetworkChannel ch, byte[] frame) {
        ClientPacketListener l = Minecraft.getInstance().getConnection();
        if (l == null) return false;
        Connection c = l.getConnection();
        if (c == null || !ch.isRemotePresent(c)) return false;
        c.send(ApiNetwork.packet(NetworkDirection.PLAY_TO_SERVER, frame));
        return true;
    }
}
