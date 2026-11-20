package alloy.mixin;

import alloy.ModsChooserScreen;
import alloy.ScreenMergeHook;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Forge and Fabric's Mod Menu both add a "Mods" button to the title (and pause) screen at the same spot, so the two
 * buttons overlap. After a screen builds its widgets, buttons with the same text whose bounds overlap are hidden and
 * replaced by one button that opens a chooser (ModsChooserScreen, which also holds the merge) with one entry per
 * original button. Generic: any two mods that stack identical buttons get the same treatment; a single button is left
 * untouched.
 *
 * Runs after every widget (re)build: the first init(int,int), and rebuildWidgets()/repositionElements(), which
 * Minecraft calls on resize and on fullscreen toggle (they recreate the original buttons without going through
 * init(int,int)). The merged button uses plain text so it renders like every other button.
 *
 * Minecraft 26.1 version. The 1.20.1 build uses bridge/src47/alloy/mixin/ModsButtonMixin.java (init(Minecraft,int,int)).
 */
@Mixin(value = Screen.class, remap = false)
public abstract class ModsButtonMixin implements ScreenMergeHook {
    /** client ticks left in which to re-check (mods may add buttons after the rebuild hooks) */
    @Unique private int alloy$recheck;

    @Shadow protected abstract <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget);
    @Shadow public abstract List<? extends GuiEventListener> children();

    @Inject(method = "init(II)V", at = @At("TAIL"), remap = false)
    private void alloy$afterInit(int width, int height, CallbackInfo ci) { alloy$merge(); alloy$recheck = 3; }

    @Inject(method = "rebuildWidgets()V", at = @At("TAIL"), remap = false)
    private void alloy$afterRebuild(CallbackInfo ci) { alloy$merge(); alloy$recheck = 3; }

    @Inject(method = "repositionElements()V", at = @At("TAIL"), remap = false)
    private void alloy$afterReposition(CallbackInfo ci) { alloy$merge(); alloy$recheck = 3; }

    @Override
    public void alloy$tickMerge() {
        if (alloy$recheck > 0) { alloy$recheck--; alloy$merge(); }
    }

    @Unique
    private void alloy$merge() {
        ModsChooserScreen.merge((Screen) (Object) this, children(), w -> addRenderableWidget(w));
    }
}
