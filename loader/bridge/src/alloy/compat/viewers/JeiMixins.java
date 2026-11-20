package alloy.compat.viewers;

import alloy.viewers.ViewerBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/** Fabric JEI: Forge @JeiPlugin classes join JEI's plugin list (see alloy.viewers.ViewerBridge). Targets by name: inert without JEI. */
public final class JeiMixins {
    private JeiMixins() {}

    @Mixin(targets = "mezz.jei.fabric.startup.FabricPluginFinder", remap = false)
    public abstract static class FabricFinder {
        @SuppressWarnings({"unchecked", "rawtypes"})
        @Inject(method = "getModPlugins", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
        private static void alloy$addForgePlugins(CallbackInfoReturnable<List> cir) {
            List<Object> out = new ArrayList<>((List<Object>) cir.getReturnValue());   // the finder may return an immutable list
            ViewerBridge.jeiFinder(out);
            cir.setReturnValue(out);
        }
    }

    @Mixin(targets = "mezz.jei.library.startup.JeiStarter", remap = false)
    public abstract static class LateAdd {
        @Inject(method = "start", at = @At("HEAD"), remap = false, require = 0)
        private void alloy$lateForgePlugins(CallbackInfo ci) {
            ViewerBridge.jeiLateAdd(this);
        }
    }
}
