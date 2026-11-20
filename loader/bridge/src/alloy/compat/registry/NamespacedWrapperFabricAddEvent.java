package alloy.compat.registry;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.event.registry.RegistryEntryAddedCallback;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraftforge.registries.RegistryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric API fires {@code RegistryEntryAddedCallback} from an injection at the RETURN of {@code MappedRegistry.register}.
 * Forge's {@code NamespacedWrapper} overrides {@code register} and never calls super (it goes to {@code ForgeRegistry.add}),
 * so on every Forge-wrapped registry (blocks, items, menus, data components, sounds, ...) the event never fired. Fabric API's
 * own menu API needs it: {@code impl.menu.Networking} fills {@code CODEC_BY_ID} for {@code ExtendedMenuType}s from the entries
 * that exist at its {@code onInitialize} plus this event, so a menu type registered later had no codec and
 * {@code sendOpenPacket} threw when a player opened the screen. Fabric's state id tracker and "for every block" compat code in
 * mods use the same event.
 *
 * <p>Where: RETURN of {@code NamespacedWrapper.onAdded}, not of {@code NamespacedWrapper.register}. {@code onAdded} is the
 * wrapper callback {@code ForgeRegistry.add} calls for every entry that really landed (after the id, name and holder are
 * bound), so one hook covers both paths: a plain {@code Registry.register} from a Fabric or vanilla caller, and Forge's
 * {@code RegisterEvent} ({@code ForgeRegistry.register}) for Forge mods. A hook on {@code register} would miss every Forge mod
 * entry. {@code NamespacedDefaultedWrapper.onAdded} calls super, so blocks and items are covered once.
 *
 * <p>Not twice: {@code onAdded} also runs for the copies Forge keeps (VANILLA, FROZEN, staging; each has its own wrapper), and
 * again for every entry when Forge rebuilds ACTIVE from a snapshot ({@code ForgeRegistry.sync}: world load, world exit, server
 * join). So the event fires only for the ACTIVE stage, and only the first time a name is added to this wrapper. Fabric's own
 * injection on {@code MappedRegistry.register} cannot fire for these registries (that method is never reached), so Fabric's
 * path and this one never overlap.
 *
 * <p>Not MODDED: Fabric's own handler also marks the registry {@code RegistryAttribute.MODDED} (its {@code onChange}). That is
 * deliberately not done here. MODDED wakes up Fabric's registry sync and id remap, which writes the {@code MappedRegistry} id
 * fields that the wrapper never reads (a Fabric mod registering into a Forge registry). Forge's own snapshot sync keeps ids in step.
 *
 * <p>A listener that throws is logged and does not abort {@code ForgeRegistry.add} half way (that would leave the Forge
 * registry and its wrapper out of step). Known gap: after a Forge snapshot rebuild the numeric ids can change, and Fabric's
 * {@code RegistryIdRemapCallback} is not fired for that (Forge's own {@code IdMappingEvent} is).
 */
@Mixin(targets = "net.minecraftforge.registries.NamespacedWrapper", remap = false)
public abstract class NamespacedWrapperFabricAddEvent<T> {
    // Not initialised in a field initializer: Forge calls onAdded while the wrapper is still being constructed
    // (before field initializers of the mixed-in class run), so the set is created on first use.
    @Unique private volatile Set<Identifier> alloy$announced;
    @Unique private static int alloy$fabricSync = -1;   // -1 unknown, 0 no fabric-registry-sync-v0, 1 present

    @Inject(method = "onAdded", at = @At("RETURN"), remap = false)
    private void alloy$fireFabricAdded(RegistryManager stage, int id, ResourceKey<T> key, T value, T oldValue,
                                       CallbackInfoReturnable<Holder.Reference<T>> cir) {
        if (stage != RegistryManager.ACTIVE || key == null || value == null) return;
        Set<Identifier> seen = alloy$announced;
        if (seen == null) {
            synchronized (this) {
                seen = alloy$announced;
                if (seen == null) alloy$announced = seen = ConcurrentHashMap.newKeySet();
            }
        }
        if (!seen.add(key.identifier())) return;   // snapshot rebuild of an entry Fabric already saw
        if (alloy$fabricSync < 0) {
            alloy$fabricSync = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("fabric-registry-sync-v0") ? 1 : 0;
        }
        if (alloy$fabricSync == 0) return;
        try {
            @SuppressWarnings("unchecked") net.minecraft.core.Registry<T> self = (net.minecraft.core.Registry<T>) (Object) this;
            RegistryEntryAddedCallback.event(self).invoker().onEntryAdded(id, key.identifier(), value);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger("Alloy").error("[Alloy] a Fabric RegistryEntryAddedCallback listener failed for {} in {}",
                    key.identifier(), key.registry(), e);
        }
    }
}
