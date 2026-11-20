package alloy.compat.interaction;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fabricmc.fabric.api.event.player.ItemEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Server-side ItemEvents.USE_ON. Forge's ItemStack.useOn returns ForgeHooks.onPlaceItemIntoWorld(ctx) on the server,
 * and the Item.useOn call lives there (after the adventure-mode check, with block snapshots captured around it). A
 * Fabric listener returning a non-null result skips Item.useOn but the result still flows through Forge's place-event
 * handling, as if the original call had returned it.
 *
 * <p>Applicability (checked): ForgeHooks is a Forge universal-jar class, but it references patched Minecraft classes
 * directly, so it is loaded by Knot (not a parent loader) and Mixin transforms it; alloy.mixins.json already does the
 * same to net.minecraftforge.network.ChannelListManager. ConflictReport.shouldApplyMixin only requires fabric-api-base
 * (prefix alloy.compat.), and the config lists this mixin in the common array, so it applies on both sides. A failed
 * apply would be logged as "Alloy lenient: skipped ...ForgeHooksUseOnCompat" (and in alloy-conflicts.txt); the last
 * Fabric-API run logged none. The self-test proves the hook at runtime with the use_on.* counters (a USE_ON listener
 * returning SUCCESS/FAIL must skip Item.useOn on the server).
 */
@Mixin(targets = "net.minecraftforge.common.ForgeHooks", remap = false)
public abstract class ForgeHooksUseOnCompat {
    @WrapOperation(method = "onPlaceItemIntoWorld", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/minecraft/world/item/Item;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"))
    private static InteractionResult alloy$handleUseOnEvent(Item instance, UseOnContext context, Operation<InteractionResult> original) {
        InteractionResult result = ItemEvents.USE_ON.invoker().useOn(context);
        if (result != null) return result;
        return original.call(instance, context);
    }
}
