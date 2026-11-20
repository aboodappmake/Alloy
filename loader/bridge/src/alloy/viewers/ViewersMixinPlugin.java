package alloy.viewers;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Config plugin of alloy.viewers.mixins.json: a viewer mixin applies only when its target class exists (that viewer is
 * installed). -Dalloy.viewers=false turns the whole bridge off.
 */
public final class ViewersMixinPlugin implements IMixinConfigPlugin {
    static final boolean ENABLED = !"false".equals(System.getProperty("alloy.viewers"));

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean presence = !"false".equals(System.getProperty("alloy.presence"));
        if (mixinClassName.endsWith(".ModListViewers")) return presence && isLoadedStatic(targetClassName) == Boolean.TRUE;
        if (mixinClassName.endsWith(".ModListViewers$Instance")) return presence && isLoadedStatic(targetClassName) == Boolean.FALSE;
        return ENABLED && ViewersMixinPlugin.class.getClassLoader().getResource(targetClassName.replace('.', '/') + ".class") != null;
    }

    /**
     * Is {@code isLoaded(String)} of the target static (Forge 64) or an instance method (Forge 47, 49)? Read from the class
     * file, without loading the class; null when the class or the method is missing.
     */
    static Boolean isLoadedStatic(String targetClassName) {
        try (java.io.InputStream in = ViewersMixinPlugin.class.getClassLoader().getResourceAsStream(targetClassName.replace('.', '/') + ".class")) {
            if (in == null) return null;
            ClassNode cn = new ClassNode();
            new org.objectweb.asm.ClassReader(in).accept(cn, org.objectweb.asm.ClassReader.SKIP_CODE);
            for (var m : cn.methods)
                if (m.name.equals("isLoaded") && m.desc.equals("(Ljava/lang/String;)Z")) return (m.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
