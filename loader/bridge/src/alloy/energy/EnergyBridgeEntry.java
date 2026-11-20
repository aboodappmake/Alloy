package alloy.energy;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Main entrypoint for the energy bridge. It only touches EnergyBridge (which refers to Fabric API's
 * transfer classes) when Team Reborn Energy is installed, so a game without Fabric API still starts.
 */
public final class EnergyBridgeEntry implements ModInitializer {
    @Override
    public void onInitialize() {
        if ("false".equals(System.getProperty("alloy.energy"))) return;
        if (!FabricLoader.getInstance().isModLoaded("team_reborn_energy")) return;
        EnergyBridge.init();
    }
}
