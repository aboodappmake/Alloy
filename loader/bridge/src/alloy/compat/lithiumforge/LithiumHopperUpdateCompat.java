package alloy.compat.lithiumforge;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lithium (Fabric build) hopper caches on Forge's Level.setBlock.
 *
 * <p>Lithium's {@code block.hopper.LevelMixin} injects into {@code Level.setBlock} before the call to
 * {@code updatePOIOnBlockStateChange} and tells hoppers next to the changed block to drop their cached inventories
 * ({@code HopperHelper.updateHopperOnUpdateSuppression}) when the block changed without neighbour updates (flags without
 * UPDATE_NEIGHBORS, e.g. commands, structures, other mods, update suppression). Forge moves that part of setBlock into
 * its own {@code markAndNotifyBlock(pos, chunk, old, new, flags, recursion)}, so the injection finds no target there and
 * the fitter strips it (26.2 and 26.3). Without it a Lithium hopper keeps pulling from a chest that was replaced that way.
 *
 * <p>This does the same call at the same point of Forge's method. It only runs when Lithium's hopper mixins are on
 * (HopperBlockEntity implements Lithium's UpdateReceiver) and the level is Lithium's BlockEntityGetter, so nothing changes
 * without Lithium or with its hopper option off. A second call (a Lithium build whose own injection still applies) only
 * invalidates the same caches again.
 */
@Mixin(Level.class)
public abstract class LithiumHopperUpdateCompat {
    /** Lithium's HopperHelper.updateHopperOnUpdateSuppression(Level, BlockPos, int, LevelChunk, boolean), or NONE */
    @Unique private static volatile MethodHandle alloy$hopper;
    @Unique private static final MethodHandle NONE = MethodHandles.constant(Object.class, null);

    @WrapOperation(method = "markAndNotifyBlock", require = 0,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;updatePOIOnBlockStateChange(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V"))
    private void alloy$lithiumHopperUpdate(Level self, BlockPos pos, BlockState old, BlockState now, Operation<Void> original,
                                           @Local(argsOnly = true) LevelChunk chunk, @Local(argsOnly = true, ordinal = 0) int flags) {
        MethodHandle h = alloy$hopper;
        if (h == null) alloy$hopper = h = alloy$find(self);
        if (h != NONE) {
            try {
                h.invokeExact(self, pos, flags, chunk, old != now);
            } catch (Throwable t) {
                alloy$hopper = NONE;
                alloy.Bridge.LOG.warn("Alloy: Lithium hopper update on Forge's markAndNotifyBlock turned off: {}", t.toString());
            }
        }
        original.call(self, pos, old, now);
    }

    @Unique
    private static MethodHandle alloy$find(Level level) {
        try {
            ClassLoader cl = LithiumHopperUpdateCompat.class.getClassLoader();
            Class<?> receiver = Class.forName("net.caffeinemc.mods.lithium.common.hopper.UpdateReceiver", false, cl);
            Class<?> getter = Class.forName("net.caffeinemc.mods.lithium.common.world.blockentity.BlockEntityGetter", false, cl);
            Class<?> hopperBe = Class.forName("net.minecraft.world.level.block.entity.HopperBlockEntity", false, cl);
            if (!receiver.isAssignableFrom(hopperBe) || !getter.isInstance(level)) return NONE;   // Lithium's hopper option is off
            Class<?> helper = Class.forName("net.caffeinemc.mods.lithium.common.hopper.HopperHelper", false, cl);
            MethodHandle h = MethodHandles.publicLookup().findStatic(helper, "updateHopperOnUpdateSuppression",
                    MethodType.methodType(void.class, Level.class, BlockPos.class, int.class, LevelChunk.class, boolean.class));
            alloy.Bridge.LOG.info("Alloy: Lithium hopper updates run from Forge's Level.markAndNotifyBlock");
            return h;
        } catch (ReflectiveOperationException | LinkageError e) {
            return NONE;   // no Lithium, or another Lithium API
        }
    }
}
