package alloy.api.impl;

import alloy.api.CrossEvents;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.listener.ObjBooleanBiConsumer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wires Alloy's built-in cross-side events (on Forge's event bus, which always exists in Alloy, so they fire
 * whether or not Fabric API is installed) and registers the network channel. Runs as the bridge's Fabric "main"
 * entrypoint (after Forge's mod loading, before any server starts or player connects). Forge mods may register their
 * API listeners earlier (in their constructor): the API itself needs no hooks for that.
 */
public final class ApiHooks implements ModInitializer {
    private static boolean installed;

    @Override
    public void onInitialize() { install(); }

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        ApiNetwork.init();
        try {
            PlayerEvent.PlayerLoggedInEvent.BUS.addListener(e -> fire(CrossEvents.PLAYER_JOIN, player(e.getEntity())));
            PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(e -> fire(CrossEvents.PLAYER_LEAVE, player(e.getEntity())));
            ServerStartedEvent.BUS.addListener(e -> fire(CrossEvents.SERVER_STARTED, Map.of("dedicated", e.getServer().isDedicatedServer())));
            ServerStoppingEvent.BUS.addListener(e -> fire(CrossEvents.SERVER_STOPPING, Map.of("dedicated", e.getServer().isDedicatedServer())));
            // monitor listener: runs after every other Forge listener and is told whether the break was cancelled
            BlockEvent.BreakEvent.BUS.addListener((ObjBooleanBiConsumer<BlockEvent.BreakEvent>) (e, cancelled) -> {
                if (!cancelled && ApiBackend.get().hub.hasEventListeners(CrossEvents.BLOCK_BROKEN)) fire(CrossEvents.BLOCK_BROKEN, block(e));
            });
            ApiBackend.LOG.info("Alloy API {}: built-in cross-side events registered", alloy.api.Alloy.API_VERSION);
        } catch (Throwable t) {
            ApiBackend.LOG.error("Alloy API: could not register built-in events", t);
        }
    }

    private static void fire(String name, Map<String, Object> data) {
        ApiBackend.get().hub.postEvent(name, data, Owner.ALLOY);
    }

    private static Map<String, Object> player(Player p) {
        return Map.of("uuid", p.getStringUUID(), "name", p.getScoreboardName());
    }

    private static Map<String, Object> block(BlockEvent.BreakEvent e) {
        BlockPos pos = e.getPos();
        BlockState state = e.getState();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", pos.getX());
        m.put("y", pos.getY());
        m.put("z", pos.getZ());
        m.put("dimension", e.getLevel() instanceof Level l ? l.dimension().identifier().toString() : "unknown");
        m.put("state_id", Block.getId(state));
        m.put("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        if (e.getPlayer() != null) m.put("player", e.getPlayer().getStringUUID());
        return Collections.unmodifiableMap(m);
    }
}
