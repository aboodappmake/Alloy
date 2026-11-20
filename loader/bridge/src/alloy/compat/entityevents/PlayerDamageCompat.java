package alloy.compat.entityevents;

import alloy.DamageTrack;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Player overrides actuallyHurt with its own Forge hook calls: report them like LivingEntity's (EntityEventsCompat). */
@Mixin(value = Player.class, remap = false)
public abstract class PlayerDamageCompat {
    @WrapOperation(method = "actuallyHurt", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/minecraftforge/common/ForgeHooks;onLivingHurt(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;F)F"))
    private float alloy$trackLivingHurt(LivingEntity entity, DamageSource source, float amount, Operation<Float> original) {
        float result = original.call(entity, source, amount);
        ((DamageTrack) (Object) this).alloy$forgeHurt(amount, result);
        return result;
    }

    @WrapOperation(method = "actuallyHurt", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/minecraftforge/common/ForgeHooks;onLivingDamage(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;F)F"))
    private float alloy$trackLivingDamage(LivingEntity entity, DamageSource source, float amount, Operation<Float> original) {
        float result = original.call(entity, source, amount);
        ((DamageTrack) (Object) this).alloy$forgeDamage(amount, result);
        return result;
    }
}
