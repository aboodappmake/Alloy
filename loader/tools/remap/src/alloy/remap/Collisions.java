package alloy.remap;

import org.objectweb.asm.*;

import java.util.*;

/**
 * Accidental overrides that the Mojang pivot creates. In the source namespace a game method has a token name
 * (method_N, m_N_), so a mod method called isActive()Z cannot override it. After the remap the game method is
 * isActive()Z too, and the mod method silently overrides it.
 *
 * Fix (plain mod classes): rename the mod method to name$alloy, together with every reference whose owner is
 * that class or a sub class in the same jar. Not fixed, only reported: mixin members (they merge into the target;
 * renaming can break the interface they implement) and methods that also implement a non-game super type.
 */
final class Collisions {
    static void find(JarRemapper jr) throws java.io.IOException {
        NsRemapper r = jr.r;
        if (jr.fixesFile != null && java.nio.file.Files.exists(jr.fixesFile)) {
            for (String l : java.nio.file.Files.readAllLines(jr.fixesFile)) {
                String[] p = l.split("\t");
                if (p.length == 2) r.fixes.put(p[0], p[1]);
            }
            r.clearCaches();
        }
        Hierarchy obfH = new Hierarchy();
        if (jr.gameObf != null) obfH.addJar(jr.gameObf, null, "game-obf");
        try { find(jr, obfH); } finally { obfH.close(); }
        if (jr.fixesFile != null && !jr.stats.collisionsFixed.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (String k : jr.newFixes) b.append(k).append('\t').append(r.fixes.get(k)).append('\n');
            java.nio.file.Files.writeString(jr.fixesFile, b.toString(), java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        }
    }

    /** Overridable: not private, not static, not final (a mod method cannot override those). Unknown: true. */
    static boolean overridable(Mappings.Table t, Hierarchy obfH, String srcOwner, String nd) {
        String k = t.obfKey.get(srcOwner + "." + nd);
        if (k == null) return true;
        int dot = k.indexOf('.');
        Hierarchy.ClassInfo ci = obfH.get(k.substring(0, dot));
        if (ci == null) return true;
        Integer a = ci.macc.get(k.substring(dot + 1));
        return a == null || (a & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == 0;
    }

    private static void find(JarRemapper jr, Hierarchy obfH) {
        NsRemapper r = jr.r;
        Mappings.Table t = jr.t;
        Hierarchy h = jr.h;
        // A jar with no obfuscated ids at all is already in Mojang names (or never touches the game): its methods
        // that match game names are real overrides, so nothing may be renamed (keeps remap(remap(x)) == remap(x)).
        boolean tokens = false;
        java.util.regex.Pattern tok = java.util.regex.Pattern.compile("(?:method|field|comp|class)_\\d|[mf]_\\d+_");
        boolean[] found = {false};
        for (byte[] b : jr.inputClasses.values()) {
            new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
                void test(String s) { if (s != null && tok.matcher(s).find()) found[0] = true; }
                @Override public void visit(int v, int a, String n, String s, String sup, String[] itf) { test(sup); }
                @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                    test(n); test(d);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int op, String o, String nm, String ds, boolean i) { test(o); test(nm); test(ds); }
                        @Override public void visitFieldInsn(int op, String o, String nm, String ds) { test(o); test(nm); test(ds); }
                    };
                }
                @Override public FieldVisitor visitField(int a, String n, String d, String s, Object v) { test(n); test(d); return null; }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (found[0]) { tokens = true; break; }
        }
        if (!tokens) return;
        Map<String, Map<String, String>> rev = new HashMap<>();
        for (Map.Entry<String, byte[]> e : jr.inputClasses.entrySet()) {
            String cls = e.getKey();
            if (t.methods.containsKey(cls) || cls.startsWith("net/minecraft/class_")) continue; // a game class
            ClassReader cr = new ClassReader(e.getValue());
            List<String[]> methods = new ArrayList<>();
            boolean[] mixin = {false};
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public AnnotationVisitor visitAnnotation(String d, boolean v) {
                    if (d.equals("Lorg/spongepowered/asm/mixin/Mixin;")) mixin[0] = true;
                    return null;
                }
                @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] ex) {
                    String[] m = {n, d, String.valueOf(a), ""};
                    methods.add(m);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public AnnotationVisitor visitAnnotation(String ad, boolean vis) {
                            if (ad.startsWith("Lorg/spongepowered/") || ad.startsWith("Lcom/llamalad7/")) m[3] = "annotated";
                            return null;
                        }
                    };
                }
            }, ClassReader.SKIP_CODE);
            if (mixin[0]) continue; // mixin merges are checked (and reported) by the checker with the real targets
            List<String> supers = h.supers(cls);
            for (String[] m : methods) {
                String n = m[0], d = m[1];
                int acc = Integer.parseInt(m[2]);
                if (n.startsWith("<") || (acc & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC)) != 0) continue;
                if (!r.mapMethodName(cls, n, d).equals(n)) continue; // renamed on purpose (a real override)
                String md = r.mapMethodDesc(d);
                String hit = null;
                boolean otherSuper = false;
                for (String s : supers) {
                    boolean game = t.methods.containsKey(s);
                    if (game) {
                        if (hit == null) {
                            String srcKey = rev.computeIfAbsent(s, k -> reverse(t, r, k)).get(n + md);
                            if (srcKey != null && overridable(t, obfH, s, srcKey)) hit = s;
                        }
                    } else {
                        Hierarchy.ClassInfo ci = h.get(s);
                        if (ci != null && ci.methods.contains(n + d)) otherSuper = true;
                    }
                }
                if (hit == null) continue;
                String what = cls + "." + n + d + " vs " + r.map(hit) + "." + n + md;
                if (otherSuper) { jr.stats.collisionsReported.add(what + " (also implements a non-game super)"); continue; }
                r.fixes.put(cls + "." + n + d, n + "$alloy");
                jr.newFixes.add(cls + "." + n + d);
                jr.stats.collisionsFixed.add(what + " -> " + n + "$alloy");
            }
        }
        if (!r.fixes.isEmpty()) r.clearCaches();
    }

    /** Mojang name + Mojang desc of game methods of one source class whose source name differs from Mojang's. */
    /** Mojang name+desc -> source name+desc. */
    private static Map<String, String> reverse(Mappings.Table t, NsRemapper r, String owner) {
        Map<String, String> out = new HashMap<>();
        Map<String, String> m = t.methods.get(owner);
        if (m == null) return out;
        m.forEach((k, moj) -> {
            int p = k.indexOf('(');
            if (!k.substring(0, p).equals(moj)) out.put(moj + r.mapMethodDesc(k.substring(p)), k);
        });
        return out;
    }
}
