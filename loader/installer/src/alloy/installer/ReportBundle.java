package alloy.installer;

import alloy.report.LogDiagnosis;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Crash report bundle for testers (java -jar alloy-installer.jar --report [--game DIR] [--out FILE] [--print]):
 * one zip with the logs, the newest crash report, hs_err files, the mod list with SHA-256, Alloy, Java and PC facts,
 * Mod Doctor's output and the crash diagnosis. Every text in it goes through {@link Redact} first.
 *
 * It opens only a fixed list of files (logs/latest.log, logs/debug.log, crash-reports/*.txt, hs_err_pid*.log,
 * alloy-report.txt, .alloy/prep.properties, the jars in mods/ and forgemods/), and never a file that
 * {@link Redact#isSecretFile} names (launcher accounts, tokens, options.txt). Nothing is uploaded: the tester sends the zip.
 */
public final class ReportBundle {
    static final long LOG_MAX = 8L << 20, DEBUG_TAIL = 4L << 20, CRASH_MAX = 4L << 20;
    static final int HS_ERR_MAX = 2;

    /** the bundle before it is written: published path -> redacted text, plus the summary */
    public static final class Bundle {
        public final Path game;
        public final LinkedHashMap<String, String> texts = new LinkedHashMap<>();
        public LogDiagnosis.Result diagnosis;
        public String diagnosedFile = "";
        Bundle(Path game) { this.game = game; }

        /** everything a tester sees before saving: each file with a header */
        public String preview(int perFile) {
            StringBuilder b = new StringBuilder();
            for (var e : texts.entrySet()) {
                String v = e.getValue();
                b.append("===== ").append(e.getKey()).append(" (").append(v.length()).append(" characters) =====\n");
                b.append(v.length() > perFile ? v.substring(0, perFile) + "\n[... " + (v.length() - perFile) + " more characters in the file ...]\n" : v);
                if (!v.endsWith("\n")) b.append('\n');
                b.append('\n');
            }
            return b.toString();
        }
    }

    public static int cli(String[] args) {
        Path game = null, out = null;
        boolean print = false;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--game" -> { if (i + 1 < args.length) game = Path.of(args[++i]); }
                    case "--out" -> { if (i + 1 < args.length) out = Path.of(args[++i]); }
                    case "--print" -> print = true;
                    default -> { if (game == null && !args[i].startsWith("--")) game = Path.of(args[i]); else { usage(); return 2; } }
                }
            }
        } catch (InvalidPathException e) {
            System.err.println("[alloy] --report: not a valid path: " + e.getInput());
            return 2;
        }
        try {
            Path g = gameDir(game == null ? Path.of("").toAbsolutePath() : game);
            if (g == null) {
                System.err.println("[alloy] --report: " + (game == null ? "this folder" : game) + " has no logs/, crash-reports/ or mods/ folder. Pass the game folder with --game DIR.");
                return 2;
            }
            Bundle b = build(g);
            if (out == null) out = g.resolve(defaultName());
            write(b, out);
            if (print) System.out.println(b.preview(20_000));
            System.out.println(b.texts.get("report.txt"));
            System.out.println("[alloy] crash report bundle written: " + out.toAbsolutePath());
            System.out.println("[alloy] Personal data (login token, account ids, player and user names, e-mails, IP addresses) was removed."
                    + " Open the zip and check it before you send it. Send it privately, not on a public paste site.");
            return 0;
        } catch (Exception e) {
            System.err.println("[alloy] --report failed: " + e);
            if (System.getProperty("alloy.debug") != null) e.printStackTrace();
            return 1;
        }
    }

    static void usage() {
        System.err.println("usage: --report [--game GAMEDIR] [--out FILE.zip] [--print]   (default GAMEDIR: this folder, default FILE: GAMEDIR/alloy-report-<time>.zip)");
    }

    static String defaultName() {
        return "alloy-report-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now()) + ".zip";
    }

    /** the game folder: DIR itself, or the .minecraft / minecraft folder inside a Prism or MultiMC instance folder */
    public static Path gameDir(Path dir) {
        Path d = dir.toAbsolutePath().normalize();
        for (Path c : List.of(d, d.resolve(".minecraft"), d.resolve("minecraft")))
            if (Files.isDirectory(c.resolve("logs")) || Files.isDirectory(c.resolve("crash-reports")) || Files.isDirectory(c.resolve("mods"))) return c;
        return null;
    }

    public static Bundle build(Path game) throws IOException {
        Bundle b = new Bundle(game);
        Map<String, String> raw = new LinkedHashMap<>();   // path in the zip -> text before redaction
        Map<String, Long> when = new HashMap<>();

        Path latest = game.resolve("logs/latest.log");
        if (readable(latest)) { raw.put("logs/latest.log", headTail(latest, LOG_MAX)); when.put("logs/latest.log", mtime(latest)); }
        Path debug = game.resolve("logs/debug.log");
        if (readable(debug)) raw.put("logs/debug.log", tail(debug, DEBUG_TAIL));
        Path crash = newest(game.resolve("crash-reports"), n -> n.endsWith(".txt"), 1).stream().findFirst().orElse(null);
        if (crash != null) { String k = "crash-reports/" + crash.getFileName(); raw.put(k, headTail(crash, CRASH_MAX)); when.put(k, mtime(crash)); }
        List<Path> hs = new ArrayList<>();
        for (Path d : new LinkedHashSet<>(Arrays.asList(game, game.getParent())))
            if (d != null) hs.addAll(newest(d, n -> n.startsWith("hs_err_pid") && n.endsWith(".log"), HS_ERR_MAX));
        hs.sort(Comparator.comparingLong(ReportBundle::mtime).reversed());
        for (Path p : hs.subList(0, Math.min(HS_ERR_MAX, hs.size()))) {
            String k = "hs_err/" + p.getFileName();
            raw.put(k, headTail(p, CRASH_MAX));
            when.put(k, mtime(p));
        }
        Path ar = game.resolve("alloy-report.txt");
        if (readable(ar)) raw.put("alloy-report.txt", headTail(ar, CRASH_MAX));

        // diagnosis: the newest crash text (crash report, hs_err, or latest.log), read by the same rules as the game
        String diagKey = when.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        if (diagKey != null && diagKey.equals("logs/latest.log") && crash != null && when.get("logs/latest.log") - when.get("crash-reports/" + crash.getFileName()) < 120_000)
            diagKey = "crash-reports/" + crash.getFileName();   // the log written right after the crash: the report has the cause
        if (diagKey != null) {
            b.diagnosedFile = diagKey;
            b.diagnosis = LogDiagnosis.diagnose(LogDiagnosis.cut(raw.get(diagKey)));
        }
        StringBuilder diag = new StringBuilder();
        for (var e : raw.entrySet()) {
            if (e.getKey().equals("alloy-report.txt")) continue;
            LogDiagnosis.Result r = e.getKey().equals(diagKey) ? b.diagnosis : LogDiagnosis.diagnose(LogDiagnosis.cut(e.getValue()));
            diag.append(e.getKey()).append(e.getKey().equals(diagKey) ? "  (the newest, used for the summary)" : "").append('\n');
            for (String l : r.lines()) diag.append("  ").append(l).append('\n');
            diag.append('\n');
        }

        String doctor = doctor(game);
        String mods = mods(game);
        String facts = facts(game, raw);

        Redact red = new Redact();
        for (String s : raw.values()) red.learn(s);
        red.learn(doctor).learn(facts).learn(game.toString());

        StringBuilder rep = new StringBuilder();
        rep.append("Alloy crash report bundle, made ").append(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(java.time.LocalDateTime.now().withNano(0))).append("\n\n");
        if (b.diagnosis != null) {
            rep.append("Diagnosis (").append(diagKey).append("):\n");
            for (String l : b.diagnosis.lines()) rep.append("  ").append(l).append('\n');
        } else rep.append("Diagnosis: no log or crash report found in this game folder.\n");
        rep.append('\n').append(facts).append('\n');
        rep.append("Files in this bundle:\n");
        List<String> names = new ArrayList<>(List.of("report.txt", "report.json", "diagnosis.txt", "mods.txt", "doctor.txt"));
        names.addAll(raw.keySet());
        for (String n : names) rep.append("  ").append(n).append('\n');
        rep.append("\nPersonal data was removed from every file: the login token and the account values of the game's command line\n"
                + "(accessToken, uuid, xuid, clientId, username), session ids, other tokens, the user folder name in paths, the player name,\n"
                + "e-mail addresses and IP addresses.\n"
                + "Launcher account files, options.txt and server lists are never read.\n");

        b.texts.put("report.txt", red.apply(rep.toString()));
        b.texts.put("report.json", red.apply(json(b, facts, raw.keySet())));
        b.texts.put("diagnosis.txt", red.apply(diag.toString()));
        b.texts.put("mods.txt", red.apply(mods));
        b.texts.put("doctor.txt", red.apply(doctor));
        for (var e : raw.entrySet()) b.texts.put(e.getKey(), red.apply(e.getValue()));
        return b;
    }

    public static void write(Bundle b, Path out) throws IOException {
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(tmp))) {
            for (var e : b.texts.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
    }

    // ------------------------------------------------------------------ parts

    /** Alloy, Minecraft, Java, OS and RAM facts (plain lines "Name: value") */
    static String facts(Path game, Map<String, String> raw) {
        StringBuilder f = new StringBuilder();
        Properties prep = new Properties();
        Path pf = game.resolve(".alloy/prep.properties");
        if (readable(pf)) try (Reader r = Files.newBufferedReader(pf, StandardCharsets.UTF_8)) { prep.load(r); } catch (IOException ignored) { }
        String mc = prep.getProperty("minecraft", "");
        f.append("Alloy installer: ").append(Main.ALLOY_VERSION).append(", jar SHA-256 ").append(selfHash()).append('\n');
        f.append("Minecraft: ").append(mc.isEmpty() ? "(not recorded; older install or not an Alloy folder)" : mc)
                .append(", side ").append(prep.getProperty("side", "?")).append('\n');
        String inst = "?", game1 = "missing";
        // the bridge this installer has for that version (same lookup as Installer.bridgeJar, without switching the selected version)
        try (InputStream in = Installer.class.getResourceAsStream(!mc.isEmpty() && Installer.class.getResource("/bridges/" + mc + "/alloy-bridge.jar") != null
                ? "/bridges/" + mc + "/alloy-bridge.jar" : "/alloy-bridge.jar")) {
            inst = in == null ? "(none: not run from an installer jar)" : Util.hex(java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        } catch (Exception e) { inst = "? (" + e.getMessage() + ")"; }
        Path bj = game.resolve("mods/alloy-bridge.jar");
        if (Files.isRegularFile(bj)) try { game1 = Util.digest(bj, "SHA-256"); } catch (IOException e) { game1 = "unreadable"; }
        f.append("Alloy bridge in mods/: ").append(game1).append(game1.equals(inst) ? " (same as this installer's)" : inst.length() == 64 && game1.length() == 64 ? " (differs from this installer's " + inst + ")" : "").append('\n');
        if (!game1.equals(inst)) f.append("Alloy bridge in this installer: ").append(inst).append('\n');
        String gameJava = firstGroup(raw, "(?m)^\\s*Java Version: (.+)$", "(?m)^# JRE version: (.+)$", "(?m)Java is (.+?), running");
        f.append("Java of the game: ").append(gameJava == null ? "(not in the logs)" : gameJava.trim()).append('\n');
        String xmx = firstGroup(raw, "(-Xmx\\d+[kKmMgG]?)");
        if (xmx != null) f.append("Game memory setting: ").append(xmx).append('\n');
        f.append("Java of this installer: ").append(System.getProperty("java.version")).append(" (").append(System.getProperty("java.vendor")).append(")\n");
        f.append("OS: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append(' ').append(System.getProperty("os.arch")).append('\n');
        try {
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            f.append("RAM: ").append(os.getTotalMemorySize() >> 20).append(" MB total, ").append(os.getFreeMemorySize() >> 20).append(" MB free now\n");
        } catch (Throwable t) { f.append("RAM: unknown\n"); }
        f.append("CPU threads: ").append(Runtime.getRuntime().availableProcessors()).append('\n');
        f.append("Game folder: ").append(game).append('\n');
        return f.toString();
    }

    static String selfHash() {
        try {
            Path p = Util.selfJar();
            return Files.isRegularFile(p) ? Util.digest(p, "SHA-256") : "(not run from a jar)";
        } catch (Exception e) { return "?"; }
    }

    static String firstGroup(Map<String, String> raw, String... rx) {
        for (String r : rx) {
            Pattern p = Pattern.compile(r);
            for (String s : raw.values()) {
                Matcher m = p.matcher(s);
                if (m.find()) return m.group(1);
            }
        }
        return null;
    }

    /** mods/ and forgemods/: folder, file, size, SHA-256 */
    static String mods(Path game) {
        StringBuilder b = new StringBuilder("# folder\tfile\tbytes\tsha256\n");
        int n = 0;
        for (String dir : List.of("mods", "forgemods")) {
            Path d = game.resolve(dir);
            if (!Files.isDirectory(d)) { b.append("# ").append(dir).append("/ not found\n"); continue; }
            List<Path> jars = new ArrayList<>();
            try (var st = Files.list(d)) { st.filter(p -> { String x = p.getFileName().toString().toLowerCase(Locale.ROOT); return x.endsWith(".jar") || x.endsWith(".jar.disabled") || x.endsWith(".zip"); })
                    .filter(p -> !Redact.isSecretFile(p)).sorted().forEach(jars::add); }
            catch (IOException e) { b.append("# ").append(dir).append("/ unreadable: ").append(e.getMessage()).append('\n'); continue; }
            for (Path p : jars) {
                String h;
                long size = -1;
                try { size = Files.size(p); h = Util.digest(p, "SHA-256"); } catch (IOException e) { h = "unreadable"; }
                b.append(dir).append('\t').append(p.getFileName()).append('\t').append(size).append('\t').append(h).append('\n');
                n++;
            }
        }
        b.append("# ").append(n).append(" file(s)\n");
        return b.toString();
    }

    /** Mod Doctor's text output for the game folder (its side from .alloy/prep.properties) */
    static String doctor(Path game) {
        if (!Files.isDirectory(game.resolve("mods")) && !Files.isDirectory(game.resolve("forgemods"))) return "Mod Doctor: no mods/ or forgemods/ folder.\n";
        PrintStream old = System.out, oldErr = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int rc;
        String selected = Installer.MC;   // Mod Doctor checks the game folder's version; the GUI's choice is put back afterwards
        try (PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8)) {
            System.setOut(ps);
            System.setErr(ps);
            Installer.selectFor(game);
            List<String> a = new ArrayList<>(List.of(game.toString()));
            String side = Main.installedSide(game);
            if (side != null) a.addAll(List.of("--side", side));
            rc = alloy.ideas.ModDoctor.cli(a.toArray(new String[0]));
        } catch (Throwable t) {
            rc = -1;
            buf.writeBytes(("\nMod Doctor stopped: " + t + "\n").getBytes(StandardCharsets.UTF_8));
        } finally {
            System.setOut(old);
            System.setErr(oldErr);
            try { if (selected != null && Installer.supports(selected)) Installer.select(selected); } catch (IOException ignored) { }
        }
        return buf.toString(StandardCharsets.UTF_8) + "\n(exit code " + rc + ": 0 ok, 1 warnings, 2 blockers)\n";
    }

    static String json(Bundle b, String facts, Collection<String> files) {
        StringBuilder j = new StringBuilder("{\n  \"format\": 1,\n");
        j.append("  \"facts\": {");
        String sep = "\n";
        for (String l : facts.split("\n")) {
            int c = l.indexOf(": ");
            if (c < 0) continue;
            j.append(sep).append("    ").append(q(l.substring(0, c))).append(": ").append(q(l.substring(c + 2)));
            sep = ",\n";
        }
        j.append("\n  },\n");
        if (b.diagnosis != null) {
            LogDiagnosis.Result r = b.diagnosis;
            j.append("  \"diagnosis\": {\"file\": ").append(q(b.diagnosedFile)).append(", \"kind\": ").append(q(r.kind().name()))
                    .append(", \"signature\": ").append(q(r.signature())).append(", \"mods\": [");
            for (int i = 0; i < r.mods().size(); i++) j.append(i == 0 ? "" : ", ").append(q(r.mods().get(i)));
            j.append("], \"line\": ").append(q(r.line())).append(", \"fix\": ").append(q(r.fix())).append(", \"also\": [");
            for (int i = 0; i < r.also().size(); i++) {
                LogDiagnosis.Hit h = r.also().get(i);
                j.append(i == 0 ? "\n" : ",\n").append("    {\"signature\": ").append(q(h.id())).append(", \"note\": ").append(h.note())
                        .append(", \"cause\": ").append(q(h.cause())).append(", \"fix\": ").append(q(h.fix())).append('}');
            }
            j.append(r.also().isEmpty() ? "]},\n" : "\n  ]},\n");
        }
        j.append("  \"files\": [");
        int i = 0;
        for (String f : files) j.append(i++ == 0 ? "" : ", ").append(q(f));
        return j.append("]\n}\n").toString();
    }

    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    // ------------------------------------------------------------------ file reading (only through these)

    static boolean readable(Path p) { return p != null && !Redact.isSecretFile(p) && Files.isRegularFile(p) && Files.isReadable(p); }

    static long mtime(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0; }
    }

    /** the newest {@code n} files in dir whose lower-case name passes {@code ok} (no sub folders) */
    static List<Path> newest(Path dir, java.util.function.Predicate<String> ok, int n) {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        try (var st = Files.list(dir)) {
            return st.filter(p -> ok.test(p.getFileName().toString().toLowerCase(Locale.ROOT)) && readable(p))
                    .sorted(Comparator.comparingLong(ReportBundle::mtime).reversed()).limit(n).toList();
        } catch (IOException e) { return List.of(); }
    }

    /** the whole file, or its first quarter and last three quarters of {@code max} bytes */
    static String headTail(Path p, long max) throws IOException {
        long size = Files.size(p);
        if (size <= max) return text(Files.readAllBytes(p));
        long head = max / 4, tail = max - head;
        try (var ch = Files.newByteChannel(p, StandardOpenOption.READ)) {
            byte[] a = read(ch, 0, (int) head), b = read(ch, size - tail, (int) tail);
            return text(a) + "\n[... " + (size - head - tail) + " bytes left out by the Alloy report ...]\n" + text(b);
        }
    }

    static String tail(Path p, long max) throws IOException {
        long size = Files.size(p);
        if (size <= max) return text(Files.readAllBytes(p));
        try (var ch = Files.newByteChannel(p, StandardOpenOption.READ)) {
            String s = text(read(ch, size - max, (int) max));
            int nl = s.indexOf('\n');
            return "[... first " + (size - max) + " bytes left out by the Alloy report ...]\n" + (nl >= 0 ? s.substring(nl + 1) : s);
        }
    }

    private static byte[] read(java.nio.channels.SeekableByteChannel ch, long at, int n) throws IOException {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(n);
        ch.position(at);
        while (bb.hasRemaining() && ch.read(bb) > 0) { }
        return Arrays.copyOf(bb.array(), bb.position());
    }

    private static String text(byte[] b) { return new String(b, StandardCharsets.UTF_8).replace("\r\n", "\n"); }

    static String when(long ms) { return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime().withNano(0).toString().replace('T', ' '); }
}
