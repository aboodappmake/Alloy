package alloy.compat.registry;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric's DynamicRegistriesImpl (fabric-registry-sync) copies {@code RegistryDataLoader.WORLDGEN_REGISTRIES} into its own
 * lists when its class is initialised (first time any mod registers a dynamic registry: Moonlight, ...), and
 * {@code addSyncedRegistry} replaces {@code RegistryDataLoader.SYNCHRONIZED_REGISTRIES} / {@code RegistrySynchronization.NETWORKABLE_REGISTRIES}
 * with copies. Forge adds its own data-pack registries (forge:biome_modifier, ...) later, in DataPackRegistryEvent, to ITS lists,
 * which are then no longer the ones in use: the world loads without them and ServerLifecycleHooks.runModifiers dies with
 * "Missing registry: forge:biome_modifier" (seen in the Fabric matrix 2: Moonlight, Biomes O' Plenty).
 *
 * <p>Fix: after Forge registered a data-pack registry, mirror every entry of Forge's lists that is missing into the lists that
 * are in use now (the fields of RegistryDataLoader/RegistrySynchronization and, by reflection, Fabric's WORLD_REGISTRIES and
 * BOOTSTRAPPING_REGISTRIES when Fabric API is present). Entries are matched by registry key, so nothing is added twice.
 *
 * <p>All lists are read by name, because the names move between versions: up to 26.2 Forge and vanilla call the world list
 * WORLDGEN_REGISTRIES and Fabric has WORLD_REGISTRIES + BOOTSTRAPPING_REGISTRIES; from 26.3 the world list is WORLD_REGISTRIES,
 * there is a RELOADABLE_REGISTRIES list on all three sides, and Fabric's other list is ALL_DYNAMIC_REGISTRIES.
 */
@Mixin(targets = "net.minecraftforge.registries.DataPackRegistriesHooks", remap = false)
public abstract class DataPackRegistriesFabricMirror {
    private static final String HOOKS = "net.minecraftforge.registries.DataPackRegistriesHooks";
    private static final String FABRIC = "net.fabricmc.fabric.impl.registry.sync.DynamicRegistriesImpl";

    @Inject(method = "addRegistryCodec", at = @At("TAIL"), remap = false)
    private static void alloy$mirrorIntoFabricLists(CallbackInfo ci) {
        try {
            List<RegistryDataLoader.RegistryData<?>> world = list(HOOKS, "WORLD_REGISTRIES", "WORLDGEN_REGISTRIES");
            List<RegistryDataLoader.RegistryData<?>> reloadable = list(HOOKS, "RELOADABLE_REGISTRIES");
            for (String f : new String[] {"WORLD_REGISTRIES", "BOOTSTRAPPING_REGISTRIES", "ALL_DYNAMIC_REGISTRIES"})
                mirror(world, list(FABRIC, f));
            for (String f : new String[] {"RELOADABLE_REGISTRIES", "ALL_DYNAMIC_REGISTRIES"})
                mirror(reloadable, list(FABRIC, f));
            mirror(list(HOOKS, "SYNCHRONIZED_REGISTRIES"), RegistryDataLoader.SYNCHRONIZED_REGISTRIES);
            @SuppressWarnings("unchecked") Set<ResourceKey<? extends Registry<?>>> forgeNet = (Set<ResourceKey<? extends Registry<?>>>) field(HOOKS, "NETWORKABLE_REGISTRIES");
            Field nf = RegistrySynchronization.class.getDeclaredField("NETWORKABLE_REGISTRIES");
            nf.setAccessible(true);
            @SuppressWarnings("unchecked") Set<ResourceKey<? extends Registry<?>>> now = (Set<ResourceKey<? extends Registry<?>>>) nf.get(null);
            if (now != null && forgeNet != null && now != forgeNet) {
                try { now.addAll(forgeNet); } catch (UnsupportedOperationException ignored) { }
            }
        } catch (Throwable t) {
            System.err.println("[Alloy] could not mirror Forge data-pack registries into Fabric's lists: " + t);
        }
    }

    /** The first of the named static fields that exists (null when the class is missing: no Fabric API, or none of the names). */
    @SuppressWarnings("unchecked")
    private static List<RegistryDataLoader.RegistryData<?>> list(String owner, String... names) {
        return (List<RegistryDataLoader.RegistryData<?>>) field(owner, names);
    }

    private static Object field(String owner, String... names) {
        Class<?> c;
        try {
            c = Class.forName(owner, true, DataPackRegistriesFabricMirror.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
        for (String n : names) {
            try {
                Field f = c.getDeclaredField(n);
                f.setAccessible(true);
                return f.get(null);
            } catch (NoSuchFieldException e) {
                // another version's name
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("[Alloy] " + owner + "." + n + " not readable: " + e);
                return null;
            }
        }
        return null;
    }

    private static void mirror(List<RegistryDataLoader.RegistryData<?>> forge, List<RegistryDataLoader.RegistryData<?>> target) {
        if (forge == null || target == null || forge == target) return;
        for (RegistryDataLoader.RegistryData<?> d : List.copyOf(forge)) {
            boolean have = false;
            for (RegistryDataLoader.RegistryData<?> t : target) if (t.key().equals(d.key())) { have = true; break; }
            if (!have) {
                try { target.add(d); } catch (UnsupportedOperationException ignored) { return; }
            }
        }
    }
}
