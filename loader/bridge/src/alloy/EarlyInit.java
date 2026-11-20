package alloy;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Plugin of the generated alloy_early.mixins.json, which build_bridge.py lists first in alloy_modmixins,
 * ahead of every Forge mod's mixin config. Its onLoad starts Forge's FML during Mixin startup, so Forge mods'
 * mixin plugins (some read FMLEnvironment.dist / FMLPaths in onLoad) find a running FML.
 */
public final class EarlyInit implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) {
        // lenient / conflict-check error handler as early as possible (builds without alloy.mixins.json, e.g. 1.20.1, need it here)
        if (ConflictReport.ON || ConflictReport.LENIENT)
            org.spongepowered.asm.mixin.Mixins.registerErrorHandlerClass(ConflictReport.class.getName());
        MixinTransformerWrapper.openStartWindow();
        Bridge.init();
    }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
