package alloy.compat.interaction;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fabricmc.fabric.api.item.v1.FabricItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Replacement for Fabric API fabric-item-api-v1's client MultiPlayerGameModeMixin.fabricItemContinueBlockBreakingInject
 * (stripped because Forge replaced the {@code ItemStack.isSameItemSameComponents} call in
 * {@code sameDestroyTarget} with {@code ItemStack.shouldCauseBlockBreakReset}). {@code FabricItem#allowContinuingBlockBreaking}
 * lets an item keep the current block-breaking progress when its stack changed (e.g. a durability tick): when the
 * stack would reset the progress, same item and the item allows continuing, it does not.
 */
@Mixin(value = MultiPlayerGameMode.class, remap = false)
public abstract class MultiPlayerGameModeCompat {
    @Shadow @Final private Minecraft minecraft;

    @WrapOperation(method = "sameDestroyTarget", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/minecraft/world/item/ItemStack;shouldCauseBlockBreakReset(Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean alloy$allowContinuingBlockBreaking(ItemStack oldStack, ItemStack newStack, Operation<Boolean> original) {
        boolean reset = original.call(oldStack, newStack);
        if (reset && oldStack.is(newStack.getItem()) && (Object) oldStack.getItem() instanceof FabricItem item
                && item.allowContinuingBlockBreaking(this.minecraft.player, oldStack, newStack)) {
            return false;
        }
        return reset;
    }
}
