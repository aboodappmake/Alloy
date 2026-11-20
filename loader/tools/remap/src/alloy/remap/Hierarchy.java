package alloy.remap;

import org.objectweb.asm.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.zip.*;

/**
 * Class index over jars and in-memory classes. Parses class headers lazily (super, interfaces, declared members).
 * A jar can be added with a name function, so the obfuscated game jar can serve as the class hierarchy of
 * another namespace (its class names and super types are translated while reading).
 */
public final class Hierarchy {
    public static final class ClassInfo {
        public final String name, sup;
        public String[] itf;
        public final int access;
        public final Set<String> methods = new HashSet<>();     // name + desc
        public final Set<String> methodNames = new HashSet<>();
        public final Set<String> fields = new HashSet<>();      // name + ":" + desc
        public final Set<String> fieldNames = new HashSet<>();
        public final Map<String, Integer> macc = new HashMap<>(); // name+desc or name:desc -> access flags
        public final String origin;
        ClassInfo(String name, String sup, String[] itf, int access, String origin) {
            this.name = name; this.sup = sup; this.itf = itf; this.access = access; this.origin = origin;
        }
    }

    private interface Source { byte[] read() throws IOException; }

    private static final class Entry {
        final Source src; final Function<String, String> rename; final String origin;
        Entry(Source src, Function<String, String> rename, String origin) { this.src = src; this.rename = rename; this.origin = origin; }
    }

    private final Map<String, Entry> index = new HashMap<>();
    private final Map<String, ClassInfo> cache = new HashMap<>();
    private final List<ZipFile> open = new ArrayList<>();
    public boolean useJdk = true;

    /** Adds a jar. Classes already present keep priority (first added wins). */
    public Set<String> addJar(Path jar, Function<String, String> rename, String origin) throws IOException {
        Set<String> names = new HashSet<>();
        if (!Files.isRegularFile(jar)) return names;
        ZipFile z = new ZipFile(jar.toFile());
        open.add(z);
        for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
            ZipEntry ze = e.nextElement();
            String n = ze.getName();
            if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
            String cn = n.substring(0, n.length() - 6);
            if (rename != null) cn = rename.apply(cn);
            names.add(cn);
            index.putIfAbsent(cn, new Entry(() -> z.getInputStream(ze).readAllBytes(), rename, origin));
        }
        return names;
    }

    public void addClass(String name, byte[] bytes, String origin) {
        index.putIfAbsent(name, new Entry(() -> bytes, null, origin));
    }

    /** Replaces any earlier entry (used for patched classes). */
    public void putClass(String name, byte[] bytes, String origin) {
        index.put(name, new Entry(() -> bytes, null, origin));
        cache.remove(name);
    }

    /** Mixin merge: the target class gains the mixin's interfaces and members. */
    public void merge(String target, Collection<String> itfs, Collection<String> methods, Collection<String> fields) {
        ClassInfo ci = get(target);
        if (ci == null) return;
        Set<String> all = new LinkedHashSet<>(Arrays.asList(ci.itf));
        all.addAll(itfs);
        ci.itf = all.toArray(new String[0]);
        for (String m : methods) { ci.methods.add(m); ci.methodNames.add(m.substring(0, m.indexOf('('))); }
        for (String f : fields) { ci.fields.add(f); ci.fieldNames.add(f.substring(0, f.indexOf(':'))); }
    }

    public boolean has(String name) { return get(name) != null; }

    public ClassInfo get(String name) {
        if (name == null) return null;
        ClassInfo ci = cache.get(name);
        if (ci != null || cache.containsKey(name)) return ci;
        byte[] b = null; Function<String, String> rn = null; String origin = null;
        Entry e = index.get(name);
        try {
            if (e != null) { b = e.src.read(); rn = e.rename; origin = e.origin; }
            else if (useJdk && (name.startsWith("java/") || name.startsWith("javax/") || name.startsWith("jdk/") || name.startsWith("sun/"))) {
                try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
                    if (in != null) { b = in.readAllBytes(); origin = "jdk"; }
                }
            }
        } catch (IOException ex) { b = null; }
        if (b == null) { cache.put(name, null); return null; }
        ci = parse(b, rn, origin);
        cache.put(name, ci);
        return ci;
    }

    private static ClassInfo parse(byte[] b, Function<String, String> rn, String origin) {
        ClassReader cr = new ClassReader(b);
        Function<String, String> r = rn == null ? Function.identity() : rn;
        String[] itf = cr.getInterfaces().clone();
        for (int i = 0; i < itf.length; i++) itf[i] = r.apply(itf[i]);
        String sup = cr.getSuperName() == null ? null : r.apply(cr.getSuperName());
        ClassInfo ci = new ClassInfo(r.apply(cr.getClassName()), sup, itf, cr.getAccess(), origin);
        if (rn == null) {
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public FieldVisitor visitField(int a, String n, String d, String s, Object v) {
                    ci.fields.add(n + ":" + d); ci.fieldNames.add(n); ci.macc.put(n + ":" + d, a); return null;
                }
                @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                    ci.methods.add(n + d); ci.methodNames.add(n); ci.macc.put(n + d, a); return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return ci;
    }

    /** The class that declares a member (key name+desc or name:desc), searching supers; null if none. */
    public ClassInfo declaring(String owner, String key) {
        ClassInfo ci = get(owner);
        if (ci != null && ci.macc.containsKey(key)) return ci;
        for (String s : supers(owner)) { ClassInfo c = get(s); if (c != null && c.macc.containsKey(key)) return c; }
        return null;
    }

    /** All super types (classes then interfaces), breadth first, excluding the class itself. */
    public List<String> supers(String name) {
        List<String> out = new ArrayList<>();
        Deque<String> q = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        q.add(name); seen.add(name);
        while (!q.isEmpty()) {
            ClassInfo ci = get(q.poll());
            if (ci == null) continue;
            if (ci.sup != null && seen.add(ci.sup)) { out.add(ci.sup); q.add(ci.sup); }
            for (String i : ci.itf) if (seen.add(i)) { out.add(i); q.add(i); }
        }
        return out;
    }

    public void close() {
        for (ZipFile z : open) try { z.close(); } catch (IOException ignored) { }
        open.clear();
    }
}
