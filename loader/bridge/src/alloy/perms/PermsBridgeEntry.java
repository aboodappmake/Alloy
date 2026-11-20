package alloy.perms;

import alloy.Bridge;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Main entrypoint of the permissions bridge (1.x builds, build_ui47 adds it). It only touches PermsBridge when a Fabric
 * mod brought fabric-permissions-api, so a game without it loads nothing from here.
 */
public final class PermsBridgeEntry implements ModInitializer {
    @Override
    public void onInitialize() {
        if ("false".equals(System.getProperty("alloy.permsBridge"))) return;
        if (!FabricLoader.getInstance().isModLoaded("fabric-permissions-api-v0")) return;
        PermsBridge.info = Bridge.LOG::info;
        PermsBridge.warn = Bridge.LOG::warn;
        PermsBridge.init(PermsBridgeEntry.class.getClassLoader());
    }
}
