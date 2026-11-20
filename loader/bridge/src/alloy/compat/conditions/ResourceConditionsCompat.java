package alloy.compat.conditions;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.serialization.DynamicOps;
import java.util.Map;
import net.fabricmc.fabric.impl.resource.conditions.ResourceConditionsImpl;
import net.fabricmc.fabric.mixin.resource.conditions.RegistryOpsAccessor;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Replacement for Fabric API fabric-resource-conditions-api-v1 SimpleJsonResourceReloadListenerMixin (disabled: its
 * {@code @Inject(lambda$scanDirectory$0)} cannot apply because Forge changed that lambda to
 * (Codec, Identifier, Map, Object) and added {@code ForgeHooks.onJsonDataParsed}).
 *
 * Forge's scanDirectory already evaluates {@code forge:conditions} / {@code forge:conditional} right after reading the
 * JSON, via {@code ForgeHooks.readConditional(ops, json)}, and skips the file when that returns null. We wrap that one
 * call: first Fabric's {@code fabric:load_conditions} (same arguments Fabric's mixin used: the directory prefix, the
 * resource id and the RegistryInfoLookup of the ops), then Forge's own check. A file is loaded only if both pass, and a
 * rejected file takes Forge's existing "Skipping loading {} as its conditions were not met" path.
 *
 * <p>Up to 26.2 the call is in the static {@code scanDirectory(ResourceManager, FileToIdConverter, ...)}; 26.3 removed
 * scanDirectory and reads the files in the instance method {@code prepare}, with the directory in the {@code lister}
 * field. One handler per shape, each optional (require = 0): on any version exactly one of them finds its method.
 */
@Mixin(value = SimpleJsonResourceReloadListener.class, remap = false)
public abstract class ResourceConditionsCompat {
    @Shadow @Final private FileToIdConverter lister;

    @WrapOperation(method = "scanDirectory(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/resources/FileToIdConverter;Lcom/mojang/serialization/DynamicOps;Lcom/mojang/serialization/Codec;Ljava/util/Map;)V",
            remap = false, require = 0,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/minecraftforge/common/ForgeHooks;readConditional(Lcom/mojang/serialization/DynamicOps;Lcom/google/gson/JsonElement;)Lcom/google/gson/JsonElement;"))
    private static JsonElement alloy$fabricConditions(DynamicOps<JsonElement> ops, JsonElement json,
            Operation<JsonElement> original,
            @Local(argsOnly = true) FileToIdConverter finder,
            @Local Map.Entry<Identifier, Resource> entry) {
        return fabricPasses(ops, json, finder, entry) ? original.call(ops, json) : null;
    }

    /** 26.3: the same call inside {@code prepare}, the directory comes from {@code this.lister}. */
    @WrapOperation(method = "prepare(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)Ljava/util/Map;",
            remap = false, require = 0,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/minecraftforge/common/ForgeHooks;readConditional(Lcom/mojang/serialization/DynamicOps;Lcom/google/gson/JsonElement;)Lcom/google/gson/JsonElement;"))
    private JsonElement alloy$fabricConditionsPrepare(DynamicOps<JsonElement> ops, JsonElement json,
            Operation<JsonElement> original,
            @Local Map.Entry<Identifier, Resource> entry) {
        return fabricPasses(ops, json, lister, entry) ? original.call(ops, json) : null;
    }

    private static boolean fabricPasses(DynamicOps<JsonElement> ops, JsonElement json, FileToIdConverter finder, Map.Entry<Identifier, Resource> entry) {
        if (json == null || !json.isJsonObject()) return true;
        RegistryOps.RegistryInfoLookup lookup = ops instanceof RegistryOpsAccessor acc ? acc.getRegistryInfoGetter() : null;
        JsonObject obj = json.getAsJsonObject();
        return ResourceConditionsImpl.applyResourceConditions(obj, finder.prefix(), entry.getKey(), lookup);
    }
}
