package alloy.terrablender;

import alloy.Bridge;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Forge TerraBlender + Fabric mods that use the "terrablender" entrypoint (Biomes O' Plenty Fabric, Wilder Wild).
 * Fabric TerraBlender calls those entrypoints itself; Forge TerraBlender never does. When Forge TerraBlender's mod class
 * is constructed (mixin alloy.compat.terrablender.TerraBlenderForgeMixin), this registers a common-setup listener on
 * TerraBlender's own mod bus that calls onTerraBlenderInitialized() on every such entrypoint, each in its own try/catch.
 * Common setup is where Forge mods register their TerraBlender regions too.
 */
public final class TerraBlenderBridge {
    private TerraBlenderBridge() {}

    private static boolean registered;

    public static synchronized void onForgeTerraBlender() {
        if (registered) return;
        registered = true;
        try {
            FMLCommonSetupEvent.getBus(FMLJavaModLoadingContext.get().getModBusGroup())
                    .addListener(e -> e.enqueueWork(TerraBlenderBridge::callEntrypoints));
            Bridge.LOG.info("Alloy terrablender: Forge TerraBlender found, Fabric 'terrablender' entrypoints run at its common setup");
        } catch (Throwable t) {
            Bridge.LOG.error("Alloy terrablender: cannot hook Forge TerraBlender's common setup", t);
        }
    }

    static void callEntrypoints() {
        List<String> done = new ArrayList<>();
        for (EntrypointContainer<Object> c : FabricLoader.getInstance().getEntrypointContainers("terrablender", Object.class)) {
            String mod = c.getProvider().getMetadata().getId();
            try {
                Object ep = c.getEntrypoint();
                ep.getClass().getMethod("onTerraBlenderInitialized").invoke(ep);
                done.add(mod);
            } catch (Throwable t) {
                Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
                Bridge.LOG.error("Alloy terrablender: entrypoint of {} failed", mod, cause);
            }
        }
        Bridge.LOG.info("Alloy terrablender: called {} Fabric 'terrablender' entrypoint(s): {}", done.size(), done);
    }
}
