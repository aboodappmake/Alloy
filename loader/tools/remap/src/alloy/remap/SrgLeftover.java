package alloy.remap;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

/**
 * Forge 50 and up (Minecraft 1.20.6, 1.21.x) run with Mojang names, but some Forge mods built for them still carry a few
 * SRG member names (m_NNN_, f_NNN_) in their bytecode, left over from an SRG build step. On real Forge those calls fail
 * with NoSuchMethodError/NoSuchFieldError when they run (LuckPerms-Forge 5.4.140: ResourceLocation.m_339182_ when a player
 * joins). This pass renames such member references to their Mojang names, and nothing else.
 *
 * Names come from Forge's own srg2off table (MCPConfig's fields.csv/methods.csv, a library of every Forge 50+ install):
 * one Mojang name per SRG id. A reference is renamed only when all of these hold:
 *   - the name is an SRG id of the right kind (m_ for methods, f_ for fields) and the table has it,
 *   - the owner class resolves (game jar, or a class of the same mod jar whose super types reach the game),
 *   - the Mojang name with the same descriptor exists on the owner or one of its super types,
 *   - the SRG name itself does not exist there (so a mod's own member that happens to look like an id stays).
 * A method a mod class declares with an SRG name (an override that never applies on Mojang names) is renamed only when a
 * super type declares the Mojang name with that descriptor as an overridable method and the class has no such method yet.
 * Mixin classes keep their declared members (only their references are looked at). Strings, refmaps and access
 * transformers are not touched (FML 50+ resolves SRG names in those itself). Classes without SRG ids are copied byte for
 * byte; a jar with nothing to rename is not rewritten at all.
 */
public final class SrgLeftover {
    static final Pattern ID = Pattern.compile("[mf]_\\d+_");

    final Map<String, String> methods, fields;

    public static final class Stats {
        public int jars, classesChanged, refs, decls, nested;
        public final List<String> renamed = new ArrayList<>(), skipped = new ArrayList<>();
        public String summary() {
            return "srg leftovers: " + refs + " reference(s), " + decls + " override(s) renamed in " + classesChanged
                    + " class(es); skipped " + skipped.size();
        }
    }

    public SrgLeftover(Map<String, String> methods, Map<String, String> fields) {
        this.methods = methods; this.fields = fields;
    }

    /** srg2off jar (fields.csv, methods.csv: searge,name,side,desc) or a folder with the two files */
    public static SrgLeftover load(Path srg2off) throws IOException {
        Map<String, String> m = new HashMap<>(), f = new HashMap<>();
        if (Files.isDirectory(srg2off)) {
            readCsv(Files.readAllBytes(srg2off.resolve("methods.csv")), m);
            readCsv(Files.readAllBytes(srg2off.resolve("fields.csv")), f);
        } else try (ZipFile z = new ZipFile(srg2off.toFile())) {
            ZipEntry me = z.getEntry("methods.csv"), fe = z.getEntry("fields.csv");
            if (me == null || fe == null) throw new IOException("no methods.csv/fields.csv in " + srg2off);
            try (InputStream in = z.getInputStream(me)) { readCsv(in.readAllBytes(), m); }
            try (InputStream in = z.getInputStream(fe)) { readCsv(in.readAllBytes(), f); }
        }
        return new SrgLeftover(m, f);
    }

    /** id -> name; an id listed twice with different names is dropped (ambiguous) */
    static void readCsv(byte[] data, Map<String, String> out) {
        Set<String> bad = new HashSet<>();
        for (String line : new String(data, StandardCharsets.UTF_8).split("\r?\n")) {
            String[] a = line.split(",", -1);
            if (a.length < 2 || !ID.matcher(a[0]).matches() || a[1].isEmpty()) continue;
            String old = out.putIfAbsent(a[0], a[1]);
            if (old != null && !old.equals(a[1])) bad.add(a[0]);
        }
        for (String b : bad) out.remove(b);
    }

    /** an SRG id as a whole Utf8 entry or word (the constant pool's 2-byte length is not a word character) */
    private static final Pattern RAW = Pattern.compile("(?<![A-Za-z0-9_$])[mf]_\\d+_(?![A-Za-z0-9_$])");

    /** cheap pre-check on raw class bytes */
    static boolean mayHaveIds(byte[] cls) {
        return RAW.matcher(new String(cls, StandardCharsets.ISO_8859_1)).find();
    }

    /** cheap pre-check of a whole jar (classes, nested jars): false means fixJar has nothing to do */
    public static boolean jarMayHaveIds(Path jar) throws IOException {
        return jarMayHaveIds(Files.readAllBytes(jar), 0);
    }

