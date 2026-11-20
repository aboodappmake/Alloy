package alloy.compat.registry;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.Registry;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.RegistryBuilder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forge patches a few vanilla constructors to look up the Forge "delegate" of an object right away. The one that matters is
 * {@code BucketItem(Fluid, Item.Properties)}: it stores {@code ForgeRegistries.FLUIDS.getDelegateOrThrow(fluid)}. Forge only
 * makes a delegate when the object is added to the registry, so a bucket built for a fluid that is not registered yet throws
 * {@code No delegate exists for value ...}. Vanilla's constructor only keeps the fluid, so on Fabric this is fine, and Fabric
 * mods do it: Tech Reborn's {@code ModFluids} enum builds the still fluid, the flowing fluid, the block and the bucket in its
 * constructor and registers all of them later. On Forge mods are told to use the Supplier constructor instead.
 *
 * <p>Fix: when {@code getDelegateOrThrow(value)} has no delegate and the registry is an intrusive one (blocks, fluids, items,
 * entity types, block entity types), return the object's own intrusive holder ({@code Fluid.builtInRegistryHolder()} and so
 * on, the holder vanilla makes in the constructor). That is the holder vanilla code would use; Forge's wrapper binds its key
 * when the object is registered ({@code NamespacedWrapper.onAdded}), so it resolves the same way afterwards. Only the case
 * that threw changes; registered objects still get Forge's normal delegate. {@code getDelegate(value)} (the Optional one) is
 * not touched, so code that uses it to ask "is this registered?" still gets the Forge answer.
 *
 * <p>One log line per registry the first time this happens, with the object, so a pack report shows it.
 */
@Mixin(value = ForgeRegistry.class, remap = false)
public abstract class ForgeRegistryEarlyDelegate<V> {
    @Shadow @Final private Map<V, Holder.Reference<V>> delegatesByValue;
    @Shadow abstract RegistryBuilder<V> getBuilder();
    @Shadow public abstract ResourceKey<Registry<V>> getRegistryKey();

    @Unique private static final Set<Object> alloy$logged = ConcurrentHashMap.newKeySet();
    /** Off switch for A/B runs: {@code -Dalloy.earlyDelegate=false} gives Forge's own behaviour (the throw). */
    @Unique private static final boolean alloy$off = "false".equals(System.getProperty("alloy.earlyDelegate"));

    @Inject(method = "getDelegateOrThrow(Ljava/lang/Object;)Lnet/minecraft/core/Holder$Reference;", at = @At("HEAD"),
            cancellable = true, remap = false)
    private void alloy$intrusiveHolderBeforeRegister(V value, CallbackInfoReturnable<Holder.Reference<V>> cir) {
        if (value == null || alloy$off || delegatesByValue.get(value) != null) return;
        RegistryBuilder<V> builder = getBuilder();
        if (builder == null) return;
        Function<V, Holder.Reference<V>> callback = ((RegistryBuilderIntrusiveAccess<V>) (Object) builder).alloy$intrusiveHolderCallback();
        if (callback == null) return;
        Holder.Reference<V> holder;
        try {
            holder = callback.apply(value);
        } catch (RuntimeException e) {
            return;   // keep Forge's own error
        }
        if (holder == null) return;
        if (alloy$logged.add(getRegistryKey())) {
            org.slf4j.LoggerFactory.getLogger("Alloy").info(
                    "[Alloy] {} in {} has no Forge delegate yet (created before it was registered, fine on Fabric); using its own holder. "
                    + "Further cases in this registry are not logged.", value, getRegistryKey().identifier());
        }
        cir.setReturnValue(holder);
    }
}
