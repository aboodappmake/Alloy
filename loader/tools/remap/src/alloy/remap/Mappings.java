package alloy.remap;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

/**
 * Loads Mojang official (ProGuard txt), Fabric Intermediary (tiny v2) and Forge MCPConfig (tsrg2) mappings and
 * joins them on the obfuscated names. The result is one {@link Table} per source namespace that maps to Mojang names.
 *
 * Namespaces:
 *  obf           vanilla jar names (a, abc)
 *  srg           Forge 1.20.1-1.20.4 runtime: Mojang class names, SRG member ids (m_N_, f_N_)
 *  intermediary  Fabric runtime: net/minecraft/class_N, method_N, field_N, comp_N
 */
public final class Mappings {
    /** One obfuscated class with its Mojang names. Member keys are obf name + obf descriptor. */
    static final class ObfClass {
        final String obf, mojang;
        final Map<String, String> methods = new LinkedHashMap<>(); // obfName + obfDesc -> mojang
        final Map<String, String> fields = new LinkedHashMap<>();  // obfName + ":" + obfDesc -> mojang
        final Map<String, String> fieldByName = new HashMap<>();   // obfName -> mojang (null when ambiguous)
        ObfClass(String obf, String mojang) { this.obf = obf; this.mojang = mojang; }
    }

    /** Lookup tables from one source namespace to Mojang names. */
    public static final class Table {
        public final String ns;
        public final Map<String, String> classes = new HashMap<>();          // src internal -> mojang internal
        public final Map<String, String> classesToSrc = new HashMap<>();     // obf internal -> src internal
        public final Map<String, Map<String, String>> methods = new HashMap<>(); // src owner -> name+desc -> mojang
        public final Map<String, Map<String, String>> fields = new HashMap<>();  // src owner -> name[:desc] -> mojang
        public final Map<String, String> tokens = new HashMap<>();           // unique member token -> mojang name
        public final Map<String, String> tokensByDesc = new HashMap<>();     // token + src desc (fields: token:desc) -> mojang
        public final Set<String> tokenConflicts = new TreeSet<>();
        public final Set<String> mojangClasses = new HashSet<>();
        public int missingJoins;
        /** src owner + "." + src name + src desc -> obf owner + "." + obf name + obf desc */
        public final Map<String, String> obfKey = new HashMap<>();
        /** auto table only: obf -> SRG-runtime class names (Mojang), for a second view of the obf game jar. */
        public Map<String, String> srgToSrc;
        Table(String ns) { this.ns = ns; }

        public String mapClass(String src) { String m = classes.get(src); return m == null ? src : m; }
        public boolean tokenNamespace() { return !ns.startsWith("obf"); }

        /** Ids that map to two Mojang names; they resolve only with an owner (hierarchy lookup). */
        public final Set<String> ambiguous = new HashSet<>();

        void putToken(String token, String mojang) {
            if (ambiguous.contains(token)) return;
            String old = tokens.putIfAbsent(token, mojang);
            if (old != null && !old.equals(mojang)) {
                tokenConflicts.add(token + " -> " + old + " / " + mojang);
                tokens.remove(token);
                ambiguous.add(token);
            }
        }
    }

    public static final Pattern SRG_MEMBER = Pattern.compile("[mf]_\\d+_");
    public static final Pattern INT_MEMBER = Pattern.compile("(?:method|field|comp)_\\d+");

    final Map<String, ObfClass> obf = new LinkedHashMap<>();
    final Map<String, String> mojangToObf = new HashMap<>();
    public final Map<String, Table> tables = new HashMap<>();

    public static Mappings load(Path mojangTxt, Path intermediary, Path mcp) throws IOException {
        Mappings m = new Mappings();
        m.readProguard(mojangTxt);
        Table obfT = m.buildObf();
        m.tables.put("obf", obfT);
        if (intermediary != null) m.tables.put("intermediary", m.readTiny(intermediary));
        if (mcp != null) m.tables.put("srg", m.readTsrg2(mcp));
        if (intermediary != null && mcp != null) m.tables.put("auto", union(m.tables.get("srg"), m.tables.get("intermediary")));
        // Forge 50 and up have no SRG: "auto" is plain Intermediary there
        else if (intermediary != null) m.tables.put("auto", m.tables.get("intermediary"));
        return m;
    }

