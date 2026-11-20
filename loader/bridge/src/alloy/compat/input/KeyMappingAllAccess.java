package alloy.compat.input;

import java.util.Map;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** KeyMapping.ALL (name to mapping) for OptionsKeyDedupe. */
@Mixin(value = KeyMapping.class, remap = false)
public interface KeyMappingAllAccess {
    @Accessor(value = "ALL", remap = false)
    static Map<String, KeyMapping> alloy$all() { throw new AssertionError(); }
}
