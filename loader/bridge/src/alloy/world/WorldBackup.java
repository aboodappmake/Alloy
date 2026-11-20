package alloy.world;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;

/**
 * Zips a world folder into &lt;gamedir&gt;/alloy-backups/ before Alloy loads it under a new build or a changed mod list.
 *
 *  - The zip is written as *.zip.part, read back and every entry's SHA-256 compared with the file it came from; only
 *    then is it renamed to .zip. session.lock is left out (the game may hold it).
 *  - alloy-backups/index.tsv has one line per backup (time, world, zip, files, bytes, zip SHA-256, level.dat SHA-256,
 *    fingerprint, reason). Only the newest {@link #keep} backups of each world are kept (-Dalloy.backup.keep, default 3).
 *  - Low disk space or any error never stops the game: {@link Result#warning} says what went wrong.
 * No Minecraft classes, so tests and the installer can use it too.
 */
public final class WorldBackup {
    public static final String DIR = "alloy-backups", INDEX = "index.tsv";
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** extra free space wanted on top of the world's size (zips are usually smaller, so this is a safe margin) */
    static final long MARGIN = 64L << 20;

    public final Path root;
    public int keep = Integer.getInteger("alloy.backup.keep", 3);
    /** free bytes on the backup drive; tests replace it */
    public java.util.function.LongSupplier freeSpace;

    public WorldBackup(Path gameDir) {
        this.root = gameDir.resolve(DIR);
        this.freeSpace = () -> {
            try {
                Path p = root;
                while (p != null && !Files.exists(p)) p = p.getParent();
                return p == null ? Long.MAX_VALUE : Files.getFileStore(p).getUsableSpace();
            } catch (IOException e) {
                return Long.MAX_VALUE;
            }
        };
    }

    /** What happened. zip is null when nothing was written. */
    public static final class Result {
        public Path zip;
        public boolean made, skippedSame;
        public String warning;
        public int files;
        public long bytes;
        @Override public String toString() {
            return made ? "backup " + zip + " (" + files + " files)" : skippedSame ? "backup already made: " + zip : "no backup: " + warning;
        }
    }

    /** One line of index.tsv. */
    public record Entry(String time, String world, String zip, int files, long bytes, String zipSha, String levelSha, String fingerprint, String reason) {
        String line() { return String.join("\t", time, world, zip, String.valueOf(files), String.valueOf(bytes), zipSha, levelSha, fingerprint, clean(reason)); }
        static Entry parse(String l) {
            String[] p = l.split("\t", -1);
            if (p.length < 9) return null;
            try {
                return new Entry(p[0], p[1], p[2], Integer.parseInt(p[3]), Long.parseLong(p[4]), p[5], p[6], p[7], p[8]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }

    public List<Entry> index() {
        List<Entry> out = new ArrayList<>();
        Path f = root.resolve(INDEX);
        if (!Files.isRegularFile(f)) return out;
        try {
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (l.isBlank() || l.startsWith("#")) continue;
                Entry e = Entry.parse(l);
                if (e != null) out.add(e);
            }
        } catch (IOException ignored) { }
        return out;
    }

    /** The newest backup of this world whose fingerprint and level.dat hash match, if its zip is still there. */
    public Entry sameAs(String world, String fingerprint, String levelSha) {
        Entry hit = null;
        for (Entry e : index())
            if (e.world.equals(world) && e.fingerprint.equals(fingerprint) && e.levelSha.equals(levelSha) && Files.isRegularFile(root.resolve(e.zip))) hit = e;
        return hit;
    }

    /** Back up worldDir unless an identical backup (same fingerprint, same level.dat) exists. Never throws. */
    public Result backup(Path worldDir, String fingerprint, String reason) {
        Result r = new Result();
        String world = worldDir.getFileName().toString();
        try {
            String levelSha = sha256(worldDir.resolve("level.dat"));
            Entry same = sameAs(world, fingerprint, levelSha);
            if (same != null) {
                r.skippedSame = true;
                r.zip = root.resolve(same.zip);
                return r;
            }
            long size = folderSize(worldDir);
            long free = freeSpace.getAsLong();
            if (free < size + MARGIN) {
                r.warning = "not enough free disk space for a backup of " + world + " (needs about " + mb(size + MARGIN) + ", " + mb(free) + " free)";
                return r;
            }
            Files.createDirectories(root);
            String base = safeName(world) + "-" + LocalDateTime.now().format(STAMP);
            Path zip = root.resolve(base + ".zip");
            for (int i = 2; Files.exists(zip); i++) zip = root.resolve(base + "-" + i + ".zip");
            Path part = zip.resolveSibling(zip.getFileName() + ".part");
            Map<String, String> sums = new TreeMap<>();
            long[] bytes = {0};
            try {
                write(worldDir, world, part, sums, bytes);
                verify(part, sums);
                Files.move(part, zip, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(part);
            }
            r.made = true;
            r.zip = zip;
            r.files = sums.size();
            r.bytes = bytes[0];
            appendIndex(new Entry(LocalDateTime.now().format(STAMP), world, zip.getFileName().toString(), r.files, r.bytes, sha256(zip), levelSha, fingerprint, reason));
            prune(world);
        } catch (Exception e) {
            r.warning = "backup of " + world + " failed: " + e;
        }
        return r;
    }

    static String mb(long b) { return (b >> 20) + " MB"; }

    static String safeName(String s) {
        String t = s.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        return t.isEmpty() ? "world" : t;
    }

    static long folderSize(Path dir) throws IOException {
        long[] n = {0};
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) { n[0] += a.size(); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
        });
        return n[0];
    }

