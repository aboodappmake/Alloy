package alloy.shim;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Language adapter declared by the generated alloy_modmixins mod. Fabric Loader instantiates language adapters
 * while it sets up the mods, which is before Mixin's configs are registered and before any entrypoint runs, so
 * putting forgemods/*.jar (and forgemods/.nested/*.jar) on Knot's classpath here is early enough for the Forge mods'
 * mixin configs and plugins. That is what lets launchers whose class path is fixed (Prism) run drop-in Forge mods.
 * Also mirrors the added paths into java.class.path, which is where Bridge checks "already on the classpath".
 * In a plain Fabric Loader instance with .alloy/fabric-instance.properties it first runs {@link FabricInstance} (Forge runtime
 * jars, game jar swap).
 */
public final class ForgeModsAdapter implements LanguageAdapter {
    public ForgeModsAdapter() {
        try {
            FabricInstance.apply(FabricLoader.getInstance().getGameDir());
        } catch (Exception e) {
            throw new RuntimeException("Alloy: Fabric instance mode failed: " + e.getMessage(), e);
        }
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("forgemods");
            StringBuilder cp = new StringBuilder(System.getProperty("java.class.path", ""));
            int n = 0;
            for (Path d : new Path[]{dir, dir.resolve(".nested")}) {
                if (!Files.isDirectory(d)) continue;
                try (Stream<Path> s = Files.list(d)) {
                    for (Path p : s.filter(x -> x.toString().endsWith(".jar")).sorted().toList()) {
                        Path abs = p.toAbsolutePath().normalize();
                        FabricLauncherBase.getLauncher().addToClassPath(abs);
                        cp.append(java.io.File.pathSeparator).append(abs);
                        n++;
                    }
                }
            }
            System.setProperty("java.class.path", cp.toString());
            System.out.println("[Alloy] forgemods: " + n + " jar(s) added to Knot's classpath");
        } catch (Exception e) {
            throw new RuntimeException("Alloy: could not add forgemods/ to the classpath", e);
        }
    }

    @Override
    public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
        throw new LanguageAdapterException("alloy_forgemods is not an entrypoint adapter");
    }
}
