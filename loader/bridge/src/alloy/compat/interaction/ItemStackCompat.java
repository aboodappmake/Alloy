package alloy.compat.interaction;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fabricmc.fabric.api.event.player.ItemEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Replacement for Fabric's fabric-events-interaction-v0 ItemStackMixin (keep that one disabled).
 * ItemEvents.USE: unchanged, ItemStack.use still calls Item.use. ItemEvents.USE_ON, client side: Forge moved the call
 * into the synthetic lambda$useOn$0 (the callback handed to ItemStack.onItemUse). The server side is handled by
 * {@link ForgeHooksUseOnCompat} because on the server Forge's ItemStack.useOn returns ForgeHooks.onPlaceItemIntoWorld.
 */
@Mixin(value = ItemStack.class, remap = false)
public abstract class ItemStackCompat {
    @WrapOperation(method = "use", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/minecraft/world/item/Item;use(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult alloy$handleUseEvent(Item instance, Level level, Player player, InteractionHand hand,
                                                      Operation<InteractionResult> original) {
        InteractionResult result = ItemEvents.USE.invoker().use(level, player, hand);
        if (result != null) return result;
        return original.call(instance, level, player, hand);
    }

    @WrapOperation(method = "lambda$useOn$0", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/minecraft/world/item/Item;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult alloy$handleUseOnEvent(Item instance, UseOnContext context, Operation<InteractionResult> original) {
        InteractionResult result = ItemEvents.USE_ON.invoker().useOn(context);
        if (result != null) return result;
        return original.call(instance, context);
    }
}
