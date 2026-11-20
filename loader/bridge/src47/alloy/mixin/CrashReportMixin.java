package alloy.mixin;

import alloy.report.AlloyReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * COMMON mixin, Minecraft 1.20.1 version (bridge/src47 replaces bridge/src in the Forge 47 build). Feeds every saved
 * crash report into the Alloy report. 1.20.1 has CrashReport.saveToFile(File) instead of 26.1's
 * saveToFile(Path, ReportType, List).
 */
@Mixin(targets = "net.minecraft.CrashReport", remap = false)
public class CrashReportMixin {
    @Inject(method = "saveToFile(Ljava/io/File;)Z", at = @At("RETURN"))
    private void alloy$crash(java.io.File file, CallbackInfoReturnable<Boolean> cir) {
        net.minecraft.CrashReport self = (net.minecraft.CrashReport) (Object) this;
        AlloyReport.onCrash(self.getTitle(), self.getException(), cir.getReturnValueZ() && file != null ? file.toPath() : null);
    }
}
