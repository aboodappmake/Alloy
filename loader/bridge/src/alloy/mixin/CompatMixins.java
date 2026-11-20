package alloy.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps Forge's behaviour at the call sites tools/PrepGame restored as Fabric-mixin anchors. */
public final class CompatMixins {
    /** Forge: "skip redirectOutputToLog, Forge already redirects stdout and stderr" (Bootstrap.java.patch). */
    @Mixin(targets = "net.minecraft.server.Bootstrap", remap = false)
    public static class NoWrapStreams {
        @Inject(method = "wrapStreams", at = @At("HEAD"), cancellable = true)
        private static void alloy$forgeSkipsThis(CallbackInfo ci) { ci.cancel(); }
    }

    /**
     * Networking: Forge and Fabric API both send the vanilla minecraft:register payload; Fabric API's encoder assumes
     * every minecraft:register payload is its own RegistrationPayload and fails on Forge's ForgePayload. Forge only
     * uses the remote side's list for Channel.isRemotePresent / ChannelRegistrationChangeEvent (its real version and
     * channel checks run over forge:handshake), so when Fabric's networking is present, Fabric owns that channel and
     * Forge's duplicate register/unregister sends are skipped.
     */
    @Mixin(targets = "net.minecraftforge.network.ChannelListManager", remap = false)
    public static class FabricOwnsRegisterChannel {
        private static boolean fabricNetworking() {
            return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("fabric-networking-api-v1");
        }

        @Inject(method = "addChannels(Lnet/minecraft/network/Connection;Ljava/util/Collection;)V", at = @At("HEAD"), cancellable = true)
        private static void alloy$skipAdd(net.minecraft.network.Connection c, java.util.Collection<?> ids, CallbackInfo ci) {
            if (fabricNetworking()) ci.cancel();
        }

        @Inject(method = "removeChannels(Lnet/minecraft/network/Connection;Ljava/util/Collection;)V", at = @At("HEAD"), cancellable = true)
        private static void alloy$skipRemove(net.minecraft.network.Connection c, java.util.Collection<?> ids, CallbackInfo ci) {
            if (fabricNetworking()) ci.cancel();
        }
    }

    /**
     * Counterpart of FabricOwnsRegisterChannel: Forge never sees the peer's register payload any more (Fabric's codec takes it), so
     * Forge's remote channel list (NetworkContext.remoteChannels, used by Channel.isRemotePresent, e.g. Serilum Collective's
     * sendToClient) stayed empty. Feed every channel the peer registers/unregisters through Fabric into that list.
     */
    @Mixin(targets = "net.fabricmc.fabric.impl.networking.AbstractChanneledNetworkAddon", remap = false)
    public static abstract class FabricRegisterFeedsForge {
        @org.spongepowered.asm.mixin.Shadow protected net.minecraft.network.Connection connection;

        @Inject(method = "register(Ljava/util/List;)V", at = @At("HEAD"), require = 0)
        private void alloy$mirrorRegister(java.util.List<?> ids, CallbackInfo ci) {
            alloy.ForgeChannelSync.update(connection, ids, true);
        }

        @Inject(method = "unregister(Ljava/util/List;)V", at = @At("HEAD"), require = 0)
        private void alloy$mirrorUnregister(java.util.List<?> ids, CallbackInfo ci) {
            alloy.ForgeChannelSync.update(connection, ids, false);
        }
    }

    /** Announce Forge's own channels in Fabric's register payloads, so the peer's Forge list matches ours. */
    @Mixin(targets = "net.fabricmc.fabric.impl.networking.AbstractNetworkAddon", remap = false)
    public static class FabricAnnouncesForgeChannels {
        @Inject(method = "getReceivableChannels()Ljava/util/Set;", at = @At("RETURN"), cancellable = true, require = 0)
        @SuppressWarnings({"unchecked", "rawtypes"})
        private void alloy$addForge(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<java.util.Set> cir) {
            java.util.List<?> forge = alloy.ForgeChannelSync.forgeChannels();
            if (forge.isEmpty()) return;
            java.util.Set all = new java.util.HashSet(cir.getReturnValue());
            all.addAll(forge);
            cir.setReturnValue(all);
        }
    }

    /**
     * Forge's PlayerList.getPlayers() (and getPlayersByUUID() on 26.2) returns an unmodifiable view it makes once, in the
     * constructor, over the players list. Fabric mods that swap that list for a new one after the constructor (Adventure
     * platform puts in a CopyOnWriteArrayList) leave Forge's view on the old, empty list: MinecraftServer then sends no
     * chunks to anyone (the chunk sender runs only for getPlayers()), and broadcasts and the tab list skip every player.
     * Return a view over whatever list the field holds now.
     */
    @Mixin(targets = "net.minecraft.server.players.PlayerList", remap = false)
    public static abstract class PlayerListLiveViews {
        @org.spongepowered.asm.mixin.Shadow private java.util.List<?> players;
        @org.spongepowered.asm.mixin.Shadow private java.util.Map<?, ?> playersByUUID;
        @org.spongepowered.asm.mixin.Unique private java.util.List<?> alloy$listSrc, alloy$listView;
        @org.spongepowered.asm.mixin.Unique private java.util.Map<?, ?> alloy$mapSrc, alloy$mapView;

        @Inject(method = "getPlayers()Ljava/util/List;", at = @At("HEAD"), cancellable = true)
        @SuppressWarnings({"unchecked", "rawtypes"})
        private void alloy$livePlayers(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<java.util.List> cir) {
            java.util.List<?> l = players;
            if (l != alloy$listSrc) { alloy$listView = java.util.Collections.unmodifiableList(l); alloy$listSrc = l; }
            cir.setReturnValue(alloy$listView);
        }

        @Inject(method = "getPlayersByUUID()Ljava/util/Map;", at = @At("HEAD"), cancellable = true, require = 0)
        @SuppressWarnings({"unchecked", "rawtypes"})
        private void alloy$livePlayersByUuid(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<java.util.Map> cir) {
            java.util.Map<?, ?> m = playersByUUID;
            if (m != alloy$mapSrc) { alloy$mapView = java.util.Collections.unmodifiableMap(m); alloy$mapSrc = m; }
            cir.setReturnValue(alloy$mapView);
        }
    }
}
