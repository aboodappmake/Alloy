package alloy.installer;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * Automatic fitting of Fabric mods to the Forge-patched game (Java port of static_fabric_check.py + MixinScan,
 * adapt_fabric.py, StripInjector, FixDefaultClash). No game boot, nothing is loaded: bytes -> ASM only.
 *
 * Per Fabric mod jar (and the jars nested in META-INF/jars):
 *   1. scan every mixin class, compare each target with the vanilla jar and the Forge-patched game jar
 *      (class, method + descriptor, normalized instruction hash, @At INVOKE/FIELD/NEW target presence),
 *   2. BROKEN injector            -> strip just that handler (annotation removed, rest of the mixin kept),
 *      BROKEN target class / @Overwrite / @Shadow / @Accessor field / @Invoker -> the mixin is disabled in its config,
 *      mixins that use something only a disabled mixin provided -> disabled too (dependents),
 *      accessor method that Forge already added to the target -> config overwrites.requireAnnotations=false,
 *   3. compat_replaces.txt entries (mixins/injectors that an Alloy compat mixin re-implements),
 *   4. helpers that would shadow Forge IForge* default methods are renamed (FixDefaultClash).
 */
final class Fit {
    private Fit() { }

    static final String VERSION = "fit-23";
    /** Forge-side libraries of the game being fitted (set by run; V120.forgeLibs) */
    static volatile Map<String, Object[]> FORGE_LIBS = Map.of();
    /** the version's aw_edits drop lines (V120.dropAccessWideners), set per fit run */
    static volatile List<List<String>> AW_DROPS = List.of();
    private static final String DEBUG = System.getProperty("alloy.fit.debug");
    private static final Map<String, Integer> ORDER = Map.of("SAFE", 0, "NA", 0, "RISKY", 1, "BROKEN", 2);

    // ================================================================== game side
    static final class Meth {
        String name, desc, hash;
        int n, maxLocals;
        boolean isStatic;
        final Map<Integer, Set<String>> lvt = new HashMap<>();
        final List<int[]> lvtRanges = new ArrayList<>();   // {start insn, end insn} per entry, same order as lvtNames
        final List<String> lvtNames = new ArrayList<>();
        final List<String> lvtDescs = new ArrayList<>();
        final List<Integer> lvtSlots = new ArrayList<>();
        final List<Integer> refPos = new ArrayList<>();     // insn index of each refs entry
        final List<Integer> returns = new ArrayList<>();    // insn indexes of every xRETURN
        int lastReturn = -1;                               // insn index of the last xRETURN (Mixin's TAIL)   // slot -> local variable descriptors seen there (LocalVariableTable)
        final List<String[]> refs = new ArrayList<>();   // {kind, ref}  kind M call "owner.name desc", G field "owner.name:desc", N type
    }

    static final class CI {
        String name, sup;
        final Set<String> fields = new HashSet<>();          // name + ":" + desc
        final Map<String, Meth> methods = new LinkedHashMap<>();   // name + desc
    }

    /** the game classes one fit read, as "tag class" (see {@link Dump#tag}); set per thread while a fit runs (FitCache). */
    static final ThreadLocal<Set<String>> READS = new ThreadLocal<>();

    /** lazily parsed classes of one jar (only the classes the mixins target, plus their superclasses). Thread-safe: fits of
     *  several mods share one Dump. */
    static final class Dump implements Closeable {
        final ZipFile zf;
        final String tag;   // "<side>/v" vanilla or "<side>/p" Forge-patched: names this jar in a FitCache key
        final Map<String, Optional<CI>> cache = new java.util.concurrent.ConcurrentHashMap<>();

        Dump(Path jar) throws IOException { this(jar, ""); }

        Dump(Path jar, String tag) throws IOException { zf = new ZipFile(jar.toFile()); this.tag = tag; }

        @Override public void close() throws IOException { zf.close(); }

        CI cls(String c) {
            Set<String> reads = READS.get();
            if (reads != null) reads.add(tag + " " + c);
            return cache.computeIfAbsent(c, k -> {
                try {
                    ZipEntry e = zf.getEntry(k + ".class");
                    if (e == null) return Optional.empty();
                    byte[] b;
                    try (InputStream in = zf.getInputStream(e)) { b = in.readAllBytes(); }
                    return Optional.of(parse(b));
                } catch (Exception ex) { return Optional.empty(); }
            }).orElse(null);
        }

        /** content id of one class entry ("-" when absent), for FitCache keys */
        String entryId(String c) throws IOException {
            ZipEntry e = zf.getEntry(c + ".class");
            if (e == null) return "-";
            try (InputStream in = zf.getInputStream(e)) { return Util.sha1(in.readAllBytes()); }
        }

        boolean has(String c) { return cls(c) != null; }

        List<CI> chain(String c) {
            List<CI> out = new ArrayList<>();
            CI ci = cls(c);
            for (int i = 0; ci != null && i < 40; i++) {
                out.add(ci);
                ci = ci.sup == null || ci.sup.isEmpty() ? null : cls(ci.sup);
            }
            return out;
        }

        boolean findField(String c, String name) {
            for (CI k : chain(c)) for (String f : k.fields) if (f.substring(0, f.indexOf(':')).equals(name)) return true;
            return false;
        }

        /** a field with this name and descriptor (desc null: any descriptor) in the class or its superclasses */
        boolean findField(String c, String name, String desc) {
            if (desc == null) return findField(c, name);
            for (CI k : chain(c)) for (String f : k.fields) if (f.equals(name + ":" + desc)) return true;
            return false;
        }

        boolean findMethod(String c, String name, String desc) {
            for (CI k : chain(c)) for (Meth m : k.methods.values()) if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return true;
            return false;
        }

        final Map<String, Optional<ClassNode>> raw = new java.util.concurrent.ConcurrentHashMap<>();

        /** the full class (code, frames, debug info) for Recover's proofs; recorded as a read like cls() */
        ClassNode raw(String c) {
            if (cls(c) == null) return null;
            return raw.computeIfAbsent(c, k -> {
                try {
                    ZipEntry e = zf.getEntry(k + ".class");
                    if (e == null) return Optional.empty();
                    try (InputStream in = zf.getInputStream(e)) {
                        ClassNode cn = new ClassNode();
                        new ClassReader(in.readAllBytes()).accept(cn, 0);
                        return Optional.of(cn);
                    }
                } catch (Exception ex) { return Optional.empty(); }
            }).orElse(null);
        }

        List<Meth> declared(String c) {
            CI ci = cls(c);
            return ci == null ? List.of() : new ArrayList<>(ci.methods.values());
        }
    }

