package alloy.compat.architectury;

import com.llamalad7.mixinextras.sugar.Local;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.BlockEvent;
import dev.architectury.event.events.common.InteractionEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Restores the two Architectury events the Fabric build of Architectury loses on Forge-patched Minecraft.
 *
 * <p>Architectury's own handlers use {@code locals = CAPTURE_FAILHARD} with the vanilla local-variable layout:
 * {@code MixinMinecraft.rightClickAir} (Minecraft.startUseItem, INVOKE ItemStack.isEmpty ordinal 1) and
 * {@code MixinServerPlayerGameMode.onBreak} (ServerPlayerGameMode.destroyBlock, INVOKE BlockState.getBlock ordinal 0).
 * Forge adds locals to both methods (InputEvent.InteractionKeyMappingTriggered loop; ForgeHooks.onBlockBreakEvent's exp), so
 * Mixin throws "LVT ... has incompatible changes" when Minecraft / ServerPlayerGameMode is first transformed and the
 * client dies at startup. {@code fabric_matrix.py} strips both handlers ({@code lvt_lines}), which makes Architectury (and REI,
 * which needs it) load, but CLIENT_RIGHT_CLICK_AIR and BlockEvent.BREAK never fire. These mixins call the same events at the
 * equivalent Forge points, picking the locals by type (MixinExtras @Local) instead of by position.
 *
 * <ul>
 * <li>right click air: Forge fires ForgeEventFactory.onRightClickEmpty exactly when the held stack is empty and the hit is a MISS
 *     (the same condition Architectury checks), so the event is raised right before it, with the hand of that branch.</li>
 * <li>block break: after ForgeHooks.onBlockBreakEvent (Forge's BreakEvent, so Forge listeners and cancellation have run), at the
 *     first BlockState.getBlock() call; a FALSE result returns false from destroyBlock like Architectury's does.</li>
 * </ul>
 * Needs the architectury jar on the compile class path, so this stays in a pending folder; enable together with a mixin-config
 * plugin that applies it only when mod "architectury" is loaded (alloy.compat.mixins.json "mixins": architectury.ArchitecturyEventsCompat$...).
 * Tested through a stand-alone test jar (build_fixjar.py, FIXJAR_CP=architectury jar).
 */
public final class ArchitecturyEventsCompat {
    private ArchitecturyEventsCompat() {}

    @Mixin(targets = "net.minecraft.client.Minecraft", remap = false)
    public abstract static class RightClickAir {
        @Shadow(remap = false) public LocalPlayer player;

        @Inject(method = "startUseItem", remap = false,
                at = @At(value = "INVOKE", remap = false, ordinal = 0,
                        target = "Lnet/minecraftforge/event/ForgeEventFactory;onRightClickEmpty(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)V"))
        private void alloy$architecturyRightClickAir(CallbackInfo ci, @Local(type = InteractionHand.class) InteractionHand hand) {
            InteractionEvent.CLIENT_RIGHT_CLICK_AIR.invoker().click(this.player, hand);
        }
    }

    @Mixin(targets = "net.minecraft.server.level.ServerPlayerGameMode", remap = false)
    public abstract static class BlockBreak {
        @Shadow(remap = false) protected ServerLevel level;
        @Shadow(remap = false) protected ServerPlayer player;

        @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z", remap = false, cancellable = true,
                at = @At(value = "INVOKE", remap = false, ordinal = 0,
                        target = "Lnet/minecraft/world/level/block/state/BlockState;getBlock()Lnet/minecraft/world/level/block/Block;"))
        private void alloy$architecturyBlockBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir, @Local(ordinal = 0) BlockState state) {
            EventResult r = BlockEvent.BREAK.invoker().breakBlock(this.level, pos, state, this.player, null);
            if (r.isFalse()) cir.setReturnValue(false);
        }
    }
}
