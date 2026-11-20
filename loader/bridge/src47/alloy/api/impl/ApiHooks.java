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
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wires Alloy's built-in cross-side events (on Forge's event bus, which always exists in Alloy, so they fire
 * whether or not Fabric API is installed) and registers the network channel. Runs as the bridge's Fabric "main"
 * entrypoint (after Forge's mod loading, before any server starts or player connects). Forge mods may register their
 * API listeners earlier (in their constructor): the API itself needs no hooks for that.
 *
 * Forge 47 / Minecraft 1.20.1 version (bridge/src47 replaces bridge/src in the Forge 47 build): eventbus 6 listeners on
 * MinecraftForge.EVENT_BUS instead of eventbus 7's per-event BUS fields, and ResourceLocation names. Same events, same data.
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
            var bus = MinecraftForge.EVENT_BUS;
            bus.addListener(EventPriority.NORMAL, false, PlayerEvent.PlayerLoggedInEvent.class, e -> fire(CrossEvents.PLAYER_JOIN, player(e.getEntity())));
            bus.addListener(EventPriority.NORMAL, false, PlayerEvent.PlayerLoggedOutEvent.class, e -> fire(CrossEvents.PLAYER_LEAVE, player(e.getEntity())));
            bus.addListener(EventPriority.NORMAL, false, ServerStartedEvent.class, e -> fire(CrossEvents.SERVER_STARTED, Map.of("dedicated", e.getServer().isDedicatedServer())));
            bus.addListener(EventPriority.NORMAL, false, ServerStoppingEvent.class, e -> fire(CrossEvents.SERVER_STOPPING, Map.of("dedicated", e.getServer().isDedicatedServer())));
            // like eventbus 7's monitor listener: runs after every other Forge listener (LOWEST) and also sees cancelled
            // events, so a cancelled break is skipped here
            bus.addListener(EventPriority.LOWEST, true, BlockEvent.BreakEvent.class, e -> {
                if (!e.isCanceled() && ApiBackend.get().hub.hasEventListeners(CrossEvents.BLOCK_BROKEN)) fire(CrossEvents.BLOCK_BROKEN, block(e));
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
        m.put("dimension", e.getLevel() instanceof Level l ? l.dimension().location().toString() : "unknown");
        m.put("state_id", Block.getId(state));
        m.put("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        if (e.getPlayer() != null) m.put("player", e.getPlayer().getStringUUID());
        return Collections.unmodifiableMap(m);
    }
}