    static CI parse(byte[] b) throws IOException {
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, ClassReader.SKIP_FRAMES);   // debug kept for the LocalVariableTable (local capture check)
        CI ci = new CI();
        ci.name = cn.name;
        ci.sup = cn.superName == null ? "" : cn.superName;
        for (FieldNode f : cn.fields) ci.fields.add(f.name + ":" + f.desc);
        for (MethodNode m : cn.methods) {
            Meth me = new Meth();
            me.name = m.name;
            me.desc = m.desc;
            StringBuilder sb = new StringBuilder();
            Map<LabelNode, Integer> lab = new IdentityHashMap<>();
            int idx = 0;
            for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
                if (i instanceof LabelNode l) lab.put(l, idx);
                else if (i.getOpcode() >= 0) idx++;
            }
            int n = 0;
            for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
                int op = i.getOpcode();
                if (op < 0) continue;
                n++;
                sb.append(op);
                if (i instanceof MethodInsnNode mi) { String ref = mi.owner + "." + mi.name + mi.desc; me.refs.add(new String[]{"M", ref}); me.refPos.add(n - 1); sb.append(' ').append(ref); }
                else if (i instanceof FieldInsnNode fi) { String ref = fi.owner + "." + fi.name + ":" + fi.desc; me.refs.add(new String[]{"G", ref}); me.refPos.add(n - 1); sb.append(' ').append(ref); }
                else if (i instanceof TypeInsnNode ti) { me.refs.add(new String[]{"N", ti.desc}); me.refPos.add(n - 1); sb.append(' ').append(ti.desc); }
                else if (i instanceof VarInsnNode vi) sb.append(' ').append(vi.var);
                else if (i instanceof IincInsnNode ii) sb.append(' ').append(ii.var).append(' ').append(ii.incr);
                else if (i instanceof IntInsnNode ni) sb.append(' ').append(ni.operand);
                else if (i instanceof LdcInsnNode li) sb.append(' ').append(li.cst.getClass().getSimpleName()).append(':').append(li.cst);
                else if (i instanceof JumpInsnNode ji) sb.append(' ').append(lab.get(ji.label));
                else if (i instanceof MultiANewArrayInsnNode mn) sb.append(' ').append(mn.desc).append(mn.dims);
                else if (i instanceof InvokeDynamicInsnNode di) {
                    sb.append(' ').append(di.name).append(di.desc).append(' ').append(di.bsm);
                    for (Object o : di.bsmArgs) sb.append(' ').append(o);
                    for (Object o : di.bsmArgs)
                        if (o instanceof Handle h) { me.refs.add(new String[]{"M", h.getOwner() + "." + h.getName() + h.getDesc()}); me.refPos.add(n - 1); }
                } else if (i instanceof TableSwitchInsnNode ts) {
                    sb.append(' ').append(ts.min).append(' ').append(ts.max).append(' ').append(lab.get(ts.dflt));
                    for (LabelNode l : ts.labels) sb.append(',').append(lab.get(l));
                } else if (i instanceof LookupSwitchInsnNode ls) {
                    sb.append(' ').append(lab.get(ls.dflt));
                    for (int k = 0; k < ls.keys.size(); k++) sb.append(',').append(ls.keys.get(k)).append(':').append(lab.get(ls.labels.get(k)));
                }
                sb.append(';');
            }
            if (m.tryCatchBlocks != null)
                for (TryCatchBlockNode t : m.tryCatchBlocks)
                    sb.append("T").append(lab.get(t.start)).append(',').append(lab.get(t.end)).append(',').append(lab.get(t.handler)).append(',').append(t.type).append(';');
            try {
                byte[] h = MessageDigest.getInstance("SHA-1").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (int k = 0; k < 6; k++) hex.append(String.format("%02x", h[k]));
                me.hash = hex.toString();
            } catch (Exception e) { throw new IOException(e); }
            me.n = n;
            me.maxLocals = m.maxLocals;
            me.isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
            if (m.localVariables != null)
                for (LocalVariableNode lv : m.localVariables) {
                    me.lvt.computeIfAbsent(lv.index, k -> new TreeSet<>()).add(lv.desc);
                    Integer ls = lab.get(lv.start), le = lab.get(lv.end);
                    if (ls != null && le != null) { me.lvtRanges.add(new int[]{ls, le}); me.lvtNames.add(lv.name); me.lvtDescs.add(lv.desc); me.lvtSlots.add(lv.index); }
                }
            int qi = 0;
            for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
                int op = i.getOpcode();
                if (op < 0) continue;
                if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) { me.lastReturn = qi; me.returns.add(qi); }
                qi++;
            }
            ci.methods.put(m.name + m.desc, me);
        }
        return ci;
    }

    /** vanilla + Forge-patched jar per side that is available in this game dir (client dir: client only). */
    static final class Games implements Closeable {
        final Map<String, Dump[]> sides = new LinkedHashMap<>();
        Set<String> defaults = new HashSet<>();   // IForge* default methods (name+desc)
        /** class -> method name -> vanilla descriptors of overloads PrepGame's anchors added (<game jar>.overloads.txt) */
        Map<String, Map<String, Set<String>>> overloads = new HashMap<>();
        String id = "";
        /** Fabric API module id -> version of the top-level Fabric API's copy in mods/ (see {@link #topFabricApi}) */
        Map<String, String> shadow = new TreeMap<>();

        /** reads "class name vanillaDesc" lines (missing file = no overloads) */
        void loadOverloads(Path file) throws IOException {
            if (file == null || !Files.exists(file)) return;
            for (String l : Files.readAllLines(file)) {
                String[] x = l.trim().split(" ");
                if (x.length == 3) overloads.computeIfAbsent(x[0], k -> new HashMap<>()).computeIfAbsent(x[1], k -> new TreeSet<>()).add(x[2]);
            }
        }

        void add(String side, Path vanilla, Path patched) throws IOException {
            sides.put(side, new Dump[]{new Dump(vanilla, side + "/v"), new Dump(patched, side + "/p")});
        }


        @Override public void close() throws IOException {
            for (Dump[] d : sides.values()) { d[0].close(); d[1].close(); }
        }

        void loadDefaults(Path universal) throws IOException {
            if (universal == null || !Files.exists(universal)) return;
            try (ZipFile z = new ZipFile(universal.toFile())) {
                for (ZipEntry e : Collections.list(z.entries())) {
                    String n = e.getName();
                    if (!n.startsWith("net/minecraftforge/common/extensions/IForge") || !n.endsWith(".class")) continue;
                    ClassNode cn = new ClassNode();
                    try (InputStream in = z.getInputStream(e)) { new ClassReader(in.readAllBytes()).accept(cn, ClassReader.SKIP_CODE); }
                    for (MethodNode m : cn.methods)
                        if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC)) == 0) defaults.add(m.name + m.desc);
                }
            }
        }
    }

    // ================================================================== mod jars
    /**
     * One mixin member. sel and atTgt are what Mixin really looks for: the annotation strings resolved through the mod's
     * refmap. rawSel and rawAt are the strings as written in the class, which is what a rewrite has to find.
     */
    record Rec(String type, String cls, String name, String desc, String anno, String sel, String atVal, String atTgt, String ord, String val,
               String rawSel, String rawAt) {
        Rec(String type, String cls, String name, String desc, String anno, String sel, String atVal, String atTgt, String ord, String val) {
            this(type, cls, name, desc, anno, sel, atVal, atTgt, ord, val, sel, atTgt);
        }
    }

    static final class MixinInfo {
        String side, cfg, cls;
        List<String> targets = new ArrayList<>();
        List<Rec> recs = new ArrayList<>();
    }

    static final class Cfg {
        String pkg;
        List<String> entries = new ArrayList<>();
    }

    /** one jar (the mod itself or a jar-in-jar). */
    static final class Comp {
        String id, version, name;
        Comp parent;
        final List<String[]> mixins = new ArrayList<>();   // side, config, internal class name
        final Map<String, byte[]> classes = new HashMap<>();
        final Map<String, Cfg> configs = new LinkedHashMap<>();
        final List<String> notes = new ArrayList<>();
        /** mixin internal class name -> {annotation string: refmap value}, from the refmaps of this jar's mixin configs */
        final Map<String, Map<String, String>> refmap = new HashMap<>();
        boolean fabric;

        Map<String, String> refmapOf(String mixinCls) { return refmap.getOrDefault(mixinCls, Map.of()); }
    }

    /**
     * Reads a refmap's "mappings" into out. 1.20.1 Fabric mods built with Loom name their injector targets in the
     * mod's dev names (Yarn) and map them through the refmap; the name translator rewrote the values to Mojang names.
     * 26.x mods are Mojang-named: their refmaps are absent or map strings to themselves.
     */
    static void readRefmap(byte[] b, Map<String, Map<String, String>> out) {
        if (b == null) return;
        Map<String, Object> j = Json.obj(parseJson(b));
        if (!(j.get("mappings") instanceof Map<?, ?> m)) return;
        for (var e : m.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> vals)) continue;
            Map<String, String> dst = out.computeIfAbsent(String.valueOf(e.getKey()), k -> new HashMap<>());
            for (var v : vals.entrySet()) if (v.getValue() instanceof String s) dst.put(String.valueOf(v.getKey()), s);
        }
    }

    /** the string Mixin uses for an annotation value: the refmap's value when it has one */
    static String resolve(Map<String, String> rm, String s) {
        if (s == null || s.isEmpty() || rm.isEmpty()) return s;
        String r = rm.get(s);
        return r == null ? s : r;
    }

    static Object parseJson(byte[] b) {
        String t = new String(b, StandardCharsets.UTF_8);
        if (t.startsWith("﻿")) t = t.substring(1);
        try { return Json.parse(t); }
        catch (RuntimeException e) { return Json.parse(lenient(t)); }
    }

    /** strips // and block comments and trailing commas outside of strings. */
    static String lenient(String t) {
        StringBuilder o = new StringBuilder();
        boolean str = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (str) {
                o.append(c);
                if (c == '\\' && i + 1 < t.length()) o.append(t.charAt(++i));
                else if (c == '"') str = false;
            } else if (c == '"') { str = true; o.append(c); }
            else if (c == '/' && i + 1 < t.length() && t.charAt(i + 1) == '/') { while (i < t.length() && t.charAt(i) != '\n') i++; }
            else if (c == '/' && i + 1 < t.length() && t.charAt(i + 1) == '*') { int e = t.indexOf("*/", i + 2); i = e < 0 ? t.length() : e + 1; }
            else o.append(c);
        }
        return o.toString().replaceAll(",(\\s*[}\\]])", "$1");
    }

    static Map<String, byte[]> readZip(byte[] jar) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                if (e.isDirectory()) continue;
                boolean want = n.endsWith(".class") || n.endsWith(".json") || (n.startsWith("META-INF/jars/") && n.endsWith(".jar"));
                if (want) out.put(n, z.readAllBytes());
            }
        }
        return out;
    }

    static List<Comp> components(byte[] jar, String name, Comp parent, int depth) throws IOException {
        List<Comp> out = new ArrayList<>();
        Comp c = new Comp();
        c.name = name;
        c.id = name;
        c.parent = parent;
        out.add(c);
        Map<String, byte[]> ent = readZip(jar);
        for (var e : ent.entrySet()) if (e.getKey().endsWith(".class")) c.classes.put(e.getKey().substring(0, e.getKey().length() - 6), e.getValue());
        // every root-level *mixin*.json is indexed (used for dependents and compat entries)
        for (var e : ent.entrySet()) {
            String n = e.getKey();
            if (n.indexOf('/') >= 0 || !n.endsWith(".json") || !n.contains("mixins")) continue;
            try {
                Map<String, Object> j = Json.obj(parseJson(e.getValue()));
                Cfg cf = new Cfg();
                cf.pkg = j.get("package") instanceof String s ? s : "";
                for (String k : List.of("mixins", "server", "client")) if (j.get(k) instanceof List<?> l) for (Object x : l) cf.entries.add(String.valueOf(x));
                c.configs.put(n, cf);
            } catch (RuntimeException ex) { /* not a mixin config */ }
        }
        byte[] fmj = ent.get("fabric.mod.json");
        if (fmj == null) { c.notes.add("no fabric.mod.json"); return out; }
        c.fabric = true;
        Map<String, Object> fm;
        try { fm = Json.obj(parseJson(fmj)); } catch (RuntimeException ex) { c.notes.add("bad fabric.mod.json"); return out; }
        if (fm.get("id") instanceof String s) c.id = s;
        c.version = String.valueOf(fm.getOrDefault("version", ""));
        List<String[]> entries = new ArrayList<>();   // env side, config
        if (fm.get("mixins") instanceof List<?> l) for (Object x : l) {
            if (x instanceof String s) entries.add(new String[]{"both", s});
            else if (x instanceof Map<?, ?> m) {
                String env = String.valueOf(m.get("environment") == null ? "*" : m.get("environment"));
                entries.add(new String[]{env.equals("client") ? "client" : env.equals("server") ? "server" : "both", (String) m.get("config")});
            }
        }
        for (String[] en : entries) {
            String cfg = en[1];
            if (cfg == null || !ent.containsKey(cfg)) { c.notes.add("mixin config not found: " + cfg); continue; }
            Map<String, Object> j;
            try { j = Json.obj(parseJson(ent.get(cfg))); } catch (RuntimeException ex) { c.notes.add("bad mixin config " + cfg); continue; }
            String pkg = (j.get("package") instanceof String s ? s : "").replace('.', '/');
            if (j.get("refmap") instanceof String rmName && ent.containsKey(rmName)) {
                try { readRefmap(ent.get(rmName), c.refmap); } catch (RuntimeException ex) { c.notes.add("bad refmap " + rmName); }
            }
            for (String[] ks : new String[][]{{"mixins", "both"}, {"client", "client"}, {"server", "server"}}) {
                if (!(j.get(ks[0]) instanceof List<?> l)) continue;
                for (Object m : l) {
                    String side = en[0].equals("both") ? ks[1] : en[0];
                    if (!en[0].equals("both") && !ks[1].equals("both") && !ks[1].equals(en[0])) continue;
                    String cls = (pkg + "/" + m).replace('.', '/');
                    while (cls.startsWith("/")) cls = cls.substring(1);
                    c.mixins.add(new String[]{side, cfg, cls});
                }
            }
            // a config plugin can add mixins from its getMixins() that the json does not list (Farmer's Delight
            // Refabricated's FenceGateBlockMixin). Check every @Mixin class under the config's package too.
            if (j.get("plugin") instanceof String && !pkg.isEmpty()) {
                Set<String> listed = new HashSet<>();
                for (String[] x : c.mixins) if (x[1].equals(cfg)) listed.add(x[2]);
                byte[] marker = "Lorg/spongepowered/asm/mixin/Mixin;".getBytes(StandardCharsets.UTF_8);
                for (var e : ent.entrySet()) {
                    String n = e.getKey();
                    if (!n.startsWith(pkg + "/") || !n.endsWith(".class")) continue;
                    String cls = n.substring(0, n.length() - 6);
                    if (listed.contains(cls) || !contains(e.getValue(), marker)) continue;
                    c.mixins.add(new String[]{en[0], cfg, cls});
                }
            }
        }
        List<String> nested = new ArrayList<>();
        for (String n : ent.keySet()) if (n.startsWith("META-INF/jars/") && n.endsWith(".jar")) nested.add(n);
        if (fm.get("jars") instanceof List<?> l) for (Object x : l)
            if (x instanceof Map<?, ?> m && m.get("file") instanceof String f && ent.containsKey(f) && !nested.contains(f)) nested.add(f);
        if (depth < 3) for (String n : nested) out.addAll(components(ent.get(n), n.substring(n.lastIndexOf('/') + 1).replace(".jar", ""), c, depth + 1));
        return out;
    }

    /**
     * MixinExtras expression points name their calls and fields in @Definition(method = ..., field = ...). Kept as
     * "EXPR M:owner.name(desc)|G:owner.name:desc" so the Forge method can be checked for them like an INVOKE/FIELD target.
     */
    static String expressionDefs(MethodNode m) { return expressionDefs(m, Map.of()); }

    static String expressionDefs(MethodNode m, Map<String, String> rm) {
        List<String> defs = new ArrayList<>();
        for (AnnotationNode a : annos(m.visibleAnnotations, m.invisibleAnnotations)) {
            List<Object> ds = new ArrayList<>();
            if (a.desc.equals("Lcom/llamalad7/mixinextras/expression/Definition;")) ds.add(a);
            else if (a.desc.equals("Lcom/llamalad7/mixinextras/expression/Definitions;")) ds.addAll(list(get(a, "value")));
            for (Object o : ds) {
                if (!(o instanceof AnnotationNode d)) continue;
                for (Object x : list(get(d, "method"))) { String r = memberRef(resolve(rm, String.valueOf(x))); if (r != null) defs.add("M:" + r); }
                for (Object x : list(get(d, "field"))) { String r = memberRef(resolve(rm, String.valueOf(x))); if (r != null) defs.add("G:" + r); }
            }
        }
        return defs.isEmpty() ? "" : "EXPR " + String.join("|", defs);
    }

    /** "Lowner;name(desc)ret" or "Lowner;name:desc" -> "owner.name(desc)ret" / "owner.name:desc"; null if not that shape */
    static String memberRef(String s) {
        if (!s.startsWith("L") || s.indexOf(';') < 0) return null;
        int i = s.indexOf(';');
        return s.substring(1, i) + "." + s.substring(i + 1);
    }

    static int countExact(Meth m, String kind, String ref) {
        int c = 0;
        for (String[] r : m.refs) if (r[0].equals(kind) && r[1].equals(ref)) c++;
        return c;
    }

    static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int k = 0; k < needle.length; k++) if (hay[i + k] != needle[k]) continue outer;
            return true;
        }
        return false;
    }

    // ================================================================== mixin scan
    static Object get(AnnotationNode an, String key) {
        if (an.values == null) return null;
        for (int i = 0; i + 1 < an.values.size(); i += 2) if (an.values.get(i).equals(key)) return an.values.get(i + 1);
        return null;
    }

    static List<Object> list(Object o) {
        if (o == null) return List.of();
        if (o instanceof List<?> l) return new ArrayList<>(l);
        return List.of(o);
    }

    static List<AnnotationNode> annos(List<AnnotationNode> a, List<AnnotationNode> b) {
        List<AnnotationNode> r = new ArrayList<>();
        if (a != null) r.addAll(a);
        if (b != null) r.addAll(b);
        return r;
    }

    static String simpleName(String desc) {
        String s = desc.substring(1, desc.length() - 1);
        return s.substring(s.lastIndexOf('/') + 1);
    }

    /** handler reads locals of the target: @Local parameters, or @Inject parameters after the CallbackInfo. */
    /** MixinExtras @Local parameters (found by type, ordinal or name at the injection point, not by position) */
    static boolean sugarLocals(MethodNode m) {
        for (List<AnnotationNode>[] pa : new List[][]{m.visibleParameterAnnotations, m.invisibleParameterAnnotations})
            if (pa != null) for (List<AnnotationNode> l : pa)
                if (l != null) for (AnnotationNode a : l) if (a.desc.equals("Lcom/llamalad7/mixinextras/sugar/Local;")) return true;
        return false;
    }

    /** ":a,b" for @Local(name = ...) parameters, "" if none use a name */
    static String sugarNames(MethodNode m) {
        List<String> names = new ArrayList<>();
        for (List<AnnotationNode>[] pa : new List[][]{m.visibleParameterAnnotations, m.invisibleParameterAnnotations})
            if (pa != null) for (List<AnnotationNode> l : pa)
                if (l != null) for (AnnotationNode a : l)
                    if (a.desc.equals("Lcom/llamalad7/mixinextras/sugar/Local;")) for (Object n : list(get(a, "name"))) names.add(String.valueOf(n));
        return names.isEmpty() ? "" : ":" + String.join(",", names);
    }

    /** one @Local parameter: wanted type ("?" unknown), ordinal (-1 = implicit), index (-1 = none), argsOnly, name ("" = none) */
    record LocalSpec(String type, int ordinal, int index, boolean argsOnly, String name) { }

    static final Map<String, String> LOCAL_REFS = Map.of(
            "Lcom/llamalad7/mixinextras/sugar/ref/LocalIntRef;", "I", "Lcom/llamalad7/mixinextras/sugar/ref/LocalBooleanRef;", "Z",
            "Lcom/llamalad7/mixinextras/sugar/ref/LocalFloatRef;", "F", "Lcom/llamalad7/mixinextras/sugar/ref/LocalDoubleRef;", "D",
            "Lcom/llamalad7/mixinextras/sugar/ref/LocalLongRef;", "J", "Lcom/llamalad7/mixinextras/sugar/ref/LocalShortRef;", "S",
            "Lcom/llamalad7/mixinextras/sugar/ref/LocalByteRef;", "B", "Lcom/llamalad7/mixinextras/sugar/ref/LocalCharRef;", "C");

    /** erased type arguments of every LocalRef<T> in a method signature, in order ("?" for a type variable) */
    static List<String> localRefArgs(String sig) {
        List<String> out = new ArrayList<>();
        if (sig == null) return out;
        String key = "Lcom/llamalad7/mixinextras/sugar/ref/LocalRef<";
        for (int i = sig.indexOf(key); i >= 0; i = sig.indexOf(key, i + 1)) {
            int j = i + key.length(), depth = 0, k = j;
            for (; k < sig.length(); k++) {
                char c = sig.charAt(k);
                if (c == '<') depth++;
                else if (c == '>') { if (depth == 0) break; depth--; }
            }
            String t = sig.substring(j, k);
            if (t.startsWith("T") || t.startsWith("*") || t.startsWith("+") || t.startsWith("-")) { out.add("?"); continue; }
            StringBuilder e = new StringBuilder();   // erase nested generics: Ljava/util/List<...>; -> Ljava/util/List;
            int d = 0;
            for (char c : t.toCharArray()) {
                if (c == '<') d++;
                else if (c == '>') d--;
                else if (d == 0) e.append(c);
            }
            out.add(e.toString());
        }
        return out;
    }

    /** "type,ordinal,index,argsOnly,name" per @Local parameter, '#' separated (descriptors hold ';') */
    static String sugarSpec(MethodNode m) {
        Type[] pt = Type.getArgumentTypes(m.desc);
        List<String> refArgs = localRefArgs(m.signature);
        List<String> out = new ArrayList<>();
        int refIdx = 0;
        for (int i = 0; i < pt.length; i++) {
            AnnotationNode loc = null;
            for (List<AnnotationNode>[] pa : new List[][]{m.visibleParameterAnnotations, m.invisibleParameterAnnotations})
                if (pa != null && i < pa.length && pa[i] != null) for (AnnotationNode a : pa[i]) if (a.desc.equals("Lcom/llamalad7/mixinextras/sugar/Local;")) loc = a;
            String d = pt[i].getDescriptor();
            boolean genericRef = d.equals("Lcom/llamalad7/mixinextras/sugar/ref/LocalRef;");
            String type = LOCAL_REFS.getOrDefault(d, d);
            if (genericRef) type = refIdx < refArgs.size() ? refArgs.get(refIdx) : "?";
            if (genericRef) refIdx++;
            if (loc == null) continue;
            Object o = get(loc, "ordinal"), x = get(loc, "index"), a = get(loc, "argsOnly");
            List<Object> names = list(get(loc, "name"));
            out.add(type + "," + (o == null ? -1 : o) + "," + (x == null ? -1 : x) + "," + (Boolean.TRUE.equals(a) ? 1 : 0) + "," + (names.isEmpty() ? "" : names.get(0)));
        }
        return String.join("#", out);
    }

    /**
     * the variable a @ModifyVariable picks, as a LocalSpec string: its type is the handler's return type. Several names or
     * a slice give type "?" (not proven).
     */
    static String modifyVariableSpec(MethodNode m, AnnotationNode an) {
        Object o = get(an, "ordinal"), x = get(an, "index"), a = get(an, "argsOnly");
        List<Object> names = list(get(an, "name"));
        String type = Type.getReturnType(m.desc).getDescriptor();
        if (names.size() > 1 || get(an, "slice") != null) type = "?";
        return type + "," + (o == null ? -1 : o) + "," + (x == null ? -1 : x) + "," + (Boolean.TRUE.equals(a) ? 1 : 0) + "," + (names.isEmpty() ? "" : names.get(0));
    }

    static List<LocalSpec> parseSpecs(String val) {
        int bar = val.indexOf('|');
        if (bar < 0 || bar == val.length() - 1) return List.of();
        List<LocalSpec> out = new ArrayList<>();
        for (String s : val.substring(bar + 1).split("#")) {
            String[] f = s.split(",", 5);
            out.add(new LocalSpec(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), f[3].equals("1"), f.length > 4 ? f[4] : ""));
        }
        return out;
    }

    /** instruction indexes where the injector lands; null when they cannot be told */
    static List<Integer> injectionPoints(Meth m, String atVal, String atTgt, String ord) {
        String[] sp = atVal.split(":");
        String a = sp[sp.length - 1].toUpperCase();
        int o = Integer.parseInt(ord);
        List<Integer> pts = new ArrayList<>();
        switch (a) {
            case "HEAD" -> pts.add(0);
            case "TAIL" -> { if (m.lastReturn < 0) return null; pts.add(m.lastReturn); }
            case "RETURN" -> pts.addAll(m.returns);
            case "EXPRESSION" -> {   // an expression around exactly one named call: its points are that call's
                if (atTgt == null || !atTgt.startsWith("EXPR ")) return null;
                String[] defs = atTgt.substring(5).split("\\|");
                if (defs.length != 1 || !defs[0].startsWith("M:")) return null;
                String ref = defs[0].substring(2);
                for (int i = 0; i < m.refs.size(); i++) if (m.refs.get(i)[0].equals("M") && m.refs.get(i)[1].equals(ref)) pts.add(m.refPos.get(i));
                if (pts.isEmpty()) return null;
            }
            case "INVOKE", "INVOKE_ASSIGN", "FIELD" -> {
                if (atTgt == null || atTgt.isEmpty()) return null;
                for (int i = 0; i < m.refs.size(); i++) {
                    String[] r = m.refs.get(i);
                    boolean hit;
                    if (a.equals("FIELD")) {
                        Matcher fm = FIELDSEL.matcher(atTgt.strip());
                        if (!fm.matches() || !r[0].equals("G")) continue;
                        String owner = fm.group(1) != null ? fm.group(1) : fm.group(2), fn = fm.group(3), ds = fm.group(4);
                        String[] p = refParts("G", r[1]);
                        hit = (owner == null || p[0].equals(owner)) && p[1].equals(fn) && (ds == null || p[2].equals(ds));
                    } else {
                        if (!r[0].equals("M")) continue;
                        String[] s = parseSel(atTgt), p = refParts("M", r[1]);
                        hit = (s[0] == null || p[0].equals(s[0])) && p[1].equals(s[1]) && descMatch(s[2], p[2]);
                    }
                    if (hit) pts.add(m.refPos.get(i) + (a.equals("INVOKE_ASSIGN") ? 1 : 0));
                }
            }
            default -> { return null; }
        }
        if (o >= 0) { if (o >= pts.size()) return null; return List.of(pts.get(o)); }
        return pts;
    }

    /** is a local with this name live at the method's last return (Mixin TAIL)? */
    static boolean liveAtTail(Meth m, String name) {
        for (int i = 0; i < m.lvtNames.size(); i++)
            if (m.lvtNames.get(i).equals(name)) { int[] r = m.lvtRanges.get(i); if (r[0] <= m.lastReturn && m.lastReturn < r[1]) return true; }
        return false;
    }

    static boolean capturesLocals(MethodNode m, AnnotationNode an) {
        for (List<AnnotationNode>[] pa : new List[][]{m.visibleParameterAnnotations, m.invisibleParameterAnnotations})
            if (pa != null) for (List<AnnotationNode> l : pa)
                if (l != null) for (AnnotationNode a : l) if (a.desc.equals("Lcom/llamalad7/mixinextras/sugar/Local;")) return true;
        if (an.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) {
            Type[] args = Type.getArgumentTypes(m.desc);
            for (int i = 0; i < args.length; i++)
                if (args[i].getInternalName().startsWith("org/spongepowered/asm/mixin/injection/callback/CallbackInfo"))
                    return !Recover.capturedTypes(m.desc).isEmpty();   // MixinExtras @Share / @Cancellable at the end are not locals
        }
        return false;
    }

    static MixinInfo scan(String side, String cfg, String cls, byte[] b) { return scan(side, cfg, cls, b, Map.of()); }

    /** target name of an @Accessor / @Invoker resolved through the refmap ("name:desc", "name(desc)", "Lowner;name..." -> name) */
    static String memberName(String v) {
        String s = v;
        if (s.startsWith("L") && s.indexOf(';') > 0) s = s.substring(s.indexOf(';') + 1);
        int i = s.indexOf(':'), k = s.indexOf('(');
        int cut = i < 0 ? k : k < 0 ? i : Math.min(i, k);
        return cut < 0 ? s : s.substring(0, cut);
    }

    /** rm: the mixin's refmap entries (Comp.refmapOf), empty when the jar has none */
    static MixinInfo scan(String side, String cfg, String cls, byte[] b, Map<String, String> rm) {
        MixinInfo mi = new MixinInfo();
        mi.side = side;
        mi.cfg = cfg;
        mi.cls = cls;
        ClassNode cn = new ClassNode();
        try { new ClassReader(b).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); }
        catch (Exception ex) { return null; }
        for (AnnotationNode an : annos(cn.visibleAnnotations, cn.invisibleAnnotations)) {
            if (!an.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
            for (Object o : list(get(an, "value"))) if (o instanceof Type t) mi.targets.add(t.getInternalName());
            for (Object o : list(get(an, "targets"))) {
                String raw = String.valueOf(o), r = rm.get(raw);
                if (r == null) r = rm.get(raw.replace('.', '/'));
                String s = (r == null ? raw : r).replace('.', '/');
                if (s.startsWith("L") && s.endsWith(";")) s = s.substring(1, s.length() - 1);
                mi.targets.add(s);
            }
        }
        for (FieldNode f : cn.fields)
            for (AnnotationNode an : annos(f.visibleAnnotations, f.invisibleAnnotations))
                if (an.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;")) {
                    String n = f.name, pre = "shadow$";
                    Object pr = get(an, "prefix"); if (pr != null) pre = String.valueOf(pr);
                    if (n.startsWith(pre)) n = n.substring(pre.length());
                    mi.recs.add(new Rec("SHF", cls, n, f.desc, "", "", "", "", "-1", ""));
                }
        for (MethodNode m : cn.methods) {
            // plain (unannotated) method that Mixin merges into the target: clashes when Forge added the same method
            boolean plain = (m.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_ABSTRACT)) == 0
                    && !m.name.equals("<init>") && !m.name.equals("<clinit>");
            for (AnnotationNode an : annos(m.visibleAnnotations, m.invisibleAnnotations))
                if (an.desc.contains("spongepowered/asm/mixin") || an.desc.contains("llamalad7/mixinextras")) plain = false;
            if (plain) mi.recs.add(new Rec("MTH", cls, m.name, m.desc, "", "", "", "", "-1", ""));
            for (AnnotationNode an : annos(m.visibleAnnotations, m.invisibleAnnotations)) {
                String sn = simpleName(an.desc);
                if (an.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;")) {
                    String n = m.name, pre = "shadow$";
                    Object pr = get(an, "prefix"); if (pr != null) pre = String.valueOf(pr);
                    if (n.startsWith(pre)) n = n.substring(pre.length());
                    mi.recs.add(new Rec("SHM", cls, n, m.desc, "", "", "", "", "-1", ""));
                } else if (an.desc.equals("Lorg/spongepowered/asm/mixin/Overwrite;")) {
                    mi.recs.add(new Rec("OVR", cls, m.name, m.desc, "", "", "", "", "-1", ""));
                } else if (an.desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")) {
                    Object v = get(an, "value");
                    String val = v == null ? "" : String.valueOf(v);
                    // the refmap key is the target name Mixin works out (the value, or the name after get/is/set)
                    String key = derive(m.name, val, "get", "is", "set"), r = rm.get(key);
                    mi.recs.add(new Rec("ACC", cls, m.name, m.desc, "", "", "", "", "-1", r == null ? val : memberName(r)));
                } else if (an.desc.equals("Lorg/spongepowered/asm/mixin/gen/Invoker;")) {
                    Object v = get(an, "value");
                    String val = v == null ? "" : String.valueOf(v);
                    String key = derive(m.name, val, "call", "invoke", "create"), r = rm.get(key);
                    mi.recs.add(new Rec("INV", cls, m.name, m.desc, "", "", "", "", "-1", r == null ? val : memberName(r)));
                } else if (get(an, "method") != null && an.desc.toLowerCase().contains("mixin")) {
                    List<Object> ats = list(get(an, "at"));
                    if (ats.isEmpty()) ats = new ArrayList<>(Collections.singletonList(null));
                    for (Object sel : list(get(an, "method")))
                        for (Object at : ats) {
                            String av = "", at2 = "", ord = "-1";
                            if (at instanceof AnnotationNode aa) {
                                Object v = get(aa, "value"); av = v == null ? "" : String.valueOf(v);
                                Object t = get(aa, "target"); at2 = t == null ? "" : String.valueOf(t);
                                Object o = get(aa, "ordinal"); ord = o == null ? "-1" : String.valueOf(o);
                            }
                            String rawAt = at2;
                            if (av.endsWith("EXPRESSION") && at2.isEmpty()) at2 = rawAt = expressionDefs(m, rm);
                            else at2 = resolve(rm, at2);
                            String rawSel = String.valueOf(sel);
                            String loc = capturesLocals(m, an) ? (sugarLocals(m) ? "LS" + sugarNames(m) + "|" + sugarSpec(m) : "L") : "";
                            if (an.desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyVariable;"))
                                loc = "LV|" + modifyVariableSpec(m, an) + (sugarLocals(m) ? "#" + sugarSpec(m) : "");
                            mi.recs.add(new Rec("INJ", cls, m.name, m.desc, sn, resolve(rm, rawSel), av, at2, ord, loc, rawSel, rawAt));
                        }
                }
            }
        }
        return mi;
    }

    // ================================================================== compare vanilla <-> Forge
    record Res(String st, String msg, int delta) { }

    static Res worst(Res a, Res b) { return ORDER.get(b.st) > ORDER.get(a.st) ? b : a; }

    static String maj(int delta, int vn) { return Math.abs(delta) >= 0.4 * Math.max(vn, 1) ? " [MAJOR]" : ""; }

    private static final Pattern SEL = Pattern.compile("^(?:L([^;]+);|([\\w/$]+)\\.)?([^(:]*?)(\\(.*)?$");

    /** {owner or null, name, desc or null} */
    static String[] parseSel(String s) {
        Matcher m = SEL.matcher(s.strip());
        if (!m.matches()) return new String[]{null, s, null};
        String owner = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2).replace('.', '/') : null;
        if (owner != null && owner.isEmpty()) owner = null;
        return new String[]{owner, m.group(3), m.group(4)};
    }

    static boolean nameMatch(String sel, String name) {
        if (sel.startsWith("/") && sel.endsWith("/") && sel.length() > 2) {
            try { return Pattern.matches(sel.substring(1, sel.length() - 1), name); } catch (PatternSyntaxException e) { return false; }
        }
        if (sel.endsWith("*")) return name.startsWith(sel.substring(0, sel.length() - 1));
        return sel.equals(name);
    }

    static boolean descMatch(String dsel, String desc) {
        return dsel == null || desc.equals(dsel) || (dsel.endsWith(")") && desc.startsWith(dsel));
    }

    /** {owner, name, desc} of a ref */
    static String[] refParts(String kind, String ref) {
        int dot = ref.indexOf('.');
        String owner = ref.substring(0, dot), rest = ref.substring(dot + 1);
        if (kind.equals("M")) { int i = rest.indexOf('('); return new String[]{owner, rest.substring(0, i), rest.substring(i)}; }
        if (kind.equals("G")) { int i = rest.indexOf(':'); return new String[]{owner, rest.substring(0, i), rest.substring(i + 1)}; }
        return new String[]{ref, "", ""};
    }

    private static final Pattern FIELDSEL = Pattern.compile("^(?:L([^;]+);|([\\w/$]+)\\.)?([^:]+)(?::(.*))?$");

    /** number of matching call/field/new references inside method (cls,name,desc); -1 if not checkable */
    static int countRefs(Dump d, String cls, String name, String desc, String atval, String target) {
        CI ci = d.cls(cls);
        Meth me = ci == null ? null : ci.methods.get(name + desc);
        List<String[]> refs = me == null ? List.of() : me.refs;
        String[] sp = atval.split(":");
        String v = sp[sp.length - 1].toUpperCase();
        if ((v.equals("INVOKE") || v.equals("INVOKE_ASSIGN") || v.equals("INVOKE_STRING")) && !target.isEmpty()) {
            String[] s = parseSel(target);
            int n = 0;
            for (String[] r : refs) if (r[0].equals("M")) {
                String[] p = refParts("M", r[1]);
                if ((s[0] == null || p[0].equals(s[0])) && p[1].equals(s[1]) && descMatch(s[2], p[2])) n++;
            }
            return n;
        }
        if (v.equals("FIELD") && !target.isEmpty()) {
            Matcher m = FIELDSEL.matcher(target.strip());
            if (!m.matches()) return -1;
            String owner = m.group(1) != null ? m.group(1) : m.group(2), n = m.group(3), ds = m.group(4);
            int c = 0;
            for (String[] r : refs) if (r[0].equals("G")) {
                String[] p = refParts("G", r[1]);
                if ((owner == null || p[0].equals(owner)) && p[1].equals(n) && (ds == null || p[2].equals(ds))) c++;
            }
            return c;
        }
        if (v.equals("NEW") && !target.isEmpty()) {
            String t = target.strip();
            if (t.contains("<init>")) {
                String[] s = parseSel(t);
                int c = 0;
                for (String[] r : refs) if (r[0].equals("M")) {
                    String[] p = refParts("M", r[1]);
                    if (p[1].equals("<init>") && (s[0] == null || p[0].equals(s[0])) && descMatch(s[2], p[2])) c++;
                }
                return c;
            }
            if (t.startsWith("L") && t.endsWith(";")) t = t.substring(1, t.length() - 1);
            t = t.replace('.', '/');
            int c = 0;
            for (String[] r : refs) if (r[0].equals("N") && r[1].equals(t)) c++;
            return c;
        }
        return -1;
    }

    static Res evalInj(Dump v, Dump p, String tgt, Rec rec) {
        String[] s = parseSel(rec.sel());
        String name = s[1], dsel = s[2];
        List<Meth> cands = new ArrayList<>();
        for (Meth m : v.declared(tgt)) if (nameMatch(name, m.name) && descMatch(dsel, m.desc)) cands.add(m);
        if (cands.isEmpty()) return new Res("NA", rec.sel() + " :: method not in vanilla", 0);
        Res res = new Res("SAFE", "", 0);
        CI pc = p.cls(tgt);
        for (Meth vm : cands) {
            String n = vm.name, d = vm.desc;
            Meth pm = pc == null ? null : pc.methods.get(n + d);
            if (pm == null) {
                res = worst(res, new Res("BROKEN", n + d + " :: method missing in Forge jar", 0));
                continue;
            }
            if (pm.hash.equals(vm.hash)) continue;
            int delta = pm.n - vm.n;
            Res r = new Res("RISKY", n + d + " :: body changed by Forge (" + vm.n + "->" + pm.n + " insns)" + maj(delta, vm.n), Math.abs(delta));
            String[] sp = rec.atVal().split(":");
            String a = sp[sp.length - 1].toUpperCase();
            if (a.equals("EXPRESSION") && rec.atTgt().startsWith("EXPR ")) {
                for (String def : rec.atTgt().substring(5).split("\\|")) {
                    String kind = def.substring(0, 1), ref = def.substring(2);
                    int cv = countExact(vm, kind, ref), cp = countExact(pm, kind, ref);
                    String sh = ref.length() < 90 ? ref : ref.substring(0, 87) + "...";
                    if (cv > 0 && cp == 0) { r = new Res("BROKEN", n + d + " :: expression uses " + sh + ", which Forge's method no longer has (vanilla had " + cv + ")", Math.abs(delta)); break; }
                    if (cp < cv) r = new Res("RISKY", n + d + " :: expression uses " + sh + ": " + cv + "->" + cp + " in Forge", Math.abs(delta));
                }
            }
            if (!rec.atTgt().isEmpty() && List.of("INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING", "FIELD", "NEW").contains(a)) {
                int cv = countRefs(v, tgt, n, d, rec.atVal(), rec.atTgt());
                int cp = countRefs(p, tgt, n, d, rec.atVal(), rec.atTgt());
                if (cv >= 0 && cp >= 0) {
                    String at = rec.atTgt(), sh = at.length() < 90 ? at : at.substring(0, 87) + "...";
                    int ord = Integer.parseInt(rec.ord());
                    if (cv == 0) r = new Res("NA", n + d + " :: " + a + " " + sh + " not in vanilla either", 0);
                    else if (cp == 0) r = new Res("BROKEN", n + d + " :: " + a + " " + sh + " missing in Forge (vanilla had " + cv + ")", Math.abs(delta));
                    else if (ord >= 0 && cp <= ord) r = new Res("BROKEN", n + d + " :: " + a + " " + sh + " ordinal " + ord + " out of range in Forge (" + cv + "->" + cp + ")", Math.abs(delta));
                    else if (cp != cv && ord >= 0) r = new Res("BROKEN", n + d + " :: " + a + " " + sh + " ordinal " + ord + " now points elsewhere (count " + cv + "->" + cp + " in Forge)", Math.abs(delta));
                    else if (cp != cv) r = new Res("RISKY", n + d + " :: " + a + " " + sh + " count " + cv + "->" + cp + " in Forge", Math.abs(delta));
                    else r = new Res("RISKY", n + d + " :: " + a + " " + sh + " still present (" + cp + "x)", Math.abs(delta));
                }
            }
            if (Recover.PROVEN.get().contains(Recover.key(rec.cls(), rec.name() + rec.desc(), rec.atTgt(), rec.ord()))) {
                res = worst(res, new Res("RISKY", n + d + " :: " + a + " " + rec.atTgt() + " re-anchored by the fitter (same call, same place)", r.delta()));
                continue;
            }
            if (rec.val().startsWith("LS:") && a.equals("TAIL") && !vm.lvtNames.isEmpty() && !pm.lvtNames.isEmpty())
                for (String ln : rec.val().substring(3, rec.val().indexOf('|') < 0 ? rec.val().length() : rec.val().indexOf('|')).split(","))
                    if (liveAtTail(vm, ln) && !liveAtTail(pm, ln)) {
                        r = new Res("BROKEN", n + d + " :: @Local '" + ln + "' at TAIL: in Forge's method that local has ended before the last return", r.delta());
                        break;
                    }
            // locals are judged the way Mixin finds them (Recover / MixinLocals: LVT plus Mixin's own frame walk); the old
            // LVT-only checks (by slot, by LVT range) missed javac's helper locals and locals past their scope
            if (r.st().equals("RISKY") && rec.val().equals("L") && pm.maxLocals != vm.maxLocals && Recover.captureSame(v, p, tgt, vm, pm, rec))
                r = new Res("RISKY", r.msg() + "; the captured locals still have the same types and origins at the injection point (" + vm.maxLocals + "->" + pm.maxLocals + " slots)", r.delta());
            else if (r.st().equals("RISKY") && rec.val().startsWith("LS") && pm.maxLocals != vm.maxLocals && Recover.namesSame(v, p, tgt, vm, pm, rec))
                r = new Res("RISKY", r.msg() + "; every @Local still picks the same variable at the injection point (" + vm.maxLocals + "->" + pm.maxLocals + " slots)", r.delta());
            else if (r.st().equals("RISKY") && rec.val().startsWith("LS") && pm.maxLocals != vm.maxLocals && Recover.localsSame(v, p, tgt, vm, pm, rec, null))
                r = new Res("RISKY", r.msg() + "; every @Local still picks a variable with the same origin at the injection point (" + vm.maxLocals + "->" + pm.maxLocals + " slots)", r.delta());
            else if (r.st().equals("RISKY") && rec.val().startsWith("LV") && pm.maxLocals != vm.maxLocals && Recover.localsSame(v, p, tgt, vm, pm, rec, null))
                r = new Res("RISKY", r.msg() + "; the @ModifyVariable variable still has the same origin at the injection point (" + vm.maxLocals + "->" + pm.maxLocals + " slots)", r.delta());
            else if (r.st().equals("RISKY") && rec.val().startsWith("L") && pm.maxLocals != vm.maxLocals)
                r = new Res("BROKEN", n + d + " :: handler captures locals and Forge changed the locals (" + vm.maxLocals + "->" + pm.maxLocals + " slots): LVT may not match", r.delta());
            res = worst(res, r);
        }
        return res;
    }

    /** the field descriptor an @Shadow field or @Accessor needs (getter: return type, setter: its parameter); null = unknown */
    static String fieldDesc(Rec r) {
        if (r.type().equals("SHF")) return r.desc();
        if (!r.type().equals("ACC") || r.desc() == null || !r.desc().startsWith("(")) return null;
        try {
            Type t = Type.getMethodType(r.desc());
            Type[] a = t.getArgumentTypes();
            if (a.length == 0 && t.getReturnType().getSort() != Type.VOID) return t.getReturnType().getDescriptor();
            if (a.length == 1 && t.getReturnType().getSort() == Type.VOID) return a[0].getDescriptor();
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static volatile Set<String> bridgeFields;

    /**
     * Fields the bridge adds to game classes at runtime (bridge/res-common/alloy/bridge-fields.txt, bundled in the
     * installer): "owner name descriptor" lines. The Forge jar Fit reads does not have them, the running game does.
     */
    static Set<String> bridgeFields() {
        Set<String> s = bridgeFields;
        if (s == null) bridgeFields = s = parseBridgeFields(readResourceText("/alloy/bridge-fields.txt"));
        return s;
    }

    static Set<String> parseBridgeFields(String text) {
        Set<String> s = new HashSet<>();
        if (text != null)
            for (String l : text.split("\\R")) {
                l = l.strip();
                if (l.isEmpty() || l.startsWith("#")) continue;
                String[] p = l.split("\\s+");
                if (p.length == 3) s.add(p[0] + " " + p[1] + " " + p[2]);
            }
        return Set.copyOf(s);
    }

    /** tests: use this table instead of the bundled one (null: read the bundled one again) */
    static void setBridgeFields(Set<String> s) { bridgeFields = s; }

    static String derive(String mname, String value, String... prefixes) {
        if (value != null && !value.isEmpty()) return value;
        for (String pre : prefixes)
            if (mname.startsWith(pre) && mname.length() > pre.length()) {
                String r = mname.substring(pre.length());
                return Character.toLowerCase(r.charAt(0)) + r.substring(1);
            }
        return mname;
    }

    static Res evalRec(Dump v, Dump p, String tgt, Rec r) {
        switch (r.type()) {
            case "INJ": return evalInj(v, p, tgt, r);
            case "OVR": {
                CI vc = v.cls(tgt), pc = p.cls(tgt);
                Meth vm = vc == null ? null : vc.methods.get(r.name() + r.desc());
                if (vm == null) return new Res("NA", "@Overwrite " + r.name() + r.desc() + " not in vanilla", 0);
                Meth pm = pc.methods.get(r.name() + r.desc());
                if (pm == null) return new Res("BROKEN", "@Overwrite target " + r.name() + r.desc() + " missing in Forge jar", 0);
                if (!pm.hash.equals(vm.hash)) return new Res("RISKY", "@Overwrite replaces " + r.name() + r.desc() + " which Forge changed", Math.abs(pm.n - vm.n));
                return new Res("SAFE", "", 0);
            }
            case "MTH": {
                CI vc = v.cls(tgt), pc = p.cls(tgt);
                if (pc.methods.containsKey(r.name() + r.desc()) && !vc.methods.containsKey(r.name() + r.desc()))
                    return new Res("BROKEN", "method " + r.name() + r.desc() + " added by Forge clashes with the mixin's method (cannot overwrite)", 0);
                return new Res("SAFE", "", 0);
            }
            case "SHM":
                if (!v.findMethod(tgt, r.name(), r.desc())) return new Res("NA", "@Shadow method " + r.name() + r.desc() + " not in vanilla", 0);
                return p.findMethod(tgt, r.name(), r.desc()) ? new Res("SAFE", "", 0) : new Res("BROKEN", "@Shadow method " + r.name() + r.desc() + " missing in Forge jar", 0);
            case "SHF":
            case "ACC": {
                String n = r.type().equals("SHF") ? r.name() : derive(r.name(), r.val(), "get", "is", "set");
                if (!v.findField(tgt, n)) return new Res("NA", (r.type().equals("SHF") ? "@Shadow" : "@Accessor") + " field " + n + " not in vanilla", 0);
                // Mixin finds the field by name and descriptor: Forge's KeyMapping.MAP is a KeyMappingLookup, not a Map. Only
                // checked when vanilla has the exact descriptor (else the derived one may be wrong and the old rule stands).
                String fd = fieldDesc(r);
                boolean bridged = fd != null && bridgeFields().contains(tgt + " " + n + " " + fd);
                if (!p.findField(tgt, n)) return bridged ? new Res("SAFE", "", 0) : new Res("BROKEN", "field " + n + " missing in Forge jar", 0);
                if (fd != null && v.findField(tgt, n, fd) && !p.findField(tgt, n, fd) && !bridged)
                    return new Res("BROKEN", "Forge changed the type of field " + n + " (" + fd + " in vanilla)", 0);
                return new Res("SAFE", "", 0);
            }
            case "INV": {
                String n = derive(r.name(), r.val(), "call", "invoke", "create");
                if (!v.findMethod(tgt, n, null)) return new Res("NA", "@Invoker " + n + " not in vanilla", 0);
                for (CI k : v.chain(tgt))
                    for (Meth m : k.methods.values())
                        if (m.name.equals(n) && p.findMethod(tgt, n, m.desc)) return new Res("SAFE", "", 0);
                return new Res("BROKEN", "@Invoker target " + n + " missing/changed in Forge jar", 0);
            }
            default: return new Res("NA", "?", 0);
        }
    }

    // ================================================================== findings -> plan
    record Finding(Comp comp, String cfg, String mixinCls, String target, Rec rec, Res res) { }

    static final class Plan {
        final Map<String, Set<String>> remove = new LinkedHashMap<>();    // config -> mixin names (dotted FQN or as written)
        final Set<String> relax = new TreeSet<>();
        final Map<String, Set<String>> inj = new LinkedHashMap<>();       // class path (no .class) -> handler name / name+desc
        final Map<String, Set<String>> inert = new LinkedHashMap<>();     // accessor interface -> void @Invoker/@Accessor name+desc made a no-op
        final Map<String, String> why = new LinkedHashMap<>();            // "cfg:mixin" / "class#handler" / "cfg" -> reason
        final List<String> deps = new ArrayList<>();
        final Map<String, Set<String>> keepIface = new TreeMap<>();   // accessor mixin (class path) -> accessor name+desc to drop, or "*"
        /** "class/path#method" of compat_replaces handler entries, read before the check: a BROKEN @Overwrite named there is
         *  stripped (the Alloy compat mixin does its job on Forge's method) instead of disabling the whole mixin */
        final Set<String> compatHandlers = new HashSet<>();
        int risky, broken, safe;

        void disable(String cfg, String fqn, String reason) {
            remove.computeIfAbsent(cfg, k -> new TreeSet<>()).add(fqn);
            why.putIfAbsent(cfg + ":" + fqn, reason);
        }

        /** a void @Invoker / @Accessor setter of an accessor interface whose target Forge changed: the interface stays applied
         *  (casts to it keep working, its other members too), this one member does nothing */
        void inert(String clsInternal, String member, String reason) {
            inert.computeIfAbsent(clsInternal, k -> new TreeSet<>()).add(member);
            why.putIfAbsent(clsInternal + "#" + member, reason);
        }

        void strip(String clsInternal, String handler, String reason) {
            inj.computeIfAbsent(clsInternal, k -> new TreeSet<>()).add(handler);
            why.putIfAbsent(clsInternal + "#" + handler, reason);
        }
    }

    /** the pair of vanilla/patched dumps a mixin of the given side is checked against. */
    static List<Dump[]> pairs(Games g, String mixinSide) {
        List<Dump[]> out = new ArrayList<>();
        for (var e : g.sides.entrySet())
            if (mixinSide.equals("both") || mixinSide.equals(e.getKey())) out.add(e.getValue());
        return out;
    }

    static List<Finding> check(List<Comp> comps, Games g, Plan plan) {
        List<Finding> out = new ArrayList<>();
        for (Comp c : comps) {
            for (String[] m : c.mixins) {
                byte[] b = c.classes.get(m[2]);
                if (b == null) { c.notes.add("MISSING " + m[2]); continue; }
                MixinInfo mi = scan(m[0], m[1], m[2], b, c.refmapOf(m[2]));
                if (mi == null) { c.notes.add("BADCLASS " + m[2]); continue; }
                for (Rec r : mi.recs) for (String tgt : mi.targets) {
                    Res best = null;
                    for (Dump[] vp : pairs(g, mi.side)) {
                        Dump v = vp[0], p = vp[1];
                        if (!v.has(tgt)) continue;
                        Res res = !p.has(tgt) ? new Res("BROKEN", "target class " + tgt + " missing in Forge-patched jar", 0) : evalRec(v, p, tgt, r);
                        best = best == null ? res : worst(best, res);
                        // accessor/invoker whose method Forge already added to the target (cannot overwrite)
                        if ((r.type().equals("ACC") || r.type().equals("INV")) && p.has(tgt)) {
                            CI pc = p.cls(tgt), vc = v.cls(tgt);
                            if (pc.methods.containsKey(r.name() + r.desc()) && !vc.methods.containsKey(r.name() + r.desc())) {
                                plan.relax.add(m[1]);
                                plan.why.putIfAbsent(m[1], "accessor " + r.name() + r.desc() + " on " + tgt + " duplicates a method Forge added");
                            }
                        }
                    }
                    if (best == null) best = new Res("NA", "target class not in vanilla (other mod / pseudo)", 0);
                    if (DEBUG != null && m[2].contains(DEBUG)) System.out.println("DBG " + m[2].substring(m[2].lastIndexOf('/') + 1) + " -> " + tgt.substring(tgt.lastIndexOf('/') + 1) + " " + r.type() + " " + r.name() + " [" + r.sel() + " @" + r.atVal() + " " + r.atTgt() + " #" + r.ord() + "] " + best.st() + " " + best.msg());
                    switch (best.st()) { case "BROKEN" -> plan.broken++; case "RISKY" -> plan.risky++; case "SAFE" -> plan.safe++; default -> { } }
                    if (best.st().equals("BROKEN")) {
                        out.add(new Finding(c, m[1], m[2], tgt, r, best));
                        String fqn = m[2].replace('/', '.');
                        String shortCls = fqn.substring(fqn.lastIndexOf('.') + 1);
                        if (!best.msg().startsWith("target class") && (r.type().equals("INV") || r.type().equals("ACC")) && r.desc().endsWith(")V") && isInterface(b))
                            plan.inert(m[2], r.name() + r.desc(), shortCls + " -> " + tgt.substring(tgt.lastIndexOf('/') + 1) + ": " + r.type() + " " + r.name() + " " + best.msg());
                        else if (r.type().equals("OVR") && plan.compatHandlers.contains(m[2] + "#" + r.name()))
                            plan.strip(m[2], r.name() + r.desc(), COMPAT_WHY + " (" + best.msg() + ")");
                        // a missing @Shadow method named in the lists (MoreCulling's cache mixins on Forge 47: only the
                        // ModelData getQuads exists) is dropped by StripInjector; the rest of the mixin, which adds
                        // BakedOpacity, stays (else CacheUtils' cast fails at the first resource reload, reg05 full boot)
                        else if (r.type().equals("SHM") && plan.compatHandlers.contains(m[2] + "#" + r.name()))
                            plan.strip(m[2], r.name() + r.desc(), COMPAT_WHY + " (" + best.msg() + ")");
                        else if (best.msg().startsWith("target class") || !r.type().equals("INJ"))
                            plan.disable(m[1], fqn, shortCls + " -> " + tgt.substring(tgt.lastIndexOf('/') + 1) + ": " + r.type() + " " + r.name() + " " + best.msg());
                        else
                            plan.strip(m[2], r.name() + r.desc(), shortCls + " -> " + tgt.substring(tgt.lastIndexOf('/') + 1) + ": " + best.msg());
                    }
                }
            }
        }
        return out;
    }

    static boolean isInterface(byte[] b) { return (new ClassReader(b).getAccess() & Opcodes.ACC_INTERFACE) != 0; }

    /** the inert members: annotation removed, abstract method turned into a default method that returns at once */
    static byte[] inertMembers(byte[] b, Set<String> want, int[] count) {
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, 0);
        int n = 0;
        for (MethodNode m : cn.methods) {
            if (!want.contains(m.name + m.desc) || (m.access & Opcodes.ACC_ABSTRACT) == 0 || !m.desc.endsWith(")V")) continue;
            for (List<AnnotationNode> l : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations))
                if (l != null) l.removeIf(an -> an.desc.equals("Lorg/spongepowered/asm/mixin/gen/Invoker;") || an.desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;"));
            // synthetic: Mixin decides "accessor mixin" vs "interface mixin" by the non-synthetic methods that are not
            // accessors (MixinInfo.getVariant); a plain default method would turn it into an interface mixin (target type mismatch)
            m.access = (m.access & ~Opcodes.ACC_ABSTRACT) | Opcodes.ACC_SYNTHETIC;
            m.instructions = new InsnList();
            m.instructions.add(new InsnNode(Opcodes.RETURN));
            m.maxStack = 0;
            m.maxLocals = Type.getArgumentsAndReturnSizes(m.desc) >> 2;
            m.tryCatchBlocks = new ArrayList<>();
            n++;
        }
        count[0] += n;
        if (n == 0) return b;
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    static boolean matches(String entry, String pkg, Set<String> names) {
        String fq = pkg == null || pkg.isEmpty() ? entry : pkg + "." + entry;
        return names.contains(entry) || names.contains(fq);
    }

    /** Line in the bundled 1.20.1 list (build_installer.py): the entries after it are hand strips with no Alloy replacement. */
    static final String FEATURE_OFF = "#alloy: feature-off";
    /** prefit_strip lines between these two are stripped, but an Alloy compat mixin does the same job (not a lost feature) */
    static final String RESTORED = "#alloy: restored by ", END_RESTORED = "#alloy: end-restored";
    static final String COMPAT_WHY = "compat_replaces: re-implemented by an Alloy compat mixin";
    static final String HAND_WHY = "turned off by hand: does not work with Forge's changes yet, no Alloy replacement";

    /** the "config:Mixin#handler" lines of compat_replaces.txt as "class/path#handler" (see Plan.compatHandlers) */
    static void compatHandlers(List<Comp> comps, Plan plan, String text) {
        if (text == null) return;
        Map<String, Cfg> all = new HashMap<>();
        for (Comp c : comps) all.putAll(c.configs);
        for (String line : text.split("\\R")) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#") || !line.contains(":") || !line.contains("#") || line.contains("@")) continue;
            int colon = line.indexOf(':'), hash = line.indexOf('#', colon);
            Cfg cf = all.get(line.substring(0, colon));
            if (cf == null || hash < 0) continue;
            String mixin = line.substring(colon + 1, hash);
            String fqn = mixin.startsWith(cf.pkg + ".") || cf.pkg.isEmpty() ? mixin : cf.pkg + "." + mixin;
            plan.compatHandlers.add(fqn.replace('.', '/') + "#" + line.substring(hash + 1));
        }
    }

    /** compat_replaces.txt: "config:Mixin" disables a mixin, "config:Mixin#handler" strips one injector (or a BROKEN @Overwrite). */
    static void applyCompat(List<Comp> comps, Plan plan, String text) {
        if (text == null) return;
        Map<String, Cfg> all = new HashMap<>();
        for (Comp c : comps) all.putAll(c.configs);
        String why = COMPAT_WHY;
        for (String line : text.split("\\R")) {
            line = line.strip();
            if (line.equals(FEATURE_OFF)) { why = HAND_WHY; continue; }
            if (line.startsWith(RESTORED)) { if (why.equals(HAND_WHY)) why = COMPAT_WHY + " (" + line.substring(RESTORED.length()).strip() + ")"; continue; }
            if (line.equals(END_RESTORED)) { if (why.startsWith(COMPAT_WHY + " (")) why = HAND_WHY; continue; }
            if (line.isEmpty() || line.startsWith("#") || !line.contains(":")) continue;
            int colon = line.indexOf(':');
            String cfg = line.substring(0, colon), mixin = line.substring(colon + 1);
            Cfg cf = all.get(cfg);
            if (cf == null) continue;
            if (mixin.endsWith("@relax")) {   // Forge already has the accessor's getter: the accessor may replace it (adapt_fabric.py: same entry)
                plan.relax.add(cfg);
                continue;
            }
            if (mixin.contains("@")) {   // accessor entry (mixin@name+desc or mixin@*): drop those accessors, keep the mixin
                String m = mixin.substring(0, mixin.indexOf('@')), sel = mixin.substring(mixin.indexOf('@') + 1);
                String fq = m.startsWith(cf.pkg + ".") || cf.pkg.isEmpty() ? m : cf.pkg + "." + m;
                plan.keepIface.computeIfAbsent(fq.replace('.', '/'), k -> new TreeSet<>()).add(sel);
                Set<String> rm = plan.remove.get(cfg);
                if (rm != null) rm.removeIf(x -> x.equals(m) || x.equals(fq) || fq.endsWith("." + x));
                plan.why.put(cfg + ":" + fq, "compat_replaces: accessor annotations dropped, an Alloy compat mixin implements them");
                continue;
            }
            String handler = null;
            if (mixin.contains("#")) { handler = mixin.substring(mixin.indexOf('#') + 1); mixin = mixin.substring(0, mixin.indexOf('#')); }
            String pkg = cf.pkg;
            String fqn = mixin.startsWith(pkg + ".") || pkg.isEmpty() ? mixin : pkg + "." + mixin;
            if (handler != null) plan.strip(fqn.replace('.', '/'), handler, why);
            else plan.disable(cfg, mixin, why);
        }
    }

    /** a disabled mixin may add an interface to a Minecraft class; every other mixin that uses it goes too. */
    static void expandDependencies(List<Comp> comps, Plan plan) {
        Map<String, Cfg> cfgs = new LinkedHashMap<>();
        Map<String, byte[]> classes = new HashMap<>();
        for (Comp c : comps) { cfgs.putAll(c.configs); classes.putAll(c.classes); }
        boolean changed = true;
        while (changed) {
            changed = false;
            Set<String> lost = new HashSet<>();
            for (var ce : cfgs.entrySet()) for (String e : ce.getValue().entries) {
                if (!plan.remove.containsKey(ce.getKey()) || !matches(e, ce.getValue().pkg, plan.remove.get(ce.getKey()))) continue;
                String fq = fqn(ce.getValue().pkg, e).replace('.', '/');
                byte[] b = classes.get(fq);
                if (b == null) continue;
                for (String i : new ClassReader(b).getInterfaces()) if (!i.startsWith("java/")) lost.add(i);
                lost.add(fq);
            }
            if (lost.isEmpty()) break;
            for (var ce : cfgs.entrySet()) for (String e : ce.getValue().entries) {
                if (plan.remove.containsKey(ce.getKey()) && matches(e, ce.getValue().pkg, plan.remove.get(ce.getKey()))) continue;
                String fq = fqn(ce.getValue().pkg, e);
                byte[] b = classes.get(fq.replace('.', '/'));
                String sup = b == null ? null : new ClassReader(b).getSuperName();
                String hit = sup != null && lost.contains(sup) ? sup : null;
                if (hit == null && b != null) {
                    String text = new String(b, StandardCharsets.ISO_8859_1);
                    for (String i : lost) if (!i.substring(i.lastIndexOf('/') + 1).equals("Mixin") && text.contains(i)) { hit = i; break; }
                }
                if (hit != null) {
                    plan.disable(ce.getKey(), fq, "dependent: uses " + hit.substring(hit.lastIndexOf('/') + 1) + ", added by a disabled mixin");
                    plan.deps.add(ce.getKey() + ":" + e + " (uses " + hit.substring(hit.lastIndexOf('/') + 1) + ", added by a disabled mixin)");
                    changed = true;
                }
            }
        }
    }

    static String fqn(String pkg, String e) { return pkg == null || pkg.isEmpty() ? e : pkg + "." + e; }

    // ================================================================== rewrite
    static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    static final Set<String> INJECTORS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
            "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;",
            "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;",
            "Lcom/llamalad7/mixinextras/expression/Expression;",
            "Lcom/llamalad7/mixinextras/expression/Definition;",
            "Lcom/llamalad7/mixinextras/expression/Definitions;");

    // QualifySelectors: PrepGame's anchors add a vanilla-shaped overload next to Forge's changed method. A name-only
    // `method` selector of an injector then matches both; qualify it to name+vanillaDesc. @Invoker/@Accessor need nothing:
    // Mixin matches an invoker by its own handler descriptor.
    static final Set<String> SELECTOR_ANNOS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;", "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;", "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;", "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
            "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;", "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;", "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;", "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");

    static String qualifySelector(Map<String, Map<String, Set<String>>> ov, List<String> targets, String sel) {
        String s = sel.strip(), pre = "";
        if (s.startsWith("L") && s.indexOf(';') > 0) { pre = s.substring(0, s.indexOf(';') + 1); s = s.substring(pre.length()); }
        if (s.isEmpty() || s.contains("(") || s.contains("*") || s.contains(":") || s.contains(".") || s.contains("/") || targets.isEmpty()) return null;
        String d = null;
        for (String t : targets) {   // every @Mixin target must have this overload, with one single descriptor
            Set<String> ds = ov.getOrDefault(t, Map.of()).get(s);
            if (ds == null || ds.size() != 1) return null;
            String x = ds.iterator().next();
            if (d != null && !d.equals(x)) return null;
            d = x;
        }
        return pre + s + d;
    }

    static byte[] qualifySelectors(byte[] b, Map<String, Map<String, Set<String>>> ov, List<String> logOut) {
        if (ov.isEmpty()) return b;
        String text = new String(b, StandardCharsets.ISO_8859_1);
        if (!text.contains("org/spongepowered/asm/mixin")) return b;
        boolean any = false;
        for (var m : ov.values()) for (String n : m.keySet()) if (text.contains(n)) { any = true; break; }
        if (!any) return b;
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, 0);
        List<String> tg = new ArrayList<>();
        for (List<AnnotationNode> l : Arrays.asList(cn.visibleAnnotations, cn.invisibleAnnotations)) {
            if (l == null) continue;
            for (AnnotationNode an : l) {
                if (!an.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;") || an.values == null) continue;
                for (int i = 0; i + 1 < an.values.size(); i += 2) {
                    String k = (String) an.values.get(i);
                    if (!(an.values.get(i + 1) instanceof List<?> lv)) continue;
                    for (Object o : lv) {
                        if (k.equals("value") && o instanceof Type ty) tg.add(ty.getInternalName());
                        else if (k.equals("targets") && o instanceof String s) tg.add(s.replace('.', '/'));
                    }
                }
            }
        }
        int n = 0;
        for (MethodNode m : cn.methods)
            for (List<AnnotationNode> l : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations)) {
                if (l == null) continue;
                for (AnnotationNode an : l) {
                    if (!SELECTOR_ANNOS.contains(an.desc) || an.values == null) continue;
                    for (int i = 0; i + 1 < an.values.size(); i += 2) {
                        if (!"method".equals(an.values.get(i)) || !(an.values.get(i + 1) instanceof List<?> lv)) continue;
                        @SuppressWarnings("unchecked") List<Object> mut = (List<Object>) lv;
                        for (int j = 0; j < mut.size(); j++)
                            if (mut.get(j) instanceof String s) {
                                String q = qualifySelector(ov, tg, s);
                                if (q != null) { mut.set(j, q); n++; }
                            }
                    }
                }
            }
        if (n == 0) return b;
        logOut.add(cn.name + ".class: " + n + " name-only selector(s) qualified (overload added by PrepGame's anchors)");
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** StripInjector: turns the named handlers into plain private helpers. Returns {bytes, count} via the holder. */
    static byte[] stripInjectors(byte[] b, Set<String> want, int[] count) {
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, 0);
        int n = 0;
        // StripInjector: a named abstract @Shadow whose target method Forge removed is dropped (calls go to the target)
        if (cn.methods.removeIf(m -> (want.contains(m.name + m.desc) || want.contains(m.name)) && (m.access & Opcodes.ACC_ABSTRACT) != 0
                && Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations).stream()
                        .anyMatch(l -> l != null && l.stream().anyMatch(an -> an.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;"))))) n++;
        for (MethodNode m : cn.methods) {
            if (!want.contains(m.name + m.desc) && !want.contains(m.name)) continue;
            boolean hit = false;
            for (List<AnnotationNode> l : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations))
                if (l != null && l.removeIf(an -> INJECTORS.contains(an.desc) || an.desc.equals(OVERWRITE))) hit = true;
            if (hit) {
                n++;
                if ((m.access & Opcodes.ACC_PRIVATE) == 0) m.access = (m.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE;
            }
        }
        count[0] += n;
        if (n == 0) return b;
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    static boolean has(MethodNode m, String desc) {
        for (List<AnnotationNode> l : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations))
            if (l != null) for (AnnotationNode an : l) if (an.desc.equals(desc)) return true;
        return false;
    }

    /** FixDefaultClash: private/@Unique mixin helpers named like a Forge IForge* default method get renamed. */
    static byte[] fixDefaultClash(byte[] b, Set<String> defaults, List<String> renamedOut) {
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, 0);
        boolean isMixin = cn.invisibleAnnotations != null && cn.invisibleAnnotations.stream().anyMatch(an -> an.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"));
        if (!isMixin) return b;
        Map<String, String> ren = new HashMap<>();
        for (MethodNode m : cn.methods) {
            boolean helper = (m.access & Opcodes.ACC_PRIVATE) != 0 || has(m, "Lorg/spongepowered/asm/mixin/Unique;");
            boolean shadowOrInjector = has(m, "Lorg/spongepowered/asm/mixin/Shadow;") || has(m, "Lorg/spongepowered/asm/mixin/Overwrite;");
            if (helper && !shadowOrInjector && !m.name.startsWith("<") && defaults.contains(m.name + m.desc)) {   // never a constructor: "<init>$alloy" is not a legal name
                ren.put(m.name + m.desc, m.name + "$alloy");
                renamedOut.add(cn.name + "." + m.name + m.desc);
                m.name = m.name + "$alloy";
            }
        }
        if (ren.isEmpty()) return b;
        for (MethodNode m : cn.methods)
            for (AbstractInsnNode n : m.instructions)
                if (n instanceof MethodInsnNode mi && mi.owner.equals(cn.name) && ren.containsKey(mi.name + mi.desc))
                    mi.name = ren.get(mi.name + mi.desc);
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    static final class Out {
        final List<String> disabled = new ArrayList<>(), fixed = new ArrayList<>(), renamed = new ArrayList<>();
        boolean changed;
    }

    static byte[] adapt(byte[] jar, Plan plan, Set<String> defaults, Map<String, Map<String, Set<String>>> overloads, Out log, Map<String, Cfg> cfgIndex) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        Set<String> seen = new HashSet<>();
        try (ZipInputStream zi = new ZipInputStream(new ByteArrayInputStream(jar)); ZipOutputStream zo = new ZipOutputStream(bo)) {
            for (ZipEntry e; (e = zi.getNextEntry()) != null; ) {
                String name = e.getName();
                if (!seen.add(name)) continue;
                byte[] b = e.isDirectory() ? new byte[0] : zi.readAllBytes();
                if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))) { log.changed = true; continue; }
                if (!e.isDirectory()) {
                    if (name.startsWith("META-INF/jars/") && name.endsWith(".jar")) {
                        byte[] nb = adapt(b, plan, defaults, overloads, log, cfgIndex);
                        if (!Arrays.equals(nb, b)) { b = nb; }
                    } else if (name.endsWith(".class")) {
                        String cp = name.substring(0, name.length() - 6);
                        byte[] nb = b;
                        Set<String> inert = plan.inert.get(cp);
                        if (inert != null && !removedMixin(plan, cp)) {
                            int[] cnt = {0};
                            nb = inertMembers(nb, inert, cnt);
                            if (cnt[0] > 0) log.fixed.add(name + ": member(s) " + pyList(inert) + " turned off (no-op), rest of the accessor kept");
                        }
                        Set<String> inj = plan.inj.get(cp);
                        if (inj != null) {
                            int[] cnt = {0};
                            nb = stripInjectors(nb, inj, cnt);
                            if (cnt[0] > 0) log.fixed.add(name + ": injector(s) " + pyList(inj) + " stripped, rest of the mixin kept");
                        }
                        if (plan.keepIface.containsKey(cp)) {
                            byte[] sa = stripAccessorAnnotations(nb, plan.keepIface.get(cp));
                            if (sa != nb) { nb = sa; log.fixed.add(name + ": accessor annotations " + pyList(plan.keepIface.get(cp)) + " dropped (an Alloy compat mixin implements them)"); }
                        }
                        nb = hideLoaderProbes(name, nb, log.fixed);
                        nb = qualifySelectors(nb, overloads, log.fixed);
                        if (!defaults.isEmpty() && name.contains("/mixin/")) nb = fixDefaultClash(nb, defaults, log.renamed);
                        if (nb != b) { log.changed = true; b = nb; }
                    } else if (name.indexOf('/') < 0 && (plan.remove.containsKey(name) || plan.relax.contains(name)) && name.endsWith(".json")) {
                        byte[] nb = rewriteConfig(name, b, plan, log);
                        if (nb != null) { log.changed = true; b = nb; }
                    }
                }
                ZipEntry ne = new ZipEntry(name);
                if (e.getTime() >= 0) ne.setTime(e.getTime());
                zo.putNextEntry(ne);
                zo.write(b);
                zo.closeEntry();
            }
        }
        byte[] out = bo.toByteArray();
        return out;
    }

    /** Multi-loader libraries in Fabric mods that look for a loader by class name and then use every loader they find
     *  (MixinConstraints 1.0.x in Axiom and Flashback runs its Forge code, built for an older Forge, and stops the game).
     *  The probe constant is changed to a name that does not exist (same length): the library sees Fabric only.
     *  Same table as adapt_fabric.py LOADER_PROBES. */
    static final Map<String, List<String>> LOADER_PROBES = Map.of(
            "com/moulberry/mixinconstraints/util/Abstractions.class",
            List.of("net.minecraftforge.fml.loading.FMLLoader", "net.neoforged.fml.loading.FMLLoader"));

    static byte[] hideLoaderProbes(String name, byte[] b, List<String> fixed) {
        List<String> probes = LOADER_PROBES.get(name);
        if (probes == null) return b;
        byte[] out = b;
        for (String p : probes) {
            byte[] from = p.getBytes(java.nio.charset.StandardCharsets.UTF_8), to = from.clone();
            to[to.length - 1] = '_';
            int at = indexOf(out, from);
            if (at < 0) continue;
            if (out == b) out = b.clone();
            for (; at >= 0; at = indexOf(out, from)) System.arraycopy(to, 0, out, at, to.length);
            fixed.add(name + ": loader probe " + p + " hidden (Fabric-side library sees Fabric only)");
        }
        return out;
    }

    static int indexOf(byte[] h, byte[] n) {
        outer:
        for (int i = 0; i + n.length <= h.length; i++) {
            for (int j = 0; j < n.length; j++) if (h[i + j] != n[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** Drops @Accessor and @Invoker from the given methods (name+desc, or "*" for all) of a mixin interface; an entry
     *  name+desc=invoker:method turns that accessor into an @Invoker of the method (added by an Alloy compat mixin). */
    static byte[] stripAccessorAnnotations(byte[] b, Set<String> sels) {
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, 0);
        boolean any = false;
        for (MethodNode m : cn.methods) {
            if (m.invisibleAnnotations == null) continue;
            String conv = null;
            for (String x : sels) if (x.startsWith(m.name + m.desc + "=")) conv = x.substring(x.indexOf('=') + 1);
            if (conv != null) {   // =invoker:method -> @Invoker of a compat method; =name -> accessor retargeted to that field
                for (AnnotationNode an : m.invisibleAnnotations) {
                    if (!an.desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;") && !an.desc.equals("Lorg/spongepowered/asm/mixin/gen/Invoker;")) continue;
                    String tv = conv;
                    if (conv.startsWith("invoker:")) { tv = conv.substring(8); an.desc = "Lorg/spongepowered/asm/mixin/gen/Invoker;"; }
                    List<Object> vals = an.values == null ? new ArrayList<>() : new ArrayList<>(an.values);
                    int i = vals.indexOf("value");
                    if (i >= 0 && i % 2 == 0) vals.set(i + 1, tv); else { vals.add("value"); vals.add(tv); }
                    an.values = vals;
                    any = true;
                }
                if (conv.startsWith("invoker:")) m.invisibleAnnotations.removeIf(an -> an.desc.equals("Lorg/spongepowered/asm/mixin/Mutable;"));
                continue;
            }
            if (!(sels.contains("*") || sels.contains(m.name + m.desc))) continue;
            any |= m.invisibleAnnotations.removeIf(an -> an.desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")
                    || an.desc.equals("Lorg/spongepowered/asm/mixin/gen/Invoker;"));
        }
        if (!any) return b;
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** is this mixin class removed from its config by the plan (then its inert members do not matter) */
    static boolean removedMixin(Plan plan, String cp) {
        String fq = cp.replace('/', '.');
        for (Set<String> s : plan.remove.values()) for (String e : s) if (fq.equals(e) || fq.endsWith("." + e)) return true;
        return false;
    }

    static String pyList(Collection<String> c) {
        StringBuilder sb = new StringBuilder("[");
        for (String s : new TreeSet<>(c)) { if (sb.length() > 1) sb.append(", "); sb.append('\'').append(s.contains("(") ? s.substring(0, s.indexOf('(')) : s).append('\''); }
        return sb.append(']').toString();
    }

    @SuppressWarnings("unchecked")
    static byte[] rewriteConfig(String name, byte[] b, Plan plan, Out log) {
        Map<String, Object> j;
        try { j = Json.obj(parseJson(b)); } catch (RuntimeException ex) { return null; }
        boolean mod = false;
        if (plan.relax.contains(name)) {
            Object ov = j.get("overwrites");
            Map<String, Object> m = ov instanceof Map<?, ?> ? (Map<String, Object>) ov : new LinkedHashMap<>();
            m.put("requireAnnotations", Boolean.FALSE);
            j.put("overwrites", m);
            log.fixed.add(name + ": overwrites.requireAnnotations=false (accessor replaces Forge's identical getter)");
            mod = true;
        }
        Set<String> rem = plan.remove.getOrDefault(name, Set.of());
        String pkg = j.get("package") instanceof String s ? s : "";
        for (String key : new String[]{"mixins", "server", "client"}) {
            if (!(j.get(key) instanceof List<?> l)) continue;
            List<Object> keep = new ArrayList<>();
            for (Object o : l) {
                String e = String.valueOf(o);
                if (matches(e, pkg, rem)) { log.disabled.add(name + ":" + e); mod = true; }
                else keep.add(o);
            }
            j.put(key, keep);
        }
        return mod ? Json.write(j).getBytes(StandardCharsets.UTF_8) : null;
    }

    // ================================================================== one mod
    static final class Result {
        byte[] out;
        boolean changed;
        String id = "", version = "";
        Plan plan;
        Out log;
        List<String> report = new ArrayList<>();
        int mixins;
    }

    /** returns null when the jar is not a Fabric mod. */
    static Result fit(byte[] jar, String jarName, Games g, String compat) throws IOException {
        // FIT-L / FIT-O: point handlers at the lambda or overload Forge has instead of the vanilla one (FitTargets)
        // FIT-Q: name-only selectors that Forge's added overloads make ambiguous get the vanilla descriptor (FitTargets)
        byte[] original = jar;
        Recover.PROVEN.get().clear();
        List<String> retargets = new ArrayList<>();
        jar = FitTargets.apply(jar, jarName, g, retargets, 0, compat);
        boolean retargeted = jar != original;
        List<Comp> comps = components(jar, jarName, null, 0);
        Comp root = comps.get(0);
        if (!root.fabric) return null;
        Result r = new Result();
        r.id = root.id;
        r.version = root.version;
        r.mixins = comps.stream().mapToInt(c -> c.mixins.size()).sum();
        // a nested Fabric API module that the top-level Fabric API in mods/ has in a newer version is never loaded (Fabric
        // Loader picks the newest copy of each mod id): it is not checked, so nothing in it is stripped or listed as features off
        List<String> shadowed = new ArrayList<>();
        if (!g.shadow.isEmpty() && !root.id.equals("fabric-api")) comps.removeIf(c -> {
            if (c == root || !c.fabric || !shadowedBy(g.shadow, c.id, c.version)) return false;
            shadowed.add(c.id + " " + c.version);
            return true;
        });
        Plan plan = new Plan();
        compatHandlers(comps, plan, compat);
        check(comps, g, plan);
        applyCompat(comps, plan, compat);
        int before = plan.deps.size();
        expandDependencies(comps, plan);
        r.plan = plan;
        r.log = new Out();
        Map<String, Cfg> idx = new HashMap<>();
        byte[] out = adapt(jar, plan, g.defaults, g.overloads, r.log, idx);
        r.changed = r.log.changed || retargeted;
        r.out = r.log.changed ? out : retargeted ? jar : original;
        for (String d : retargets)
            if (d.contains(": FIT-L") || d.contains(": FIT-O") || d.contains(": FIT-Q") || d.contains(": FIT-D") || d.contains(": AT-MOVED") || d.contains(": AT-ORDINAL") || d.contains(": ACCESSOR-BRIDGE")) {
                if (!d.contains("(not applied")) r.report.add("  handler retargeted: " + d);
            }
        if (System.getProperty("alloy.fit.decisions") != null) for (String d : retargets) r.report.add("  decision: " + d);
        // report
        for (String s : new TreeSet<>(r.log.disabled)) {
            int c = s.indexOf(':');
            String cfg = s.substring(0, c), e = s.substring(c + 1);
            String why = null;
            for (var en : plan.why.entrySet()) if (en.getKey().startsWith(cfg + ":") && matches(e, "", Set.of(en.getKey().substring(cfg.length() + 1)))) { why = en.getValue(); break; }
            if (why == null) for (var en : plan.why.entrySet()) if (en.getKey().startsWith(cfg + ":") && en.getKey().endsWith("." + e)) { why = en.getValue(); break; }
            r.report.add("  mixin disabled: " + s + (why == null ? "" : "   (" + why + ")"));
        }
        for (var en : plan.inj.entrySet()) {
            String cls = en.getKey();
            String simple = cls.substring(cls.lastIndexOf('/') + 1);
            for (String h : en.getValue()) {
                String why = plan.why.get(cls + "#" + h);
                boolean did = r.log.fixed.stream().anyMatch(f -> f.startsWith(cls + ".class:") && f.contains("'" + (h.contains("(") ? h.substring(0, h.indexOf('(')) : h) + "'"));
                if (did) r.report.add("  injector stripped: " + simple + "#" + (h.contains("(") ? h.substring(0, h.indexOf('(')) : h) + "   (" + why + ")");
            }
        }
        // a disabled accessor interface that the mod's own plain code casts to: that code fails when it runs (ClassCastException)
        Set<String> mixinNames = new HashSet<>();
        for (Comp c : comps) for (String[] m : c.mixins) mixinNames.add(m[2]);
        for (Comp c : comps) for (String[] m : c.mixins) {
            byte[] b = c.classes.get(m[2]);
            if (b == null || !isInterface(b) || !removedMixin(plan, m[2])) continue;
            byte[] needle = m[2].getBytes(StandardCharsets.ISO_8859_1);
            List<String> users = new ArrayList<>();
            for (Comp k : comps) for (var e : k.classes.entrySet())
                if (!mixinNames.contains(e.getKey()) && !e.getKey().equals(m[2]) && contains(e.getValue(), needle)) users.add(e.getKey().substring(e.getKey().lastIndexOf('/') + 1));
            if (!users.isEmpty()) r.report.add("  note: " + String.join(", ", users.subList(0, Math.min(3, users.size()))) + (users.size() > 3 ? " and " + (users.size() - 3) + " more" : "")
                    + " use " + m[2].substring(m[2].lastIndexOf('/') + 1) + ", which is off; that part of the mod may fail when it runs");
        }
        for (var en : plan.inert.entrySet()) {
            String cls = en.getKey(), simple = cls.substring(cls.lastIndexOf('/') + 1);
            boolean did = r.log.fixed.stream().anyMatch(f -> f.startsWith(cls + ".class: member(s)"));
            if (did) for (String h : en.getValue())
                r.report.add("  invoker turned off: " + simple + "#" + h.substring(0, h.indexOf('(')) + "   (" + plan.why.get(cls + "#" + h) + "; the accessor stays, so casts to it still work)");
        }
        for (String s : r.log.fixed) if (s.contains("requireAnnotations")) r.report.add("  accessor relaxed: " + s);
        if (!shadowed.isEmpty()) r.report.add("  nested Fabric API copies not checked (the top-level Fabric API " + g.shadow.getOrDefault("fabric-api", "")
                + " has newer ones, and Fabric loads only the newest): " + String.join(", ", shadowed));
        for (String s : new TreeSet<>(r.log.renamed)) r.report.add("  helper renamed (clashes with a Forge default method): " + s);
        return r;
    }

    /** true when the top-level Fabric API has this module in a strictly newer version (on a tie Fabric may pick either copy) */
    static boolean shadowedBy(Map<String, String> shadow, String id, String version) {
        String top = shadow.get(id);
        if (top == null || version == null || id.equals("fabric-api")) return false;
        try { return alloy.ideas.Versions.cmp(top, version) > 0; } catch (RuntimeException e) { return false; }
    }

    /**
     * The newest top-level Fabric API in mods/ (original or fitted copy): "fabric-api" and each of its nested modules -> version.
     * Empty when there is none (a mod's own nested Fabric API copy is then what Fabric loads, and it is fitted as before).
     */
    static Map<String, String> topFabricApi(Path mods, Properties state) {
        Map<String, String> best = new TreeMap<>();
        Map<String, String> known = new HashMap<>();   // jar or fitted output name -> mod id, from the fit state (saves opening every jar)
        for (String k : state.stringPropertyNames()) {
            String[] v = state.getProperty(k).split("\\|", -1);
            if (v.length < 5) continue;
            known.put(k, v[3]);
            if (!v[2].isEmpty()) known.put(v[2], v[3]);
        }
        try {
            for (Path p : Util.jars(mods)) {
                String kid = known.get(p.getFileName().toString());
                if (kid != null && !kid.equals("fabric-api")) continue;
                try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(p.toFile())) {
                    ZipEntry fe = z.getEntry("fabric.mod.json");
                    if (fe == null) continue;
                    String[] iv = idVersion(z.getInputStream(fe).readAllBytes());
                    if (iv == null || !iv[0].equals("fabric-api")) continue;
                    if (best.containsKey("fabric-api")) {
                        try { if (alloy.ideas.Versions.cmp(iv[1], best.get("fabric-api")) <= 0) continue; } catch (RuntimeException e) { continue; }
                    }
                    Map<String, String> m = new TreeMap<>();
                    m.put("fabric-api", iv[1]);
                    for (var en = z.entries(); en.hasMoreElements(); ) {
                        ZipEntry e = en.nextElement();
                        if (!e.getName().startsWith("META-INF/jars/") || !e.getName().endsWith(".jar")) continue;
                        try (ZipInputStream nz = new ZipInputStream(z.getInputStream(e))) {
                            for (ZipEntry ne; (ne = nz.getNextEntry()) != null; ) {
                                if (!ne.getName().equals("fabric.mod.json")) continue;
                                String[] mv = idVersion(nz.readAllBytes());
                                if (mv != null) m.put(mv[0], mv[1]);
                                break;
                            }
                        }
                    }
                    best = m;
                } catch (IOException | RuntimeException e) { /* not a readable mod jar */ }
            }
        } catch (IOException e) { /* no mods/ */ }
        return best;
    }

    private static String[] idVersion(byte[] fmj) {
        try {
            Map<String, Object> j = Json.obj(parseJson(fmj));
            return j.get("id") instanceof String id ? new String[]{id, String.valueOf(j.getOrDefault("version", ""))} : null;
        } catch (RuntimeException e) { return null; }
    }

    // ================================================================== game dir integration
    private static final Set<String> OWN_IDS = Set.of("alloyloader", "alloy", "alloy_modmixins", "alloy-bridge");

    static boolean isOwn(String fileName, String id) {
        return OWN_IDS.contains(id) || id.startsWith("alloy") || fileName.startsWith("alloy-") || fileName.startsWith("alloy_");
    }

    static String readResourceText(String name) {
        try { return new String(Util.resource(name), StandardCharsets.UTF_8); } catch (IOException e) { return null; }
    }

    /** prefit index: lines "id|version|jar name" */
    static String[] prefitFor(String id, String version) {
        String idx = readResourceText("/prefit/index.txt");
        if (idx == null) return null;
        for (String l : idx.split("\\R")) {
            String[] p = l.split("\\|");
            if (p.length == 3 && p[0].equals(id) && p[1].equals(version)) return p;
        }
        return null;
    }

    static String stem(String n) { return n.endsWith(".jar") ? n.substring(0, n.length() - 4) : n; }

    /**
     * Before the Mod Doctor and the fit: makes mods/ consistent when originals are dropped in again over an instance that
     * already holds their fitted copies (reinstall, or a player adding an updated version). A "-alloy.jar" is derived from
     * its original, never a second mod:
     *  - same original dropped again (same sha): it is tucked into .alloy/originals/, the fitted copy stays;
     *  - same file name, other content: the old fitted copy and state entry are dropped (the new jar is fitted afterwards);
     *  - other file name, same mod id (new or older version): the earlier fitted copy / jar, its originals entry and state are removed.
     */
    static void reconcile(Path game) {
        try {
            game = game.toAbsolutePath().normalize();
            Path work = game.resolve(".alloy"), mods = game.resolve("mods"), originals = work.resolve("originals"), fitDir = work.resolve("fit");
            Path stateFile = work.resolve("fit-state.properties");
            if (!Files.isDirectory(mods) || !Files.exists(stateFile)) return;
            Properties state = new Properties();
            try (Reader r = Files.newBufferedReader(stateFile)) { state.load(r); }
            if (state.isEmpty()) return;
            boolean dirty = false;
            for (Path p : Util.jars(mods)) {
                String n = p.getFileName().toString();
                if (n.endsWith("-alloy.jar") || n.startsWith("alloy-") || n.startsWith("alloy_")) continue;
                String sha = StatCache.sha1(p);
                String[] ov = state.containsKey(n) ? state.getProperty(n).split("\\|", -1) : null;
                if (ov != null && ov.length >= 5) {
                    if (ov[0].equals(sha)) {
                        if (!ov[2].isEmpty() && Files.exists(mods.resolve(ov[2]))) {   // same original again: keep the fitted copy
                            moveOriginal(p, originals, n);
                            Util.log("alloy-fit: " + n + " is already fitted (" + ov[2] + "), original tucked away");
                        }
                        continue;
                    }
                    if (!ov[2].isEmpty()) Files.deleteIfExists(mods.resolve(ov[2]));   // changed content, same name
                    state.remove(n);
                    dirty = true;
                    Util.log("alloy-fit: " + n + " changed, earlier fitted copy dropped");
                }
                String id;
                try { Comp root = components(Files.readAllBytes(p), n, null, 0).get(0); if (!root.fabric || isOwn(n, root.id)) continue; id = root.id; }
                catch (Throwable t) { continue; }
                for (String k : new ArrayList<>(state.stringPropertyNames())) {
                    String[] v = state.getProperty(k).split("\\|", -1);
                    if (k.equals(n) || v.length < 5 || !v[3].equals(id)) continue;
                    Path kin = mods.resolve(k);
                    if (Files.exists(kin) && !StatCache.sha1(kin).equals(v[0])) continue;   // an unrelated jar of that id: a real duplicate
                    if (Files.exists(kin)) Files.delete(kin);
                    if (!v[2].isEmpty()) Files.deleteIfExists(mods.resolve(v[2]));
                    Files.deleteIfExists(originals.resolve(k));
                    Files.deleteIfExists(fitDir.resolve(k + ".txt"));
                    state.remove(k);
                    dirty = true;
                    Util.log("alloy-fit: " + k + " replaced by " + n + " (same mod id " + id + ")");
                }
            }
            if (dirty) try (Writer w = Files.newBufferedWriter(stateFile)) { state.store(w, "alloy-fit state: jar = sha1|fingerprint|output|modid|status"); }
        } catch (Throwable t) {
            Util.log("alloy-fit: reconcile skipped (" + t + ")");
        }
    }

    /** Called after every prep: fits new / changed Fabric mods in mods/. Never throws for a single mod. */
    static void run(Path game, String prepKey) {
        try {
            game = game.toAbsolutePath().normalize();
            Path work = game.resolve(".alloy"), mods = game.resolve("mods");
            if (!Files.isDirectory(mods)) return;
            Properties cfg = new Properties();
            try (Reader r = Files.newBufferedReader(work.resolve("prep.properties"))) { cfg.load(r); }
            Path vanilla = game.resolve(cfg.getProperty("vanilla")).normalize();
            Path gameJar = game.resolve(cfg.getProperty("gameJar")).normalize();
            Path universal = game.resolve(cfg.getProperty("universal")).normalize();
            if (V120.on()) {   // 1.20.1: Mojang-named vanilla client vs the Mojang-named Forge game, Forge universal in Mojang names
                Path names = V120.names(V120.installerFor(game));
                if (names == null) throw new IOException("the Minecraft " + Installer.MC + " names folder is missing");
                vanilla = names.resolve(V120.vanillaMojangName("server".equals(cfg.getProperty("side"))));
                universal = game.resolve(cfg.getProperty("universalMojang")).normalize();
            }
            String side = cfg.getProperty("side", "client");
            Path originals = work.resolve("originals"), fitDir = work.resolve("fit");
            Path stateFile = work.resolve("fit-state.properties");
            Properties state = new Properties();
            if (Files.exists(stateFile)) try (Reader r = Files.newBufferedReader(stateFile)) { state.load(r); }
            String compat = V120.on() ? V120.compatText() : readResourceText("/compat_replaces.txt");
            Path ovFile = gameJar.resolveSibling(gameJar.getFileName() + ".overloads.txt");
            String fp0 = Util.sha1(VERSION + "|" + prepKey + "|" + Files.size(gameJar) + "|" + (compat == null ? "" : Util.sha1(compat))
                    + "|" + (Files.exists(ovFile) ? Util.sha1(Files.readString(ovFile)) : "")
                    + (V120.on() && !V120.awEditsText().isEmpty() ? "|aw " + Util.sha1(V120.awEditsText()) : ""));
            AW_DROPS = V120.on() ? V120.awDrops(V120.awEditsText()) : List.of();
            Map<String, String> shadow = topFabricApi(mods, state);   // nested Fabric API copies it makes inert are not fitted
            String fp1 = shadow.isEmpty() ? fp0 : Util.sha1(fp0 + "|shadow " + shadow);
            FORGE_LIBS = V120.on() ? V120.forgeLibs(game) : Map.of();   // loader-specific twins (V120.relocateTwins)
            String fp = FORGE_LIBS.isEmpty() ? fp1 : Util.sha1(fp1 + "|forgelibs " + V120.forgeLibsKey(FORGE_LIBS));

            // work list: new/changed jars dropped in mods/, plus earlier fits whose game jar fingerprint is stale
            List<Path> todo = new ArrayList<>();
            for (Path p : Util.jars(mods)) {
                String n = p.getFileName().toString();
                if (n.endsWith("-alloy.jar") || n.startsWith("alloy-") || n.startsWith("alloy_")) continue;
                todo.add(p);
            }
            Set<String> inMods = new HashSet<>();
            try (var s = Files.list(mods)) { s.forEach(p -> inMods.add(p.getFileName().toString())); }
            for (String k : new TreeSet<>(state.stringPropertyNames())) {
                String[] v = state.getProperty(k).split("\\|", -1);   // sha|fingerprint|output|id|status
                if (todo.stream().anyMatch(p -> p.getFileName().toString().equals(k))) continue;
                if (v.length >= 5 && !v[1].equals(fp) && !v[2].isEmpty() && inMods.contains(v[2]) && Files.exists(originals.resolve(k))) todo.add(originals.resolve(k));
            }
            // jars left in mods/ as they are (status unchanged / skip) for this same jar and game jar: fitOne would return at
            // once without touching anything, but listing them kept every launch off Prep's no-change fast path
            todo.removeIf(p -> {
                if (!p.getParent().equals(mods)) return false;
                String old = state.getProperty(p.getFileName().toString());
                String[] v = old == null ? null : old.split("\\|", -1);
                if (v == null || v.length < 5 || !v[1].equals(fp) || !(v[4].equals("unchanged") || v[4].equals("skip"))) return false;
                try { return v[0].equals(StatCache.sha1(p)); } catch (IOException e) { return false; }
            });
            if (todo.isEmpty()) { Util.log("alloy-fit: nothing to fit"); writeReport(work, fitDir, state, compat); return; }
            Util.log("alloy-fit: " + todo.size() + " Fabric mod jar(s) to check");
            if (V120.on()) V120.remapFabric(game, todo);   // Fabric mods are published in Intermediary names; the 1.20.1 runtime is Mojang-named

            Games g = new Games();
            g.shadow = shadow;
            try {
                g.add(side, vanilla, gameJar);
                g.loadDefaults(universal);
                g.loadOverloads(gameJar.resolveSibling(gameJar.getFileName() + ".overloads.txt"));
                boolean dirty = false;
                // the fits themselves (pure: jar bytes + game jars -> Result) run ahead on a few threads; applying them
                // (files, state, log) stays in this loop, in the same order as before
                Prefetch pre = new Prefetch(todo, mods, g, compat, fp, state, fitDir);
                try {
                    for (int i = 0; i < todo.size(); i++) {
                        Path p = todo.get(i);
                        String n = p.getFileName().toString();
                        pre.ahead(i);
                        try {
                            dirty |= fitOne(game, p, n, g, compat, fp, state, originals, fitDir, pre.take(i));
                        } catch (Throwable t) {
                            Util.log("alloy-fit: " + n + " could not be fitted (" + t + "); left as it is");
                            if (System.getProperty("alloy.debug") != null) t.printStackTrace();
                        }
                    }
                } finally { pre.close(); }
                try (Writer w = Files.newBufferedWriter(stateFile)) { state.store(w, "alloy-fit state: jar = sha1|fingerprint|output|modid|status"); }
                writeReport(work, fitDir, state, compat);
            } finally { g.close(); }
        } catch (Throwable t) {
            Util.log("alloy-fit: skipped (" + t + ")");
            if (System.getProperty("alloy.debug") != null) t.printStackTrace();
        }
    }

    private static boolean fitOne(Path game, Path src, String n, Games g, String compat, String fp, Properties state,
                                  Path originals, Path fitDir, java.util.concurrent.Future<Result> ahead) throws Exception {
        Path mods = game.resolve("mods");
        byte[] jar = Files.readAllBytes(src);
        String sha = Util.sha1(jar);
        if (V120.on()) {
            byte[] moj = V120.remappedFabric(game, sha);
            if (moj == null) throw new IOException("no Mojang-named copy of " + n + " (name translator failed? see .alloy/remap120/fabric/fabric-remap.log)");
            jar = moj;
        }
        String old = state.getProperty(n);
        String[] ov = old == null ? null : old.split("\\|", -1);
        boolean fromOriginals = !src.getParent().equals(mods);
        if (ov != null && ov.length >= 5 && ov[0].equals(sha) && ov[1].equals(fp) && !fromOriginals) {
            // same jar dropped again and already fitted for this game jar: just tuck it away
            if (ov[4].equals("unchanged") || ov[4].equals("skip")) return false;
            if (Files.exists(mods.resolve(ov[2]))) { moveOriginal(src, originals, n); Util.log("alloy-fit: " + n + " already fitted"); return false; }
        }
        // fabric mod? own mod?
        Map<String, byte[]> peek = null;
        Result r;
        String[] pre = null;
        {
            List<Comp> cs = components(jar, n, null, 0);
            Comp root = cs.get(0);
            if (!root.fabric || isOwn(n, root.id)) {           // Forge jar, library or Alloy's own: not ours
                state.setProperty(n, sha + "|" + fp + "||" + root.id + "|skip");
                return true;
            }
            pre = prefitFor(root.id, root.version);
        }
        String outName = stem(n) + "-alloy.jar";
        Path rep = fitDir.resolve(n + ".txt");
        Files.createDirectories(fitDir);
        List<String> lines = new ArrayList<>();
        String id, version, status;
        byte[] outBytes;
        if (pre != null) {
            outBytes = Util.resource("/prefit/" + pre[2]);
            id = pre[0]; version = pre[1];
            status = "prefit";
            lines.add("  hand-fitted jar shipped with the installer used (same mod id and version): " + pre[2]);
            String dis = readResourceText("/prefit/" + pre[2] + ".disabled.txt"), fx = readResourceText("/prefit/" + pre[2] + ".fixed.txt");
            if (dis != null) for (String l : dis.split("\\R")) if (!l.isBlank()) lines.add("  mixin disabled: " + l);
            if (fx != null) for (String l : fx.split("\\R")) if (!l.isBlank()) lines.add("  fixed: " + l);
            outName = pre[2];
        } else {
            r = ahead == null ? null : Prefetch.get(ahead);
            if (r == null) r = FitCache.fit(jar, n, sha, g, compat, fitDir, mods);
            if (r == null) return false;
            id = r.id; version = r.version;
            lines.addAll(r.report);
            if (!r.changed && V120.on()) { status = "remapped"; outBytes = r.out; lines.add("  nothing to fit: only translated to Mojang names"); }
            else if (!r.changed) { status = "unchanged"; outBytes = null; lines.add("  nothing to change: left in mods/ as it is"); }
            else { status = "fitted"; outBytes = r.out; }
        }
        if (V120.on() && outBytes != null && pre == null) outBytes = V120.relocateNestedAntlr(outBytes, lines, n);   // Iris 1.7: its own ANTLR
        if (V120.on() && outBytes != null && pre == null) outBytes = V120.relocateForgeCopies(outBytes, lines, n);   // Forge Config API Port
        if (V120.on() && outBytes != null && pre == null) outBytes = V120.relocateTwins(outBytes, FORGE_LIBS, lines, n);   // TRansition
        if (V120.on() && outBytes != null && pre == null) outBytes = V120.relocateForgeNamed(outBytes, lines, n);   // CreativeCore
        if (V120.on() && outBytes != null && pre == null) outBytes = V120.dropAccessWideners(outBytes, AW_DROPS, lines, n);   // Axiom StairBlock.base
        String head = "== " + n + " (" + id + " " + version + ") -> " + (outBytes == null ? "unchanged" : outName) + " [" + status + "]";
        Files.writeString(rep, head + "\n" + String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"));
        // an older output of the same mod id (other file name) would make Fabric see the id twice
        for (String k : new ArrayList<>(state.stringPropertyNames())) {
            String[] v = state.getProperty(k).split("\\|", -1);
            if (!k.equals(n) && v.length >= 5 && v[3].equals(id) && !v[2].isEmpty() && !v[2].equals(outName)) {
                Files.deleteIfExists(mods.resolve(v[2]));
                state.remove(k);
                Util.log("alloy-fit: replaced earlier " + k + " (same mod id " + id + ")");
            }
        }
        if (outBytes != null) {
            Files.write(mods.resolve(outName), outBytes);
            if (!fromOriginals) moveOriginal(src, originals, n);
            Util.log("alloy-fit: " + n + " -> mods/" + outName + " (" + status + ", " + lines.size() + " change(s) listed in .alloy/fit-report.txt)");
        } else if (fromOriginals) {
            Util.log("alloy-fit: " + n + " unchanged");
        }
        state.setProperty(n, sha + "|" + fp + "|" + (outBytes == null ? "" : outName) + "|" + id + "|" + status);
        return true;
    }

    private static void moveOriginal(Path src, Path originals, String n) throws IOException {
        Files.createDirectories(originals);
        Files.move(src, originals.resolve(n), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writeReport(Path work, Path fitDir, Properties state, String compat) throws IOException {
        StringBuilder sb = new StringBuilder("Alloy fit report (" + VERSION + "): Fabric mods adapted to the Forge-patched game.\n"
                + "Originals are kept in .alloy/originals/. Removed features are listed per mod.\n\n");
        List<String> reps = new ArrayList<>();
        for (String k : new TreeSet<>(state.stringPropertyNames())) {
            Path p = fitDir.resolve(k + ".txt");
            if (Files.exists(p)) { String t = Files.readString(p); reps.add(t); sb.append(t).append('\n'); }
        }
        Files.writeString(work.resolve("fit-report.txt"), sb.toString());
        // the player-facing list (alloy-report.txt, the log, the Mod Doctor): what is off, in plain words
        try {
            FeaturesOff.Summary s = FeaturesOff.write(work, reps, compat);
            if (!s.items.isEmpty())
                Util.log("alloy-fit: " + s.headline() + " (they do not fit Forge's changes; the mods load and work, nothing to do). List: alloy-report.txt, or .alloy/" + FeaturesOff.FILE);
        } catch (Exception e) { Util.log("alloy-fit: the list of features that are off was not written (" + e + ")"); }
    }

    /**
     * Runs the pure part of fitOne (Fit.fit through FitCache) for the next few jars of the work list on worker threads.
     * A jar that fitOne will not fit (already done for this game jar, not a Fabric mod, own jar, hand-fitted) gets no task.
     * -Dalloy.fit.threads=N sets the thread count (1 = no worker threads).
     */
    static final class Prefetch implements Closeable {
        final List<Path> todo;
        final Path mods, fitDir;
        final Games g;
        final String compat, fp;
        final Properties state;
        final java.util.concurrent.ExecutorService pool;
        final int window;
        final List<java.util.concurrent.Future<Result>> tasks = new ArrayList<>();

        Prefetch(List<Path> todo, Path mods, Games g, String compat, String fp, Properties state, Path fitDir) {
            this.todo = todo; this.mods = mods; this.g = g; this.compat = compat; this.fp = fp; this.state = state; this.fitDir = fitDir;
            int n = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
            String prop = System.getProperty("alloy.fit.threads");
            if (prop != null) try { n = Math.max(1, Integer.parseInt(prop.trim())); } catch (NumberFormatException e) { /* default */ }
            pool = n <= 1 || todo.size() < 2 ? null : java.util.concurrent.Executors.newFixedThreadPool(n, r -> {
                Thread th = new Thread(r, "alloy-fit");
                th.setDaemon(true);
                th.setPriority(Thread.MIN_PRIORITY);
                return th;
            });
            window = 2 * n;
        }

        /** keeps tasks submitted for items i .. i+window */
        void ahead(int i) {
            if (pool == null) return;
            while (tasks.size() < todo.size() && tasks.size() <= i + window) {
                Path p = todo.get(tasks.size());
                String old = state.getProperty(p.getFileName().toString());
                tasks.add(pool.submit(() -> speculate(p, old)));
            }
        }

        java.util.concurrent.Future<Result> take(int i) {
            if (pool == null || i >= tasks.size()) return null;
            java.util.concurrent.Future<Result> f = tasks.get(i);
            tasks.set(i, null);   // the result can be collected once fitOne used it
            return f;
        }

        private Result speculate(Path src, String old) throws Exception {
            String n = src.getFileName().toString();
            byte[] jar = Files.readAllBytes(src);
            String sha = Util.sha1(jar);
            if (V120.on()) {
                jar = V120.remappedFabric(mods.getParent(), sha);
                if (jar == null) return null;   // fitOne reports it
            }
            String[] ov = old == null ? null : old.split("\\|", -1);
            boolean fromOriginals = !src.getParent().equals(mods);
            if (ov != null && ov.length >= 5 && ov[0].equals(sha) && ov[1].equals(fp) && !fromOriginals) return null;   // fitOne's early exits
            Comp root = components(jar, n, null, 0).get(0);
            if (!root.fabric || isOwn(n, root.id) || prefitFor(root.id, root.version) != null) return null;
            return FitCache.fit(jar, n, sha, g, compat, fitDir, mods);
        }

        /** the task's result (null: not computed); its exception as the fit itself would have thrown it */
        static Result get(java.util.concurrent.Future<Result> f) throws Exception {
            try { return f.get(); }
            catch (java.util.concurrent.ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof Exception x) throw x;
                if (c instanceof Error x) throw x;
                throw e;
            }
        }

        @Override public void close() { if (pool != null) pool.shutdownNow(); }
    }

    /**
     * Fit results remembered per mod jar in .alloy/fit/NAME.cache, keyed by everything a fit reads: the mod jar (sha1), this
     * installer build, compat_replaces.txt, the overloads and IForge default methods, and the content (sha1) of every game
     * class, vanilla and Forge-patched, the fit looked at. When Forge mods change, PrepGame rebuilds the game jar and every
     * Fabric mod used to be fitted again; now a mod is fitted again only when a class it depends on really changed.
     * The fitted jar is not copied: the entry holds its sha1 and the copy already in mods/ is reused.
     * -Dalloy.fit.nocache=true turns it off.
     */
    static final class FitCache {
        private FitCache() { }

        static final String MAGIC = "alloy-fitcache-1", NULL_TOKEN = "<null>";
        private static volatile String self;

        static boolean off() { return Boolean.getBoolean("alloy.fit.nocache"); }

        static Path file(Path fitDir, String n) { return fitDir.resolve(n + ".cache"); }

        static Result fit(byte[] jar, String n, String sha, Games g, String compat, Path fitDir, Path mods) throws IOException {
            if (!off()) {
                Result hit = load(jar, n, sha, g, compat, fitDir, mods);
                if (hit != null) return hit;
            }
            Set<String> reads = new HashSet<>();
            Result r;
            READS.set(reads);
            try { r = Fit.fit(jar, n, g, compat); }
            finally { READS.remove(); }
            if (r != null && !off()) {
                try { save(r, n, sha, g, compat, fitDir, reads); }
                catch (IOException e) { /* the cache is optional */ }
            }
            return r;
        }

        static String selfId() {
            String s = self;
            if (s == null) {
                try { s = Util.sha1(Files.readAllBytes(Util.selfJar())); }
                catch (Exception e) {
                    try { s = "c" + Util.sha1(Util.resource("/alloy/installer/Fit.class")); } catch (IOException x) { s = "?" + System.nanoTime(); }
                }
                self = s;
            }
            return s;
        }

        static String key(String sha, Games g, String compat, Collection<String> reads) throws IOException {
            StringBuilder sb = new StringBuilder(MAGIC).append('|').append(VERSION).append('|').append(sha).append('|').append(selfId())
                    .append('|').append(compat == null ? "-" : Util.sha1(compat)).append('\n');
            sb.append("S|").append(g.sides.keySet()).append('\n');
            for (var c : new TreeMap<>(g.overloads).entrySet())
                for (var m : new TreeMap<>(c.getValue()).entrySet())
                    sb.append("O|").append(c.getKey()).append(' ').append(m.getKey()).append(' ').append(new TreeSet<>(m.getValue())).append('\n');
            sb.append("D|").append(Util.sha1(String.join(",", new TreeSet<>(g.defaults)))).append('\n');
            if (!g.shadow.isEmpty()) sb.append("H|").append(g.shadow).append('\n');   // top-level Fabric API modules (nested copies skipped)
            Map<String, Dump> byTag = new HashMap<>();
            for (Dump[] d : g.sides.values()) for (Dump x : d) byTag.put(x.tag, x);
            for (String r : new TreeSet<>(reads)) {
                int sp = r.indexOf(' ');
                Dump d = sp < 0 ? null : byTag.get(r.substring(0, sp));
                if (d == null) return null;   // a side that is not there any more
                sb.append(r).append('|').append(d.entryId(r.substring(sp + 1))).append('\n');
            }
            return Util.sha1(sb.toString());
        }

        static void save(Result r, String n, String sha, Games g, String compat, Path fitDir, Set<String> reads) throws IOException {
            String key = key(sha, g, compat, reads);
            if (key == null) return;
            List<String> l = new ArrayList<>(List.of(MAGIC, key, sha, r.id == null ? NULL_TOKEN : r.id, r.version == null ? NULL_TOKEN : r.version,
                    r.changed ? "1" : "0", r.changed ? Util.sha1(r.out) : "-", String.valueOf(reads.size())));
            l.addAll(new TreeSet<>(reads));
            l.addAll(r.report);
            Files.createDirectories(fitDir);
            Path f = file(fitDir, n), tmp = fitDir.resolve(n + ".cache.tmp");
            Files.write(tmp, l, StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }

        /** the remembered Result when nothing it depends on changed and the fitted copy in mods/ is still that output, else null */
        static Result load(byte[] jar, String n, String sha, Games g, String compat, Path fitDir, Path mods) {
            try {
                Path f = file(fitDir, n);
                if (!Files.exists(f)) return null;
                List<String> l = Files.readAllLines(f, StandardCharsets.UTF_8);
                if (l.size() < 8 || !l.get(0).equals(MAGIC) || !l.get(2).equals(sha)) return null;
                int nr = Integer.parseInt(l.get(7));
                if (l.size() < 8 + nr) return null;
                List<String> reads = l.subList(8, 8 + nr);
                if (!l.get(1).equals(key(sha, g, compat, reads))) return null;
                Result r = new Result();
                r.id = l.get(3).equals(NULL_TOKEN) ? null : l.get(3);
                r.version = l.get(4).equals(NULL_TOKEN) ? null : l.get(4);
                r.changed = l.get(5).equals("1");
                if (r.changed) {
                    Path out = mods.resolve(stem(n) + "-alloy.jar");
                    if (!Files.exists(out)) return null;
                    byte[] b = Files.readAllBytes(out);
                    if (!Util.sha1(b).equals(l.get(6))) return null;
                    r.out = b;
                } else r.out = jar;
                r.report = new ArrayList<>(l.subList(8 + nr, l.size()));
                if (DEBUG != null) System.out.println("DBG fit cache hit " + n);
                return r;
            } catch (Exception e) { return null; }
        }
    }

    // ================================================================== dev command
    /** java -jar installer.jar --fit IN.jar --out OUT.jar --client VANILLA,PATCHED [--server VANILLA,PATCHED] [--universal U] [--overloads FILE] [--compat FILE] [--mods DIR] */
    static void dev(Map<String, String> o) throws Exception {
        Games g = new Games();
        try {
            for (String s : new String[]{"client", "server"}) if (o.containsKey(s)) {
                String[] p = o.get(s).split(",");
                g.add(s, Path.of(p[0]), Path.of(p[1]));
            }
            if (o.containsKey("universal")) g.loadDefaults(Path.of(o.get("universal")));
            if (o.containsKey("overloads")) g.loadOverloads(Path.of(o.get("overloads")));
            if (o.containsKey("mods")) g.shadow = topFabricApi(Path.of(o.get("mods")), new Properties());   // the folder with the top-level Fabric API
            String compat = o.containsKey("compat") ? Files.readString(Path.of(o.get("compat"))) : readResourceText("/compat_replaces.txt");
            Path in = Path.of(o.get("fit"));
            long t = System.currentTimeMillis();
            Result r = fit(Files.readAllBytes(in), in.getFileName().toString(), g, compat);
            if (r == null) { System.out.println("not a Fabric mod"); return; }
            Path out = Path.of(o.get("out"));
            Files.write(out, r.out);
            Files.writeString(Path.of(out + ".disabled.txt"), String.join("\n", new TreeSet<>(r.log.disabled)) + "\n");
            Files.writeString(Path.of(out + ".fixed.txt"), String.join("\n", new TreeSet<>(r.log.fixed)) + "\n");
            Files.writeString(Path.of(out + ".report.txt"), String.join("\n", r.report) + "\n");
            System.out.println("fit " + in.getFileName() + " " + r.id + " " + r.version + ": mixins " + r.mixins + ", safe/risky/broken units " + r.plan.safe + "/" + r.plan.risky + "/" + r.plan.broken
                    + ", disabled " + new TreeSet<>(r.log.disabled).size() + ", fixed lines " + new TreeSet<>(r.log.fixed).size() + ", dependents " + r.plan.deps.size()
                    + ", " + (System.currentTimeMillis() - t) + " ms");
        } finally { g.close(); }
    }
}
