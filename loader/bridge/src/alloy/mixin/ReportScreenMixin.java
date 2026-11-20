package alloy.mixin;

import alloy.report.Entry;
import alloy.report.AlloyReport;
import alloy.report.ReportScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

/**
 * CLIENT mixin (register in alloy.mixins.json "client"). When Forge shows its raw LoadingErrorScreen because mod loading
 * failed, show the Alloy {@link ReportScreen} instead. A warnings-only LoadingErrorScreen is left alone (only logged).
 * Disable with -Dalloy.report.screen=false.
 * NOTE: lives in package alloy.mixin because that is the package of the mixin config; the source sits in report/.
 */
// setScreen is on Minecraft up to 26.1 and on Gui from 26.2; ConflictReport.shouldApplyMixin keeps the one that has it
@Mixin(targets = {"net.minecraft.client.Minecraft", "net.minecraft.client.gui.Gui"}, remap = false)
public class ReportScreenMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen alloy$report(Screen screen) {
        try {
            if (screen == null || !AlloyReport.SCREEN || !screen.getClass().getName().equals("net.minecraftforge.client.gui.LoadingErrorScreen")) return screen;
            List<net.minecraftforge.fml.ModLoadingException> errors = field(screen, "modLoadErrors");
            List<net.minecraftforge.fml.ModLoadingWarning> warnings = field(screen, "modLoadWarnings");
            List<Entry> found = AlloyReport.onForgeLoad(errors, warnings);
            if (found.isEmpty()) return screen;
            Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toAbsolutePath();
            return new ReportScreen(AlloyReport.errors(), AlloyReport.reportFile(), gameDir);
        } catch (Throwable t) {
            alloy.Bridge.LOG.warn("Alloy report: could not replace the error screen: {}", t.toString());
            return screen;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (List<T>) f.get(o);
    }
}
