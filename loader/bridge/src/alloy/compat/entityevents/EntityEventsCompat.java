package alloy.compat.entityevents;

import alloy.DamageTrack;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replacements for Fabric API fabric-entity-events-v1 LivingEntityMixin injectors that cannot apply on Forge's
 * LivingEntity, plus a Forge-aware replacement for AFTER_DAMAGE.
 *
 * <p><b>SET_BED_OCCUPATION_STATE</b> ({@code setOccupiedState}): Forge replaced vanilla's
 * {@code level.setBlock(pos, state.setValue(BedBlock.OCCUPIED, b), 3)} in {@code lambda$stopSleeping$0} and
 * {@code startSleeping} with {@code state.setBedOccupied(level, pos, this, b)}, so we redirect that call. Fabric's
 * untouched {@code modifyBedForOccupiedState} (ModifyVariable on the {@code Level.getBlockState} assignment) swaps the
 * receiver for {@code Blocks.RED_BED} when an ALLOW_BED listener returns ALLOW (for non-bed blocks such as sleeping
 * bags). Like Fabric's original handler we therefore ignore the receiver and re-read the real block: listeners get the
 * real state, and the occupied property is only written to a real block that has it (through Forge's setBedOccupied on
 * the real state, so custom Forge beds keep working).
 *
 * <p><b>MODIFY_SLEEPING_DIRECTION</b> ({@code onGetSleepingDirection}, stripped): Forge's {@code getBedOrientation} asks
 * {@code BlockState.getBedDirection} instead of {@code BedBlock.getBedOrientation}; we wrap that call instead.
 *
 * <p><b>AFTER_DAMAGE</b>: Fabric's {@code afterDamage} injector is stripped from its mixin (compat_replaces.txt entry
 * {@code LivingEntityMixin#afterDamage}) because it reads hurtServer's pre-Forge {@code damage} argument. We fire the
 * event ourselves at the same TAIL with Fabric's arguments, but damageTaken is Forge's final damage (LivingDamageEvent
 * result), and nothing fires when Forge cancelled or zeroed the hit.
 */
@Mixin(value = LivingEntity.class, remap = false)
public abstract class EntityEventsCompat implements DamageTrack {
    // ---- sleeping ---------------------------------------------------------------------------------------------

    @Redirect(method = {"lambda$stopSleeping$0", "startSleeping"}, remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/minecraft/world/level/block/state/BlockState;setBedOccupied(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/LivingEntity;Z)V"))
    private void alloy$setBedOccupied(BlockState receiver, Level level, BlockPos pos, LivingEntity sleeper, boolean occupied) {
        // receiver may be Fabric's RED_BED swap (ALLOW_BED = ALLOW); the real block is what is in the world.
        BlockState real = level.getBlockState(pos);
        if (EntitySleepEvents.SET_BED_OCCUPATION_STATE.invoker().setBedOccupationState((LivingEntity) (Object) this, pos, real, occupied)) {
            return;
        }
        // unswapped: Forge behaviour (custom beds may override setBedOccupied without an OCCUPIED property);
        // swapped: Fabric behaviour, only touch a real block that has OCCUPIED
        if (receiver == real || real.hasProperty(BedBlock.OCCUPIED)) {
            real.setBedOccupied(level, pos, sleeper, occupied);
        }
    }

    @WrapOperation(method = "getBedOrientation", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getBedDirection(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Direction;"))
    private Direction alloy$modifySleepingDirection(BlockState state, LevelReader level, BlockPos pos, Operation<Direction> original) {
        Direction direction = original.call(state, level, pos);
        return EntitySleepEvents.MODIFY_SLEEPING_DIRECTION.invoker().modifySleepDirection((LivingEntity) (Object) this, pos, direction);
    }

    // ---- AFTER_DAMAGE -----------------------------------------------------------------------------------------

    /** Forge's final damage (LivingDamageEvent result) of the current hurtServer call, NaN when not known. */
    @Unique private float alloy$dealt = Float.NaN;
    /** Forge cancelled or zeroed the current hit (LivingHurtEvent / LivingDamageEvent). */
    @Unique private boolean alloy$forgeZeroed;

    @Inject(method = "hurtServer", at = @At("HEAD"), remap = false)
    private void alloy$resetDealt(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        this.alloy$dealt = Float.NaN;
        this.alloy$forgeZeroed = false;
    }

    @WrapOperation(method = "actuallyHurt", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/minecraftforge/common/ForgeHooks;onLivingHurt(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;F)F"))
    private float alloy$trackLivingHurt(LivingEntity entity, DamageSource source, float amount, Operation<Float> original) {
        float result = original.call(entity, source, amount);
        alloy$forgeHurt(amount, result);
        return result;
    }

    @Override
    public void alloy$forgeHurt(float in, float out) {
        if (in > 0 && out <= 0) this.alloy$forgeZeroed = true;
    }

    @Override
    public void alloy$forgeDamage(float in, float out) {
        // in == 0 means vanilla absorption ate everything (not Forge): keep the pre-Forge value then
        if (in > 0) {
            if (out <= 0) this.alloy$forgeZeroed = true;
            else this.alloy$dealt = out;
        }
    }

    @WrapOperation(method = "actuallyHurt", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/minecraftforge/common/ForgeHooks;onLivingDamage(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;F)F"))
    private float alloy$trackLivingDamage(LivingEntity entity, DamageSource source, float amount, Operation<Float> original) {
        float result = original.call(entity, source, amount);
        alloy$forgeDamage(amount, result);
        return result;
    }

    /**
     * Replacement for Fabric's stripped {@code LivingEntityMixin#afterDamage} (compat_replaces.txt). Same semantics as
     * Fabric: TAIL of hurtServer (so not on the early "no damage / invulnerable" returns), skipped when the entity is
     * dead or dying, baseDamageTaken = the {@code originalDamage} local, blocked = the {@code blocked} local. The only
     * difference: damageTaken is Forge's final amount (LivingDamageEvent result) when actuallyHurt ran, and nothing
     * fires when Forge cancelled or zeroed the hit.
     */
    @Inject(method = "hurtServer", at = @At("TAIL"), remap = false)
    private void alloy$afterDamage(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir,
                                      @Local(name = "originalDamage") float originalDamage, @Local(name = "blocked") boolean blocked) {
        boolean zeroed = this.alloy$forgeZeroed;
        float dealt = this.alloy$dealt;
        this.alloy$forgeZeroed = false;
        this.alloy$dealt = Float.NaN;
        LivingEntity self = (LivingEntity) (Object) this;
        if (zeroed || self.isDeadOrDying()) return;
        ServerLivingEntityEvents.AFTER_DAMAGE.invoker().afterDamage(self, source, originalDamage, Float.isNaN(dealt) ? amount : dealt, blocked);
    }
}
