package alloy.compat.itemchunk;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Dedicated-server replacement for the disabled fabric-lifecycle-events-v1 server.LevelChunkMixin
 * (ServerBlockEntityEvents.BLOCK_ENTITY_LOAD / BLOCK_ENTITY_UNLOAD). All four of its injectors are redone here; the
 * getBlockEntity one at Forge's new call site (Forge moved the "isRemoved -> blockEntities.remove(pos)" branch in front of
 * createBlockEntity, so Fabric's slice starting at createBlockEntity no longer contains a Map.remove).
 * WrapOperation is used instead of Fabric's Redirect/Local so it composes with other mods and needs no local capture.
 * Register in the "server" list of alloy.mixins.json (the client variant covers both sides on a client).
 */
@Mixin(value = LevelChunk.class, remap = false)
public abstract class ServerLevelChunkLifecycleMixin {
    @Shadow
    public abstract Level getLevel();

    /** setBlockEntity: Map.put(pos, be) -> LOAD (Fabric: only when the put did not return the same instance). */
    @WrapOperation(method = "setBlockEntity", at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object alloy$fabricLoad(Map<BlockPos, BlockEntity> map, Object pos, Object be, Operation<Object> original) {
        Object previous = original.call(map, pos, be);
        if (be instanceof BlockEntity blockEntity && blockEntity != previous && this.getLevel() instanceof ServerLevel serverLevel) {
            ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.invoker().onLoad(blockEntity, serverLevel);
        }
        return previous;
    }

    /** setBlockEntity: the replaced entry gets setRemoved() -> UNLOAD right after (the method has a single setRemoved call). */
    @WrapOperation(method = "setBlockEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BlockEntity;setRemoved()V"))
    private void alloy$fabricUnloadReplaced(BlockEntity previous, Operation<Void> original) {
        original.call(previous);
        if (this.getLevel() instanceof ServerLevel serverLevel) {
            ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.invoker().onUnload(previous, serverLevel);
        }
    }

    /** removeBlockEntity: UNLOAD fires just BEFORE setRemoved() (Fabric injected at the INVOKE, shift BEFORE). */
    @WrapOperation(method = "removeBlockEntity(Lnet/minecraft/core/BlockPos;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BlockEntity;setRemoved()V"))
    private void alloy$fabricUnloadRemoved(BlockEntity removeThis, Operation<Void> original) {
        if (this.getLevel() instanceof ServerLevel serverLevel) {
            ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.invoker().onUnload(removeThis, serverLevel);
        }
        original.call(removeThis);
    }

    /**
     * getBlockEntity: the "entry is already removed" eviction. In Forge's patched method the first Map.remove(Object) is
     * blockEntities.remove(pos) (before the pending/creation logic); the second is pendingBlockEntities.remove(pos).
     */
    @WrapOperation(method = "getBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk$EntityCreationType;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;", ordinal = 0))
    private Object alloy$fabricUnloadEvicted(Map<BlockPos, BlockEntity> map, Object pos, Operation<Object> original) {
        Object removed = original.call(map, pos);
        if (removed instanceof BlockEntity blockEntity && this.getLevel() instanceof ServerLevel serverLevel) {
            ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.invoker().onUnload(blockEntity, serverLevel);
        }
        return removed;
    }
}