    static boolean jarMayHaveIds(byte[] jar, int depth) throws IOException {
        try (ZipInputStream zi = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = zi.getNextEntry()) != null; ) {
                String n = e.getName();
                if (n.endsWith(".class") && !n.startsWith("META-INF/versions/")) { if (mayHaveIds(zi.readAllBytes())) return true; }
                else if ((n.endsWith(".jar") || n.endsWith(".jarinjar")) && depth < 4) { if (jarMayHaveIds(zi.readAllBytes(), depth + 1)) return true; }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ jars

    /**
     * Rewrites jar in place when something was renamed; true then. game: the Mojang-named game (and Forge) jars, for
     * the class hierarchy.
     */
    public boolean fixJar(Path jar, List<Path> game, Stats st) throws IOException {
        return fixJar(jar, jar.getFileName().toString(), game, st);
    }

    /** the same, with the name the log uses for the jar (the player's file name, not a staging copy's) */
    public boolean fixJar(Path jar, String label, List<Path> game, Stats st) throws IOException {
        byte[] in = Files.readAllBytes(jar);
        Hierarchy h = new Hierarchy();
        try {
            for (Path g : game) h.addJar(g, null, "game");
            byte[] out = fixJarBytes(in, h, st, label, 0);
            st.jars++;
            if (out == null) return false;
            Path tmp = jar.resolveSibling(jar.getFileName() + ".srg.tmp");
            Files.write(tmp, out);
            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } finally { h.close(); }
    }

    /** the jar with leftovers renamed, or null when nothing changed. Nested jars (.jar, .jarinjar) are handled too. */
    byte[] fixJarBytes(byte[] jar, Hierarchy h, Stats st, String label, int depth) throws IOException {
        List<Object[]> entries = new ArrayList<>();   // {ZipEntry, byte[]}
        try (ZipInputStream zi = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = zi.getNextEntry()) != null; ) entries.add(new Object[]{e, zi.readAllBytes()});
        }
        // the jar's own classes join the hierarchy, so a reference through a mod subclass resolves to the game member
        for (Object[] x : entries) {
            String n = ((ZipEntry) x[0]).getName();
            if (n.endsWith(".class") && !n.startsWith("META-INF/")) {
                try { h.addClass(new ClassReader((byte[]) x[1]).getClassName(), (byte[]) x[1], label); }
                catch (RuntimeException ignored) { /* not a class file */ }
            }
        }
        boolean changed = false;
        // phase 1: SRG-named overrides get their Mojang name first, and the hierarchy sees the renamed classes, so that
        // references to them (mod code calling its own override through the subclass) resolve in phase 2 in one pass
        Map<Object[], ClassNode> nodes = new LinkedHashMap<>();
        Map<ClassNode, Integer> declCount = new HashMap<>();
        for (Object[] x : entries) {
            String n = ((ZipEntry) x[0]).getName();
            if (!n.endsWith(".class") || n.startsWith("META-INF/versions/") || !mayHaveIds((byte[]) x[1])) continue;
            ClassNode cn = new ClassNode();
            try { new ClassReader((byte[]) x[1]).accept(cn, 0); }
            catch (RuntimeException e) { continue; }
            nodes.put(x, cn);
            int d = fixDecls(cn, h, st, label);
            declCount.put(cn, d);
            if (d > 0) h.putClass(cn.name, write(cn), label);
        }
        for (Map.Entry<Object[], ClassNode> en : nodes.entrySet()) {
            ClassNode cn = en.getValue();
            int n = declCount.get(cn) + fixRefs(cn, h, st, label);
            if (n == 0) continue;
            st.classesChanged++;
            en.getKey()[1] = write(cn);
            changed = true;
        }
        for (Object[] x : entries) {
            String n = ((ZipEntry) x[0]).getName();
            if ((n.endsWith(".jar") || n.endsWith(".jarinjar")) && depth < 4) {
                byte[] nb;
                try { nb = fixJarBytes((byte[]) x[1], h, st, label + "!" + n, depth + 1); }
                catch (IOException ex) { nb = null; }
                if (nb != null) { st.nested++; x[1] = nb; changed = true; }
            }
        }
        if (!changed) return null;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(jar.length + 1024);
        try (ZipOutputStream zo = new ZipOutputStream(bo)) {
            for (Object[] x : entries) {
                ZipEntry e = (ZipEntry) x[0];
                ZipEntry ne = new ZipEntry(e.getName());
                ne.setTime(e.getTime());
                byte[] b = (byte[]) x[1];
                if (e.getMethod() == ZipEntry.STORED) {   // nested jars are often stored: keep that
                    ne.setMethod(ZipEntry.STORED);
                    ne.setSize(b.length);
                    ne.setCompressedSize(b.length);
                    CRC32 c = new CRC32();
                    c.update(b);
                    ne.setCrc(c.getValue());
                }
                zo.putNextEntry(ne);
                zo.write(b);
                zo.closeEntry();
            }
        }
        return bo.toByteArray();
    }

    // ------------------------------------------------------------------ classes

    static byte[] write(ClassNode cn) {
        ClassWriter w = new ClassWriter(0);
        cn.accept(w);
        return w.toByteArray();
    }

    /** SRG-named method declarations that override a game method (not in mixins); returns the number renamed */
    int fixDecls(ClassNode cn, Hierarchy h, Stats st, String label) {
        if (isMixin(cn)) return 0;
        int n = 0;
        for (MethodNode m : cn.methods)
            if (m.name.startsWith("m_") && ID.matcher(m.name).matches()) n += fixDecl(cn, m, h, st, label);
        return n;
    }

    /** member references (field/method instructions, lambdas, method handles); returns the number renamed */
    int fixRefs(ClassNode cn, Hierarchy h, Stats st, String label) {
        int n = 0;
        for (MethodNode m : cn.methods) {
            if (m.instructions == null) continue;
            for (AbstractInsnNode i : m.instructions) {
                if (i instanceof MethodInsnNode) {
                    MethodInsnNode mi = (MethodInsnNode) i;
                    String r = method(mi.owner, mi.name, mi.desc, h, st, label, cn.name);
                    if (r != null) { mi.name = r; n++; }
                } else if (i instanceof FieldInsnNode) {
                    FieldInsnNode fi = (FieldInsnNode) i;
                    String r = field(fi.owner, fi.name, fi.desc, h, st, label, cn.name);
                    if (r != null) { fi.name = r; n++; }
                } else if (i instanceof InvokeDynamicInsnNode) {
                    n += fixIndy((InvokeDynamicInsnNode) i, h, st, label, cn.name);
                } else if (i instanceof LdcInsnNode && ((LdcInsnNode) i).cst instanceof Handle) {
                    Handle hd = (Handle) ((LdcInsnNode) i).cst, nh = handle(hd, h, st, label, cn.name);
                    if (nh != null) { ((LdcInsnNode) i).cst = nh; n++; }
                }
            }
        }
        return n;
    }

    static boolean isMixin(ClassNode cn) {
        for (List<AnnotationNode> l : Arrays.asList(cn.invisibleAnnotations, cn.visibleAnnotations))
            if (l != null) for (AnnotationNode a : l) if (a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) return true;
        return false;
    }

    private int fixIndy(InvokeDynamicInsnNode in, Hierarchy h, Stats st, String label, String where) {
        int n = 0;
        for (int k = 0; k < in.bsmArgs.length; k++) {
            if (in.bsmArgs[k] instanceof Handle) {
                Handle nh = handle((Handle) in.bsmArgs[k], h, st, label, where);
                if (nh != null) { in.bsmArgs[k] = nh; n++; }
            }
        }
        // LambdaMetafactory: the indy name is the functional interface's method (the indy's return type), args[0] its type
        if (in.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") && ID.matcher(in.name).matches() && in.name.startsWith("m_")
                && in.bsmArgs.length > 0 && in.bsmArgs[0] instanceof Type) {
            String itf = Type.getReturnType(in.desc).getInternalName();
            String r = method(itf, in.name, ((Type) in.bsmArgs[0]).getDescriptor(), h, st, label, where);
            if (r != null) { in.name = r; n++; }
        }
        return n;
    }

    private Handle handle(Handle hd, Hierarchy h, Stats st, String label, String where) {
        boolean isField = hd.getTag() <= Opcodes.H_PUTSTATIC;
        String r = isField ? field(hd.getOwner(), hd.getName(), hd.getDesc(), h, st, label, where)
                : method(hd.getOwner(), hd.getName(), hd.getDesc(), h, st, label, where);
        return r == null ? null : new Handle(hd.getTag(), hd.getOwner(), r, hd.getDesc(), hd.isInterface());
    }

    /** Mojang name for a method reference, or null (not an id, not in the table, or not safe) */
    String method(String owner, String name, String desc, Hierarchy h, Stats st, String label, String where) {
        if (!name.startsWith("m_") || !ID.matcher(name).matches()) return null;
        if (owner.startsWith("[")) owner = "java/lang/Object";
        String moj = methods.get(name);
        String what = label + " " + where + " -> " + owner + "." + name + desc;
        if (moj == null) { st.skipped.add(what + ": not in srg2off"); return null; }
        if (!h.has(owner)) { st.skipped.add(what + ": owner not found"); return null; }
        if (h.declaring(owner, name + desc) != null) { st.skipped.add(what + ": the SRG name exists there"); return null; }
        if (h.declaring(owner, moj + desc) == null) { st.skipped.add(what + ": no " + moj + desc + " on the owner or its supers"); return null; }
        st.refs++;
        st.renamed.add(what + " => " + moj);
        return moj;
    }

    String field(String owner, String name, String desc, Hierarchy h, Stats st, String label, String where) {
        if (!name.startsWith("f_") || !ID.matcher(name).matches()) return null;
        String moj = fields.get(name);
        String what = label + " " + where + " -> " + owner + "." + name + ":" + desc;
        if (moj == null) { st.skipped.add(what + ": not in srg2off"); return null; }
        if (!h.has(owner)) { st.skipped.add(what + ": owner not found"); return null; }
        if (h.declaring(owner, name + ":" + desc) != null) { st.skipped.add(what + ": the SRG name exists there"); return null; }
        if (h.declaring(owner, moj + ":" + desc) == null) { st.skipped.add(what + ": no " + moj + ":" + desc + " on the owner or its supers"); return null; }
        st.refs++;
        st.renamed.add(what + " => " + moj);
        return moj;
    }

    /** a declared SRG-named override: renamed only when a super type has the Mojang method as an overridable one */
    private int fixDecl(ClassNode cn, MethodNode m, Hierarchy h, Stats st, String label) {
        String moj = methods.get(m.name);
        String what = label + " " + cn.name + " declares " + m.name + m.desc;
        if (moj == null) { st.skipped.add(what + ": not in srg2off"); return 0; }
        if ((m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0) { st.skipped.add(what + ": static or private"); return 0; }
        for (MethodNode o : cn.methods) if (o.name.equals(moj) && o.desc.equals(m.desc)) { st.skipped.add(what + ": " + moj + " already declared"); return 0; }
        for (String s : h.supers(cn.name)) {
            Hierarchy.ClassInfo ci = h.get(s);
            Integer acc = ci == null ? null : ci.macc.get(moj + m.desc);
            if (acc == null) continue;
            if ((acc & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) != 0) { st.skipped.add(what + ": " + s + "." + moj + " is not overridable"); return 0; }
            m.name = moj;
            st.decls++;
            st.renamed.add(what + " => " + moj + " (overrides " + s + ")");
            return 1;
        }
        st.skipped.add(what + ": no super type has " + moj + m.desc);
        return 0;
    }

    // ------------------------------------------------------------------ installer entry

    /**
     * For the installer's prep (V120.SrgLeft calls it by reflection, so the installer compiles without this package).
     * cache[0] keeps the loaded table between jars. Returns null when the jar has no SRG-looking names, else
     * {"1" or "0" (jar changed), number renamed, log text}.
     */
    public static String[] prep(Object[] cache, Path csv, Path jar, String name, List<Path> game) throws IOException {
        if (!jarMayHaveIds(jar)) return null;
        if (cache[0] == null) cache[0] = load(csv);
        Stats st = new Stats();
        boolean changed = ((SrgLeftover) cache[0]).fixJar(jar, name, game, st);
        StringBuilder b = new StringBuilder(name).append(": ").append(st.summary()).append('\n');
        for (String r : st.renamed) b.append("  renamed ").append(r).append('\n');
        for (String r : st.skipped) b.append("  skipped ").append(r).append('\n');
        return new String[]{changed ? "1" : "0", String.valueOf(st.refs + st.decls), b.toString()};
    }

    // ------------------------------------------------------------------ CLI (remap tool: srgleft)

    /** srgleft --csv srg2off.jar --game a.jar;b.jar JAR... : rewrites the jars in place and prints what was renamed */
    static void cli(Map<String, String> o, List<String> jars) throws Exception {
        SrgLeftover s = load(Paths.get(o.get("csv")));
        List<Path> game = Main.cp(o.get("game"));
        Stats st = new Stats();
        int changed = 0;
        for (String j : jars) if (s.fixJar(Paths.get(j), game, st)) { changed++; System.out.println("changed " + j); }
        for (String r : st.renamed) System.out.println("  renamed " + r);
        for (String r : st.skipped) System.out.println("  skipped " + r);
        System.out.println(st.summary() + "; jars " + st.jars + ", changed " + changed);
    }
}
