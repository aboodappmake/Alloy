package alloy.compat.registry;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraftforge.registries.RegistryManager;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Runs Forge's {@code GameData.freezeData} (the FREEZE_DATA step: copy ACTIVE into the FROZEN snapshot) while holding the
 * monitor of {@code RegistryManager.FROZEN}. NamespacedWrapperLateRegister holds the same monitor for a late registration, so
 * a late entry from another thread (GeckoLib-style class inits run on resource reload workers on the client) is either in
 * ACTIVE before Forge copies it, or added to FROZEN by LateRegister after the copy, never lost in between. Behaviour is
 * otherwise unchanged; nothing else locks this object.
 */
@Mixin(targets = "net.minecraftforge.registries.GameData", remap = false)
public abstract class GameDataFreezeLock {
    @WrapMethod(method = "freezeData()V", remap = false)
    private static void alloy$freezeUnderLock(Operation<Void> original) {
        synchronized (RegistryManager.FROZEN) {
            original.call();
        }
    }
}
