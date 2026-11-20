package alloy;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Forge 47 (Minecraft 1.20.1) replaced vanilla's CreativeModeTab constructor (Row, int, Type, Component, Supplier,
 * DisplayItemsGenerator) with a 14-argument one and a protected one that takes the Builder. Fabric libraries that subclass
 * CreativeModeTab call vanilla's constructor: owo-lib's OwoItemGroup (Numismatic Overhaul in Zombie Invade) failed with
 * NoSuchMethodError at startup. This adds vanilla's constructor back when the class is loaded; it builds a Builder with
 * the same row, column, title, icon, items and type and calls Forge's Builder constructor, so the tab gets Forge's
 * defaults like a vanilla tab. PrepGame does the same on 26.x, where Forge's constructor has 7 arguments.
 */
final class CreativeTabAnchor {
    static final String TAB = "net/minecraft/world/item/CreativeModeTab";
    static final String B = TAB + "$Builder";
    static final String VANILLA = "(L" + TAB + "$Row;IL" + TAB + "$Type;Lnet/minecraft/network/chat/Component;Ljava/util/function/Supplier;L" + TAB + "$DisplayItemsGenerator;)V";
    static final String BUILDER_CTOR = "(L" + B + ";)V";

    private CreativeTabAnchor() { }

    static byte[] apply(byte[] bytes) {
        try {
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, 0);
            if (has(cn, "<init>", VANILLA) || !has(cn, "<init>", BUILDER_CTOR)) return bytes;
            MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", VANILLA, null, null);
            var i = m.instructions;
            i.add(new VarInsnNode(Opcodes.ALOAD, 0));
            i.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW, B));
            i.add(new InsnNode(Opcodes.DUP));
            i.add(new VarInsnNode(Opcodes.ALOAD, 1));
            i.add(new VarInsnNode(Opcodes.ILOAD, 2));
            i.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, B, "<init>", "(L" + TAB + "$Row;I)V", false));
            i.add(new VarInsnNode(Opcodes.ALOAD, 4));
            i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, B, "title", "(Lnet/minecraft/network/chat/Component;)L" + B + ";", false));
            i.add(new VarInsnNode(Opcodes.ALOAD, 5));
            i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, B, "icon", "(Ljava/util/function/Supplier;)L" + B + ";", false));
            i.add(new VarInsnNode(Opcodes.ALOAD, 6));
            i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, B, "displayItems", "(L" + TAB + "$DisplayItemsGenerator;)L" + B + ";", false));
            i.add(new VarInsnNode(Opcodes.ALOAD, 3));
            i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, B, "type", "(L" + TAB + "$Type;)L" + B + ";", false));
            i.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, TAB, "<init>", BUILDER_CTOR, false));
            i.add(new InsnNode(Opcodes.RETURN));
            m.maxStack = 5;
            m.maxLocals = 7;
            cn.methods.add(m);
            ClassWriter cw = new ClassWriter(0);
            cn.accept(cw);
            Bridge.LOG.info("Alloy: CreativeModeTab gets vanilla's constructor back (Fabric item group libraries call it)");
            return cw.toByteArray();
        } catch (Throwable t) {
            Bridge.LOG.warn("Alloy: CreativeModeTab constructor anchor failed: {}", t.toString());
            return bytes;
        }
    }

    private static boolean has(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) if (m.name.equals(name) && m.desc.equals(desc)) return true;
        return false;
    }
}
