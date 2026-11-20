package alloy.worldsafe;

import alloy.world.WorldSafeClient;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CLIENT, Minecraft 1.20.1 and 1.20.2 (srcPick in versions/1.20.2.json). Opening a saved world from the world list (or Quick Play): Alloy checks it first and may
 * back it up and ask (alloy.world.WorldSafeClient). 1.20.1 and 1.20.2: loadLevel(Screen lastScreen, String levelId).
 */
@Mixin(value = WorldOpenFlows.class, remap = false)
public abstract class WorldOpenMixin {
    @Shadow @Final private LevelStorageSource levelSource;

    @Inject(method = "loadLevel", at = @At("HEAD"), cancellable = true)
    private void alloy$worldSafe(Screen lastScreen, String levelId, CallbackInfo ci) {
        WorldOpenFlows self = (WorldOpenFlows) (Object) this;
        if (WorldSafeClient.intercept(levelSource.getBaseDir().resolve(levelId), () -> self.loadLevel(lastScreen, levelId),
                () -> alloy.Screens.set(lastScreen))) ci.cancel();
    }
}
