package alloy.compat.registry;

import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code PoiType} is a record, so its {@code hashCode}/{@code equals} are computed from {@code matchingStates}. Moonlight
 * (Fabric, {@code RegHelper.addExtraPOIStatesRegistration}, used by Amendments) adds block states to vanilla PoI types by
 * replacing that final field in place ({@code PoiTypeAccessor.setMatchingStates}, a @Mutable accessor) after the type is
 * registered. Vanilla and Fabric registries look values up by identity, so that is harmless there. Forge's
 * {@code ForgeRegistry} keeps its values in hash-based {@code BiMap}s: after the change the leatherworker PoI type (cauldron
 * states plus Amendments' liquid/dye cauldrons) no longer finds its own id or name, and the next snapshot injection fails
 * with "Registry entry for minecraft:point_of_interest_type PoiType[...], id -1, doesn't yield a name" when a world is
 * opened (Fabric matrix: fab_amendments, client stuck at "Preparing Resources...").
 *
 * <p>Fix: PoI types compare and hash by identity. They are registry singletons; nothing compares two different PoiType
 * instances by content (the game uses holders and keys), so this only makes Forge's maps behave like vanilla's.
 */
@Mixin(PoiType.class)
public abstract class PoiTypeIdentityMixin {
    @Inject(method = "hashCode", at = @At("HEAD"), cancellable = true, remap = false)
    private void alloy$identityHash(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(System.identityHashCode(this));
    }

    @Inject(method = "equals", at = @At("HEAD"), cancellable = true, remap = false)
    private void alloy$identityEquals(Object other, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this == other);
    }
}
