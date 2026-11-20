package alloy.mixin;

import alloy.ScreenMergeHook;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Re-checks the current screen for overlapping duplicate buttons once per client tick after a (re)build. */
@Mixin(value = Minecraft.class, remap = false)
public abstract class MinecraftTickMergeMixin {
    @Inject(method = "tick()V", at = @At("HEAD"), remap = false)
    private void alloy$mergeButtons(CallbackInfo ci) {
        if (alloy.Screens.current() instanceof ScreenMergeHook h) h.alloy$tickMerge();   // Minecraft.screen up to 26.1, gui.screen() on 26.2
    }
}
