package alloy.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Client diagnostics: log every screen change ("Alloy screen: ..."), so headless test runs can tell where the game is. */
// setScreen is on Minecraft up to 26.1 and on Gui from 26.2; ConflictReport.shouldApplyMixin keeps the one that has it
@Mixin(targets = {"net.minecraft.client.Minecraft", "net.minecraft.client.gui.Gui"}, remap = false)
public class ScreenLogMixin {
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void alloy$log(net.minecraft.client.gui.screens.Screen screen, CallbackInfo ci) {
        String title = "";
        try { title = screen == null ? "" : screen.getTitle().getString(); } catch (Throwable ignored) { }
        alloy.Bridge.LOG.info("Alloy screen: {} '{}'", screen == null ? "none (in game)" : screen.getClass().getName(), title);
    }
}
