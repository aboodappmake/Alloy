package alloy.remap;

import java.nio.file.*;
import java.util.*;

/**
 * CLI.
 *
 *   remap IN.jar OUT.jar --from srg|intermediary|obf --mojang client.txt --intermediary int.jar --mcp mcp.zip
 *         [--game-obf client.jar] [--classpath a.jar;b.jar] [--aw-namespace named]
 *   check JAR --game a.jar;b.jar [--libs x.jar;y.jar] [--report out.txt]
 *   stats --mojang ... --intermediary ... --mcp ...
 *   srgleft --csv srg2off.jar --game a.jar;b.jar JAR...   (Forge 50+ mods: leftover SRG member names, in place)
 *   batch --list FILE --mojang ... --intermediary ... --mcp ... [--game-obf client.jar] [--classpath a.jar;b.jar]
 *         many remaps with one mappings load; FILE lines: from TAB aw-namespace TAB in.jar TAB out.jar [TAB extra;class;path]
 *
 * --game-obf: the obfuscated vanilla jar; it provides the Minecraft class hierarchy for srg/intermediary inputs.
 * For --from obf the input jar is the vanilla jar itself.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length == 0) { usage(); return; }
        Map<String, String> o = new HashMap<>();
        List<String> pos = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) o.put(args[i].substring(2), args[++i]);
            else pos.add(args[i]);
        }
        switch (args[0]) {
            case "remap": remap(pos, o); break;
            case "batch": batch(o); break;
            case "check": check(pos, o); break;
            case "compare": compare(pos); break;
            case "corpus": Corpus.run(o, load(o)); break;
            case "export": Export.run(o, load(o)); break;
            case "srgleft": SrgLeftover.cli(o, pos); break;   // Forge 50+: leftover SRG member names -> Mojang (srg2off)
            case "stats": {
                Mappings m = load(o);
                System.out.print(m.stats());
                for (Mappings.Table t : m.tables.values()) for (String c : t.tokenConflicts) System.out.println("  conflict " + t.ns + ": " + c);
                break;
            }
            default: usage();
        }
    }

    static void usage() {
        System.out.println("usage: remap IN OUT --from srg|intermediary|obf --mojang TXT [--intermediary JAR] [--mcp ZIP] [--game-obf JAR] [--classpath CP] [--aw-namespace NS]");
        System.out.println("       check JAR --game CP [--libs CP] [--report FILE] [--strict 1]");
        System.out.println("       stats --mojang TXT --intermediary JAR --mcp ZIP");
    }

    static Mappings load(Map<String, String> o) throws Exception {
        long t0 = System.currentTimeMillis();
        Mappings m = Mappings.load(Paths.get(o.get("mojang")),
                o.containsKey("intermediary") ? Paths.get(o.get("intermediary")) : null,
                o.containsKey("mcp") ? Paths.get(o.get("mcp")) : null);
        System.out.println("mappings loaded in " + (System.currentTimeMillis() - t0) + " ms");
        return m;
    }

    static List<Path> cp(String s) {
        List<Path> out = new ArrayList<>();
        if (s == null) return out;
        for (String x : s.split(";")) if (!x.isBlank()) out.add(Paths.get(x.trim()));
        return out;
    }

    static void remap(List<String> pos, Map<String, String> o) throws Exception {
        String from = o.getOrDefault("from", "");
        if (pos.size() != 2 || from.isEmpty()) { usage(); System.exit(2); }
        Mappings m = load(o);
        remapOne(m, from, o.getOrDefault("aw-namespace", "named"), Paths.get(pos.get(0)), Paths.get(pos.get(1)),
                o.get("game-obf"), cp(o.get("classpath")), o.get("fixes"));
    }

    /** batch: one mappings load, then every line of --list (see the class comment). Exit code 1 if any line failed. */
    static void batch(Map<String, String> o) throws Exception {
        Mappings m = load(o);
        int bad = 0;
        for (String line : Files.readAllLines(Paths.get(o.get("list")))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("	");
            List<Path> cp = cp(o.get("classpath"));
            if (f.length > 4) cp.addAll(0, cp(f[4]));
            try {
                remapOne(m, f[0], f[1], Paths.get(f[2]), Paths.get(f[3]), o.get("game-obf"), cp, o.get("fixes"));
            } catch (Exception e) {
                bad++;
                System.out.println("FAILED " + f[2] + ": " + e);
            }
        }
        if (bad > 0) System.exit(1);
    }

    static void remapOne(Mappings m, String from, String awNs, Path in, Path out, String gameObf, List<Path> classpath, String fixes) throws Exception {
        Mappings.Table t = m.table(from);
        Hierarchy h = new Hierarchy();
        JarRemapper jr = new JarRemapper(t, h, awNs);
        long t0 = System.currentTimeMillis();
        if (gameObf != null) jr.gameObf = Paths.get(gameObf);
        if (fixes != null) jr.fixesFile = Paths.get(fixes);
        List<JarRemapper.Item> items = jr.prepare(in);
        if (gameObf != null) {
            Path g = Paths.get(gameObf);
            h.addJar(g, n -> t.classesToSrc.getOrDefault(n, n), "game-obf");
            if (t.srgToSrc != null) h.addJar(g, n -> t.srgToSrc.getOrDefault(n, n), "game-obf-srg"); // auto: both views
        }
        for (Path p : classpath) h.addJar(p, null, p.getFileName().toString());
        jr.write(items, out);
        h.close();
        NsRemapper.Stats s = jr.stats;
        System.out.printf("remapped %s -> %s (%s -> mojang) in %d ms%n", in, out, from, System.currentTimeMillis() - t0);
        System.out.printf("accessor/invoker values added %d%n", s.accessorValues);
        for (String c : s.collisionsFixed) System.out.println("collision fixed: " + c);
        for (String c : s.collisionsReported) System.out.println("collision NOT fixed (review): " + c);
        for (String c : s.overloadsDropped) System.out.println("overload dropped (same as another after the rename): " + c);
        System.out.printf("classes %d (renamed %d), strings %d, nested jars %d, refmaps %d (%d values changed), access wideners %d, access transformers %d, js %d, other text %d, fabrication maps %d, other resources with ids %d%n",
                s.classes, s.renamedClasses, s.strings, s.nestedJars, s.refmaps, s.refmapValues, s.awFiles, s.atFiles, s.jsFiles, s.textFiles,
                s.fabRefMaps, s.resourceFiles);
        if (!s.unknown.isEmpty()) {
            System.out.println("unknown tokens (left as they are): " + s.unknown.size());
            int n = 0;
            for (Map.Entry<String, Integer> e : s.unknown.entrySet()) {
                if (n++ >= 20) { System.out.println("  ..."); break; }
                System.out.println("  " + e.getKey() + " x" + e.getValue());
            }
        }
    }

    /** Compares class and member names of two jars (only classes present in both are compared member-wise). */
    static void compare(List<String> pos) throws Exception {
        Map<String, Set<String>> a = names(Paths.get(pos.get(0))), b = names(Paths.get(pos.get(1)));
        int onlyA = 0, onlyB = 0, common = 0, memberDiff = 0, members = 0;
        List<String> sample = new ArrayList<>();
        for (String c : a.keySet()) if (!b.containsKey(c)) { onlyA++; if (sample.size() < 20) sample.add("only in first: " + c); }
        for (String c : b.keySet()) if (!a.containsKey(c)) { onlyB++; if (sample.size() < 20) sample.add("only in second: " + c); }
        for (Map.Entry<String, Set<String>> e : a.entrySet()) {
            Set<String> o = b.get(e.getKey());
            if (o == null) continue;
            common++;
            members += e.getValue().size();
            for (String m : e.getValue()) if (!o.contains(m)) { memberDiff++; if (sample.size() < 40) sample.add("member only in first: " + e.getKey() + " " + m); }
            for (String m : o) if (!e.getValue().contains(m)) { memberDiff++; if (sample.size() < 40) sample.add("member only in second: " + e.getKey() + " " + m); }
        }
        System.out.printf("classes: %d common, %d only in first, %d only in second; members compared %d, differing %d%n", common, onlyA, onlyB, members, memberDiff);
        for (String s : sample) System.out.println("  " + s);
    }

    static Map<String, Set<String>> names(Path jar) throws Exception {
        Map<String, Set<String>> out = new HashMap<>();
        for (JarRemapper.Item it : JarRemapper.readJar(Files.readAllBytes(jar))) {
            if (!it.name.endsWith(".class") || it.name.startsWith("META-INF/")) continue;
            org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(it.data);
            Set<String> s = new HashSet<>();
            cr.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override public org.objectweb.asm.FieldVisitor visitField(int ac, String n, String d, String sg, Object v) { s.add(n + ":" + d); return null; }
                @Override public org.objectweb.asm.MethodVisitor visitMethod(int ac, String n, String d, String sg, String[] e) { s.add(n + d); return null; }
            }, org.objectweb.asm.ClassReader.SKIP_CODE);
            out.put(cr.getClassName(), s);
        }
        return out;
    }

    static void check(List<String> pos, Map<String, String> o) throws Exception {
        if (pos.size() != 1) { usage(); System.exit(2); }
        Checker c = new Checker();
        if (o.containsKey("original")) c.original = Paths.get(o.get("original"));
        if (o.containsKey("mojang")) c.renamedGame = load(o).renamedMojangMembers(o.getOrDefault("from", "auto"));
        c.run(Paths.get(pos.get(0)), cp(o.get("game")), cp(o.get("libs")),
                o.containsKey("report") ? Paths.get(o.get("report")) : null);
        // --strict 1: ids left in text resources fail the check (they mean a file format the translator missed)
        if (o.containsKey("strict") && c.resourceLeftoverCount() > 0) System.exit(3);
    }
}
