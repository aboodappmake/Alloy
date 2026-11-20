package alloy.installer;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Forge-vs-Fabric @Redirect collision fix (tools/RedirectToWrap, bundled in the jar, run as a child JVM).
 * Runs after Fabric auto-fit in every prep. Rewrites colliding Forge handlers to @WrapOperation in the forgemods/ copies.
 * Pristine copies live in .alloy/originals/forgemods/ (the user's files; the same dir Fit uses for Fabric originals;
 * on 1.20.1 .alloy/originals/forgemods-collide/, the Mojang-named copies, and V120's remap state follows each rewrite),
 * state in .alloy/collide.properties:  stateKey, and  jar.NAME = origSize|origMtime|curSize|curMtime|converted
 * Prep's hash uses {@link #identity} (the ORIGINAL size/mtime of a converted jar), so conversion never re-triggers prep,
 * and re-runs always start from the pristine files, so nothing is converted twice.
 */
final class Collide {
    private Collide() { }

    static Path stateFile(Path game) { return game.resolve(".alloy/collide.properties"); }
    /** on 1.20.1 .alloy/originals/forgemods holds the SRG-named player files (V120), so Collide keeps its Mojang-named ones apart */
    static Path originals(Path game) { return game.resolve(V120.on() ? ".alloy/originals/forgemods-collide" : ".alloy/originals/forgemods"); }

    private static Properties load(Path game) {
        Properties p = new Properties();
        try { if (Files.exists(stateFile(game))) try (Reader r = Files.newBufferedReader(stateFile(game))) { p.load(r); } }
        catch (IOException e) { /* treated as empty */ }
        return p;
    }

    private static String own(Path j) throws IOException {
        return Files.size(j) + "|" + Files.getLastModifiedTime(j).toMillis();
    }

    /** "size|mtime" of the user's jar: the recorded original when the file is our converted copy, else the file itself. */
    static String identity(Path game, Path jar) throws IOException {
        String v = load(game).getProperty("jar." + jar.getFileName());
        if (v != null) {
            String[] a = v.split("\\|");
            if (a.length >= 4 && (a[2] + "|" + a[3]).equals(own(jar))) return a[0] + "|" + a[1];
        }
        return own(jar);
    }

    /** true when the file in forgemods/ is the converted copy this class wrote (so it is not the user's file). */
    private static boolean isOurs(Properties st, Path j) throws IOException {
        String v = st.getProperty("jar." + j.getFileName());
        if (v == null) return false;
        String[] a = v.split("\\|");
        return a.length >= 5 && a[4].equals("1") && (a[2] + "|" + a[3]).equals(own(j));
    }

    /**
     * Before the Mod Doctor: a tracked Forge jar (converted copy + pristine original) that is superseded by a freshly dropped
     * jar with the same Forge mod id and another file name (an update) is removed with its originals entry and state.
     */
    static void reconcile(Path game) {
        try {
            game = game.toAbsolutePath().normalize();
            Path fmods = game.resolve("forgemods");
            Properties st = load(game);
            if (!Files.isDirectory(fmods) || st.stringPropertyNames().stream().noneMatch(k -> k.startsWith("jar."))) return;
            List<String> tracked = new ArrayList<>(), fresh = new ArrayList<>();
            for (Path j : Util.jars(fmods)) {
                String n = j.getFileName().toString();
                String v = st.getProperty("jar." + n);
                boolean known = false;
                if (v != null) {
                    String[] a = v.split("\\|");
                    known = a.length >= 4 && ((a[2] + "|" + a[3]).equals(own(j)) || (a[0] + "|" + a[1]).equals(own(j)));
                }
                (known ? tracked : fresh).add(n);
            }
            if (fresh.isEmpty() || tracked.isEmpty()) return;
            boolean dirty = false;
            for (String n : fresh) {
                Set<String> mine = forgeIds(fmods.resolve(n));
                if (mine.isEmpty()) continue;
                for (String k : new ArrayList<>(tracked)) {
                    if (k.equals(n)) continue;
                    Path o = originals(game).resolve(k);
                    Set<String> theirs = forgeIds(Files.exists(o) ? o : fmods.resolve(k));
                    if (Collections.disjoint(mine, theirs)) continue;
                    Files.deleteIfExists(fmods.resolve(k));
                    Files.deleteIfExists(o);
                    st.remove("jar." + k);
                    st.remove("stateKey");
                    tracked.remove(k);
                    dirty = true;
                    Util.log("alloy-collide: " + k + " replaced by " + n + " (same Forge mod id)");
                }
            }
            if (dirty) try (Writer w = Files.newBufferedWriter(stateFile(game))) { st.store(w, "alloy collision state"); }
        } catch (Throwable t) {
            Util.log("alloy-collide: reconcile skipped (" + t + ")");
        }
    }

    private static Set<String> forgeIds(Path jar) {
        Set<String> s = new HashSet<>();
        alloy.ideas.ModMeta.JarInfo ji = alloy.ideas.ModMeta.read(jar);
        for (var m : ji.mods) if (m.loader == alloy.ideas.ModMeta.Loader.FORGE) s.add(m.id);
        return s;
    }

    static void run(Path game, String prepKey) {
        try {
            game = game.toAbsolutePath().normalize();
            Path mods = game.resolve("mods"), fmods = game.resolve("forgemods"), nested = fmods.resolve(".nested");
            Path orig = originals(game), log = game.resolve("alloy-collisions.txt");
            Properties st = load(game);
            // 1. pristine copies of every top-level Forge jar (new or user-replaced ones are captured as they are)
            List<Path> top = Util.jars(fmods);
            Set<String> names = new TreeSet<>();
            Files.createDirectories(orig);
            for (Path j : top) {
                String n = j.getFileName().toString();
                names.add(n);
                if (!isOurs(st, j)) {
                    Files.copy(j, orig.resolve(n), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                } else if (!Files.exists(orig.resolve(n))) throw new IOException("original of " + n + " is missing");
            }
            try (var s = Files.list(orig)) {   // forget originals whose mod was removed
                for (Path o : s.toList()) if (!names.contains(o.getFileName().toString())) Files.delete(o);
            }
            List<Path> fabric = new ArrayList<>();
            Map<String, String> sides = ForgeInMods.overrides(game);   // Forge jars in mods/ are not Fabric mods (their mirror is a Forge jar above)
            for (Path p : Util.jars(mods)) if (!p.getFileName().toString().startsWith("alloy-") && ForgeInMods.loadsOnFabric(p, sides)) fabric.add(p);
            StringBuilder sb = new StringBuilder("c2|").append(Util.sha1(Util.resource("/RedirectToWrap.class"))).append('|').append(prepKey).append('\n');
            for (String n : names) sb.append("F|").append(n).append('|').append(identity(game, fmods.resolve(n))).append('\n');
            for (Path p : fabric) sb.append("M|").append(p.getFileName()).append('|').append(own(p)).append('\n');
            String key = Util.sha1(sb.toString());
            boolean current = key.equals(st.getProperty("stateKey"));
            for (String n : names) if (st.getProperty("jar." + n) == null) current = false;
            if (current) { Util.log("alloy-collide: unchanged"); return; }

            // 2. restore what an earlier run converted, then scan the pristine Forge + nested jars against the Fabric set
            for (String n : names) {
                String v = st.getProperty("jar." + n);
                if (v != null && v.endsWith("|1")) Files.copy(orig.resolve(n), fmods.resolve(n), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
            Path work = game.resolve(".alloy/collide-tmp");
            deleteTree(work);
            Path pn = work.resolve("nested"), out = work.resolve("out");
            Files.createDirectories(pn);
            Map<String, byte[]> found = new LinkedHashMap<>();
            for (String n : names) try (InputStream in = new BufferedInputStream(Files.newInputStream(orig.resolve(n)))) { Prep.scan(in, new ArrayList<>(), found); }
            for (var e : found.entrySet()) Files.write(pn.resolve(e.getKey()), e.getValue());
            List<String> lines = new ArrayList<>();
            Set<String> changedTop = new HashSet<>(), changedNested = new HashSet<>();
            if (!names.isEmpty() && !fabric.isEmpty()) {
                List<String> cmd = new ArrayList<>(List.of(Util.ownJava().toString(), "-Xmx1g", "-XX:ActiveProcessorCount=2"));
                cmd.addAll(Util.JVM_PROPS);
                cmd.addAll(List.of("-cp", Util.selfJar().toString(), "RedirectToWrap", out.toString()));
                StringJoiner fj = new StringJoiner(";");
                for (Path p : fabric) fj.add(p.toString());
                cmd.add(fj.toString());
                for (String n : names) cmd.add(orig.resolve(n).toString());
                try (var s = Files.list(pn)) { for (Path p : s.sorted().toList()) cmd.add(p.toString()); }
                // Big packs (VORTEX 370 Forge jars, MMC4 300) go past Windows' 32k command line ("CreateProcess error=206"):
                // the arguments after the main class go in a file (RedirectToWrap reads "@file"; java's own @argfiles end at the main class)
                int main = cmd.indexOf("RedirectToWrap") + 1;
                List<String> args = new ArrayList<>(cmd.subList(main, cmd.size()));
                Path af = work.resolve("collide.args");
                Files.write(af, args, java.nio.charset.StandardCharsets.UTF_8);
                cmd.subList(main, cmd.size()).clear();
                cmd.add("@" + af);
                Object[] r = Util.runCapture(cmd, work);
                String text = (String) r[1];
                if ((int) r[0] != 0) {
                    Util.log("alloy-collide: RedirectToWrap failed, collisions are not resolved: " + tail(text));
                    patchMixinExtras(fmods, names, changedTop);
                    finish(game, null, names, changedTop, log, lines);
                    deleteTree(work);
                    return;
                }
                for (String l : text.lines().toList()) if (l.startsWith("COLLISION") || l.startsWith("CONVERT") || l.startsWith("WARN")) lines.add(l);
                if (Files.isDirectory(out)) try (var s = Files.list(out)) {
                    for (Path f : s.toList()) {
                        String n = f.getFileName().toString();
                        if (names.contains(n)) { Files.copy(f, fmods.resolve(n), StandardCopyOption.REPLACE_EXISTING); changedTop.add(n); }
                        if (found.containsKey(n) && Files.isDirectory(nested)) { Files.copy(f, nested.resolve(n), StandardCopyOption.REPLACE_EXISTING); changedNested.add(n); }
                    }
                }
            }
            patchMixinExtras(fmods, names, changedTop);
            // nested jars that no longer collide (or were never converted): pristine ones
            if (Files.isDirectory(nested)) for (var e : found.entrySet())
                if (!changedNested.contains(e.getKey())) Files.write(nested.resolve(e.getKey()), e.getValue());
            deleteTree(work);
            finish(game, key, names, changedTop, log, lines);
            Util.log("alloy-collide: " + changedTop.size() + " Forge jar(s) rewritten" + (lines.isEmpty() ? ", no collisions" : ", see alloy-collisions.txt"));
        } catch (Throwable t) {
            Util.log("alloy-collide: skipped (" + t + ")");
            if (System.getProperty("alloy.debug") != null) t.printStackTrace();
        } finally {
            if (V120.on()) {   // every forgemods/ jar is a Mojang-named copy here (V120 ran first in this prep): keep them ours
                try { V120.adopt(game, Util.jars(game.resolve("forgemods")).stream().map(p -> p.getFileName().toString()).toList()); }
                catch (Throwable t) { Util.log("alloy-collide: remap state not updated (" + t + ")"); }
            }
        }
    }

    /**
     * Drops dangling MixinExtras jar-in-jar entries (see Prep.stripDanglingMixinExtras) from the forgemods/ copies.
     * Runs on whatever is in forgemods/ now (pristine or converted), so the pristine original in originals/ stays untouched
     * and a patched jar is recorded as "converted" like any other rewritten copy (identity and prep hash stay stable).
     */
    private static void patchMixinExtras(Path fmods, Set<String> names, Set<String> changedTop) {
        for (String n : names) {
            try {
                Path j = fmods.resolve(n), t = fmods.resolve(n + ".mxpatch");
                Files.deleteIfExists(t);
                if (Prep.stripDanglingMixinExtras(j, t)) {
                    Files.move(t, j, StandardCopyOption.REPLACE_EXISTING);
                    changedTop.add(n);
                    Util.log("alloy-collide: " + n + ": dropped MixinExtras jar-in-jar entry whose jar is not shipped (Fabric provides MixinExtras)");
                }
            } catch (IOException e) { Util.log("alloy-collide: MixinExtras patch of " + n + " skipped (" + e + ")"); }
        }
    }

    private static void finish(Path game, String key, Set<String> names, Set<String> changed, Path log, List<String> lines) throws IOException {
        Properties st = new Properties();
        for (String n : names) {
            Path j = game.resolve("forgemods").resolve(n), o = originals(game).resolve(n);
            st.setProperty("jar." + n, own(o) + "|" + own(j) + "|" + (changed.contains(n) ? "1" : "0"));
        }
        if (key != null) st.setProperty("stateKey", key);
        try (Writer w = Files.newBufferedWriter(stateFile(game))) { st.store(w, "alloy collision state"); }
        if (lines.isEmpty()) Files.deleteIfExists(log);
        else { Files.writeString(log, String.join("\n", lines) + "\n"); lines.forEach(l -> Util.log("  " + l)); }
    }

    private static String tail(String s) { return s.length() > 600 ? s.substring(s.length() - 600) : s; }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) { for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(x); }
    }
}
