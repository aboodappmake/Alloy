package alloy;

import cpw.mods.modlauncher.api.IModuleLayerManager;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Stand-in for ModLauncher's module layers. Under Fabric every Forge/mod class lives in Knot's unnamed module, so
 * each "layer" is the boot layer, and every place FML loads services from a layer is redirected (see the mixins)
 * to {@link #services(Class)}, which uses Knot's class loader.
 */
public final class BridgeLayers implements IModuleLayerManager {
    public static final BridgeLayers INSTANCE = new BridgeLayers();

    @Override
    public Optional<ModuleLayer> getLayer(Layer layer) {
        return Optional.of(ModuleLayer.boot());
    }

    public static ClassLoader loader() {
        return BridgeLayers.class.getClassLoader(); // KnotClassLoader
    }

    public static Module module() {
        return loader().getUnnamedModule();
    }

    public static <S> ServiceLoader<S> services(Class<S> c) {
        return ServiceLoader.load(c, loader());
    }

    public static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, true, loader());
    }
}