    /**
     * Union of the SRG and Intermediary tables. Their tokens never overlap (m_N_/f_N_ vs method_N/field_N/comp_N,
     * owners are Mojang class names vs net/minecraft/class_N), so one table serves Forge, Fabric and mixed jars.
     */
    static Table union(Table srg, Table in) {
        Table u = new Table("auto");
        for (Table t : new Table[]{srg, in}) {
            u.classes.putAll(t.classes);
            t.methods.forEach((k, v) -> u.methods.computeIfAbsent(k, x -> new HashMap<>()).putAll(v));
            t.fields.forEach((k, v) -> u.fields.computeIfAbsent(k, x -> new HashMap<>()).putAll(v));
            u.tokens.putAll(t.tokens);
            u.tokensByDesc.putAll(t.tokensByDesc);
            u.obfKey.putAll(t.obfKey);
            u.ambiguous.addAll(t.ambiguous);
            u.tokenConflicts.addAll(t.tokenConflicts);
            u.mojangClasses.addAll(t.mojangClasses);
        }
        u.srgToSrc = srg.classesToSrc;
        u.classesToSrc.putAll(in.classesToSrc);
        return u;
    }

    public Table table(String ns) {
        Table t = tables.get(ns);
        if (t == null) throw new IllegalArgumentException("no mappings loaded for namespace " + ns);
        return t;
    }

    // ---------------------------------------------------------------- Mojang ProGuard txt (named -> obf)

    private void readProguard(Path p) throws IOException {
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        // pass 1: classes
        for (String l : lines) {
            if (l.isEmpty() || l.startsWith("#") || l.startsWith(" ")) continue;
            int a = l.indexOf(" -> ");
            String named = l.substring(0, a).replace('.', '/');
            String ob = l.substring(a + 4, l.length() - 1).replace('.', '/');
            obf.put(ob, new ObfClass(ob, named));
            mojangToObf.put(named, ob);
        }
        // pass 2: members
        ObfClass cur = null;
        for (String l : lines) {
            if (l.isEmpty() || l.startsWith("#")) continue;
            if (!l.startsWith(" ")) {
                int a = l.indexOf(" -> ");
                cur = obf.get(l.substring(a + 4, l.length() - 1).replace('.', '/'));
                continue;
            }
            String s = l.trim();
            if (s.startsWith("#")) continue;
            int arrow = s.indexOf(" -> ");
            String obfName = s.substring(arrow + 4);
            String left = s.substring(0, arrow);
            // strip line number prefixes "12:34:"
            while (!left.isEmpty() && Character.isDigit(left.charAt(0))) left = left.substring(left.indexOf(':') + 1);
            int sp = left.indexOf(' ');
            String type = left.substring(0, sp);
            String rest = left.substring(sp + 1);
            int par = rest.indexOf('(');
            if (par >= 0) {
                String name = rest.substring(0, par);
                if (name.indexOf('.') >= 0 || name.startsWith("<")) continue; // inlined from elsewhere, ctors
                String args = rest.substring(par + 1, rest.indexOf(')'));
                StringBuilder d = new StringBuilder("(");
                if (!args.isEmpty()) for (String t : args.split(",")) d.append(obfDesc(t));
                d.append(')').append(obfDesc(type));
                cur.methods.putIfAbsent(obfName + d, name);
            } else {
                String key = obfName + ":" + obfDesc(type);
                cur.fields.putIfAbsent(key, rest);
                if (cur.fieldByName.containsKey(obfName) && !rest.equals(cur.fieldByName.get(obfName)))
                    cur.fieldByName.put(obfName, null);
                else cur.fieldByName.put(obfName, rest);
            }
        }
    }

