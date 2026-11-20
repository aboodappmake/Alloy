package alloy.report;

import alloy.report.Entry.Severity;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraftforge.fml.LoadingFailedException;
import net.minecraftforge.fml.ModLoadingException;
import net.minecraftforge.fml.ModLoadingWarning;
import net.minecraftforge.fml.loading.EarlyLoadingException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * The Alloy error report. Failure hooks call the on* methods; every call classifies (see {@link Classifier}), stores the
 * result and rewrites {@code <gamedir>/alloy-report.txt}. The file is written on every run (OK / warnings / errors).
 * All entry points swallow their own exceptions: the report must never be the thing that breaks the game.
 *
 * Hooks:
 *  - Bridge.run end              -> {@link #onForgeScan()}      (FML early errors, broken files, mod list)
 *  - Bridge.init catch           -> {@link #onStartFailure}
 *  - ConflictReport.record       -> {@link #onMixinError}
 *  - ReportScreenMixin (client)  -> {@link #onForgeLoad}        (LoadingErrorScreen contents)
 *  - CrashReportMixin            -> {@link #onCrash}
 *  - shutdown hook               -> {@link #flush()}
 */
public final class AlloyReport {
    private AlloyReport() { }

    public static final boolean SCREEN = !"false".equals(System.getProperty("alloy.report.screen"));
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("Alloy");
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final Set<String> KEYS = new HashSet<>();
    private static boolean fatalPrinted, hookInstalled;
    private static volatile Path file;

    // ------------------------------------------------------------------ hooks

    /** FML scan finished (called at the end of Bridge.run): early errors and broken files, then writes the first report. */
    public static void onForgeScan() {
        safe(() -> {
            installHook();
            // alloy.Fml: LoadingModList is static on Forge 64 and an instance (LoadingModList.get()) on Forge 47
            for (Object o : alloy.Fml.errors()) if (o instanceof EarlyLoadingException e) for (Entry x : Classifier.fromEarly(e)) add(x);
            for (Object o : alloy.Fml.brokenFiles()) {
                if (!(o instanceof net.minecraftforge.forgespi.locating.IModFile f)) continue;
                add(new Entry(Severity.ERROR, "Forge", "", String.valueOf(f.getFileName()), "Forge cannot use mod file " + f.getFileName(),
                        "The file is not a valid Forge mod for this version.", "Replace or remove that jar.", ""));
            }
            onCollisions();
            onTwiceInstalled();
            logFeaturesOff();
            write();
        });
    }

    /** Ids that are libraries shipped inside many jars on both sides on purpose: not worth a warning. */
    private static final Set<String> SHARED_LIBS = Set.of("mixinextras", "mixinsquared", "minecraft", "forge", "java", "fabricloader", "night_config", "kotlin");

    /** The same mod id loaded as a Forge mod (forgemods/) and as a Fabric mod (mods/): both copies run side by side. */
    private static void onTwiceInstalled() {
        try {
            Map<String, ModIndex.Ref> forge = new HashMap<>(), fabric = new HashMap<>();
            for (ModIndex.Ref r : ModIndex.get().all()) (r.side.equals("Forge") ? forge : fabric).putIfAbsent(r.id, r);
            for (var e : forge.entrySet()) {
                ModIndex.Ref fa = fabric.get(e.getKey());
                if (fa == null || SHARED_LIBS.contains(e.getKey()) || fa.jar.isEmpty() || e.getValue().jar.isEmpty()) continue;
                if (fa.jar.contains("alloy-stub-")) continue;   // Alloy's own class-less placeholder for the Forge mod (Prep.forgeStubs)
                // the exact wording is read back by LogDiagnosis (DUP_LINE) when a later crash is in one of the two copies
                LOG.warn("Alloy: mod '{}' is installed twice: Forge {} in forgemods/ and Fabric {} in mods/", e.getKey(), e.getValue().jar, fa.jar);
                add(new Entry(Severity.WARN, "Forge", e.getValue().label(), e.getValue().jar, "Mod " + e.getValue().name + " is installed twice (a Forge build and a Fabric build)",
                        "Both " + e.getValue().jar + " (forgemods/) and " + fa.jar + " (mods/) are loaded. For a library that two other mods need this is fine; for a normal mod it often doubles its features or crashes.",
                        "If the game misbehaves, keep one build: delete " + fa.jar + " from mods/ or " + e.getValue().jar + " from forgemods/.", ""));
            }
        } catch (Throwable ignored) { }
    }

    /** tools/RedirectToWrap (install time) converted Forge @Redirects that collided with a Fabric @Redirect: tell the user what is bypassed. */
    private static void onCollisions() {
        try {
            Path f = reportFile().resolveSibling("alloy-collisions.txt");
            if (!Files.isRegularFile(f)) return;
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (!l.startsWith("CONVERT ")) continue;
                String[] p = l.substring(8).split(" ", 3);
                String jar = p[0], handler = p.length > 1 ? p[1] : "";
                add(new Entry(Severity.WARN, "Forge", "", jar, "A Forge mod and a Fabric mod change the same game call",
                        "Both a Forge mod (" + jar + ") and a Fabric mod (probably Fabric API) replace the same call in Minecraft. Alloy let both load; "
                        + "at that call the Forge mod's replacement is used, so the Fabric mod's replacement is bypassed (the matching Fabric feature may not work while this Forge mod is installed).",
                        "Nothing, unless a Fabric mod feature stops working: then remove the Forge mod that shows up here.", handler));
            }
        } catch (Throwable ignored) { }
    }

    /** FML start failure (Bridge.failure). Returns a one-line summary suitable for the exception message. */
    public static String onStartFailure(Throwable t) {
        try {
            Entry e = Classifier.fromStartFailure(t);
            if (generic(e) || e.title.endsWith("An error occurred") || e.title.contains("could not find its own")) {
                // FML often logs the real cause (a broken mod file) before it throws something vague: read the log so far
                Entry d = Classifier.fromDiagnosis(LogDiagnosis.diagnose(logSoFar() + "\n" + Classifier.traceText(t)), e.technical);
                if (d != null) e = new Entry(d.severity, d.side, d.mod, d.jar, "Forge could not start: " + d.title, d.detail, d.fix, d.technical);
            }
            add(e);
            fatal();
            return e.title;
        } catch (Throwable x) {
            return "";
        }
    }

    /** Mixin prepare/apply error (called from ConflictReport.record). */
    public static void onMixinError(String phase, String config, String mixin, String target, Throwable th, boolean lenient) {
        safe(() -> { add(Classifier.fromMixin(phase, config, mixin, target, th, lenient)); write(); });
    }

    /** Forge's loading errors/warnings as shown by LoadingErrorScreen (client) or thrown by ServerModLoader. */
    public static List<Entry> onForgeLoad(List<ModLoadingException> errors, List<ModLoadingWarning> warnings) {
        List<Entry> out = new ArrayList<>();
        try {
            if (errors != null) for (ModLoadingException e : errors) { Entry x = Classifier.fromModLoadingException(e); add(x); out.add(x); }
            if (warnings != null) for (ModLoadingWarning w : warnings) add(Classifier.fromModLoadingWarning(w));
            if (!out.isEmpty()) fatal(); else write();
        } catch (Throwable t) { LOG.warn("Alloy report: classifying Forge loading problems failed: {}", t.toString()); }
        return out;
    }

    /** An uncaught crash (the vanilla CrashReport). The throwable may wrap a Forge LoadingFailedException. */
    public static void onCrash(String title, Throwable t, Path crashFile) {
        safe(() -> {
            LoadingFailedException lfe = null;
            for (Throwable c = t; c != null && lfe == null; c = c.getCause() == c ? null : c.getCause()) if (c instanceof LoadingFailedException l) lfe = l;
            String text = "";
            try { if (crashFile != null) text = Files.readString(crashFile, StandardCharsets.UTF_8); } catch (Throwable ignored) { }
            if (lfe != null) onForgeLoad(lfe.getErrors(), warnings());
            else {
                // A crash before Forge shows its error screen (e.g. a mod's color handler running after its own
                // registration was skipped) hides the mod loading error that caused it: list those first.
                for (ModLoadingException e : pendingForgeErrors()) add(Classifier.fromModLoadingException(e));
                Entry crash = t != null ? Classifier.fromThrowable(t, "Minecraft") : Classifier.fromCrashText(title, text);
                if (generic(crash) && !text.isEmpty()) {   // the saved report has more (Forge's mod sections, suspected mods)
                    Entry d = Classifier.fromDiagnosis(LogDiagnosis.diagnose(text), crash.technical);
                    if (d != null) crash = d;
                }
                // a specific cause found in the crash text goes first (seen live: Cherished Worlds' "single listener"
                // was listed under a vague "failed to load before the crash")
                boolean specific = !generic(crash);
                if (specific) add(crash);
                for (String id : failedForgeMods()) {
                    ModIndex.Ref r = ModIndex.get().forgeMod(id);
                    if (specific && (crash.mod.contains(id) || r != null && !r.jar.isEmpty() && r.jar.equals(crash.jar))) continue;   // already explained
                    String name = r != null ? r.name : id;
                    add(new Entry(Severity.ERROR, "Forge", r != null ? r.label() : id, r != null ? r.jar : "",
                            "Forge mod " + name + " failed to load before the crash",
                            name + " failed while Forge was loading mods, and the game crashed before Forge could show that error."
                                    + " The crash listed below is most likely a result of it. The real error is in logs/latest.log"
                                    + " (search for \"" + id + "\", for example \"Failed to create mod instance. ModID: " + id + "\").",
                            "Fix this error first: check that every library " + name + " needs is installed, or update or remove "
                                    + (r != null ? r.jar : name) + ".", ""));
                }
                if (!specific) add(crash);
            }
            if (crashFile != null) {
                LOG.error("Alloy: crash report saved to {}", crashFile);
                List<Entry> errs = errors();   // the first error is the cause (a Forge failure listed above comes first)
                if (!errs.isEmpty()) stampCrashFile(crashFile, errs.get(0).headline());
            }
            fatal();
        });
    }

    /** ModLoader's warnings (ModLoader.getWarnings is static on Forge 64, an instance method on Forge 47). */
    static List<ModLoadingWarning> warnings() {
        List<ModLoadingWarning> l = new ArrayList<>();
        try { for (Object o : alloy.Fml.modLoaderWarnings()) if (o instanceof ModLoadingWarning w) l.add(w); } catch (Throwable ignored) { }
        return l;
    }

    /** logs/latest.log as written so far (the last 400 KB), or "" */
    static String logSoFar() {
        try {
            Path log = reportFile().resolveSibling("logs").resolve("latest.log");
            if (!Files.isRegularFile(log)) return "";
            byte[] b = Files.readAllBytes(log);
            int from = Math.max(0, b.length - 400_000);
            return new String(b, from, b.length - from, StandardCharsets.UTF_8);
        } catch (Throwable t) { return ""; }
    }

    /** generic fallback entries from Classifier (no known cause) */
    static boolean generic(Entry e) {
        return e.title.startsWith("An error occurred") || e.title.startsWith("Minecraft crashed") || e.title.endsWith(" crashed") || e.title.startsWith("Unknown error");
    }

    /** Puts the plain-language line above Minecraft's "---- Minecraft Crash Report ----" header (once). */
    static void stampCrashFile(Path f, String headline) {
        try {
            String old = Files.readString(f, StandardCharsets.UTF_8);
            if (old.startsWith("ALLOY: ")) return;
            String top = headline + "\n// Alloy's reading of this crash. Details: " + reportFile().getFileName() + " in the game folder.\n\n";
            Files.writeString(f, top + old, StandardCharsets.UTF_8);
        } catch (Throwable t) { LOG.warn("Alloy report: cannot add the summary to {}: {}", f, t.toString()); }
    }

    /** The one-line summary of the first error, or "" when there is none. */
    public static String headline() {
        List<Entry> errs = errors();
        return errs.isEmpty() ? "" : errs.get(0).headline();
    }

    /** Mod loading errors FML has collected but not shown yet (ModLoader.LOADING_EXCEPTIONS), empty if loading is fine. */
    @SuppressWarnings("unchecked")
    static List<ModLoadingException> pendingForgeErrors() {
        try {
            if (net.minecraftforge.fml.ModLoader.isLoadingStateValid()) return List.of();
            java.lang.reflect.Field f = net.minecraftforge.fml.ModLoader.class.getDeclaredField("LOADING_EXCEPTIONS");
            f.setAccessible(true);
            List<ModLoadingException> l = (List<ModLoadingException>) f.get(null);
            synchronized (l) { return List.copyOf(l); }
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** Forge mods whose container is in the ERROR stage (their construction or setup threw). */
    static List<String> failedForgeMods() {
        List<String> out = new ArrayList<>();
        try {
            java.util.function.BiConsumer<String, net.minecraftforge.fml.ModContainer> each = (id, mc) -> {
                if (mc.getCurrentState() == net.minecraftforge.fml.ModLoadingStage.ERROR) out.add(id);
            };
            // static on Forge 64, an instance method on ModList.get() on Forge 47
            var m = net.minecraftforge.fml.ModList.class.getMethod("forEachModContainer", java.util.function.BiConsumer.class);
            Object list = java.lang.reflect.Modifier.isStatic(m.getModifiers()) ? null : net.minecraftforge.fml.ModList.class.getMethod("get").invoke(null);
            m.invoke(list, each);
        } catch (Throwable ignored) { }
        return out;
    }

    // ------------------------------------------------------------------ state

    public static synchronized void add(Entry e) {
        // one error per mod jar: the crash hook and Forge's error screen report the same failure (seen live: 3 entries
        // for Cherished Worlds); the first one, usually the most specific, stays
        if (e.severity == Severity.ERROR && !e.jar.isEmpty() && !e.jar.equals("alloy-bridge.jar"))
            for (Entry x : ENTRIES) if (x.severity == Severity.ERROR && x.jar.equals(e.jar)) return;
        if (KEYS.add(e.key())) ENTRIES.add(e);
    }

    public static synchronized List<Entry> entries() { return List.copyOf(ENTRIES); }

    public static synchronized List<Entry> errors() {
        List<Entry> l = new ArrayList<>();
        for (Entry e : ENTRIES) if (e.severity == Severity.ERROR) l.add(e);
        return l;
    }

    public static boolean hasErrors() { return !errors().isEmpty(); }

    public static Path reportFile() {
        Path f = file;
        if (f == null) {
            Path dir;
            try { dir = FabricLoader.getInstance().getGameDir(); } catch (Throwable t) { dir = Path.of("."); }
            file = f = dir.toAbsolutePath().normalize().resolve("alloy-report.txt");
        }
        return f;
    }

    /** Test hook: write somewhere else. */
    public static void setReportFile(Path p) { file = p; }

    public static void flush() { safe(AlloyReport::write); }

    private static void installHook() {
        if (hookInstalled) return;
        hookInstalled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            safe(() -> {
                try { for (ModLoadingWarning w : warnings()) add(Classifier.fromModLoadingWarning(w)); } catch (Throwable ignored) { }
                write();
            });
        }, "Alloy-report"));
    }

    /** A fatal problem: write the file and, on a dedicated server, print the console block. The client screen is shown by ReportScreenMixin. */
    private static void fatal() {
        write();
        boolean server;
        try { server = FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.SERVER; } catch (Throwable t) { server = true; }
        synchronized (AlloyReport.class) {
            if (!fatalPrinted) {
                fatalPrinted = true;
                String h = headline();
                if (!h.isEmpty()) LOG.error(h);   // one plain line first, on client and server alike
                if (server) for (String l : consoleBlock()) LOG.error(l);
            }
        }
    }

    // ------------------------------------------------------------------ output

    /** Console block for the dedicated server. */
    public static List<String> consoleBlock() {
        List<String> l = new ArrayList<>();
        String bar = "=".repeat(78);
        l.add(bar);
        l.add("  ALLOY: the game cannot start because of the problem(s) below");
        l.add(bar);
        int n = 0;
        for (Entry e : errors()) {
            l.add("  " + (++n) + ". [" + e.side + "] " + e.title);
            if (!e.jar.isEmpty()) l.add("     File: " + e.jar);
            for (String d : wrap(e.detail, 72)) l.add("     " + d);
            if (!e.fix.isEmpty()) for (String d : wrap("What to do: " + e.fix, 72)) l.add("     " + d);
        }
        l.add("  Full report: " + reportFile());
        l.add(bar);
        return l;
    }

    public static synchronized String render() {
        List<Entry> errs = new ArrayList<>(), warns = new ArrayList<>();
        for (Entry e : ENTRIES) (e.severity == Severity.ERROR ? errs : warns).add(e);
        StringBuilder sb = new StringBuilder();
        sb.append("Alloy report - ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        sb.append("STATUS: ").append(!errs.isEmpty() ? "ERRORS (" + errs.size() + ")" : !warns.isEmpty() ? "WARNINGS (" + warns.size() + ")" : "OK").append('\n');
        if (!errs.isEmpty()) sb.append(errs.get(0).headline()).append('\n');
        try {
            var fl = FabricLoader.getInstance();
            sb.append("Side: ").append(fl.getEnvironmentType()).append("   Game dir: ").append(fl.getGameDir().toAbsolutePath().normalize()).append('\n');
        } catch (Throwable ignored) { }
        sb.append('\n');
        section(sb, "ERRORS (the game cannot run correctly)", errs);
        section(sb, "WARNINGS (the game runs, something is reduced or disabled)", warns);
        if (errs.isEmpty() && warns.isEmpty()) sb.append("No problems were detected by Alloy.\n\n");
        try { featuresOffSection(sb); } catch (Throwable ignored) { }
        try {
            ModIndex idx = ModIndex.get();
            List<String> forge = new ArrayList<>(), fabric = new ArrayList<>();
            for (ModIndex.Ref r : idx.all()) (r.side.equals("Forge") ? forge : fabric).add("  " + r.id + " " + r.version + "  (" + r.jar + ")");
            sb.append("FORGE MODS (").append(forge.size()).append(")\n");
            forge.stream().distinct().forEach(s -> sb.append(s).append('\n'));
            sb.append("\nFABRIC MODS (").append(fabric.size()).append(")\n");
            fabric.stream().distinct().forEach(s -> sb.append(s).append('\n'));
        } catch (Throwable ignored) { }
        return sb.toString();
    }

    // ------------------------------------------------------------------ features off (written by the fitter)

    /** One line of .alloy/features-off.txt: mod, version, jar, kind, mixin, target, area, plain words. */
    record Off(String mod, String version, String jar, String kind, String key, String target, String area, String words) { }

    static Path featuresOffFile() { return reportFile().resolveSibling(".alloy").resolve("features-off.txt"); }

    /** The fitter's list (empty when there is none). meta[0] = done by Alloy's own code instead, meta[1] = data generation only. */
    static List<Off> featuresOff(int[] meta) {
        List<Off> out = new ArrayList<>();
        try {
            Path f = featuresOffFile();
            if (!Files.isRegularFile(f)) return out;
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (l.startsWith("#meta")) {
                    if (meta != null) for (String kv : l.split("\t")) {
                        try {
                            if (kv.startsWith("replaced=")) meta[0] = Integer.parseInt(kv.substring(9));
                            else if (kv.startsWith("datagen=")) meta[1] = Integer.parseInt(kv.substring(8));
                        } catch (NumberFormatException ignored) { }
                    }
                    continue;
                }
                if (l.startsWith("#") || l.isBlank()) continue;
                String[] p = l.split("\t", -1);
                if (p.length >= 8) out.add(new Off(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]));
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** "23 small parts of 7 Fabric mods", or "" */
    static String featuresOffCount(List<Off> l) {
        if (l.isEmpty()) return "";
        Set<String> mods = new HashSet<>();
        for (Off o : l) mods.add(o.mod);
        return l.size() + (l.size() == 1 ? " small part" : " small parts") + " of " + mods.size() + " Fabric mod" + (mods.size() == 1 ? "" : "s");
    }

    private static boolean featuresLogged;

    /** one line at startup, so the log says it too */
    private static void logFeaturesOff() {
        if (featuresLogged) return;
        featuresLogged = true;
        List<Off> l = featuresOff(null);
        if (l.isEmpty()) return;
        Map<String, Integer> per = new LinkedHashMap<>();
        for (Off o : l) per.merge(o.mod, 1, Integer::sum);
        List<String> most = new ArrayList<>();
        per.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(3).forEach(e -> most.add(e.getKey() + " " + e.getValue()));
        LOG.info("Alloy: {} {} switched off on Alloy (they do not fit Forge's changes; the mods load and work, nothing to do). Most: {}. The list is in alloy-report.txt",
                featuresOffCount(l), l.size() == 1 ? "is" : "are", String.join(", ", most));
    }

    private static void featuresOffSection(StringBuilder sb) {
        int[] meta = new int[2];
        List<Off> l = featuresOff(meta);
        if (l.isEmpty()) return;
        String count = featuresOffCount(l);
        sb.append("== SWITCHED OFF ON ALLOY (").append(count).append(") ==\n");
        for (String w : wrap("This is not an error and there is nothing to do. " + count + (l.size() == 1 ? " is" : " are") + " switched off: each one is a"
                + " hook of a Fabric mod that does not fit the changes Forge makes to Minecraft. The mods load and work; most of these are"
                + " speed-ups or details you will not notice. Hooks that Alloy does itself in another way are not listed. If something you"
                + " need is missing, tell the Alloy developer. The technical list is in .alloy/fit-report.txt.", 90)) sb.append(w).append('\n');
        if (meta[0] > 0 || meta[1] > 0) {
            List<String> also = new ArrayList<>();
            if (meta[0] > 0) also.add(meta[0] + " more " + (meta[0] == 1 ? "is" : "are") + " done by Alloy's own code instead, so they still work");
            if (meta[1] > 0) also.add(meta[1] + " only matter for data generation, not for playing");
            sb.append("Not counted: ").append(String.join("; ", also)).append(".\n");
        }
        Map<String, List<Off>> per = new LinkedHashMap<>();
        for (Off o : l) per.computeIfAbsent(o.mod, k -> new ArrayList<>()).add(o);
        for (var e : per.entrySet()) {
            Off f = e.getValue().get(0);
            sb.append('\n').append(f.mod).append(' ').append(f.version).append(" (").append(f.jar).append("): ").append(e.getValue().size()).append(" off\n");
            for (Off o : e.getValue())
                sb.append("  - ").append(o.words).append(" (").append(o.target).append(o.area.isEmpty() || o.area.equals("other") ? "" : ", " + o.area).append(")\n");
        }
        sb.append('\n');
    }

    private static void section(StringBuilder sb, String head, List<Entry> list) {
        if (list.isEmpty()) return;
        sb.append("== ").append(head).append(" ==\n");
        int n = 0;
        for (Entry e : list) {
            sb.append('\n').append(++n).append(". ").append(e.title).append('\n');
            sb.append("   Side: ").append(e.side);
            if (!e.mod.isEmpty()) sb.append("   Mod: ").append(e.mod);
            if (!e.jar.isEmpty()) sb.append("   File: ").append(e.jar);
            sb.append('\n');
            if (!e.detail.isEmpty()) for (String l : wrap(e.detail, 90)) sb.append("   ").append(l).append('\n');
            if (!e.fix.isEmpty()) for (String l : wrap("What to do: " + e.fix, 90)) sb.append("   ").append(l).append('\n');
            if (!e.technical.isEmpty()) { sb.append("   Technical:\n"); for (String l : e.technical.split("\n")) sb.append("     ").append(l).append('\n'); }
        }
        sb.append('\n');
    }

    public static synchronized void write() {
        try {
            Path f = reportFile();
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, render(), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            LOG.warn("Alloy report: cannot write {}: {}", file, t.toString());
        }
    }

    /** Word wrap by character count (no font needed). */
    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String w : para.split("\\s+")) {
                if (line.length() > 0 && line.length() + 1 + w.length() > width) { out.add(line.toString()); line.setLength(0); }
                if (line.length() > 0) line.append(' ');
                line.append(w);
            }
            if (line.length() > 0) out.add(line.toString());
        }
        return out;
    }

    private static void safe(Runnable r) {
        try { r.run(); } catch (Throwable t) { try { LOG.warn("Alloy report: internal error: {}", t.toString()); } catch (Throwable ignored) { } }
    }
}
