package alloy.installer;

import alloy.ideas.ModMeta;
import alloy.report.LogDiagnosis;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crash recovery before launch. When the last start crashed during startup or while a world was loading, the next launch
 * (--prep, --launch-prep, --server-start; before FML and Fabric start) reads the crash report or logs/latest.log, lets
 * {@link LogDiagnosis} name the mod(s) behind it, and offers a way out instead of crashing the same way again:
 *   client with a screen: a small window (RecoveryWindow) with "Start without MOD", "Try the other loader's build of MOD"
 *     (when the pack import kept one, .alloy/alternates.tsv), "Start normally anyway" and "Open crash report";
 *   server or no screen: one log line with the -Dalloy.recover=disable:MODID flag to use.
 * Nothing is switched off without that click (or the flag, or --disable-mod). Mods are never deleted: the player's jar
 * moves to .alloy/disabled/FOLDER/, Alloy's own derived copies of it (fitted, translated) to .alloy/disabled/.derived/,
 * and .alloy/disabled/disabled.tsv lists them. --restore-disabled GAMEDIR [MOD] puts them back; the next prep redoes the
 * derived copies. A world that used a switched-off mod still gets Alloy's backup and missing-mod warning (WorldSafe), as
 * the mod really is absent then.
 *
 *   -Dalloy.recover=off               no check at all
 *   -Dalloy.recover=ignore            start normally, do not ask again for this crash
 *   -Dalloy.recover=disable:ID[,ID]   switch those mods off (and the mods that need them), then start
 *   -Dalloy.recover=restore           put every switched-off mod back, then start
 * Test only: -Dalloy.recover.testClick=disable|alternate|normal|open (RecoveryWindow clicks that button itself after
 * -Dalloy.recover.testShot=PNG was written), -Dalloy.recover.noWindow=true (log line even with a screen).
 */
final class CrashRecovery {
    private CrashRecovery() { }

    enum Phase { STARTUP, WORLD_LOAD, PLAY, UNKNOWN }

    enum Confidence { HIGH, MEDIUM, LOW }

    /** the crash that is looked at: the file, its time, its text, the log of the same start (or null) */
    record Evidence(Path file, long time, String text, String log, Phase phase, String key) { }

    /** a mod jar in mods/ or forgemods/: {@code path} is what the game loaded, {@code user} the player's own file behind it */
    record Jar(String folder, String name, Path path, Path user, List<String> ids, List<String> names, ModMeta.JarInfo info) {
        String id() { return ids.isEmpty() ? name : ids.get(0); }
        String title() { return names.isEmpty() ? id() : names.get(0); }
        /** the player's file name (a fitted copy shows as the jar the player put in mods/) */
        String fileName() { return folder.equals("mods") ? user.getFileName().toString() : name; }
        boolean forge() { return folder.equals("forgemods") || info != null && info.loaders.contains(ModMeta.Loader.FORGE) && !info.loaders.contains(ModMeta.Loader.FABRIC); }
    }

    /** the other loader's build of a mod, kept by the pack import in .alloy/alternates/FOLDER/FILE */
    record Alternate(String id, String loader, String folder, String file, Path stored) {
        String loaderName() { return loader.equals("forge") ? "Forge" : "Fabric"; }
    }

    record Culprit(String label, Jar jar, Confidence confidence, List<Jar> dependents, Alternate alternate) {
        boolean canDisable() { return dependents.size() <= MAX_DEPENDENTS; }
    }

    /** what recovery found; {@code culprits} empty = nothing to offer ({@code why} says why) */
    record Plan(Evidence ev, LogDiagnosis.Result diagnosis, List<Culprit> culprits, String why) {
        boolean offer() { return !culprits.isEmpty(); }
    }

    static final int MAX_DEPENDENTS = 8, MAX_CULPRITS = 2;
    static final long WEEK = 7L * 24 * 3600 * 1000;
    static final String DISABLED = ".alloy/disabled", INDEX = DISABLED + "/disabled.tsv", STATE = ".alloy/recovery.properties",
            ALTERNATES = ".alloy/alternates.tsv";

    // ------------------------------------------------------------------ evidence

    /** a fatal line in latest.log of a start that never wrote a crash report (Fabric's resolution error, wrong Java) */
    static final List<String> LOG_FATAL = List.of("Incompatible mods found", "Some of your mods are incompatible", "UnsupportedClassVersionError",
            "Alloy could not start", "Alloy needs Java", "net.fabricmc.loader.impl.FormattedException");

