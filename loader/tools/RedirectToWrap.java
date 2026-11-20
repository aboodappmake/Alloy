import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Alloy: resolve Forge-mod-vs-Fabric-mod @Redirect collisions on the same vanilla call site.
 *
 * Mixin allows ONE @Redirect per call site; a second one is "@Redirect conflict ... Skipping" and then, with defaultRequire,
 * "Critical injection failure" (client crash at startup). When a Forge mod and a Fabric mod both redirect the same call
 * (e.g. Paladin's Furniture and Fabric API both redirect Codec.flatComapMap in BlockStateModel$Unbaked.<clinit>), this tool
 * rewrites the FORGE mod's handler, in a patched copy of its jar, from @Redirect to MixinExtras @WrapOperation:
 *   - annotation Redirect(method, slice, at, remap, require, expect, allow, constraints) -> WrapOperation (same members; `at`
 *     and `slice` become one-element arrays)
 *   - handler signature gets a trailing Operation parameter (the handler body ignores it, so it behaves exactly as the
 *     Redirect did); locals after the parameters are shifted and frames/maxLocals fixed up
 * MixinExtras applies its injectors after the plain ones, so the Fabric @Redirect applies first and the Forge WrapOperation
 * wraps the call to that redirect's handler: both coexist. (When the Forge handler does not call the original, the Forge
 * mod's replacement is what finally runs and the Fabric side's replacement is bypassed at that call site.)
 * Only INVOKE redirects whose handler parameter count matches the target call are converted; others are reported as WARN.
 *
 * usage: java RedirectToWrap <out dir> <fabric jar>[;<fabric jar>...] <forge jar>...
 *   Fabric jars are scanned recursively (META-INF/jars/*.jar). For every Forge jar that changed, <out dir>/<same name> is
 *   written (unchanged jars are not copied). Output lines: "COLLISION ...", "CONVERT ...", "WARN ...", and a final summary
 *   "RedirectToWrap: N handler(s) converted, W warning(s), J jar(s) written". Scans only; never loads classes.
 */
public class RedirectToWrap {
    static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    static final String OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";

    /** one @Redirect handler: targets (internal names), selectors, at.target (normalized owner.name+desc), ordinal, value */
    record Site(String mixin, String handler, List<String> targets, List<String> methods, String atValue, String atTarget, int ordinal) {}

    static int converted, warned, jarsWritten;

    public static void main(String[] a) throws Exception {
        if (a.length == 1 && a[0].startsWith("@")) a = Files.readAllLines(Path.of(a[0].substring(1))).stream().filter(l -> !l.isEmpty()).toArray(String[]::new);   // big packs: args from a file (Windows' 32k command line)
        Path outDir = Path.of(a[0]);
        List<Site> fabric = new ArrayList<>();
        for (String f : a[1].split(";")) if (!f.isBlank() && Files.exists(Path.of(f))) scanJar(Files.readAllBytes(Path.of(f)), fabric);
        Files.createDirectories(outDir);
        for (int i = 2; i < a.length; i++) {
            Path jar = Path.of(a[i]);
            byte[] res = rewriteJar(jar.getFileName().toString(), Files.readAllBytes(jar), fabric);
            if (res != null) { Files.write(outDir.resolve(jar.getFileName().toString()), res); jarsWritten++; }
        }
        System.out.println("RedirectToWrap: " + converted + " handler(s) converted, " + warned + " warning(s), " + jarsWritten + " jar(s) written");
    }

    // ------------------------------------------------------------------------------------------------ scanning
    static void scanJar(byte[] jar, List<Site> out) throws IOException {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                if (n.startsWith("META-INF/jars/") && n.endsWith(".jar")) scanJar(z.readAllBytes(), out);
                else if (n.endsWith(".class") && !n.startsWith("META-INF/")) {
                    byte[] b = z.readAllBytes();
                    if (contains(b, "Lorg/spongepowered/asm/mixin/injection/Redirect;")) {
                        ClassNode cn = new ClassNode();
                        new ClassReader(b).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                        sites(cn, out, null);
                    }
                }
            }
        }
    }

    /** collect Redirect sites of class cn; if `only` != null, handler name+desc -> annotation node is recorded there */
    static void sites(ClassNode cn, List<Site> out, Map<Site, MethodNode> only) {
        List<String> targets = targetsOf(cn);
        if (targets.isEmpty()) return;
        for (MethodNode m : cn.methods) {
            for (List<AnnotationNode> l : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations)) {
                if (l == null) continue;
                for (AnnotationNode an : l) {
                    if (!REDIRECT.equals(an.desc)) continue;
                    List<String> methods = new ArrayList<>();
                    AnnotationNode at = null;
                    if (an.values != null) for (int i = 0; i + 1 < an.values.size(); i += 2) {
                        String k = (String) an.values.get(i); Object v = an.values.get(i + 1);
                        if (k.equals("method") && v instanceof List<?> ml) for (Object o : ml) methods.add((String) o);
                        else if (k.equals("at") && v instanceof AnnotationNode at2) at = at2;
                    }
                    if (at == null) continue;
                    String value = "", target = ""; int ordinal = -1;
                    for (int i = 0; at.values != null && i + 1 < at.values.size(); i += 2) {
                        String k = (String) at.values.get(i); Object v = at.values.get(i + 1);
                        if (k.equals("value")) value = (String) v;
                        else if (k.equals("target")) target = normTarget((String) v);
                        else if (k.equals("ordinal")) ordinal = (Integer) v;
                    }
                    Site s = new Site(cn.name, m.name + m.desc, targets, methods, value, target, ordinal);
                    out.add(s);
                    if (only != null) only.put(s, m);
                }
            }
        }
    }

    static List<String> targetsOf(ClassNode cn) {
        List<String> t = new ArrayList<>();
        List<AnnotationNode> as = new ArrayList<>();
        if (cn.visibleAnnotations != null) as.addAll(cn.visibleAnnotations);
        if (cn.invisibleAnnotations != null) as.addAll(cn.invisibleAnnotations);
        for (AnnotationNode a : as) {
            if (!a.desc.equals(MIXIN) || a.values == null) continue;
            for (int i = 0; i + 1 < a.values.size(); i += 2) {
                String k = (String) a.values.get(i);
                if (!(a.values.get(i + 1) instanceof List<?> l)) continue;
                for (Object o : l) {
                    if (k.equals("value") && o instanceof Type ty) t.add(ty.getInternalName());
                    else if (k.equals("targets") && o instanceof String s) t.add(s.replace('.', '/'));
                }
            }
        }
        return t;
    }

    /** "Lowner;name(desc)ret" | "owner.name(desc)ret" | "owner/name(desc)ret" | "name(desc)ret" -> "owner.name(desc)ret" (owner with '/') */
    static String normTarget(String s) {
        int p = s.indexOf('(');
        if (p < 0) return s;
        String head = s.substring(0, p), rest = s.substring(p);
        if (head.startsWith("L") && head.contains(";")) {
            int sc = head.indexOf(';');
            return head.substring(1, sc).replace('.', '/') + "." + head.substring(sc + 1) + rest;
        }
        int dot = head.lastIndexOf('.');
        if (dot >= 0) return head.substring(0, dot).replace('.', '/') + "." + head.substring(dot + 1) + rest;
        int sl = head.lastIndexOf('/');
        if (sl >= 0) return head.substring(0, sl) + "." + head.substring(sl + 1) + rest;
        return head + rest;
    }

    static boolean collides(Site a, Site b) {
        if (!a.atValue.equals(b.atValue) || !a.atTarget.equals(b.atTarget)) return false;
        if (a.ordinal != b.ordinal && a.ordinal != -1 && b.ordinal != -1) return false;
        if (a.targets.stream().noneMatch(b.targets::contains)) return false;
        for (String x : a.methods) for (String y : b.methods) {
            int px = x.indexOf('('), py = y.indexOf('(');
            String nx = px < 0 ? x : x.substring(0, px), ny = py < 0 ? y : y.substring(0, py);
            if (nx.startsWith("L") && nx.contains(";")) nx = nx.substring(nx.indexOf(';') + 1);   // Lowner;name form
            if (ny.startsWith("L") && ny.contains(";")) ny = ny.substring(ny.indexOf(';') + 1);
            if (!nx.equals(ny)) continue;
            if (px < 0 || py < 0 || x.substring(px).equals(y.substring(py))) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ rewriting
    static byte[] rewriteJar(String name, byte[] jar, List<Site> fabric) throws IOException {
        // pass 1: rewrite the classes that hold a colliding @Redirect (entry index -> new bytes); most jars have none, and
        // recompressing every jar only to throw the copy away was most of this tool's run time
        Map<Integer, byte[]> rewritten = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            int i = 0;
            for (ZipEntry e; (e = z.getNextEntry()) != null; i++) {
                String n = e.getName();
                if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
                byte[] b = z.readAllBytes();
                if (!contains(b, "Lorg/spongepowered/asm/mixin/injection/Redirect;")) continue;
                byte[] nb = rewriteClass(name, b, fabric);
                if (nb != b) rewritten.put(i, nb);
            }
        }
        if (rewritten.isEmpty()) return null;
        // pass 2: the patched copy, entry by entry as before
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar)); ZipOutputStream zo = new ZipOutputStream(bo)) {
            int i = 0;
            for (ZipEntry e; (e = z.getNextEntry()) != null; i++) {
                byte[] b = z.readAllBytes();
                String n = e.getName();
                byte[] nb = rewritten.getOrDefault(i, b);
                ZipEntry ne = new ZipEntry(n);
                ne.setTime(e.getTime());
                zo.putNextEntry(ne);
                zo.write(nb);
                zo.closeEntry();
            }
        }
        return bo.toByteArray();
    }

    static byte[] rewriteClass(String jar, byte[] b, List<Site> fabric) {
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, ClassReader.EXPAND_FRAMES);
        Map<Site, MethodNode> mine = new LinkedHashMap<>();
        sites(cn, new ArrayList<>(), mine);
        boolean any = false;
        for (Map.Entry<Site, MethodNode> en : mine.entrySet()) {
            Site s = en.getKey();
            Site other = fabric.stream().filter(f -> collides(s, f)).findFirst().orElse(null);
            if (other == null) continue;
            System.out.println("COLLISION " + jar + " " + cn.name + "#" + s.handler + " vs Fabric " + other.mixin + "#" + other.handler
                    + " on " + s.targets + " " + s.methods + " " + s.atTarget + " ordinal=" + s.ordinal);
            if (convert(jar, cn, en.getValue(), s)) { converted++; any = true; }
        }
        if (!any) return b;
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    @SuppressWarnings("unchecked")
    static boolean convert(String jar, ClassNode cn, MethodNode m, Site s) {
        if (!s.atValue.equals("INVOKE")) { warn(jar, cn.name, m.name, "only INVOKE redirects are converted (at.value=" + s.atValue + ")"); return false; }
        int tp = s.atTarget.indexOf('(');
        if (tp < 0) { warn(jar, cn.name, m.name, "at.target is not a method call: " + s.atTarget); return false; }
        Type[] targs = Type.getArgumentTypes(s.atTarget.substring(tp));
        Type[] hargs = Type.getArgumentTypes(m.desc);
        if (hargs.length != targs.length && hargs.length != targs.length + 1) {
            warn(jar, cn.name, m.name, "handler has " + hargs.length + " parameter(s), target call has " + targs.length + " (extra parameters unsupported)");
            return false;
        }
        for (List<AnnotationNode> l : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations)) {
            if (l == null) continue;
            for (int i = 0; i < l.size(); i++) {
                AnnotationNode an = l.get(i);
                if (!REDIRECT.equals(an.desc)) continue;
                AnnotationNode w = new AnnotationNode(WRAP);
                w.values = new ArrayList<>();
                for (int j = 0; an.values != null && j + 1 < an.values.size(); j += 2) {
                    String k = (String) an.values.get(j); Object v = an.values.get(j + 1);
                    if ((k.equals("at") || k.equals("slice")) && v instanceof AnnotationNode) { List<Object> one = new ArrayList<>(); one.add(v); v = one; }
                    w.values.add(k); w.values.add(v);
                }
                l.set(i, w);
            }
        }
        // new trailing parameter: Operation; slot = first slot after the existing parameters
        boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
        int newSlot = isStatic ? 0 : 1;
        for (Type t : hargs) newSlot += t.getSize();
        Type ret = Type.getReturnType(m.desc);
        StringBuilder nd = new StringBuilder("(");
        for (Type t : hargs) nd.append(t.getDescriptor());
        m.desc = nd.append(OPERATION).append(')').append(ret.getDescriptor()).toString();
        m.signature = null;
        m.maxLocals += 1;
        // shift locals at/after newSlot
        for (AbstractInsnNode n : m.instructions) {
            if (n instanceof VarInsnNode v && v.var >= newSlot) v.var++;
            else if (n instanceof IincInsnNode v && v.var >= newSlot) v.var++;
        }
        m.localVariables = null;           // debug info only; the shifted slots would be stale
        m.parameters = null;               // MethodParameters would be one short
        m.invisibleParameterAnnotations = null; m.visibleParameterAnnotations = null;
        m.visibleAnnotableParameterCount = 0; m.invisibleAnnotableParameterCount = 0;
        int entry = (isStatic ? 0 : 1) + hargs.length;   // index of the new local in a frame's locals list (params are 1 entry each)
        for (AbstractInsnNode n : m.instructions) {
            if (!(n instanceof FrameNode f) || f.local == null) continue;
            while (f.local.size() < entry) f.local.add(Opcodes.TOP);
            f.local.add(entry, "com/llamalad7/mixinextras/injector/wrapoperation/Operation");
        }
        System.out.println("CONVERT " + jar + " " + cn.name + "#" + m.name + ": @Redirect -> @WrapOperation, handler now " + m.desc);
        return true;
    }

    static void warn(String jar, String cls, String m, String msg) { warned++; System.out.println("WARN " + jar + " " + cls + "#" + m + ": " + msg); }

    static boolean contains(byte[] b, String s) {
        byte[] p = s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i + p.length <= b.length; i++) {
            for (int j = 0; j < p.length; j++) if (b[i + j] != p[j]) continue outer;
            return true;
        }
        return false;
    }
}
