package alloy.compat.itemchunk;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Client replacement for the disabled fabric-lifecycle-events-v1 client.LevelChunkMixin: fires ServerBlockEntityEvents for
 * chunks of the integrated server's ServerLevel and ClientBlockEntityEvents for ClientLevel chunks. Same four injectors as
 * ServerLevelChunkLifecycleMixin (see there). Register in the "client" list of alloy.mixins.json; do NOT load together
 * with the server variant (that would double-fire the server events).
 */
@Mixin(value = LevelChunk.class, remap = false)
public abstract class ClientLevelChunkLifecycleMixin {
    @Shadow
    public abstract Level getLevel();

    private void alloy$fireLoad(BlockEntity be) {
        Level level = this.getLevel();
        if (level instanceof ServerLevel serverLevel) {
            ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.invoker().onLoad(be, serverLevel);
        } else if (level instanceof ClientLevel clientLevel) {
            ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.invoker().onLoad(be, clientLevel);
        }
    }

    private void alloy$fireUnload(BlockEntity be) {
        Level level = this.getLevel();
        if (level instanceof ServerLevel serverLevel) {
            ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.invoker().onUnload(be, serverLevel);
        } else if (level instanceof ClientLevel clientLevel) {
            ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.invoker().onUnload(be, clientLevel);
        }
    }

    @WrapOperation(method = "setBlockEntity", at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object alloy$fabricLoad(Map<BlockPos, BlockEntity> map, Object pos, Object be, Operation<Object> original) {
        Object previous = original.call(map, pos, be);
        if (be instanceof BlockEntity blockEntity && blockEntity != previous) {
            this.alloy$fireLoad(blockEntity);
        }
        return previous;
    }

    @WrapOperation(method = "setBlockEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BlockEntity;setRemoved()V"))
    private void alloy$fabricUnloadReplaced(BlockEntity previous, Operation<Void> original) {
        original.call(previous);
        this.alloy$fireUnload(previous);
    }

    @WrapOperation(method = "removeBlockEntity(Lnet/minecraft/core/BlockPos;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BlockEntity;setRemoved()V"))
    private void alloy$fabricUnloadRemoved(BlockEntity removeThis, Operation<Void> original) {
        this.alloy$fireUnload(removeThis);
        original.call(removeThis);
    }

    /** Forge's early eviction: first Map.remove(Object) in getBlockEntity is blockEntities.remove(pos). */
    @WrapOperation(method = "getBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk$EntityCreationType;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;", ordinal = 0))
    private Object alloy$fabricUnloadEvicted(Map<BlockPos, BlockEntity> map, Object pos, Operation<Object> original) {
        Object removed = original.call(map, pos);
        if (removed instanceof BlockEntity blockEntity) {
            this.alloy$fireUnload(blockEntity);
        }
        return removed;
    }
}
