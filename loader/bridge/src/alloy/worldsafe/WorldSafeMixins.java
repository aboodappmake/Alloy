package alloy.worldsafe;

import alloy.world.McNbt;
import alloy.world.WorldSafe;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * World safety hooks shared by every Minecraft version Alloy builds (1.20.1 and 26.x use the same names and the same
 * call shapes here). Config alloy.worldsafe.mixins.json, defaultRequire 0: a hook that does not apply on some version
 * costs that feature only, never the game. The work is in alloy.world.WorldSafe.
 */
public final class WorldSafeMixins {
    private WorldSafeMixins() {}

    /** Every world open (client and dedicated server) goes through validateAndCreateAccess: backup, check, server refusal. */
    @Mixin(value = LevelStorageSource.class, remap = false)
    public abstract static class Access {
        @Inject(method = "validateAndCreateAccess", at = @At("HEAD"))
        private void alloy$worldSafe(String levelId, CallbackInfoReturnable<LevelStorageSource.LevelStorageAccess> cir) {
            WorldSafe.beforeAccess(((LevelStorageSource) (Object) this).getBaseDir().resolve(levelId));
        }
    }

    /**
     * level.dat: the root compound right before it is written, after Forge's writeAdditionalLevelSaveData added "fml".
     * Alloy adds "alloy" (Fabric mod list, fingerprint). 1.20.1 writes it with NbtIo.writeCompressed inside
     * saveDataTag; 26.x hands it to the private saveLevelData(CompoundTag). Only the saveDataTag path is hooked, so
     * renaming or upgrading a world in the world list never stamps the current mod list on a world that was not loaded.
     * "saveDataTag*" and "load*": the names are overloaded, and a plain name only matches the first method.
     */
    @Mixin(value = LevelStorageSource.LevelStorageAccess.class, remap = false)
    public abstract static class Save {
        @ModifyArg(method = "saveDataTag*", at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/NbtIo;writeCompressed"), index = 0)
        private CompoundTag alloy$rootOld(CompoundTag root) {
            McNbt.levelSave(root);
            return root;
        }

        @ModifyArg(method = "saveDataTag*", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;saveLevelData(Lnet/minecraft/nbt/CompoundTag;)V"), index = 0)
        private CompoundTag alloy$root(CompoundTag root) {
            McNbt.levelSave(root);
            return root;
        }
    }

    /** Player files: the keeper remembers mod data of missing mods when a file is read and puts it back when it is written. */
    @Mixin(value = PlayerDataStorage.class, remap = false)
    public abstract static class Players {
        @ModifyExpressionValue(method = "load*", at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/NbtIo;readCompressed"))
        private CompoundTag alloy$read(CompoundTag tag) {
            McNbt.playerRead(tag);
            return tag;
        }

        @ModifyArg(method = "save*", at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/NbtIo;writeCompressed"), index = 0)
        private CompoundTag alloy$write(CompoundTag tag) {
            return McNbt.playerWrite(tag);
        }
    }
}