    static boolean skip(Path rel) {
        String n = rel.getFileName().toString();
        return n.equals("session.lock") || n.endsWith(".alloy-tmp");
    }

    /** Zip worldDir as &lt;world&gt;/... entries; sums gets entry name -&gt; SHA-256 of the bytes written. */
    static void write(Path worldDir, String world, Path zip, Map<String, String> sums, long[] bytes) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var s = Files.walk(worldDir)) { s.filter(Files::isRegularFile).forEach(files::add); }
        files.sort(Comparator.naturalOrder());
        byte[] buf = new byte[1 << 16];
        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip)))) {
            for (Path f : files) {
                Path rel = worldDir.relativize(f);
                if (skip(rel)) continue;
                String name = world + "/" + rel.toString().replace('\\', '/');
                MessageDigest md = sha();
                ZipEntry e = new ZipEntry(name);
                e.setTime(Files.getLastModifiedTime(f).toMillis());
                z.putNextEntry(e);
                try (InputStream in = Files.newInputStream(f)) {
                    for (int n; (n = in.read(buf)) > 0; ) { md.update(buf, 0, n); z.write(buf, 0, n); bytes[0] += n; }
                } catch (IOException ex) {
                    // a file the game holds open (rare outside session.lock): leave it out, say so in the zip
                    z.closeEntry();
                    z.putNextEntry(new ZipEntry(name + ".alloy-unreadable.txt"));
                    z.write(("not readable: " + ex).getBytes(StandardCharsets.UTF_8));
                    z.closeEntry();
                    continue;
                }
                z.closeEntry();
                sums.put(name, hex(md.digest()));
            }
            ZipEntry m = new ZipEntry(world + "/alloy-backup-sha256.txt");
            z.putNextEntry(m);
            StringBuilder sb = new StringBuilder("# SHA-256 of every file in this backup, written by Alloy\n");
            sums.forEach((k, v) -> sb.append(v).append("  ").append(k).append('\n'));
            z.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
    }

    /** Read the zip back; every listed entry must be there with the same SHA-256. */
    static void verify(Path zip, Map<String, String> sums) throws IOException {
        Map<String, String> got = new HashMap<>();
        byte[] buf = new byte[1 << 16];
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                MessageDigest md = sha();
                for (int n; (n = z.read(buf)) > 0; ) md.update(buf, 0, n);
                got.put(e.getName(), hex(md.digest()));
            }
        }
        for (var e : sums.entrySet())
            if (!e.getValue().equals(got.get(e.getKey()))) throw new IOException("backup check failed for " + e.getKey());
    }

    void appendIndex(Entry e) throws IOException {
        Path f = root.resolve(INDEX);
        boolean head = !Files.exists(f);
        String text = (head ? "# time\tworld\tzip\tfiles\tbytes\tzip sha256\tlevel.dat sha256\tfingerprint\treason\n" : "") + e.line() + "\n";
        Files.writeString(f, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Delete the oldest backups of one world beyond keep, and rewrite the index without them. */
    void prune(String world) throws IOException {
        if (keep < 1) return;
        List<Entry> all = index();
        List<Entry> mine = new ArrayList<>();
        for (Entry e : all) if (e.world.equals(world)) mine.add(e);
        if (mine.size() <= keep) return;
        Set<Entry> drop = new HashSet<>(mine.subList(0, mine.size() - keep));
        for (Entry e : drop) Files.deleteIfExists(root.resolve(e.zip));
        StringBuilder sb = new StringBuilder("# time\tworld\tzip\tfiles\tbytes\tzip sha256\tlevel.dat sha256\tfingerprint\treason\n");
        for (Entry e : all) if (!drop.contains(e)) sb.append(e.line()).append('\n');
        Path f = root.resolve(INDEX), tmp = root.resolve(INDEX + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
    }

    static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String sha256(Path f) throws IOException {
        if (!Files.isRegularFile(f)) return "-";
        MessageDigest md = sha();
        byte[] buf = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(f)) { for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n); }
        return hex(md.digest());
    }

    public static String sha256(String s) {
        return hex(sha().digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }
}
