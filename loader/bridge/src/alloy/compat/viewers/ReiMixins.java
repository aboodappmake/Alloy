package alloy.compat.viewers;

import alloy.viewers.ViewerBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

/** Fabric REI: Forge @REIPluginCommon / @REIPluginClient classes are registered next to REI's Fabric entrypoint plugins. */
@Mixin(targets = "me.shedaniel.rei.fabric.PluginDetectorImpl", remap = false)
public abstract class ReiMixins {
    @Inject(method = "detectCommonPlugins", at = @At("TAIL"), remap = false, require = 0)
    private void alloy$forgeCommonPlugins(CallbackInfo ci) {
        ViewerBridge.reiCommon();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "detectClientPlugins", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private void alloy$forgeClientPlugins(CallbackInfoReturnable<Supplier> cir) {
        cir.setReturnValue(ViewerBridge.reiClientSupplier((Supplier<Runnable>) cir.getReturnValue()));
    }
}
