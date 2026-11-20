package alloy.compat.registry;

import java.util.Map;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Balm (Waystones, ...) registers a PoiType in the registry and then calls {@code PoiTypes.registerBlockStates(holder, states)}
 * through an accessor, which is what Fabric's own PoiType helpers do (vanilla does not do it for registry additions). On Forge
 * the registry add already registers the block states of the PoI type, so the second call throws
 * "... is defined in more than one PoI type" and the mod's main entrypoint dies (Fabric matrix 2: Waystones).
 *
 * <p>Fix: registering a block state for the SAME PoI type again is not an error: the vanilla check (a previous, different
 * holder in TYPE_BY_STATE) is only triggered for a really different PoI type.
 */
@Mixin(PoiTypes.class)
public abstract class PoiTypesRegisterTwiceMixin {
    @WrapOperation(method = "lambda$registerBlockStates$0", remap = false,
            at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private static Object alloy$samePoiTypeIsFine(Map<Object, Object> map, Object state, Object holder, Operation<Object> original) {
        Object old = original.call(map, state, holder);
        if (old == null || old == holder || old.equals(holder)) return null;
        if (old instanceof Holder<?> a && holder instanceof Holder<?> b && a.unwrapKey().isPresent() && a.unwrapKey().equals(b.unwrapKey())) return null;
        return old;
    }
}
