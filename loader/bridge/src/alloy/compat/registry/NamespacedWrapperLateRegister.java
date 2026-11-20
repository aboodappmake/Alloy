package alloy.compat.registry;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.RegistryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Fabric mods register content straight into vanilla registries ({@code Registry.register(BuiltInRegistries.X, ...)}) from
 * their {@code main}/{@code client} entrypoints, or from a static initializer a mixin triggers (GeckoLib: the data component in
 * GeckoLibConstants.&lt;clinit&gt;, called from SpecialModelRenderers.bootstrap). On Forge those registries are
 * {@code NamespacedWrapper}s whose {@code register} throws "Can not register to a locked registry. Modder should use Forge
 * Register methods." once the Forge registry is frozen. Seen in the Fabric matrix: JEI (JustEnoughItems.onInitialize), GeckoLib,
 * Xaero's Minimap (and Xaero's World Map, whose init aborts silently so {@code WorldMap.events} stays null).
 *
 * <p>Fix: a registration that does not come from Forge itself briefly re-opens the registry ({@code ForgeRegistry.unfreeze()}
 * clears both the ForgeRegistry flag and {@code NamespacedWrapper.locked}), lets Forge's own {@code register} run
 * ({@code ForgeRegistry.add} assigns the id and keeps the snapshot consistent), and freezes it again. Calls whose first
 * non-registry stack frame is in {@code net.minecraftforge.*} are left alone, so Forge keeps its "too late" protection.
 *
 * <p>Needs MixinExtras (@WrapMethod: try/finally around the whole method). The config entry to add when enabling
 * (rename ENABLED.pending to ENABLED too): {@code "registry.NamespacedWrapperLateRegister"} in alloy.compat.mixins.json
 * ("mixins" array). Tested by a stand-alone Fabric test jar built from this very file.
 *
 * <p>FROZEN snapshot: Forge copies ACTIVE to its FROZEN snapshot once, in FREEZE_DATA, and rebuilds the
 * registries from FROZEN on world load, world exit and server join. A registration let through after FREEZE_DATA (a client
 * class init during the first resource reload, a server-start hook) is therefore also added to FROZEN here, with the same id.
 * Every reopen is logged with its phase ("[Alloy] late registration ..."), so a run shows when such mods register.
 */
@Mixin(targets = "net.minecraftforge.registries.NamespacedWrapper", remap = false)
public abstract class NamespacedWrapperLateRegister<T> {
    @org.spongepowered.asm.mixin.Unique private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("Alloy");
    @Shadow(remap = false) boolean locked;
    @Shadow(remap = false) private ForgeRegistry<T> delegate;

    @WrapMethod(method = "register(Lnet/minecraft/resources/ResourceKey;Ljava/lang/Object;Lnet/minecraft/core/RegistrationInfo;)Lnet/minecraft/core/Holder$Reference;", remap = false)
    private Holder.Reference<T> alloy$allowFabricRegistration(ResourceKey<T> key, T value, RegistrationInfo info,
                                                                  Operation<Holder.Reference<T>> original) {
        if (!locked || delegate == null) return original.call(key, value, info);
        String caller = firstCaller();
        if (caller.startsWith("net.minecraftforge.")) return original.call(key, value, info);   // Forge keeps its "too late" error
        // FROZEN is the monitor GameDataFreezeLock holds around Forge's freezeData: a late entry lands either before the
        // ACTIVE -> FROZEN copy (and is copied by Forge) or after it (and is copied here), never in the middle.
        synchronized (RegistryManager.FROZEN) {
            Holder.Reference<T> h;
            // put back exactly what was there: the wrapper can be locked (vanilla freeze) while Forge's registry is still
            // open. Freezing it here then closed it before Forge's own mod was built ("Attempted to register the alias
            // forge:reach_distance -> forge:block_reach too late", MineDave, depending on thread timing).
            boolean wasFrozen = delegate.isLocked(), wasLocked = locked;
            delegate.unfreeze();
            try {
                h = original.call(key, value, info);
            } finally {
                if (wasFrozen) delegate.freeze();
                locked = wasLocked;
            }
            ForgeRegistry<T> frozen = RegistryManager.FROZEN.getRegistry(delegate.getRegistryName());
            String phase;
            if (frozen == null) {
                phase = "before FREEZE_DATA (Forge copies it to FROZEN)";
            } else {
                phase = "after FREEZE_DATA, " + copyToFrozen(frozen, key, value);
            }
            LOG.info("[Alloy] late registration {} into {} by {}: {}", key.identifier(), delegate.getRegistryName(), caller, phase);
            return h;
        }
    }

    /**
     * Forge rebuilds ACTIVE from FROZEN on world exit ({@code revertToFrozen}) and builds the world-load and server-join staging
     * registries from FROZEN ({@code injectSnapshot}). An entry that is only in ACTIVE vanishes there. Add it to FROZEN with
     * the id ACTIVE gave it (Forge picks the next free id if that one is taken, which only happens while a world with
     * remapped ids is open), the same way freezeData's own copy adds entries.
     */
    private String copyToFrozen(ForgeRegistry<T> frozen, ResourceKey<T> key, T value) {
        Identifier name = key.identifier();
        if (frozen.containsKey(name)) return "already in FROZEN";
        int id = delegate.getID(value);
        boolean wasLocked = frozen.isLocked();
        try {
            frozen.unfreeze();
            frozen.register(id, name, value);
        } catch (RuntimeException e) {
            LOG.error("[Alloy] could not copy late entry {} into Forge's FROZEN {}: it can disappear on world reload", name,
                    delegate.getRegistryName(), e);
            return "NOT copied to FROZEN (" + e + ")";
        } finally {
            if (wasLocked) frozen.freeze();
        }
        int got = frozen.getID(name);
        return got == id ? "copied to FROZEN as id " + id : "copied to FROZEN as id " + got + " (ACTIVE id " + id + ")";
    }

    /** First stack frame outside the registry classes, Alloy's own compat code and the JDK. */
    private static String firstCaller() {
        return StackWalker.getInstance().walk(frames -> frames
                .map(f -> f.getClassName())
                .filter(n -> !n.equals("net.minecraft.core.Registry") && !n.startsWith("net.minecraftforge.registries.NamespacedWrapper")
                        && !n.startsWith("alloy.compat.registry.") && !n.startsWith("java.") && !n.startsWith("jdk."))
                .findFirst().orElse("?"));
    }
}
