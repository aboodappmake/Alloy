package alloy.compat.registry;

import java.util.function.Function;

import net.minecraft.core.Holder;
import net.minecraftforge.registries.RegistryBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reads Forge's package-private intrusive holder callback (for example {@code Fluid::builtInRegistryHolder}). */
@Mixin(value = RegistryBuilder.class, remap = false)
public interface RegistryBuilderIntrusiveAccess<T> {
    @Invoker(value = "getIntrusiveHolderCallback", remap = false)
    Function<T, Holder.Reference<T>> alloy$intrusiveHolderCallback();
}
