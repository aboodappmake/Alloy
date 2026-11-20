package alloy;

/**
 * Added to every Screen by mixin.ModsButtonMixin. Some mods add their buttons after a screen's (re)build hooks ran
 * (e.g. Mod Menu on resize / fullscreen toggle), so the overlap merge is re-checked once on the next client tick
 * (mixin.MinecraftTickMergeMixin).
 */
public interface ScreenMergeHook {
    void alloy$tickMerge();
}