    /** ProGuard type name (Mojang names) to an obfuscated JVM descriptor. */
    private String obfDesc(String t) {
        int dims = 0;
        while (t.endsWith("[]")) { dims++; t = t.substring(0, t.length() - 2); }
        String base;
        switch (t) {
            case "int": base = "I"; break;
            case "long": base = "J"; break;
            case "float": base = "F"; break;
            case "double": base = "D"; break;
            case "boolean": base = "Z"; break;
            case "byte": base = "B"; break;
            case "char": base = "C"; break;
            case "short": base = "S"; break;
            case "void": base = "V"; break;
            default:
                String in = t.replace('.', '/');
                base = "L" + mojangToObf.getOrDefault(in, in) + ";";
        }
        return "[".repeat(dims) + base;
    }

    private Table buildObf() {
        Table t = new Table("obf");
        for (ObfClass c : obf.values()) {
            t.classes.put(c.obf, c.mojang);
            t.classesToSrc.put(c.obf, c.obf);
            t.mojangClasses.add(c.mojang);
            t.methods.put(c.obf, new HashMap<>(c.methods));
            Map<String, String> f = new HashMap<>(c.fields);
            c.fieldByName.forEach((k, v) -> { if (v != null) f.put(k, v); });
            t.fields.put(c.obf, f);
        }
        return t;
    }

    // ---------------------------------------------------------------- shared helpers

    /** Rewrites class names inside a descriptor with the given map. */
    static String mapDesc(String desc, Map<String, String> map) {
        StringBuilder b = new StringBuilder(desc.length());
        int i = 0;
        while (i < desc.length()) {
            char c = desc.charAt(i);
            if (c == 'L') {
                int e = desc.indexOf(';', i);
                String n = desc.substring(i + 1, e);
                b.append('L').append(map.getOrDefault(n, n)).append(';');
                i = e + 1;
            } else { b.append(c); i++; }
        }
        return b.toString();
    }

    private void addMethod(Table t, String srcOwner, String srcName, String srcDesc, String mojang) {
        t.methods.computeIfAbsent(srcOwner, k -> new HashMap<>()).put(srcName + srcDesc, mojang);
    }

    private void addField(Table t, String srcOwner, String srcName, String srcDesc, String mojang) {
        Map<String, String> f = t.fields.computeIfAbsent(srcOwner, k -> new HashMap<>());
        if (srcDesc != null) f.put(srcName + ":" + srcDesc, mojang);
        f.put(srcName, mojang);
    }

