package alloy.remap;

import org.objectweb.asm.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Runtime mapping files for the loaders (the static remap cannot see names built at run time):
 *  - fabric-runtime.tiny  tiny v2 "official intermediary named" (named = Mojang), COMPLETED: Intermediary lists a method
 *                         only on the root of its override family; here every overrider gets its own line, so
 *                         Fabric's MappingResolver (exact owner lookup) also answers for sub classes.
 *  - srg2mojang.tsv       SRG member id -> Mojang name (Forge name function, naming=mcp)
 *  - tokens.tsv           union token map (SRG + Intermediary members, Intermediary classes) -> Mojang;
 *                         ambiguous ids are listed with their descriptor
 *
 *   export --out DIR --game-obf client.jar --mojang ... --intermediary ... --mcp ...
 */
final class Export {
    static void run(Map<String, String> o, Mappings m) throws Exception {
        Path out = Paths.get(o.get("out"));
        Files.createDirectories(out);
        Mappings.Table in = m.table("intermediary"), srg = m.table("srg"), o2i = m.table("obf2int");

        // obf jar: hierarchy and declared members
        Map<String, String[]> supers = new HashMap<>();
        Map<String, List<String>> declared = new LinkedHashMap<>(); // obf owner -> "m name desc" / "f name desc"
        try (ZipFile z = new ZipFile(o.get("game-obf"))) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                if (!ze.getName().endsWith(".class") || ze.getName().startsWith("META-INF/")) continue;
                ClassReader cr = new ClassReader(z.getInputStream(ze).readAllBytes());
                List<String> s = new ArrayList<>();
                if (cr.getSuperName() != null) s.add(cr.getSuperName());
                s.addAll(Arrays.asList(cr.getInterfaces()));
                supers.put(cr.getClassName(), s.toArray(new String[0]));
                List<String> mem = new ArrayList<>();
                cr.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public FieldVisitor visitField(int a, String n, String d, String sg, Object v) { mem.add("f " + n + " " + d); return null; }
                    @Override public MethodVisitor visitMethod(int a, String n, String d, String sg, String[] ex) {
                        if (!n.startsWith("<")) mem.add("m " + n + " " + d + " " + a);
                        return null;
                    }
                }, ClassReader.SKIP_CODE);
                declared.put(cr.getClassName(), mem);
            }
        }
        Map<String, String> obfToMoj = m.table("obf").classes;
        int listed = 0, completed = 0;
        StringBuilder t = new StringBuilder("tiny\t2\t0\tofficial\tintermediary\tnamed\n");
        for (Map.Entry<String, List<String>> e : declared.entrySet()) {
            String ob = e.getKey();
            String inn = o2i.classes.getOrDefault(ob, ob), moj = obfToMoj.getOrDefault(ob, ob);
            t.append("c\t").append(ob).append('\t').append(inn).append('\t').append(moj).append('\n');
            Map<String, String> ownM = o2i.methods.getOrDefault(ob, Collections.emptyMap());
            for (String mem : e.getValue()) {
                String[] p = mem.split(" ");
                String n = p[1], d = p[2];
                if (p[0].equals("f")) {
                    String i = lookupField(o2i, ob, n, d), mj = lookupField(m.table("obf"), ob, n, d);
                    if (i == null || mj == null) continue;
                    t.append("\tf\t").append(d).append('\t').append(n).append('\t').append(i).append('\t').append(mj).append('\n');
                } else {
                    int acc = Integer.parseInt(p[3]);
                    String i = ownM.get(n + d);
                    boolean own = i != null;
                    if (i == null && (acc & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) i = inherited(o2i, supers, ob, n + d);
                    String mj = m.table("obf").methods.getOrDefault(ob, Collections.emptyMap()).get(n + d);
                    if (mj == null) mj = inherited(m.table("obf"), supers, ob, n + d);
                    if (i == null || mj == null) continue;
                    if (own) listed++; else completed++;
                    t.append("\tm\t").append(d).append('\t').append(n).append('\t').append(i).append('\t').append(mj).append('\n');
                }
            }
        }
        Files.writeString(out.resolve("fabric-runtime.tiny"), t.toString());

        StringBuilder s2m = new StringBuilder();
        new TreeMap<>(srg.tokens).forEach((k, v) -> s2m.append(k).append('\t').append(v).append('\n'));
        Files.writeString(out.resolve("srg2mojang.tsv"), s2m.toString());

        Mappings.Table u = m.table("auto");
        StringBuilder tok = new StringBuilder("# token\tmojang  (class tokens use / ; ambiguous ids: token+desc)\n");
        new TreeMap<>(in.classes).forEach((k, v) -> { if (!k.equals(v)) tok.append(k).append('\t').append(v).append('\n'); });
        new TreeMap<>(u.tokens).forEach((k, v) -> tok.append(k).append('\t').append(v).append('\n'));
        new TreeMap<>(u.tokensByDesc).forEach((k, v) -> {
            String name = k.contains(":") ? k.substring(0, k.indexOf(':')) : k.substring(0, k.indexOf('('));
            if (u.ambiguous.contains(name)) tok.append(k).append('\t').append(v).append('\n');
        });
        Files.writeString(out.resolve("tokens.tsv"), tok.toString());
        System.out.printf("fabric-runtime.tiny: %d classes, %d method lines from Intermediary, %d added by completion%n",
                declared.size(), listed, completed);
        System.out.printf("srg2mojang.tsv: %d ids; tokens.tsv: %d tokens + %d ambiguous with descriptor%n",
                srg.tokens.size(), u.tokens.size(), u.ambiguous.size());
    }

    private static String lookupField(Mappings.Table t, String owner, String n, String d) {
        Map<String, String> f = t.fields.get(owner);
        if (f == null) return null;
        String r = f.get(n + ":" + d);
        return r != null ? r : f.get(n);
    }

    private static String inherited(Mappings.Table t, Map<String, String[]> supers, String owner, String nd) {
        Deque<String> q = new ArrayDeque<>(Arrays.asList(supers.getOrDefault(owner, new String[0])));
        Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) {
            String s = q.poll();
            if (!seen.add(s)) continue;
            Map<String, String> mm = t.methods.get(s);
            if (mm != null && mm.containsKey(nd)) return mm.get(nd);
            q.addAll(Arrays.asList(supers.getOrDefault(s, new String[0])));
        }
        return null;
    }
}
