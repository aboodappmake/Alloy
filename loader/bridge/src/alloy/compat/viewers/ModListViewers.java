package alloy.compat.viewers;

import alloy.presence.Presence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forge mods ask ModList.isLoaded("jei") etc. to turn on their viewer support; on 26.1.2 the viewers exist only as Fabric
 * mods, which FML cannot see. When FML says no, alloy.presence answers: Fabric recipe viewers (jei, roughlyenoughitems,
 * emi) are a bridged list, data conditions see every Fabric mod, other code checks follow the caller rule.
 *
 * <p>Forge 64 has a static ModList.isLoaded, Forge 47 and 49 an instance one, and Mixin rejects a handler whose static
 * flag differs from the target's. ViewersMixinPlugin applies this class on a static target and {@link Instance} on an
 * instance target. (The 1.20.x bridge does not build alloy.compat.viewers; it hooks ModList through
 * compat120/presence.)
 */
@Mixin(targets = "net.minecraftforge.fml.ModList", remap = false)
public abstract class ModListViewers {
    @Inject(method = "isLoaded", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private static void alloy$presence(String modId, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && Presence.forgeIsLoaded(modId)) cir.setReturnValue(true);
    }

    /** Same hook for an instance ModList.isLoaded (Forge 47, 49). */
    @Mixin(targets = "net.minecraftforge.fml.ModList", remap = false)
    public abstract static class Instance {
        @Inject(method = "isLoaded", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
        private void alloy$presence(String modId, CallbackInfoReturnable<Boolean> cir) {
            if (!cir.getReturnValueZ() && Presence.forgeIsLoaded(modId)) cir.setReturnValue(true);
        }
    }
}
