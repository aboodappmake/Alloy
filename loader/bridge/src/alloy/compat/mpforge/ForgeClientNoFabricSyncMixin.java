package alloy.compat.mpforge;

import net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager;
import net.fabricmc.fabric.impl.registry.sync.packet.RegistrySyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.mixin.networking.accessor.ServerCommonPacketListenerImplAccessor;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraftforge.network.ConnectionType;
import net.minecraftforge.network.NetworkContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A pure Forge client (handshake hostname carries the "\0FORGE" marker, so Forge's NetworkContext type is MODDED) that does
 * not speak Fabric's registry sync channel must not be kicked with "requires Fabric Loader and Fabric API": Forge's own
 * registry sync (forge:sync_registries) handles it. Alloy clients can receive the Fabric payload and keep the Fabric sync;
 * vanilla clients (type VANILLA) fall through to Fabric's original decision.
 */
@Mixin(value = RegistrySyncManager.class, remap = false)
public abstract class ForgeClientNoFabricSyncMixin {
    @Inject(method = "configureClient", at = @At("HEAD"), cancellable = true)
    private static void alloy$skipForPureForge(ServerConfigurationPacketListenerImpl handler, MinecraftServer server, CallbackInfo ci) {
        Connection connection = ((ServerCommonPacketListenerImplAccessor) handler).getConnection();
        if (NetworkContext.get(connection).getType() == ConnectionType.MODDED
                && !ServerConfigurationNetworking.canSend(handler, RegistrySyncPayload.ID)) {
            ci.cancel();
        }
    }
}
