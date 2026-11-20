package alloy.api.impl;

import alloy.Isolation;
import alloy.api.ModInfo;
import alloy.api.Side;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Which mod (and side) owns a class. A class belongs to the Fabric mod whose root contains its .class file, or to the
 * Forge mod file that contains it. Fallbacks: Isolation's ownership index (when isolation is on), then the jar's
 * folder (forgemods/ = Forge, mods/ = Fabric). Results are cached per class once FML's mod list exists.
 */
final class Owners {
    private Owners() {}

    /** Fabric "mods" that are not a side's mod: the platform and Alloy's own jars */
    private static final Set<String> NOT_MODS = Set.of("java", "minecraft", "fabricloader", "mixinextras", "alloyloader", "alloy", "alloy_modmixins", "alloy_api");

    private static final ClassValue<Owner> CACHE = new ClassValue<>() {
        @Override protected Owner computeValue(Class<?> c) { return resolve(c); }
    };

    private static volatile List<ModInfo> mods;

    static Owner of(Class<?> c) {
        if (c == null) return Owner.NONE;
        return forgeReady() ? CACHE.get(c) : resolve(c);
    }

    private static boolean forgeReady() {
        try {
            return !alloy.Fml.mods().isEmpty();   // LoadingModList: static on Forge 64, instance on Forge 47
        } catch (Throwable t) {
            return false;
        }
    }

    private static Owner resolve(Class<?> c) {
        String name = c.getName();
        int lambda = name.indexOf("$$");   // hidden lambda / proxy classes: owner is the declaring class
        if (lambda > 0) name = name.substring(0, lambda);
        if (name.startsWith("alloy.")) return Owner.ALLOY;
        String entry = name.replace('.', '/') + ".class";

        try {
            for (ModContainer mc : FabricLoader.getInstance().getAllMods()) {
                String id = mc.getMetadata().getId();
                if (NOT_MODS.contains(id) || id.startsWith("fabric-")) continue;   // Fabric API modules count too, below
                if (mc.findPath(entry).isPresent()) return new Owner(Side.FABRIC, id);
            }
            for (ModContainer mc : FabricLoader.getInstance().getAllMods()) {
                String id = mc.getMetadata().getId();
                if (id.startsWith("fabric-") && mc.findPath(entry).isPresent()) return new Owner(Side.FABRIC, id);
            }
        } catch (Throwable t) {
            ApiBackend.LOG.debug("Alloy API: Fabric owner lookup failed for {}: {}", name, t.toString());
        }
        try {
            for (ModFileInfo mfi : alloy.Fml.modFiles()) {
                List<String> ids = mfi.getMods().stream().map(m -> m.getModId()).toList();
                if (ids.isEmpty() || ids.contains("minecraft") || ids.contains("forge")) continue;
                Path p = mfi.getFile().findResource(entry);
                if (p != null && Files.exists(p)) return new Owner(Side.FORGE, ids.get(0));
            }
        } catch (Throwable t) {
            ApiBackend.LOG.debug("Alloy API: Forge owner lookup failed for {}: {}", name, t.toString());
        }
        if (Isolation.MODE != Isolation.Mode.OFF) {
            Isolation.Side s = Isolation.sideOf(entry.substring(0, entry.length() - 6));
            if (s == Isolation.Side.FABRIC) return new Owner(Side.FABRIC, null);
            if (s == Isolation.Side.FORGE) return new Owner(Side.FORGE, null);
        }
        try {
            CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null && "file".equals(cs.getLocation().getProtocol())) {
                Path jar = Path.of(cs.getLocation().toURI()).toAbsolutePath().normalize();
                Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
                if (jar.startsWith(game.resolve("forgemods"))) return new Owner(Side.FORGE, null);
                if (jar.startsWith(game.resolve("mods"))) return new Owner(Side.FABRIC, null);
            }
        } catch (Throwable ignored) {
        }
        return Owner.NONE;
    }

    /** Every mod on both sides; cached once FML's mod list exists. */
    static List<ModInfo> mods() {
        List<ModInfo> m = mods;
        if (m != null) return m;
        List<ModInfo> out = new ArrayList<>();
        try {
            for (ModContainer mc : FabricLoader.getInstance().getAllMods()) {
                var md = mc.getMetadata();
                if (md.getId().equals("alloy_modmixins")) continue;   // generated helper, not a mod
                if (md.containsCustomValue("alloy:forge-stub")) continue;   // stand-in for a Forge mod, listed on the Forge side
                out.add(new ModInfo(md.getId(), md.getName(), md.getVersion().getFriendlyString(), Side.FABRIC));
            }
        } catch (Throwable t) {
            ApiBackend.LOG.warn("Alloy API: cannot list Fabric mods: {}", t.toString());
        }
        boolean forge = false;
        try {
            for (Object o : alloy.Fml.mods()) {
                if (!(o instanceof net.minecraftforge.forgespi.language.IModInfo mi)) continue;
                out.add(new ModInfo(mi.getModId(), mi.getDisplayName(), String.valueOf(mi.getVersion()), Side.FORGE));
                forge = true;
            }
        } catch (Throwable t) {
            ApiBackend.LOG.debug("Alloy API: Forge mod list not ready: {}", t.toString());
        }
        m = Collections.unmodifiableList(out);
        if (forge) mods = m;
        return m;
    }
}
