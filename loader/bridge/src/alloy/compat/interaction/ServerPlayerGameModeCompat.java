package alloy.compat.interaction;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Full replacement for Fabric API fabric-events-interaction-v0's ServerPlayerGameModeMixin (keep that one disabled).
 *
 * <p>Three injectors apply to Forge's patched ServerPlayerGameMode exactly as on Fabric (HEAD of
 * handleBlockBreakAction / useItemOn / useItem; they run before Forge's own LeftClickBlock / RightClickBlock /
 * onItemRightClick code because they sit at HEAD). The two PlayerBlockBreakEvents injectors are moved:
 * <ul>
 *   <li>BEFORE/CANCELED: Fabric hooked Block.playerWillDestroy in destroyBlock. We wrap Forge's
 *       {@code ForgeHooks.onBlockBreakEvent} call (the first thing destroyBlock does after reading the state) so that
 *       every player action is Fabric-first: BEFORE runs in front of Forge's BreakEvent and of
 *       {@code ItemStack.onBlockStartBreak} (AOE tools break their neighbours there). It is guarded by the same
 *       pre-checks Forge's BreakEvent applies (GameMasterBlock, ItemStack.canDestroyBlock, blockActionRestricted), so
 *       adventure/spectator still never fire BEFORE. On deny we do what Forge's own deny does: resend the block and its
 *       BlockEntity packet, and report -1 (destroyBlock returns false), so Forge's BreakEvent is never posted.</li>
 *   <li>AFTER: Fabric hooked Block.destroy in destroyBlock. Forge moved it into removeBlock; we inject after that
 *       call. The BlockEntity is captured at the head of removeBlock (before onDestroyedByPlayer removes it) in a
 *       MixinExtras @Share local, so nested destroyBlock calls (tree-fellers, vein-miners in mineBlock/destroy) cannot
 *       clobber it.</li>
 * </ul>
 * Known remaining difference: Forge runs ItemStack.mineBlock (tool durability) before removeBlock, so an AFTER listener
 * sees the tool already damaged (an empty hand if it just broke); on Fabric alone AFTER runs before mineBlock.
 */
@Mixin(value = ServerPlayerGameMode.class, remap = false)
public abstract class ServerPlayerGameModeCompat {
    @Shadow @Final protected ServerPlayer player;
    @Shadow protected ServerLevel level;

    // ---- identical to Fabric ----------------------------------------------------------------------------------

    @Inject(method = "handleBlockBreakAction", at = @At("HEAD"), cancellable = true, remap = false)
    private void alloy$startBlockBreak(BlockPos pos, ServerboundPlayerActionPacket.Action action, Direction direction,
                                          int worldHeight, int sequence, CallbackInfo info) {
        if (action != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) return;

        InteractionResult result = AttackBlockCallback.EVENT.invoker().interact(this.player, this.level, InteractionHand.MAIN_HAND, pos, direction);

        if (result != InteractionResult.PASS) {
            // The client might have broken the block on its side, so make sure to let it know.
            this.player.connection.send(new ClientboundBlockUpdatePacket(this.level, pos));

            if (this.level.getBlockState(pos).hasBlockEntity()) {
                BlockEntity blockEntity = this.level.getBlockEntity(pos);
                if (blockEntity != null) {
                    Packet<ClientGamePacketListener> updatePacket = blockEntity.getUpdatePacket();
                    if (updatePacket != null) this.player.connection.send(updatePacket);
                }
            }
            info.cancel();
        }
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true, remap = false)
    private void alloy$interactBlock(ServerPlayer player, Level world, ItemStack stack, InteractionHand hand,
                                        BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result = UseBlockCallback.EVENT.invoker().interact(player, world, hand, hitResult);
        if (result != InteractionResult.PASS) {
            cir.setReturnValue(result);
        }
    }

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true, remap = false)
    private void alloy$interactItem(ServerPlayer player, Level world, ItemStack stack, InteractionHand hand,
                                       CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result = UseItemCallback.EVENT.invoker().interact(player, world, hand);
        if (result != InteractionResult.PASS) {
            cir.setReturnValue(result);
        }
    }

    // ---- moved for Forge --------------------------------------------------------------------------------------

    /** PlayerBlockBreakEvents.BEFORE / CANCELED, in front of Forge's BreakEvent (see class comment). */
    @WrapOperation(method = "destroyBlock", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/minecraftforge/common/ForgeHooks;onBlockBreakEvent(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/GameType;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/core/BlockPos;)I"))
    private int alloy$fabricBeforeFirst(Level level, GameType gameType, ServerPlayer player, BlockPos pos, Operation<Integer> original) {
        BlockState state = this.level.getBlockState(pos);
        ItemStack tool = player.getMainHandItem();
        boolean vanillaAllows =
                !(state.getBlock() instanceof GameMasterBlock && !player.canUseGameMasterBlocks())
                && (tool.isEmpty() || tool.canDestroyBlock(state, level, pos, player))
                && !player.blockActionRestricted(level, pos, gameType);
        if (vanillaAllows) {
            BlockEntity blockEntity = this.level.getBlockEntity(pos);
            if (!PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(this.level, player, pos, state, blockEntity)) {
                PlayerBlockBreakEvents.CANCELED.invoker().onBlockBreakCanceled(this.level, player, pos, state, blockEntity);
                // like Forge's own deny: resend the block and its BlockEntity data (the client predicted the break)
                player.connection.send(new ClientboundBlockUpdatePacket(this.level, pos));
                BlockEntity current = this.level.getBlockEntity(pos);
                if (current != null) {
                    Packet<ClientGamePacketListener> updatePacket = current.getUpdatePacket();
                    if (updatePacket != null) player.connection.send(updatePacket);
                }
                return -1;
            }
        }
        return original.call(level, gameType, player, pos);
    }

    /** Captures the BlockEntity before onDestroyedByPlayer removes it; local to this removeBlock call. */
    @Inject(method = "removeBlock", at = @At("HEAD"), remap = false)
    private void alloy$captureBlockEntity(BlockPos pos, boolean canHarvest, CallbackInfoReturnable<Boolean> cir,
                                             @Share("breakBlockEntity") LocalRef<BlockEntity> breakBlockEntity) {
        breakBlockEntity.set(this.level.getBlockEntity(pos));
    }

    /** PlayerBlockBreakEvents.AFTER, right after Block.destroy, only when the block was really removed. */
    @Inject(method = "removeBlock", remap = false,
            at = @At(value = "INVOKE", remap = false, shift = At.Shift.AFTER,
                    target = "Lnet/minecraft/world/level/block/Block;destroy(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"))
    private void alloy$onBlockBroken(BlockPos pos, boolean canHarvest, CallbackInfoReturnable<Boolean> cir,
                                        @Local(type = BlockState.class, ordinal = 0) BlockState state,
                                        @Share("breakBlockEntity") LocalRef<BlockEntity> breakBlockEntity) {
        PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(this.level, this.player, pos, state, breakBlockEntity.get());
    }
}
