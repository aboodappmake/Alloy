package alloy.compat.itemchunk;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fabricmc.fabric.api.item.v1.EnchantingContext;
import net.fabricmc.fabric.api.item.v1.FabricItemStack;
import net.minecraft.core.Holder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Replacement for Fabric API's disabled fabric-item-api-v1 EnchantmentHelperMixin (redirected Enchantment.isPrimaryItem in
 * lambda$getAvailableEnchantmentResults$0 to FabricItemStack#canBeEnchantedWith(holder, PRIMARY), which runs the
 * EnchantmentEvents.ALLOW_ENCHANTING event and then FabricItem#canBeEnchantedWith).
 * Forge's patch already replaced that call by ItemStack.canApplyAtEnchantingTable(holder) (Forge's
 * IForgeItem#canApplyAtEnchantingTable, default Enchantment#isPrimaryItem), so here Forge's call is wrapped and combined:
 * if Fabric's answer differs from the vanilla baseline isPrimaryItem(stack) (an event or a FabricItem override decided
 * something), Fabric's answer wins; otherwise Forge's answer is kept (so Forge items overriding
 * canApplyAtEnchantingTable keep working).
 */
@Mixin(value = EnchantmentHelper.class, remap = false)
public abstract class EnchantmentHelperFabricMixin {
    @WrapOperation(method = "lambda$getAvailableEnchantmentResults$0",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;canApplyAtEnchantingTable(Lnet/minecraft/core/Holder;)Z"))
    private static boolean alloy$fabricEnchantingChecks(ItemStack stack, Holder<Enchantment> enchantment, Operation<Boolean> original) {
        boolean forge = original.call(stack, enchantment);
        Object o = stack;
        if (o instanceof FabricItemStack fabricStack) {
            boolean fabric = fabricStack.canBeEnchantedWith(enchantment, EnchantingContext.PRIMARY);
            if (fabric != enchantment.value().isPrimaryItem(stack)) {
                return fabric;
            }
        }
        return forge;
    }
}