    /**
     * The newest crash report, or logs/latest.log when Fabric stopped before any crash report (as Mod Doctor's "Last start
     * failed" line). Null when there is none, it is older than 7 days, or a later start got further (a newer latest.log
     * without a fatal line).
     */
    static Evidence evidence(Path game, long now) {
        try {
            Path newest = null;
            long t = 0;
            Path cr = game.resolve("crash-reports");
            if (Files.isDirectory(cr)) try (var st = Files.list(cr)) {
                for (Path p : st.filter(x -> x.getFileName().toString().endsWith(".txt") && Files.isRegularFile(x)).toList()) {
                    long m = Files.getLastModifiedTime(p).toMillis();
                    if (m > t) { t = m; newest = p; }
                }
            }
            Path log = game.resolve("logs/latest.log");
            String logText = null;
            boolean logOnly = false;
            if (Files.isRegularFile(log)) {
                long lm = Files.getLastModifiedTime(log).toMillis();
                if (lm > t + 60_000) {   // a start after the newest crash report
                    String s = headTail(log);   // every launch: the start (resolution) and the end (the stop) are enough
                    if (LOG_FATAL.stream().noneMatch(s::contains)) return null;   // it got further than that crash
                    newest = log; t = lm; logText = read(log); logOnly = true;
                } else if (newest != null && lm >= t - 5_000) logText = read(log);   // the log of the start that crashed
            }
            if (newest == null || now - t > WEEK) return null;
            String text = logOnly ? logText : read(newest);
            Phase ph = phase(logOnly ? null : text, logText, logOnly, t);
            return new Evidence(newest, t, text, logOnly ? null : logText, ph, key(newest));
        } catch (IOException e) {
            return null;
        }
    }

    static String key(Path f) throws IOException {
        return f.getFileName() + "|" + Files.getLastModifiedTime(f).toMillis() + "|" + Files.size(f);
    }

