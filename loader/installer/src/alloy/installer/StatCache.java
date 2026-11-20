package alloy.installer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File stamps and remembered SHA-1s for one prep run (.alloy/stat.cache).
 *
 * A prep that finds nothing to do still read every jar in mods/ two or three times only to hash it (Fit.reconcile, Fit.run)
 * and wrote the same lock and Mod Doctor report again. On a 195-jar pack that was about 3 s on an idle PC and up to 15 s
 * on a busy one. A file whose size, modification time and creation time are the same as last time is taken to have the
 * same content, the rule Prep's no-change fast path already uses. -Dalloy.statcache.off=true turns it off.
 *
 * Only Prep opens it; every other caller (CLI tools, tests) hashes the files as before.
 */
public final class StatCache {
    private StatCache() { }

    private static final String HEAD = "alloy stat cache 1";
    private static volatile Path file;
    private static final Map<String, String[]> map = new ConcurrentHashMap<>();   // path -> {stamp, sha1}
    private static final Set<String> used = ConcurrentHashMap.newKeySet();
    private static volatile boolean dirty;

    static boolean off() { return Boolean.getBoolean("alloy.statcache.off"); }

    /** loads GAME/.alloy/stat.cache; the cache is used until {@link #close()} */
    static void open(Path game) {
        map.clear();
        used.clear();
        dirty = false;
        file = null;
        if (off()) return;
        Path f = game.resolve(".alloy/stat.cache");
        if (Files.isRegularFile(f)) try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            if (!lines.isEmpty() && lines.get(0).equals(HEAD))
                for (String l : lines.subList(1, lines.size())) {
                    String[] c = l.split("\t", -1);
                    if (c.length == 3) map.put(c[0], new String[]{c[1], c[2]});
                }
        } catch (IOException e) { map.clear(); }
        file = f;
    }

    /** writes the entries used in this run (when something changed) and stops using the cache */
    static void close() {
        Path f = file;
        file = null;
        if (f == null) return;
        try {
            if (!dirty && used.containsAll(map.keySet())) return;   // same entries as on disk: leave the file alone
            if (!Files.isDirectory(f.getParent())) return;
            StringBuilder sb = new StringBuilder(HEAD).append('\n');
            for (String k : new TreeSet<>(used)) {
                String[] v = map.get(k);
                if (v != null) sb.append(k).append('\t').append(v[0]).append('\t').append(v[1]).append('\n');
            }
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, sb, StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { /* only a cache */ }
        finally { map.clear(); used.clear(); }
    }

    /** size, modification time, creation time (and the file key where the OS has one) */
    public static String stamp(Path p) throws IOException {
        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
        Object k = a.fileKey();
        return a.size() + "|" + a.lastModifiedTime() + "|" + a.creationTime() + (k == null ? "" : "|" + k);
    }

    /** SHA-1 of the file's bytes, remembered by stamp while the cache is open */
    static String sha1(Path p) throws IOException {
        if (file == null) return Util.sha1(Files.readAllBytes(p));
        String key = p.toAbsolutePath().normalize().toString();
        String st = stamp(p);
        String[] v = map.get(key);
        used.add(key);
        if (v != null && v[0].equals(st)) return v[1];
        String sha = Util.sha1(Files.readAllBytes(p));
        if (st.equals(stamp(p))) { map.put(key, new String[]{st, sha}); dirty = true; }   // not while the file is being written
        return sha;
    }

    /** sorted "tag|name|stamp" lines for the files in dir whose name ends with suffix ("tag|-" when dir is missing) */
    public static String listing(String tag, Path dir, String suffix) throws IOException {
        if (!Files.isDirectory(dir)) return tag + "|-\n";
        List<String> l = new ArrayList<>();
        try (var s = Files.newDirectoryStream(dir)) {
            for (Path p : s) {
                String n = p.getFileName().toString();
                if (n.endsWith(suffix) && Files.isRegularFile(p)) l.add(n + "|" + stamp(p));
            }
        }
        Collections.sort(l);
        StringBuilder sb = new StringBuilder();
        for (String x : l) sb.append(tag).append('|').append(x).append('\n');
        return sb.toString();
    }

    /** stamp of a file, or "-" when it does not exist */
    public static String stampOrDash(Path p) {
        try { return Files.exists(p) ? stamp(p) : "-"; } catch (IOException e) { return "?"; }
    }

    /** this installer jar's stamp (its code, known.tsv and mod_risks.tsv) */
    public static String self() {
        try { return stamp(Util.selfJar()); } catch (Exception e) { return "?" + System.nanoTime(); }
    }

    public static String sha1Text(String s) { return Util.sha1(s); }
}
