package alloy.presence;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fabric Loader's own classes are not transformable, so Fabric's {@code isModLoaded} is hooked at its call sites:
 * {@code FabricLoader.isModLoaded(String)} (invokeinterface), {@code FabricLoaderImpl.isModLoaded(String)} (invokevirtual)
 * and method references to either become {@code Presence.fabricIsModLoaded(FabricLoader, String)}, which asks the real
 * loader first. Same stack shape, so no frames change. Runs on pre-Mixin bytes of Fabric side classes (mods and Fabric
 * API, never Fabric Loader, Minecraft or Alloy), from ForgeLaunchPlugins.transform. A constant pool without
 * "isModLoaded" costs one byte search. {@code -Dalloy.presence=false} turns it off.
 */
public final class PresenceRewrite {
    private PresenceRewrite() {}

    static final String API = "net/fabricmc/loader/api/FabricLoader";
    static final String IMPL = "net/fabricmc/loader/impl/FabricLoaderImpl";
    static final String NAME = "isModLoaded", DESC = "(Ljava/lang/String;)Z";
    static final String HOOK = "alloy/presence/Presence", HOOK_NAME = "fabricIsModLoaded", HOOK_DESC = "(L" + API + ";Ljava/lang/String;)Z";
    private static final byte[] KEY = NAME.getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] LOADER = "net/fabricmc/loader/".getBytes(StandardCharsets.ISO_8859_1);
    private static final String[] SKIP = {"alloy.", "net.fabricmc.loader.", "net.minecraft.", "com.mojang.", "org.spongepowered.",
            "com.llamalad7.", "net.minecraftforge.", "cpw.mods.", "java.", "javax.", "jdk.", "sun.", "org.objectweb.asm."};

    public static final boolean ON = !"false".equals(System.getProperty("alloy.presence"));
    /** Classes and call sites rewritten so far (for the log). */
    public static final AtomicInteger CLASSES = new AtomicInteger(), SITES = new AtomicInteger();

    /** Class (dotted name) that may get its calls rewritten: not the platform, not Alloy. */
    public static boolean eligible(String name) {
        if (name == null) return false;
        for (String p : SKIP) if (name.startsWith(p)) return false;
        return true;
    }

    /** {@code bytes} with every Fabric isModLoaded call pointed at the hook; the same array when there is none. */
    public static byte[] apply(String name, byte[] bytes) {
        if (!ON || bytes == null || !eligible(name) || indexOf(bytes, KEY) < 0 || indexOf(bytes, LOADER) < 0) return bytes;
        try {
            ClassReader cr = new ClassReader(bytes);
            ClassWriter cw = new ClassWriter(cr, 0);
            int[] hits = {0};
            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String sig, String[] ex) {
                    return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, n, d, sig, ex)) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                            if (isTarget(op, owner, mn, md)) {
                                hits[0]++;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, HOOK_NAME, HOOK_DESC, false);
                            } else super.visitMethodInsn(op, owner, mn, md, itf);
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String mn, String md, Handle bsm, Object... args) {
                            Object[] a = args;
                            for (int i = 0; i < args.length; i++) {
                                if (args[i] instanceof Handle h && isTargetHandle(h)) {
                                    if (a == args) a = args.clone();
                                    a[i] = new Handle(Opcodes.H_INVOKESTATIC, HOOK, HOOK_NAME, HOOK_DESC, false);
                                    hits[0]++;
                                }
                            }
                            super.visitInvokeDynamicInsn(mn, md, bsm, a);
                        }
                    };
                }
            }, 0);
            if (hits[0] == 0) return bytes;
            CLASSES.incrementAndGet();
            SITES.addAndGet(hits[0]);
            return cw.toByteArray();
        } catch (Throwable t) {
            return bytes;
        }
    }

    /** Loads this class's helpers up front (ForgeLaunchPlugins.install), so no Alloy class first loads inside a transform. */
    public static void warm() {
        try {
            apply("warm.PresenceWarm", sample("warm/PresenceWarm"));
        } catch (Throwable ignored) {
        }
    }

    /** A class with one isModLoaded call (also used by the unit test). */
    static byte[] sample(String internal) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "check", "(L" + API + ";Ljava/lang/String;)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, API, NAME, DESC, true);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    static boolean isTarget(int op, String owner, String name, String desc) {
        if (!NAME.equals(name) || !DESC.equals(desc)) return false;
        return (op == Opcodes.INVOKEINTERFACE && API.equals(owner)) || (op == Opcodes.INVOKEVIRTUAL && IMPL.equals(owner));
    }

    static boolean isTargetHandle(Handle h) {
        if (!NAME.equals(h.getName()) || !DESC.equals(h.getDesc())) return false;
        return (h.getTag() == Opcodes.H_INVOKEINTERFACE && API.equals(h.getOwner())) || (h.getTag() == Opcodes.H_INVOKEVIRTUAL && IMPL.equals(h.getOwner()));
    }

    static int indexOf(byte[] b, byte[] k) {
        outer:
        for (int i = 0, n = b.length - k.length; i <= n; i++) {
            if (b[i] != k[0]) continue;
            for (int j = 1; j < k.length; j++) if (b[i + j] != k[j]) continue outer;
            return i;
        }
        return -1;
    }
}