    static String read(Path p) throws IOException {
        return LogDiagnosis.cut(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
    }

    /** the first 64 KB and the last 256 KB of a file */
    static String headTail(Path p) throws IOException {
        long size = Files.size(p);
        if (size <= 320 * 1024) return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        try (var ch = java.nio.channels.FileChannel.open(p, StandardOpenOption.READ)) {
            java.nio.ByteBuffer a = java.nio.ByteBuffer.allocate(64 * 1024), b = java.nio.ByteBuffer.allocate(256 * 1024);
            ch.read(a, 0);
            ch.read(b, size - b.capacity());
            return new String(a.array(), 0, a.position(), StandardCharsets.UTF_8) + "\n" + new String(b.array(), 0, b.position(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ phase

    /** crash report descriptions of a crash before the game reached its menu or a world */
    static final Set<String> STARTUP_DESC = Set.of("Initializing game", "Bootstrap", "Rendering overlay", "Loading screen", "Argument parsing",
            "Mod loading error has occurred", "Mod loading has failed", "Loading Minecraft", "Mod loading failures have occurred");
    /** descriptions of a crash while a world was being opened */
    static final Set<String> WORLD_DESC = Set.of("Starting integrated server", "Loading level", "Exception initializing level",
            "Preparing spawn area", "Loading NBT data", "Exception while loading world", "Loading world");
    private static final Pattern DESC = Pattern.compile("(?m)^Description: (.+?)\\s*$");
    private static final Pattern TIME = Pattern.compile("(?m)^Time: (\\d{4}-\\d\\d-\\d\\d \\d\\d?:\\d\\d:\\d\\d)");
    /** a log line's time of day: [12:34:56] (vanilla, Fabric) or [16Nov2026 12:34:56.789] (Forge) */
    private static final Pattern LOG_TIME = Pattern.compile("^\\[(?:[^\\]\\n]*?)(\\d\\d):(\\d\\d):(\\d\\d)(?:\\.\\d+)?\\] \\[");
    /** a world is open: a player joined (client's integrated server, or a server), or a dedicated server is up */
    private static final Pattern IN_WORLD = Pattern.compile("(?m)^\\[[^\\]\\n]*\\] \\[[^\\]\\n]*/INFO\\][^\\n]*?: (?:\\S+ joined the game|Done \\(\\d)");
    private static final Pattern OPENING = Pattern.compile("(?m)^\\[[^\\]\\n]*\\] \\[[^\\]\\n]*/INFO\\][^\\n]*?: (?:Starting integrated minecraft server|Preparing start region|Preparing level|Loading \\d+ recipes|Starting minecraft server)");
    /** seconds after the world opened that still count as "while the world was loading" */
    static final int WORLD_GRACE = 120;

    static String description(String crash) {
        if (crash == null) return null;
        Matcher m = DESC.matcher(crash);
        return m.find() ? m.group(1) : null;
    }

    /**
     * When the crash happened: STARTUP (before the menu or a world), WORLD_LOAD (while a world opened, or within
     * WORLD_GRACE seconds after a player joined), PLAY (later), UNKNOWN (no log and an unclear description).
     * {@code crash} is the crash report text (null when the evidence is latest.log only), {@code log} latest.log of that start.
     */
    static Phase phase(String crash, String log, boolean logOnly, long crashMillis) {
        if (logOnly) return Phase.STARTUP;   // Fabric or Alloy stopped the start before any window
        String desc = description(crash);
        if (log != null) {
            Matcher w = IN_WORLD.matcher(log);
            int joinAt = -1;
            while (w.find()) joinAt = w.start();
            if (joinAt < 0) {
                if (desc != null && WORLD_DESC.contains(desc)) return Phase.WORLD_LOAD;
                if (OPENING.matcher(log).find()) return Phase.WORLD_LOAD;
                return Phase.STARTUP;   // never got into a world: the menu at most
            }
            int join = secondsOfDay(log, joinAt), at = crashSecond(crash, crashMillis);
            if (join < 0 || at < 0) return Phase.PLAY;
            int dt = ((at - join) % 86400 + 86400) % 86400;
            return dt <= WORLD_GRACE ? Phase.WORLD_LOAD : Phase.PLAY;
        }
        if (desc != null && STARTUP_DESC.contains(desc)) return Phase.STARTUP;
        if (desc != null && WORLD_DESC.contains(desc)) return Phase.WORLD_LOAD;
        return Phase.UNKNOWN;
    }

    static int secondsOfDay(String log, int lineStart) {
        Matcher m = LOG_TIME.matcher(log.substring(lineStart, Math.min(log.length(), lineStart + 80)));
        if (!m.find()) return -1;
        return Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3));
    }

    /** the crash's time of day: the report's "Time:" line, else the file time */
    static int crashSecond(String crash, long crashMillis) {
        if (crash != null) {
            Matcher m = TIME.matcher(crash);
            if (m.find()) {
                try {
                    LocalDateTime t = LocalDateTime.parse(m.group(1), DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm:ss"));
                    return t.toLocalTime().toSecondOfDay();
                } catch (RuntimeException ignored) { }
            }
        }
        if (crashMillis <= 0) return -1;
        return Instant.ofEpochMilli(crashMillis).atZone(ZoneId.systemDefault()).toLocalTime().toSecondOfDay();
    }

    // ------------------------------------------------------------------ diagnosis -> culprits

    /** kinds where switching the named mod off usually lets the game start; first value: confidence when named plainly */
    static Confidence confidence(LogDiagnosis.Kind k) {
        switch (k) {
            case MIXIN_CONFLICT: case FAILS_ON_REAL_LOADER: case NOT_SUPPORTED_ON_ALLOY: case NEOFORGE_MOD: case CLIENT_ONLY_ON_SERVER:
            case WRONG_VERSION: case INCOMPATIBLE_MODS:
                return Confidence.HIGH;
            case MOD_CRASH: case FROZEN_REGISTRY: case MISSING_DEPENDENCY: case REGISTRY_ENTRY: case BAD_BYTECODE: case CLASSLOADER_CLASH:
            case WORLDGEN: case DATA_ERROR: case DUPLICATE_MOD:
                return Confidence.MEDIUM;
            default:   // Java, memory, Alloy bugs, broken installs, configs, drivers, the PC: switching a mod off does not help
                return null;
        }
    }

    /** ids that are never switched off: the game, the loaders, Fabric API, Alloy */
    static boolean platform(String id) {
        String i = id.toLowerCase(Locale.ROOT);
        return i.equals("minecraft") || i.equals("forge") || i.equals("fml") || i.equals("javafml") || i.equals("fabricloader") || i.equals("fabric-loader")
                || i.equals("java") || i.equals("fabric") || i.equals("fabric-api") || i.equals("fabric_api") || i.equals("mixinextras")
                || i.startsWith("alloy") || i.startsWith("fabric-api-") || i.matches("fabric-[a-z-]+-v\\d+");
    }

    static boolean platformJar(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return ModMeta.isAlloyGenerated(n) || n.startsWith("fabric-api-") || n.startsWith("fabric-api.") || n.startsWith("forge-") || n.startsWith("fabric-loader");
    }

    static Plan decide(Path game, long now) {
        Evidence ev = evidence(game, now);
        if (ev == null) return null;
        return decide(game, ev);
    }

    static Plan decide(Path game, Evidence ev) {
        LogDiagnosis.Result r = LogDiagnosis.diagnose(ev.text());
        if (!r.specific() && ev.log() != null) {   // the crash report says little: the log of that start may say more
            LogDiagnosis.Result l = LogDiagnosis.diagnose(ev.log());
            if (l.specific()) r = l;
        }
        if (ev.phase() == Phase.PLAY || ev.phase() == Phase.UNKNOWN)
            return new Plan(ev, r, List.of(), ev.phase() == Phase.PLAY ? "the game crashed while playing, not while starting or loading a world" : "not clear when the game crashed");
        Confidence base = confidence(r.kind());
        if (base == null) return new Plan(ev, r, List.of(), "switching a mod off does not help with this kind of crash (" + r.kind() + ")");
        List<Jar> jars;
        try { jars = index(game); } catch (IOException e) { return new Plan(ev, r, List.of(), "mods/ and forgemods/ could not be read (" + e + ")"); }
        long changed = 0;
        for (Jar j : jars) try { changed = Math.max(changed, Files.getLastModifiedTime(j.user()).toMillis()); } catch (IOException ignored) { }
        if (changed > ev.time() + 2_000) return new Plan(ev, r, List.of(), "mods were added or changed since that crash");
        List<String> names = new ArrayList<>(r.mods());
        if (r.kind() == LogDiagnosis.Kind.MISSING_DEPENDENCY && names.size() > 1) names = names.subList(0, 1);   // the second one is missing
        boolean vague = r.line().contains("first mod in the") || r.line().contains("most likely") || r.line().contains("the first mod is");
        Map<String, Alternate> alts = alternates(game);
        List<Culprit> out = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (int i = 0; i < names.size() && out.size() < MAX_CULPRITS; i++) {
            String label = names.get(i);
            Jar j = resolve(jars, label);
            if (j == null || platformJar(j.name()) || j.ids().stream().anyMatch(CrashRecovery::platform) && j.ids().stream().allMatch(CrashRecovery::platform)) continue;
            if (!seen.add(j.user())) continue;
            Confidence c = i == 0 && !vague ? base : Confidence.MEDIUM;
            if (c == Confidence.HIGH && base == Confidence.MEDIUM) c = Confidence.MEDIUM;
            List<Jar> deps = dependents(jars, j);
            Alternate alt = null;
            for (String id : j.ids()) {
                Alternate a = alts.get(id);
                if (a != null && !a.loader().equals(j.forge() ? "forge" : "fabric") && deps.isEmpty()) { alt = a; break; }
            }
            out.add(new Culprit(label, j, c, deps, alt));
        }
        if (out.isEmpty()) return new Plan(ev, r, List.of(), r.mods().isEmpty() ? "the crash names no mod" : "the mod it names (" + String.join(", ", r.mods()) + ") is not an installed mod Alloy may switch off");
        out.removeIf(c -> !c.canDisable() && c.alternate() == null);
        if (out.isEmpty()) return new Plan(ev, r, List.of(), "too many other mods need the mod it names");
        return new Plan(ev, r, List.copyOf(out), "");
    }

    // ------------------------------------------------------------------ installed jars

    /** every player mod jar in mods/ and forgemods/ (Forge jars mirrored from mods/ count once, as their mods/ file) */
    static List<Jar> index(Path game) throws IOException {
        List<Jar> out = new ArrayList<>();
        Properties fit = props(game.resolve(".alloy/fit-state.properties"));
        Map<String, String> fittedFrom = new HashMap<>();   // output -> original name
        for (String k : fit.stringPropertyNames()) {
            String[] v = fit.getProperty(k).split("\\|", -1);
            if (v.length >= 3 && !v[2].isEmpty()) fittedFrom.put(v[2], k);
        }
        for (Path p : ModMeta.jars(game.resolve("mods"))) {
            String n = p.getFileName().toString();
            if (ModMeta.isAlloyGenerated(n)) continue;
            Path user = p;
            String orig = fittedFrom.get(n);
            if (orig != null && Files.isRegularFile(game.resolve(".alloy/originals").resolve(orig))) user = game.resolve(".alloy/originals").resolve(orig);
            out.add(jar("mods", n, p, user));
        }
        for (Path p : ModMeta.jars(game.resolve("forgemods"))) {
            String n = p.getFileName().toString();
            if (n.startsWith(ForgeInMods.PREFIX)) continue;   // the mods/ jar is listed already
            Path o = game.resolve(".alloy/originals/forgemods").resolve(n);
            out.add(jar("forgemods", n, p, Files.isRegularFile(o) ? o : p));
        }
        return out;
    }

    static Jar jar(String folder, String name, Path path, Path user) {
        ModMeta.JarInfo info = ModMeta.read(path);
        List<String> ids = new ArrayList<>(), names = new ArrayList<>();
        for (ModMeta.Mod m : info.mods) {
            if (!ids.contains(m.id)) ids.add(m.id);
            for (String pr : m.provides) if (!ids.contains(pr)) ids.add(pr);
            if (m.name != null && !m.name.isBlank() && !names.contains(m.name)) names.add(m.name);
        }
        return new Jar(folder, name, path, user, List.copyOf(ids), List.copyOf(names), info);
    }

    private static final Pattern NAME_ID = Pattern.compile("^(.*?) \\(([\\w.-]+)\\)$");

    /** the installed jar a diagnosis names: a mod id, a jar file (fitted copies by their original name), or a mod's display name */
    static Jar resolve(List<Jar> jars, String label) {
        String s = label.trim();
        Matcher m = NAME_ID.matcher(s);
        if (m.matches()) {
            Jar j = resolve(jars, m.group(2));
            return j != null ? j : resolve(jars, m.group(1));
        }
        if (s.startsWith(ForgeInMods.PREFIX)) s = s.substring(ForgeInMods.PREFIX.length());
        String low = s.toLowerCase(Locale.ROOT);
        for (Jar j : jars) if (j.ids().contains(s)) return j;
        for (Jar j : jars) {
            String n = j.name().toLowerCase(Locale.ROOT), u = j.user().getFileName().toString().toLowerCase(Locale.ROOT);
            if (n.equals(low) || u.equals(low) || n.equals(low + ".jar") || n.replaceAll("-alloy\\.jar$", ".jar").equals(low)) return j;
        }
        for (Jar j : jars) for (String n : j.names()) if (n.equalsIgnoreCase(s)) return j;
        for (Jar j : jars) for (String id : j.ids()) if (id.equalsIgnoreCase(s) || id.replace('_', '-').equalsIgnoreCase(s.replace('_', '-'))) return j;
        return null;
    }

    /** the other installed mods that need {@code j} (mandatory dependency), and the mods that need those, in turn */
    static List<Jar> dependents(List<Jar> jars, Jar j) {
        Set<String> gone = new HashSet<>(j.ids());
        gone.removeIf(CrashRecovery::platform);
        List<Jar> out = new ArrayList<>();
        for (boolean more = !gone.isEmpty(); more; ) {
            more = false;
            for (Jar o : jars) {
                if (o == j || out.contains(o)) continue;
                boolean needs = false;
                for (ModMeta.Mod mod : o.info().mods)
                    for (ModMeta.Dep d : mod.deps) if (d.mandatory() && !d.incompatible() && gone.contains(d.id())) needs = true;
                if (!needs) continue;
                out.add(o);
                for (String id : o.ids()) if (!platform(id)) gone.add(id);
                more = true;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ alternates (pack import)

    /** .alloy/alternates.tsv: id, loader (forge|fabric), folder (forgemods|mods), file; written by Modpack.saveAlternates */
    static Map<String, Alternate> alternates(Path game) {
        Map<String, Alternate> out = new HashMap<>();
        Path f = game.resolve(ALTERNATES);
        if (!Files.isRegularFile(f)) return out;
        try {
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (l.isBlank() || l.startsWith("#")) continue;
                String[] c = l.split("\t");
                if (c.length < 4 || !(c[2].equals("mods") || c[2].equals("forgemods")) || c[3].contains("/") || c[3].contains("\\")) continue;
                Path stored = game.resolve(".alloy/alternates").resolve(c[2]).resolve(c[3]);
                if (Files.isRegularFile(stored)) out.putIfAbsent(c[0], new Alternate(c[0], c[1], c[2], c[3], stored));
            }
        } catch (IOException ignored) { }
        return out;
    }

    // ------------------------------------------------------------------ switching off and back on

    /** one line of .alloy/disabled/disabled.tsv */
    record Entry(String when, String folder, String name, String stored, String ids, String group, String derived, String added, String why) {
        String line() { return String.join("\t", when, folder, name, stored, ids, group, derived, added, why.replace('\t', ' ').replace('\n', ' ')); }
        static Entry parse(String l) {
            String[] c = l.split("\t", -1);
            if (c.length < 9) return null;
            return new Entry(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8]);
        }
    }

    static List<Entry> entries(Path game) {
        List<Entry> out = new ArrayList<>();
        Path f = game.resolve(INDEX);
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

    static void writeEntries(Path game, List<Entry> es) throws IOException {
        Path f = game.resolve(INDEX);
        Files.createDirectories(f.getParent());
        StringBuilder b = new StringBuilder("# mods switched off by Alloy's crash recovery; put them back with: java -jar .alloy/alloy-installer.jar --restore-disabled .\n"
                + "# when\tfolder\tfile\tstored (in .alloy/disabled)\tmod ids\tswitched off with\tAlloy's derived copies\tadded alternate\twhy\n");
        for (Entry e : es) b.append(e.line()).append('\n');
        Files.writeString(f, b.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Moves j (and every jar in {@code also}) to .alloy/disabled/. The player's file goes to disabled/FOLDER/, Alloy's derived
     * copy (a fitted copy in mods/, a translated or converted copy in forgemods/) to disabled/.derived/. Returns the lines added.
     */
    static List<Entry> disable(Path game, Jar j, List<Jar> also, String why, String added) throws IOException {
        List<Entry> es = entries(game);
        List<Entry> now = new ArrayList<>();
        String when = LocalDateTime.now().withNano(0).toString();
        List<Jar> all = new ArrayList<>(List.of(j));
        all.addAll(also);
        for (Jar x : all) {
            if (!Files.exists(x.user())) continue;
            Path dir = game.resolve(DISABLED).resolve(x.folder());
            Files.createDirectories(dir);
            String userName = x.folder().equals("mods") ? x.user().getFileName().toString() : x.name();
            Path dst = dir.resolve(userName);
            for (int n = 2; Files.exists(dst); n++) dst = dir.resolve(n + "-" + userName);
            Files.move(x.user(), dst);
            List<String> derived = new ArrayList<>();
            List<Path> copies = new ArrayList<>();
            if (!x.path().equals(x.user())) copies.add(x.path());
            if (x.folder().equals("forgemods")) copies.add(game.resolve(".alloy/originals/forgemods-collide").resolve(x.name()));
            for (Path c : copies) {
                if (!Files.isRegularFile(c)) continue;
                Path dd = game.resolve(DISABLED).resolve(".derived").resolve(x.folder()).resolve(c.getFileName().toString());
                Files.createDirectories(dd.getParent());
                Files.move(c, dd, StandardCopyOption.REPLACE_EXISTING);
                derived.add(game.resolve(DISABLED).relativize(dd).toString().replace('\\', '/'));
            }
            Entry e = new Entry(when, x.folder(), userName, game.resolve(DISABLED).relativize(dst).toString().replace('\\', '/'),
                    String.join(",", x.ids()), x == j ? "" : j.name(), String.join(";", derived), x == j && added != null ? added : "",
                    x == j ? why : "needs " + j.title());
            es.add(e);
            now.add(e);
        }
        writeEntries(game, es);
        return now;
    }

    /** The pack's other-loader build in place of j: j is switched off (reversible), the kept alternate is copied in. */
    static List<Entry> useAlternate(Path game, Jar j, Alternate a, String why) throws IOException {
        Path to = game.resolve(a.folder()).resolve(a.file());
        if (Files.exists(to)) throw new IOException(a.folder() + "/" + a.file() + " exists already");
        List<Entry> es = disable(game, j, List.of(), why, a.folder() + "/" + a.file());
        Files.createDirectories(to.getParent());
        Files.copy(a.stored(), to, StandardCopyOption.COPY_ATTRIBUTES);
        // the copy keeps the import's file time: older than the crash, so the next check does not see it as a change by the player
        return es;
    }

    /**
     * Puts switched-off mods back: all (which null or "all"), or the ones whose file, mod id or group matches. A file that
     * is in its folder again already is left in .alloy/disabled (said in the log). Returns how many came back.
     */
    static int restore(Path game, String which) throws IOException {
        List<Entry> es = entries(game), keep = new ArrayList<>();
        int back = 0;
        Set<String> groups = new HashSet<>();
        for (Entry e : es) if (matches(e, which)) groups.add(e.group().isEmpty() ? e.name() : e.group());
        for (Entry e : es) {
            boolean pick = which == null || which.equals("all") || matches(e, which) || groups.contains(e.group().isEmpty() ? e.name() : e.group());
            if (!pick) { keep.add(e); continue; }
            Path src = game.resolve(DISABLED).resolve(e.stored()).normalize(), dst = game.resolve(e.folder()).resolve(e.name()).normalize();
            if (!src.startsWith(game.resolve(DISABLED)) || !dst.startsWith(game.resolve(e.folder()))) { keep.add(e); continue; }
            if (!Files.isRegularFile(src)) { Util.log("crash recovery: " + e.stored() + " is no longer in .alloy/disabled; forgotten"); continue; }
            if (Files.exists(dst)) { Util.log("crash recovery: " + e.folder() + "/" + e.name() + " is there already; the switched-off copy stays in .alloy/disabled/" + e.stored()); keep.add(e); continue; }
            if (!e.added().isEmpty()) removeAdded(game, e.added());
            Files.createDirectories(dst.getParent());
            Files.move(src, dst);
            for (String d : e.derived().split(";")) {   // Alloy's own fitted / translated copies: the next prep makes them again
                if (d.isBlank()) continue;
                Path dp = game.resolve(DISABLED).resolve(d).normalize();
                if (dp.startsWith(game.resolve(DISABLED).resolve(".derived"))) Files.deleteIfExists(dp);
            }
            Util.log("crash recovery: " + e.folder() + "/" + e.name() + " is back");
            back++;
        }
        writeEntries(game, keep);
        return back;
    }

    /**
     * Takes the alternate Alloy copied in out again (FOLDER/FILE, a copy of .alloy/alternates/FOLDER/FILE; the pack's file
     * stays there). After a prep it may sit elsewhere: a Fabric jar in .alloy/originals/ with its fitted copy in mods/, a
     * 1.20.1 Forge jar in .alloy/originals/forgemods/ with its translated copy in forgemods/. Only files that are that copy
     * (same size as the stored one) or Alloy's derived copy of it are removed.
     */
    static void removeAdded(Path game, String added) throws IOException {
        Path store = game.resolve(".alloy/alternates").resolve(added).normalize();
        if (!store.startsWith(game.resolve(".alloy/alternates")) || !Files.isRegularFile(store)) return;
        long size = Files.size(store);
        String folder = added.contains("/") ? added.substring(0, added.indexOf('/')) : "mods", name = added.substring(added.lastIndexOf('/') + 1);
        List<String> gone = new ArrayList<>();
        Path add = game.resolve(added).normalize();
        boolean derivedHere = false;
        if (folder.equals("mods")) {
            Path orig = game.resolve(".alloy/originals").resolve(name);
            if (Files.isRegularFile(orig) && Files.size(orig) == size) {
                String[] v = props(game.resolve(".alloy/fit-state.properties")).getProperty(name, "").split("\\|", -1);
                if (v.length >= 3 && !v[2].isEmpty() && Files.deleteIfExists(game.resolve("mods").resolve(v[2]))) gone.add("mods/" + v[2]);
                Files.delete(orig);
                gone.add(".alloy/originals/" + name);
            }
        } else {
            Path orig = game.resolve(".alloy/originals/forgemods").resolve(name);
            if (Files.isRegularFile(orig) && Files.size(orig) == size) {
                Files.delete(orig);
                gone.add(".alloy/originals/forgemods/" + name);
                derivedHere = true;   // forgemods/NAME is the translated copy of it
            }
        }
        if (add.startsWith(game) && Files.isRegularFile(add) && (derivedHere || Files.size(add) == size)) { Files.delete(add); gone.add(added); }
        if (!gone.isEmpty()) Util.log("crash recovery: the other loader's build Alloy copied in is removed again (" + String.join(", ", gone) + "); the pack's file stays in .alloy/alternates");
    }

    static boolean matches(Entry e, String which) {
        if (which == null || which.equals("all")) return true;
        if (e.name().equalsIgnoreCase(which) || e.name().equalsIgnoreCase(which + ".jar")) return true;
        for (String id : e.ids().split(",")) if (id.equals(which)) return true;
        return false;
    }

    // ------------------------------------------------------------------ "asked already" state

    static Properties props(Path f) {
        Properties p = new Properties();
        if (Files.isRegularFile(f)) try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { p.load(r); } catch (IOException ignored) { }
        return p;
    }

    static boolean handled(Path game, Evidence ev) {
        return ev.key().equals(props(game.resolve(STATE)).getProperty("handled"));
    }

    static void markHandled(Path game, Evidence ev, String choice) {
        try {
            Properties p = props(game.resolve(STATE));
            p.setProperty("handled", ev.key());
            p.setProperty("choice", choice);
            p.setProperty("time", LocalDateTime.now().withNano(0).toString());
            Files.createDirectories(game.resolve(".alloy"));
            try (Writer w = Files.newBufferedWriter(game.resolve(STATE), StandardCharsets.UTF_8)) { p.store(w, "Alloy crash recovery: the crash already answered"); }
        } catch (IOException e) { Util.log("crash recovery: could not save the choice (" + e + ")"); }
    }

    // ------------------------------------------------------------------ the launch step

    enum Action { DISABLE, ALTERNATE, NORMAL, CLOSED }

    record Choice(Action action, Culprit culprit) { }

    /** RecoveryWindow when a screen is there; replaced in tests */
    interface Asker { Choice ask(Path game, Plan p); }

    static Asker asker = (game, p) -> RecoveryWindow.ask(game, p);
    /** tests: whether there is a screen (null = ask AWT) */
    static Boolean screen;

    /** -Dalloy.recover.inGame: set by LaunchPrep (the game JVM). On macOS AWT must not start there (GLFW needs the main thread). */
    static boolean windowAllowed(String side) {
        if (!"client".equals(side) || Boolean.getBoolean("alloy.recover.noWindow")) return false;
        if (screen != null ? !screen : java.awt.GraphicsEnvironment.isHeadless()) return false;
        return !(Boolean.getBoolean("alloy.recover.inGame") && Util.OS_NAME.equals("osx"));
    }

    static String installerCmd(Path game) {
        try {
            Path self = Util.selfJar().toAbsolutePath().normalize();
            if (self.startsWith(game)) return "java -jar " + game.relativize(self).toString().replace('\\', '/') + " ";
        } catch (RuntimeException ignored) { }
        return "java -jar .alloy/alloy-installer.jar ";
    }

    /** Called before prep on every launch. Never throws: a failure here only costs the offer. */
    static void atLaunch(Path game) {
        long t0 = System.nanoTime();
        try {
            run(game.toAbsolutePath().normalize(), System.getProperty("alloy.recover", "").trim(), System.currentTimeMillis());
        } catch (Throwable t) {
            Util.log("crash recovery skipped (" + t + ")");
            if (System.getProperty("alloy.debug") != null) t.printStackTrace();
        }
        // -Dalloy.timing=true: to stderr like Prep's timing line (a window's waiting time is included)
        if (Boolean.getBoolean("alloy.timing")) System.err.println(String.format(Locale.ROOT, "[alloy] timing: crash recovery %.1f ms", (System.nanoTime() - t0) / 1e6));
    }

    static void run(Path game, String flag, long now) throws IOException {
        if (flag.equals("off")) return;
        String side = props(game.resolve(".alloy/prep.properties")).getProperty("side", "client");
        if (flag.equals("restore")) Util.log("crash recovery: -Dalloy.recover=restore, " + restore(game, null) + " mod(s) put back");
        Evidence ev = evidence(game, now);
        if (flag.startsWith("disable:")) {
            List<Jar> jars = index(game);
            for (String id : flag.substring("disable:".length()).split(",")) {
                if (id.isBlank()) continue;
                Jar j = resolve(jars, id.trim());
                if (j == null) {
                    boolean off = entries(game).stream().anyMatch(e -> matches(e, id.trim()));
                    Util.log("crash recovery: -Dalloy.recover=disable:" + id.trim() + ": " + (off ? "switched off already" : "no installed mod with that id or file name"));
                    continue;
                }
                if (platformJar(j.name()) || !j.ids().isEmpty() && j.ids().stream().allMatch(CrashRecovery::platform)) { Util.log("crash recovery: " + j.name() + " is part of Alloy, Fabric API or the game: not switched off"); continue; }
                List<Jar> deps = dependents(jars, j);
                disable(game, j, deps, "-Dalloy.recover=disable:" + id.trim(), null);
                Util.log("crash recovery: switched off " + j.folder() + "/" + j.fileName() + (deps.isEmpty() ? "" : " and " + names(deps) + " (they need it)") + " (-Dalloy.recover)");
                jars = index(game);
            }
            if (ev != null) markHandled(game, ev, "flag");
        }
        reminder(game);
        if (ev == null || flag.startsWith("disable:") || handled(game, ev)) return;
        if (flag.equals("ignore") || flag.equals("normal")) { markHandled(game, ev, "ignore"); Util.log("crash recovery: -Dalloy.recover=" + flag + ", starting normally"); return; }
        Plan p = decide(game, ev);
        if (!p.offer()) return;   // Mod Doctor's "Last start failed" line still explains the crash
        if (!windowAllowed(side)) { Util.log(suggestion(game, p)); return; }
        Choice c = asker.ask(game, p);
        apply(game, p, c);
    }

    static void apply(Path game, Plan p, Choice c) throws IOException {
        String why = p.ev().file().getFileName() + ": " + p.diagnosis().line();
        switch (c.action()) {
            case DISABLE -> {
                Culprit k = c.culprit();
                disable(game, k.jar(), k.dependents(), why, null);
                Util.log("crash recovery: switched off " + k.jar().folder() + "/" + k.jar().fileName() + (k.dependents().isEmpty() ? "" : " and " + names(k.dependents()) + " (they need it)")
                        + "; moved to .alloy/disabled/. Put back: " + installerCmd(game) + "--restore-disabled .");
                markHandled(game, p.ev(), "disable " + k.jar().name());
            }
            case ALTERNATE -> {
                Culprit k = c.culprit();
                useAlternate(game, k.jar(), k.alternate(), why);
                Util.log("crash recovery: using the " + k.alternate().loaderName() + " build " + k.alternate().folder() + "/" + k.alternate().file() + " in place of "
                        + k.jar().folder() + "/" + k.jar().fileName() + " (moved to .alloy/disabled/). Undo: " + installerCmd(game) + "--restore-disabled .");
                markHandled(game, p.ev(), "alternate " + k.alternate().file());
            }
            case NORMAL -> { markHandled(game, p.ev(), "normal"); Util.log("crash recovery: starting normally (the player's choice)"); }
            case CLOSED -> Util.log("crash recovery: window closed, starting normally (asks again next time)");
        }
    }

    static String names(List<Jar> js) {
        List<String> n = new ArrayList<>();
        for (Jar j : js) n.add(j.name());
        return String.join(", ", n);
    }

    /** the one log line for servers and starts without a screen */
    static String suggestion(Path game, Plan p) {
        Culprit k = p.culprits().get(0);
        String when = p.ev().phase() == Phase.WORLD_LOAD ? "while the world loaded" : "during startup";
        String id = k.jar().ids().isEmpty() ? k.jar().name() : k.jar().id();
        return "ALLOY: the last start crashed " + when + " (" + p.ev().file().getFileName() + "): " + p.diagnosis().line() + " "
                + (k.confidence() == Confidence.HIGH ? "Cause: " : "Probable cause: ") + k.jar().title() + " (" + k.jar().folder() + "/" + k.jar().fileName() + "). "
                + "To start without it (reversible, nothing is deleted): add -Dalloy.recover=disable:" + id + " to the start command, or run "
                + installerCmd(game) + "--disable-mod . " + id + (k.dependents().isEmpty() ? "" : " (also switches off " + names(k.dependents()) + ", they need it)")
                + ". To start normally and stop this line: -Dalloy.recover=ignore. Undo: --restore-disabled .";
    }

    /** a line on every launch while mods are switched off */
    static void reminder(Path game) {
        List<Entry> es = entries(game);
        if (es.isEmpty()) return;
        List<String> n = new ArrayList<>();
        for (Entry e : es) n.add(e.folder() + "/" + e.name());
        Util.log("crash recovery: " + es.size() + " mod(s) switched off (kept in .alloy/disabled/): " + String.join(", ", n)
                + ". Put them back: " + installerCmd(game) + "--restore-disabled .");
    }

    // ------------------------------------------------------------------ command line

    /**
     * --restore-disabled GAMEDIR [MOD]   put switched-off mods back (all, or one by mod id or file name)
     * --disable-mod GAMEDIR MOD           switch a mod off by hand (and the mods that need it), reversible
     * --recover-check GAMEDIR             print what recovery would offer now (changes nothing)
     */
    static int cli(String[] args) throws IOException {
        if (args.length < 2) { System.err.println("usage: --restore-disabled GAMEDIR [MOD] | --disable-mod GAMEDIR MOD | --recover-check GAMEDIR"); return 64; }
        Path game = Path.of(args[1]).toAbsolutePath().normalize();
        switch (args[0]) {
            case "--restore-disabled" -> {
                if (entries(game).isEmpty()) { Util.log("crash recovery: no mods are switched off in " + game); return 0; }
                int n = restore(game, args.length > 2 ? args[2] : null);
                Util.log("crash recovery: " + n + " mod(s) put back" + (entries(game).isEmpty() ? "" : "; still switched off: " + entries(game).size()));
                return 0;
            }
            case "--disable-mod" -> {
                if (args.length < 3) { System.err.println("usage: --disable-mod GAMEDIR MOD"); return 64; }
                run(game, "disable:" + args[2], System.currentTimeMillis());
                return entries(game).stream().anyMatch(e -> matches(e, args[2])) ? 0 : 1;
            }
            case "--recover-check" -> {
                Evidence ev = evidence(game, System.currentTimeMillis());
                if (ev == null) { System.out.println("no recent failed start"); return 0; }
                Plan p = decide(game, ev);
                System.out.println(describe(p));
                return p.offer() ? 3 : 0;
            }
            default -> { return 64; }
        }
    }

    /** a plain listing of a plan (tests and --recover-check) */
    static String describe(Plan p) {
        StringBuilder b = new StringBuilder();
        b.append("crash\t").append(p.ev().file().getFileName()).append('\n');
        b.append("phase\t").append(p.ev().phase()).append('\n');
        b.append("kind\t").append(p.diagnosis().kind()).append('\n');
        b.append("headline\t").append(p.diagnosis().headline()).append('\n');
        if (!p.offer()) b.append("offer\tnone\t").append(p.why()).append('\n');
        for (Culprit c : p.culprits()) {
            b.append("culprit\t").append(c.jar().folder()).append('/').append(c.jar().fileName()).append('\t').append(c.confidence())
                    .append("\tid=").append(c.jar().id()).append("\tdeps=").append(names(c.dependents()));
            if (c.alternate() != null) b.append("\talt=").append(c.alternate().folder()).append('/').append(c.alternate().file());
            b.append('\n');
        }
        return b.toString();
    }
}
