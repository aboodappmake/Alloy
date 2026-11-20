package alloy.compat.loading;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraftforge.client.loading.ClientModLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge's mod loading (its last stages and FREEZE_DATA) runs inside the first resource reload. RRLS (Remove Reloading
 * Screen, a Fabric mod) lets the game go on while that reload is still busy, so a world could be opened before Forge had
 * frozen its registries; Forge then threw "frozen is null" in GameData.injectSnapshot and the world never opened
 * (MineDave, Sensible MC). Opening a world now waits, on the render thread's own task loop, until Forge's client mod
 * loading is done. Without such a mod nothing waits: loading is already over when a world can be picked.
 */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenWaitsForForge {
    @Inject(method = "openWorldLoadLevelStem", at = @At("HEAD"), require = 0)
    private void alloy$waitForForgeLoading(CallbackInfo ci) {
        if (!ClientModLoader.isLoading()) return;
        org.slf4j.LoggerFactory.getLogger("Alloy").info("[Alloy] a world is opening while Forge's mod loading still runs (RRLS-like mod); waiting for it");
        Minecraft.getInstance().managedBlock(() -> !ClientModLoader.isLoading());
    }
}