    static byte[] readMaybeZipped(Path p, String entrySuffix) throws IOException {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!n.endsWith(".jar") && !n.endsWith(".zip")) return Files.readAllBytes(p);
        try (ZipFile z = new ZipFile(p.toFile())) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                if (ze.getName().endsWith(entrySuffix)) return z.getInputStream(ze).readAllBytes();
            }
        }
        throw new IOException(entrySuffix + " not found in " + p);
    }

    // ---------------------------------------------------------------- Fabric Intermediary tiny v2 (official -> intermediary)

    private Table readTiny(Path p) throws IOException {
        Table t = new Table("intermediary");
        String text = new String(readMaybeZipped(p, "mappings.tiny"), StandardCharsets.UTF_8);
        String[] lines = text.split("\r?\n");
        String[] head = lines[0].split("\t");
        if (!head[0].equals("tiny") || !head[1].equals("2")) throw new IOException("not tiny v2: " + p);
        int iObf = -1, iInt = -1;
        for (int i = 3; i < head.length; i++) {
            if (head[i].equals("official")) iObf = i - 3;
            if (head[i].equals("intermediary")) iInt = i - 3;
        }
        // pass 1: classes
        for (String l : lines) {
            if (!l.startsWith("c\t")) continue;
            String[] c = l.split("\t", -1);
            t.classesToSrc.put(c[1 + iObf], c[1 + iInt]);
        }
        for (Map.Entry<String, String> e : t.classesToSrc.entrySet()) {
            ObfClass oc = obf.get(e.getKey());
            String moj = oc != null ? oc.mojang : e.getKey();
            t.classes.put(e.getValue(), moj);
            t.mojangClasses.add(moj);
        }
        // pass 2: members (descriptors are in the first namespace column)
        ObfClass cur = null; String curSrc = null;
        Table toInt = new Table("obf2int"); // obf -> intermediary (test only: makes an Intermediary-named game jar)
        boolean firstIsObf = iObf == 0;
        if (!firstIsObf) throw new IOException("tiny file must list official first: " + p);
        int missing = 0;
        for (String l : lines) {
            if (l.startsWith("c\t")) {
                String[] c = l.split("\t", -1);
                cur = obf.get(c[1 + iObf]); curSrc = c[1 + iInt];
                continue;
            }
            if (cur == null || !(l.startsWith("\tm\t") || l.startsWith("\tf\t"))) continue;
            String[] m = l.substring(1).split("\t", -1);
            String desc = m[1], on = m[2 + iObf], in = m[2 + iInt];
            String srcDesc = mapDesc(desc, t.classesToSrc);
            if (m[0].equals("m")) {
                String moj = cur.methods.get(on + desc);
                if (moj == null) { missing++; continue; }
                addMethod(t, curSrc, in, srcDesc, moj);
                t.obfKey.put(curSrc + "." + in + srcDesc, cur.obf + "." + on + desc);
                if (INT_MEMBER.matcher(in).matches()) { t.putToken(in, moj); t.tokensByDesc.put(in + srcDesc, moj); }
                if (toInt != null) toInt.methods.computeIfAbsent(c0(cur), k -> new HashMap<>()).put(on + desc, in);
            } else {
                String moj = cur.fields.get(on + ":" + desc);
                if (moj == null) moj = cur.fieldByName.get(on);
                if (moj == null) { missing++; continue; }
                addField(t, curSrc, in, srcDesc, moj);
                if (INT_MEMBER.matcher(in).matches()) { t.putToken(in, moj); t.tokensByDesc.put(in + ":" + srcDesc, moj); }
                if (toInt != null) { Map<String, String> f = toInt.fields.computeIfAbsent(c0(cur), k -> new HashMap<>());
                    f.put(on + ":" + desc, in); f.putIfAbsent(on, in); }
            }
        }
        t.missingJoins = missing;
        if (toInt != null) {
            toInt.classes.putAll(t.classesToSrc);
            for (String o : obf.keySet()) toInt.classesToSrc.put(o, o);
            tables.put("obf2int", toInt);
        }
        return t;
    }

    private static String c0(ObfClass c) { return c.obf; }

    // ---------------------------------------------------------------- Forge MCPConfig tsrg2 (obf -> srg ids)

    private Table readTsrg2(Path p) throws IOException {
        Table t = new Table("srg");
        String text = new String(readMaybeZipped(p, "joined.tsrg"), StandardCharsets.UTF_8);
        String[] lines = text.split("\r?\n");
        if (!lines[0].startsWith("tsrg2")) throw new IOException("not tsrg2: " + p);
        // Forge runtime class names are Mojang's (MERGE_MAPPING --classes). Members use SRG ids.
        for (ObfClass c : obf.values()) {
            t.classesToSrc.put(c.obf, c.mojang);
            t.mojangClasses.add(c.mojang);
        }
        ObfClass cur = null;
        int missing = 0;
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i];
            if (l.isEmpty()) continue;
            if (l.charAt(0) != '\t') {
                cur = obf.get(l.split(" ")[0]);
                continue;
            }
            if (l.startsWith("\t\t") || cur == null) continue; // params, "static"
            String[] m = l.substring(1).split(" ");
            if (m.length >= 3 && m[1].startsWith("(")) { // method: obf desc srg [id]
                String moj = cur.methods.get(m[0] + m[1]);
                String srg = m[2];
                if (moj == null) { if (!srg.startsWith("<")) missing++; continue; }
                addMethod(t, cur.mojang, srg, mapDesc(m[1], t.classesToSrc), moj);
                t.obfKey.put(cur.mojang + "." + srg + mapDesc(m[1], t.classesToSrc), cur.obf + "." + m[0] + m[1]);
                if (SRG_MEMBER.matcher(srg).matches()) { t.putToken(srg, moj); t.tokensByDesc.put(srg + mapDesc(m[1], t.classesToSrc), moj); }
            } else { // field: obf srg [id]  (or obf desc srg [id])
                String on = m[0];
                boolean hasDesc = m.length >= 3 && !m[2].matches("\\d+");
                String srg = hasDesc ? m[2] : m[1];
                String moj = hasDesc ? cur.fields.get(on + ":" + m[1]) : null;
                if (moj == null) moj = cur.fieldByName.get(on);
                if (moj == null) { missing++; continue; }
                addField(t, cur.mojang, srg, hasDesc ? mapDesc(m[1], t.classesToSrc) : null, moj);
                if (SRG_MEMBER.matcher(srg).matches()) t.putToken(srg, moj);
            }
        }
        t.missingJoins = missing;
        return t;
    }

    /** Mojang owner.name+desc (fields owner.name:desc) of every game member whose name was obfuscated. */
    public Set<String> renamedMojangMembers(String ns) {
        if (ns.equals("auto")) { Set<String> u = renamedMojangMembers("srg"); u.addAll(renamedMojangMembers("intermediary")); return u; }
        if (ns.equals("srg")) { // SRG keeps some obfuscated names (methods that implement library interfaces, like test)
            Table t = table("srg");
            Set<String> out = new HashSet<>();
            t.methods.forEach((owner, m) -> m.forEach((k, moj) -> {
                int p = k.indexOf('(');
                if (!k.substring(0, p).equals(moj)) out.add(owner + "." + moj + k.substring(p));
            }));
            t.fields.forEach((owner, m) -> m.forEach((k, moj) -> {
                int p = k.indexOf(':');
                if (p > 0 && !k.substring(0, p).equals(moj)) out.add(owner + "." + moj + k.substring(p));
            }));
            return out;
        }
        return renamedMojangMembers();
    }

    /** Intermediary renames exactly the obfuscated members, so this set serves obf and intermediary. */
    public Set<String> renamedMojangMembers() {
        Map<String, String> o2m = new HashMap<>();
        for (ObfClass c : obf.values()) o2m.put(c.obf, c.mojang);
        Set<String> out = new HashSet<>();
        for (ObfClass c : obf.values()) {
            c.methods.forEach((k, moj) -> {
                int p = k.indexOf('(');
                if (!k.substring(0, p).equals(moj)) out.add(c.mojang + "." + moj + mapDesc(k.substring(p), o2m));
            });
            c.fields.forEach((k, moj) -> {
                int p = k.indexOf(':');
                if (!k.substring(0, p).equals(moj)) out.add(c.mojang + "." + moj + ":" + mapDesc(k.substring(p + 1), o2m));
            });
        }
        return out;
    }

    public String stats() {
        StringBuilder b = new StringBuilder();
        int om = 0, of = 0;
        for (ObfClass c : obf.values()) { om += c.methods.size(); of += c.fields.size(); }
        b.append(String.format("mojang: %d classes, %d methods, %d fields%n", obf.size(), om, of));
        for (Table t : tables.values()) {
            if (t.ns.startsWith("obf") || t.ns.equals("auto")) continue;
            int m = 0, f = 0;
            for (Map<String, String> x : t.methods.values()) m += x.size();
            for (Map<String, String> x : t.fields.values()) f += x.size();
            b.append(String.format("%s: %d classes, %d method keys, %d field keys, %d tokens, %d token conflicts, %d entries with no Mojang name%n",
                    t.ns, t.classesToSrc.size(), m, f, t.tokens.size(), t.tokenConflicts.size(), t.missingJoins));
        }
        return b.toString();
    }
}
