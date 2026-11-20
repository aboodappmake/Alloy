package alloy.worldsafe;

import alloy.world.WorldSafeClient;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CLIENT. Opening a saved world from the world list (or Quick Play): Alloy checks it first and may back it up and ask
 * (alloy.world.WorldSafeClient). Minecraft 1.20.4: checkForBackupAndLoad(String, Runnable onCancel).
 */
@Mixin(value = WorldOpenFlows.class, remap = false)
public abstract class WorldOpenMixin {
    @Shadow @Final private LevelStorageSource levelSource;

    @Inject(method = "checkForBackupAndLoad", at = @At("HEAD"), cancellable = true)
    private void alloy$worldSafe(String levelId, Runnable onCancel, CallbackInfo ci) {
        WorldOpenFlows self = (WorldOpenFlows) (Object) this;
        if (WorldSafeClient.intercept(levelSource.getBaseDir().resolve(levelId), () -> self.checkForBackupAndLoad(levelId, onCancel), onCancel)) ci.cancel();
    }
}
