package alloy.compat.terrablender;

import alloy.terrablender.TerraBlenderBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forge TerraBlender's @Mod class: hook its common setup for Fabric "terrablender" entrypoints. Inert without it. */
@Mixin(targets = "terrablender.core.TerraBlenderForge", remap = false)
public abstract class TerraBlenderForgeMixin {
    @Inject(method = "<init>", at = @At("TAIL"), remap = false, require = 0)
    private void alloy$fabricEntrypoints(CallbackInfo ci) {
        TerraBlenderBridge.onForgeTerraBlender();
    }
}
