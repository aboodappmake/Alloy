package alloy;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Small byte-code shape fixes for Forge-patched game classes, applied before Mixin sees them.
 *
 * Bootstrap.bootStrap(): vanilla starts with "if (isBootstrapped) return;". Forge's recompiled copy has
 * "if (!isBootstrapped) { ... }" with one return at the end. A Fabric mod's @Inject(at = TAIL) there runs once on
 * vanilla, but on every bootStrap() call on Forge (Forge calls it more than once). CristelLib registers its built-in
 * packs from that hook and then freezes its registry, so the second call crashed MineDave ("BuiltInPack Registry is
 * already frozen"). This puts the early return back, so TAIL (the last return) is again only reached on the first call.
 */
final class ForgeShapeFixes {
    static final String BOOTSTRAP = "net.minecraft.server.Bootstrap";
    static final String SPRITE_LOADER = "net.minecraft.client.renderer.texture.SpriteLoader";

    /**
     * SpriteLoader: vanilla lowers an atlas' mip level when a texture is too small for it (a 14x14 texture allows 1
     * level). Forge only does that when its client config allowMipmapLowering is true, and the default is false; on 26.x
     * the atlas upload then fails ("mipLevels must be at most 4 for a texture of width 14", MineDave), and every resource
     * pack is dropped. Fabric mods and packs expect vanilla here, so Alloy answers true, as vanilla behaves.
     */
    static byte[] spriteLoader(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        int n = 0;
        for (MethodNode m : cn.methods)
            for (AbstractInsnNode i : m.instructions.toArray())
                if (i instanceof MethodInsnNode mi && mi.name.equals("allowMipmapLowering") && mi.desc.equals("()Z")
                        && mi.owner.equals("net/minecraftforge/common/ForgeConfig$Client")) {
                    InsnList r = new InsnList();
                    r.add(new InsnNode(Opcodes.POP));
                    r.add(new InsnNode(Opcodes.ICONST_1));
                    m.instructions.insertBefore(mi, r);
                    m.instructions.remove(mi);
                    n++;
                }
        if (n == 0) return bytes;
        ClassWriter cw = new ClassWriter(0);   // same stack depth (receiver replaced by the boolean)
        cn.accept(cw);
        Bridge.LOG.info("Alloy: atlases lower their mip level for small textures, as on vanilla (Forge allowMipmapLowering read as true)");
        return cw.toByteArray();
    }

    static byte[] bootstrap(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean changed = false;
        for (MethodNode m : cn.methods) {
            if (!m.name.equals("bootStrap") || !m.desc.equals("()V")) continue;
            AbstractInsnNode first = m.instructions.getFirst();
            while (first != null && first.getOpcode() < 0) first = first.getNext();
            if (!(first instanceof FieldInsnNode f) || f.getOpcode() != Opcodes.GETSTATIC || !f.name.equals("isBootstrapped")) continue;
            AbstractInsnNode next = f.getNext();
            while (next != null && next.getOpcode() < 0) next = next.getNext();
            if (!(next instanceof JumpInsnNode j) || j.getOpcode() != Opcodes.IFNE) continue;   // vanilla shape (IFEQ + RETURN): nothing to do
            LabelNode body = new LabelNode();
            InsnList add = new InsnList();
            add.add(new InsnNode(Opcodes.RETURN));
            add.add(body);
            add.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            m.instructions.insert(j, add);
            j.setOpcode(Opcodes.IFEQ);
            j.label = body;
            changed = true;
        }
        if (!changed) return bytes;
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        Bridge.LOG.info("Alloy: Bootstrap.bootStrap() has its early return again (TAIL injectors run once, as on vanilla)");
        return cw.toByteArray();
    }
}
