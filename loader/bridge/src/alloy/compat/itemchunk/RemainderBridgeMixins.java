package alloy.compat.itemchunk;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import org.spongepowered.asm.mixin.Mixin;

/**
 * OPTIONAL, add only if an IncompatibleClassChangeError / "conflicting default methods" shows up for getCraftingRemainder.
 *
 * Crafting, furnace-fuel and brewing remainders need NO re-implementation of Fabric's CraftingRecipeMixin,
 * AbstractFurnaceBlockEntityMixin and BrewingStandBlockEntityMixin: Forge's patched call sites already call the
 * stack-sensitive ItemStack#getCraftingRemainder() (IForgeItemStack) which calls Item#getCraftingRemainder(ItemStack), and
 * FabricItem#getCraftingRemainder(ItemStack) has the same name, descriptor and default behaviour. Any Item subclass that
 * overrides it (Forge or Fabric style) is therefore honoured by Forge's call sites, and Forge already reads the stack before
 * shrinking it (the reason for Fabric's furnace "copyStack" hook).
 *
 * The only problem can be that the injected interfaces FabricItem (via Fabric's ItemMixin) and FabricItemStack (via
 * ItemStackMixin) bring second default methods with the same signature as IForgeItem / IForgeItemStack, which the JVM
 * rejects as ambiguous when neither is overridden. These two mixins add a concrete class method with Fabric's and Forge's
 * (identical) default behaviour, which wins over both interface defaults.
 */
public final class RemainderBridgeMixins {
    private RemainderBridgeMixins() {}

    @Mixin(value = Item.class, remap = false)
    public abstract static class ItemRemainder {
        public ItemStackTemplate getCraftingRemainder(ItemStack stack) {
            Object self = this;
            return ((Item) self).getCraftingRemainder();
        }
    }

    @Mixin(value = ItemStack.class, remap = false)
    public abstract static class ItemStackRemainder {
        public ItemStackTemplate getCraftingRemainder() {
            Object self = this;
            ItemStack stack = (ItemStack) self;
            return stack.getItem().getCraftingRemainder(stack);
        }
    }
}
