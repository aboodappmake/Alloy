package alloy.mixin;

import alloy.report.AlloyReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * COMMON mixin (register in alloy.mixins.json "mixins"; client and server). Feeds every saved crash report into the
 * Alloy report: classification, alloy-report.txt, and on a dedicated server the console block.
 * NOTE: lives in package alloy.mixin because that is the package of the mixin config; the source sits in report/.
 */
@Mixin(targets = "net.minecraft.CrashReport", remap = false)
public class CrashReportMixin {
    @Inject(method = "saveToFile(Ljava/nio/file/Path;Lnet/minecraft/ReportType;Ljava/util/List;)Z", at = @At("RETURN"))
    private void alloy$crash(java.nio.file.Path path, net.minecraft.ReportType type, java.util.List<String> extra, CallbackInfoReturnable<Boolean> cir) {
        net.minecraft.CrashReport self = (net.minecraft.CrashReport) (Object) this;
        AlloyReport.onCrash(self.getTitle(), self.getException(), cir.getReturnValueZ() ? path : null);
    }
}
