package alloy.compat.conditions;

import com.google.gson.JsonElement;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Decoder;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Proxy;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Minecraft 26.3: Fabric API's loot events (LootTableEvents.REPLACE / MODIFY) and advancement events
 * (AdvancementEvents) on Forge's way of loading those files.
 *
 * <p>26.3 loads loot tables and advancements through {@code ResourceManagerRegistryLoadTask}. Fabric API's
 * fabric-loot-api-v3 and fabric-advancement-api-v1 each have a ResourceManagerRegistryLoadTaskMixin that wraps
 * {@code PendingRegistration.loadFromResource} and casts the decoded value to LootTable or Advancement. Forge patches
 * that decoder so it returns {@code Optional<T>} (empty when the file's forge:conditions fail), and the casts threw
 * ClassCastException: every world failed with "Errors in the currently selected data pack(s)". The fitter turns both
 * Fabric mixins off (compat_replaces.txt) and this one does the same work on Forge's shape: the Fabric hooks run on the
 * value inside the Optional, an empty Optional stays empty.
 *
 * <p>Fabric API is called by reflection (it is optional, and its impl classes differ between versions). The target class
 * only exists from 26.3 on, hence @Pseudo and require = 0.
 */
@Pseudo
@Mixin(targets = "net.minecraft.resources.ResourceManagerRegistryLoadTask", remap = false)
public abstract class ConditionalRegistryLoadCompat {
    @Shadow(remap = false) @Final private ResourceManager resourceManager;

    // per kind: getActiveReloadProvider, determineSource, modify, holder provider constructor; empty = that Fabric module is not there
    @Unique private static volatile MethodHandle[] alloy$loot;
    @Unique private static volatile MethodHandle[] alloy$advancement;

    @WrapOperation(method = "lambda$load$2", remap = false, require = 0,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/minecraft/resources/RegistryLoadTask$PendingRegistration;loadFromResource(Lcom/mojang/serialization/Decoder;Lnet/minecraft/resources/RegistryOps;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/server/packs/resources/Resource;)Lcom/mojang/datafixers/util/Either;"))
    private Either<Object, Exception> alloy$fabricHooksOnForgeOptional(Decoder<Object> decoder, RegistryOps<JsonElement> ops,
            ResourceKey<Object> key, Resource resource, Operation<Either<Object, Exception>> original) {
        Either<Object, Exception> out = original.call(decoder, ops, key, resource);
        boolean loot = key.isFor(Registries.LOOT_TABLE);
        if (!loot && !key.isFor(Registries.ADVANCEMENT)) return out;
        MethodHandle[] h = loot ? alloy$lootHandles() : alloy$advancementHandles();
        if (h.length == 0) return out;
        try {
            Object reload = h[0].invoke(resourceManager);
            if (reload == null) return out;
            Object holders = h[3].invoke(alloy$infoLookup(ops), reload);
            Object source = h[1].invoke(resource);
            Object id = loot ? key : key.identifier();   // the loot hook takes the key, the advancement hook its id
            return out.mapLeft(v -> v instanceof Optional<?> o ? o.map(t -> alloy$modify(h, key, id, t, source, holders)) : alloy$modify(h, key, id, v, source, holders));
        } catch (Throwable t) {
            alloy.Bridge.LOG.warn("Alloy: Fabric load hooks skipped for {}: {}", key, t.toString());
            return out;
        }
    }

    @Unique
    private static Object alloy$modify(MethodHandle[] h, ResourceKey<Object> key, Object id, Object value, Object source, Object holders) {
        try {
            return h[2].invoke(id, value, source, holders);
        } catch (Throwable t) {
            alloy.Bridge.LOG.warn("Alloy: Fabric modify hook failed for {}: {}", key, t.toString());
            return value;
        }
    }

    /** Fabric's RegistryInfoLookup over RegistryOps.getter (what its mixins build with a method reference). */
    @Unique
    private static Object alloy$infoLookup(RegistryOps<JsonElement> ops) throws ReflectiveOperationException {
        Class<?> iface = RegistryOps.RegistryInfoLookup.class;
        java.lang.reflect.Method getter = RegistryOps.class.getMethod("getter", ResourceKey.class);
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface}, (p, m, args) -> {
            if (m.getDeclaringClass() == Object.class) {
                return switch (m.getName()) {
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == args[0];
                    default -> "AlloyRegistryInfoLookup";
                };
            }
            return getter.invoke(ops, args[0]);
        });
    }

    @Unique
    private static MethodHandle[] alloy$lootHandles() {
        MethodHandle[] h = alloy$loot;
        if (h == null) alloy$loot = h = alloy$find("loot", "LootUtil", "LootTableHolderProvider", "net.fabricmc.fabric.api.loot.v3.LootTableSource",
                "net.minecraft.world.level.storage.loot.LootTable", "modifyLootTable", ResourceKey.class);
        return h;
    }

    @Unique
    private static MethodHandle[] alloy$advancementHandles() {
        MethodHandle[] h = alloy$advancement;
        if (h == null) {
            Class<?> id;
            try {
                id = Class.forName("net.minecraft.resources.Identifier", false, ConditionalRegistryLoadCompat.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                return alloy$advancement = new MethodHandle[0];
            }
            alloy$advancement = h = alloy$find("advancement", "AdvancementUtil", "AdvancementHolderProvider", "net.fabricmc.fabric.api.advancement.v1.AdvancementSource",
                    "net.minecraft.advancements.Advancement", "modifyAdvancement", id);
        }
        return h;
    }

    /** Handles into net.fabricmc.fabric.impl.&lt;pkg&gt;: Util.getActiveReloadProvider, Util.determineSource, Util.&lt;modify&gt;, holder provider constructor. */
    @Unique
    private static MethodHandle[] alloy$find(String pkg, String util, String holder, String source, String value, String modify, Class<?> first) {
        try {
            ClassLoader cl = ConditionalRegistryLoadCompat.class.getClassLoader();
            Class<?> u = Class.forName("net.fabricmc.fabric.impl." + pkg + "." + util, false, cl);
            Class<?> hp = Class.forName("net.fabricmc.fabric.impl." + pkg + "." + holder, false, cl);
            Class<?> src = Class.forName(source, false, cl);
            Class<?> val = Class.forName(value, false, cl);
            Class<?> provider = Class.forName("net.minecraft.core.HolderLookup$Provider", false, cl);
            MethodHandles.Lookup l = MethodHandles.publicLookup();
            return new MethodHandle[] {
                l.findStatic(u, "getActiveReloadProvider", MethodType.methodType(provider, ResourceManager.class)),
                l.findStatic(u, "determineSource", MethodType.methodType(src, Resource.class)),
                l.findStatic(u, modify, MethodType.methodType(val, first, val, src, provider)),
                l.findConstructor(hp, MethodType.methodType(void.class, RegistryOps.RegistryInfoLookup.class, provider))};
        } catch (ReflectiveOperationException | LinkageError e) {
            return new MethodHandle[0];   // no Fabric API, or another API shape: Forge's loading stays as it is
        }
    }
}
