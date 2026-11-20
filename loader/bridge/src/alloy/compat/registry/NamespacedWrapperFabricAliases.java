package alloy.compat.registry;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric API (fabric-registry-sync) gives every {@code MappedRegistry} {@code addAlias(from, to)} and resolves aliases in the
 * vanilla lookup methods with @ModifyVariable. Forge's {@code NamespacedWrapper} overrides those lookups
 * ({@code get(ResourceKey)}, {@code get(Identifier)}, {@code getHolder}, {@code getValue}, {@code containsKey}) and reads its own maps,
 * so a Fabric alias is never applied. Balm (Waystones, ...) renames blocks that way; the world-generation data of Waystones then
 * names the old ids and loading dies with "Unknown registry key in ResourceKey[minecraft:block]: waystones:waystone".
 *
 * <p>Fix: when a lookup finds nothing, follow Fabric's alias chain (the merged private map {@code MappedRegistry.aliases}) and look
 * up the target. Lookups that already succeed are untouched, as in Fabric (an alias applies only when the name is not present).
 */
@Mixin(targets = "net.minecraftforge.registries.NamespacedWrapper", remap = false)
public abstract class NamespacedWrapperFabricAliases<T> {
    @Shadow(remap = false) private Map<Identifier, Holder.Reference<T>> holdersByName;

    private static Field aliasField;
    private static boolean aliasFieldLooked;

    @SuppressWarnings("unchecked")
    private Identifier alloy$alias(Identifier id) {
        if (id == null) return null;
        if (!aliasFieldLooked) {
            aliasFieldLooked = true;
            try {
                aliasField = MappedRegistry.class.getDeclaredField("aliases");   // merged in by Fabric's MappedRegistryMixin
                aliasField.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                aliasField = null;
            }
        }
        if (aliasField == null) return null;
        try {
            Map<Identifier, Identifier> aliases = (Map<Identifier, Identifier>) aliasField.get(this);
            if (aliases == null || aliases.isEmpty()) return null;
            Identifier to = aliases.get(id);
            for (int i = 0; to != null && i < 16 && aliases.containsKey(to) && !holdersByName.containsKey(to); i++) to = aliases.get(to);
            return to;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @Inject(method = "get(Lnet/minecraft/resources/ResourceKey;)Ljava/util/Optional;", at = @At("RETURN"), cancellable = true, remap = false)
    private void alloy$aliasGetKey(ResourceKey<T> key, CallbackInfoReturnable<Optional<Holder.Reference<T>>> cir) {
        if (cir.getReturnValue().isPresent() || key == null) return;
        Identifier to = alloy$alias(key.identifier());
        if (to != null) cir.setReturnValue(Optional.ofNullable(holdersByName.get(to)));
    }

    @Inject(method = "get(Lnet/minecraft/resources/Identifier;)Ljava/util/Optional;", at = @At("RETURN"), cancellable = true, remap = false)
    private void alloy$aliasGetId(Identifier id, CallbackInfoReturnable<Optional<Holder.Reference<T>>> cir) {
        if (cir.getReturnValue().isPresent()) return;
        Identifier to = alloy$alias(id);
        if (to != null) cir.setReturnValue(Optional.ofNullable(holdersByName.get(to)));
    }

    @Inject(method = "getHolder(Lnet/minecraft/resources/Identifier;)Ljava/util/Optional;", at = @At("RETURN"), cancellable = true, remap = false)
    private void alloy$aliasGetHolder(Identifier id, CallbackInfoReturnable<Optional<Holder.Reference<T>>> cir) {
        if (cir.getReturnValue().isPresent()) return;
        Identifier to = alloy$alias(id);
        if (to != null) cir.setReturnValue(Optional.ofNullable(holdersByName.get(to)));
    }

    @Inject(method = "getValue(Lnet/minecraft/resources/Identifier;)Ljava/lang/Object;", at = @At("RETURN"), cancellable = true, remap = false)
    private void alloy$aliasValue(Identifier id, CallbackInfoReturnable<T> cir) {
        if (cir.getReturnValue() != null) return;
        Identifier to = alloy$alias(id);
        Holder.Reference<T> h = to == null ? null : holdersByName.get(to);
        if (h != null && h.isBound()) cir.setReturnValue(h.value());
    }

    @Inject(method = "containsKey(Lnet/minecraft/resources/Identifier;)Z", at = @At("RETURN"), cancellable = true, remap = false)
    private void alloy$aliasContains(Identifier id, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) return;
        Identifier to = alloy$alias(id);
        if (to != null && holdersByName.containsKey(to)) cir.setReturnValue(true);
    }
}
