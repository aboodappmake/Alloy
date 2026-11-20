package alloy.compat.attributes;

import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replacement for Fabric API fabric-object-builder-v1 {@code AttributeSupplierBuilderAccessor} (keep that one disabled).
 *
 * Why Fabric's accessor fails: it is {@code @Accessor("builder") ImmutableMap.Builder getBuilder()} on
 * AttributeSupplier$Builder, but Forge changed that field to a plain {@code Map} (plus an {@code others} list and a
 * public copy constructor {@code Builder(AttributeSupplier)}), so the accessor finds no field of type ImmutableMap$Builder.
 * Its sole user is FabricDefaultAttributeRegistryImpl.createFromExistingSupplier (used by
 * FabricDefaultAttributeRegistry.MODIFY), which copies an existing supplier's instances into a fresh Builder. Forge's
 * copy constructor does exactly that, so we replace the method body with it.
 *
 * The rest of the feature (DefaultAttributesMixin making DefaultAttributes.SUPPLIERS mutable, DefaultAttributesAccessor,
 * register(...) putting into SUPPLIERS) applies unchanged; Forge's getSupplier/hasSupplier fall back to SUPPLIERS after
 * ForgeHooks.getAttributesView (Forge's own EntityAttributeCreationEvent map), so both sources are seen.
 */
@Mixin(targets = "net.fabricmc.fabric.impl.object.builder.FabricDefaultAttributeRegistryImpl", remap = false)
public abstract class AttributeBuilderCompat {
    @Inject(method = "createFromExistingSupplier", at = @At("HEAD"), cancellable = true, remap = false)
    private static void alloy$copyBuilder(AttributeSupplier supplier,
                                             CallbackInfoReturnable<AttributeSupplier.Builder> cir) {
        cir.setReturnValue(new AttributeSupplier.Builder(supplier));
    }
}
