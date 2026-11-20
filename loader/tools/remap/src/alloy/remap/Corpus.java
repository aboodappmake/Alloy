package alloy.remap;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Corpus test in one JVM: remap every jar, then check each remapped jar against the Mojang-named game and compare
 * with the same check of the original jar against the source-named game ("no new unresolved references").
 *
 *   corpus --list jars.txt --out DIR --from srg|intermediary --mojang ... --intermediary ... --mcp ...
 *          --game-obf client.jar --game CP --source-game CP --libs CP [--libs-mojang CP] [--libs-source CP]
 */
final class Corpus {
    static void run(Map<String, String> o, Mappings m) throws Exception {
        String from = o.get("from");
        Mappings.Table t = m.table(from);
        Path out = Paths.get(o.get("out"));
        Files.createDirectories(out.resolve("jars"));
        Files.createDirectories(out.resolve("reports"));
        List<Path> jars = new ArrayList<>();
        for (String l : Files.readAllLines(Paths.get(o.get("list")))) if (!l.isBlank()) jars.add(Paths.get(l.trim()));
        // which game names count as "renamed" for the collision report: the namespace the jars were built for
        Set<String> renamed = m.renamedMojangMembers(o.getOrDefault("check-from", from));
        List<Path> mcLibs = Main.cp(o.get("libs"));
        List<Path> outs = new ArrayList<>();
        PrintStream log = new PrintStream(new FileOutputStream(out.resolve("corpus.log").toFile(), true), true, "UTF-8");
        Map<Path, NsRemapper.Stats> rstats = new HashMap<>();
        Map<Path, String> failed = new HashMap<>();
        long t0 = System.currentTimeMillis();
        for (Path j : jars) {
            Path dst = out.resolve("jars").resolve(j.getFileName().toString());
            try {
                Hierarchy h = new Hierarchy();
                JarRemapper jr = new JarRemapper(t, h, o.getOrDefault("aw-namespace", "named"));
                jr.gameObf = Paths.get(o.get("game-obf"));
                jr.fixesFile = out.resolve("fixes.tsv");
                List<JarRemapper.Item> items = jr.prepare(j);
                Path g = Paths.get(o.get("game-obf"));
                h.addJar(g, n -> t.classesToSrc.getOrDefault(n, n), "game-obf");
                if (t.srgToSrc != null) h.addJar(g, n -> t.srgToSrc.getOrDefault(n, n), "game-obf-srg");
                for (Path p : mcLibs) h.addJar(p, null, "lib");
                jr.write(items, dst);
                h.close();
                rstats.put(j, jr.stats);
                for (String c : jr.stats.collisionsFixed) log.println("FIXED " + j.getFileName() + " " + c);
                for (String c : jr.stats.collisionsReported) log.println("COLLISION " + j.getFileName() + " " + c);
                outs.add(dst);
            } catch (Throwable e) {
                failed.put(j, String.valueOf(e));
                log.println("REMAP FAIL " + j + " " + e);
            }
        }
        log.println("remapped " + outs.size() + "/" + jars.size() + " in " + (System.currentTimeMillis() - t0) + " ms");
        PrintStream nul = new PrintStream(OutputStream.nullOutputStream());
        PrintStream so = System.out;
        StringBuilder tsv = new StringBuilder("jar\tclasses\tmixins\tmember_refs\tunres_before\tunres_after\trefmap_bad_before\trefmap_bad_after"
                + "\tmixin_bad_before\tmixin_bad_after\tselector_bad_before\tselector_bad_after\tleftover\tcollisions\taccess\tunknown_tokens\tstrings\tnote\n");
        for (Path j : jars) {
            if (failed.containsKey(j)) { tsv.append(j.getFileName()).append("\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\tremap failed: ").append(failed.get(j)).append('\n'); continue; }
            Path dst = out.resolve("jars").resolve(j.getFileName().toString());
            String name = j.getFileName().toString();
            Checker after = new Checker(), before = new Checker();
            try {
                System.setOut(nul);
                List<Path> la = new ArrayList<>(mcLibs);
                la.addAll(Main.cp(o.get("libs-mojang")));
                for (Path p : outs) if (!p.equals(dst)) la.add(p);
                after.original = j;
                after.renamedGame = renamed;
                after.run(dst, Main.cp(o.get("game")), la, out.resolve("reports").resolve(name + ".after.txt"));
                List<Path> lb = new ArrayList<>(mcLibs);
                lb.addAll(Main.cp(o.get("libs-source")));
                for (Path p : jars) if (!p.equals(j)) lb.add(p);
                before.run(j, Main.cp(o.get("source-game")), lb, out.resolve("reports").resolve(name + ".before.txt"));
            } catch (Throwable e) {
                System.setOut(so);
                log.println("CHECK FAIL " + j + " " + e);
                tsv.append(name).append("\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\tcheck failed: ").append(e).append('\n');
                continue;
            } finally { System.setOut(so); }
            NsRemapper.Stats s = rstats.get(j);
            tsv.append(name).append('\t').append(after.input.size()).append('\t').append(after.mixins).append('\t').append(after.refs)
                    .append('\t').append(before.unresolved.size()).append('\t').append(after.unresolved.size())
                    .append('\t').append(before.refmapBad).append('\t').append(after.refmapBad)
                    .append('\t').append(before.shadowBad).append('\t').append(after.shadowBad)
                    .append('\t').append(before.selectorsBad).append('\t').append(after.selectorsBad)
                    .append('\t').append(after.leftovers.size()).append('\t').append(after.collisions.size()).append('\t').append(after.access.size())
                    .append('\t').append(s.unknown.size()).append('\t').append(s.strings).append("\t\n");
            log.println("checked " + name + " before=" + before.unresolved.size() + " after=" + after.unresolved.size()
                    + " collisions=" + after.collisions.size() + " access=" + after.access.size());
        }
        Files.writeString(out.resolve("corpus.tsv"), tsv.toString());
        log.println("done in " + (System.currentTimeMillis() - t0) + " ms");
        log.close();
    }
}
