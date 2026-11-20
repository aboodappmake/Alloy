package alloy.report;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads crash text (a crash report, latest.log, a launcher log) and says in one plain line why the game stopped and
 * what to do. Pure JDK, no Minecraft, Forge or Fabric classes: the bridge uses it in the game, the installer's Mod
 * Doctor uses it before launch to explain the last crash, and tests/test_log_diagnosis.py feeds it saved snippets.
 *
 *   java alloy.report.LogDiagnosis FILE...   prints  file<TAB>KIND<TAB>mod,mod<TAB>headline
 */
public final class LogDiagnosis {
    private LogDiagnosis() { }

    public enum Kind {
        /** a mod needs another mod that is not installed (or has the wrong version) */
        MISSING_DEPENDENCY,
        /** a mod was made for another Minecraft, Forge or library version */
        WRONG_VERSION,
        /** the game or a mod needs a newer Java */
        WRONG_JAVA,
        /** known to fail the same way on plain Forge or plain Fabric: not caused by Alloy */
        FAILS_ON_REAL_LOADER,
        /** the mod refuses to run next to the other loader, by design */
        NOT_SUPPORTED_ON_ALLOY,
        /** a mod's mixin (code patch) does not fit, usually a Fabric mod against Forge's changes or the other way round */
        MIXIN_CONFLICT,
        /** a mod added blocks/items after the game closed its registries */
        FROZEN_REGISTRY,
        /** two copies of one mod */
        DUPLICATE_MOD,
        /** mods that declare they do not work together */
        INCOMPATIBLE_MODS,
        /** a NeoForge-only mod */
        NEOFORGE_MOD,
        OUT_OF_MEMORY,
        /** a bug in Alloy itself (for example isolation hiding a class a mod needs) */
        ALLOY_BUG,
        /** Forge's or Alloy's own files are missing or unreadable */
        BROKEN_INSTALL,
        /** a client-only mod on a dedicated server */
        CLIENT_ONLY_ON_SERVER,
        /** Java refused a class's code (broken by a transformer or a code patch) */
        BAD_BYTECODE,
        /** one class or library loaded twice, or by two class loaders that clash */
        CLASSLOADER_CLASH,
        /** a signed jar that was changed or shares a package with another jar */
        JAR_SIGNATURE,
        /** a block, item or other registry entry that is missing or was never registered */
        REGISTRY_ENTRY,
        /** world generation data that does not fit together */
        WORLDGEN,
        /** a broken data pack, resource pack or KubeJS file */
        DATA_ERROR,
        /** the world was saved with other mods or versions */
        WORLD_MISMATCH,
        /** a broken config file, or a setting that has to change */
        CONFIG,
        /** the PC, the disk, the network or a second program, not a mod */
        ENVIRONMENT,
        /** the server or the game hung */
        HANG,
        /** client and server do not match, or a packet is too big */
        NETWORK,
        /** a native crash in the graphics driver or in Java itself */
        DRIVER,
        /** extra information only (a crash signature "note") */
        HINT,
        /** a mod crashed and none of the rules above explains why */
        MOD_CRASH,
        UNKNOWN
    }

    /**
     * @param signature id of the crash signature that gave the headline ("" = a built-in rule)
     * @param also      the other crash signatures that matched (notes included), in priority order
     */
    public record Result(Kind kind, List<String> mods, String line, String fix, String signature, List<Hit> also) {
        public Result(Kind kind, List<String> mods, String line, String fix) { this(kind, mods, line, fix, "", List.of()); }
        /** The one line printed at the top of the log, the crash report and alloy-report.txt. */
        public String headline() { return "ALLOY: " + line + (fix.isEmpty() ? "" : " Fix: " + fix); }
        public boolean specific() { return kind != Kind.UNKNOWN && kind != Kind.MOD_CRASH && kind != Kind.HINT; }
        /** the headline, then one "Also: ..." line per other matching signature */
        public List<String> lines() {
            List<String> l = new ArrayList<>();
            l.add(headline());
            for (Hit h : also) l.add("Also: " + h.text());
            return l;
        }
        Result with(String sig, List<Hit> more) { return new Result(kind, mods, line, fix, sig, List.copyOf(more)); }
    }

    /** One matching crash signature (alloy/report/crash-signatures.json). */
    public record Hit(String id, Kind kind, boolean note, String cause, String fix, List<String> mods, String matched) {
        public String text() { return cause + (fix.isEmpty() ? "" : " Fix: " + fix); }
    }

    static final String MC = "26.1.2", JAVA = "25";

    /** Known libraries by package prefix: display name and Modrinth slug ("" = none). Shared with {@link Classifier}. */
    public static final String[][] KNOWN_LIBS = {
            {"net.fabricmc.fabric", "Fabric API", "fabric-api"},
            {"me.shedaniel.clothconfig2", "Cloth Config API", "cloth-config"},
            {"me.shedaniel.autoconfig", "Cloth Config API", "cloth-config"},
            {"me.shedaniel.math", "Cloth Config API", "cloth-config"},
            {"dev.architectury", "Architectury API", "architectury-api"},
            {"software.bernie.geckolib", "GeckoLib", "geckolib"},
            {"mod.azure.azurelib", "AzureLib", "azurelib"},
            {"com.teamresourceful.resourcefullib", "Resourceful Lib", "resourceful-lib"},
            {"com.teamresourceful.resourcefulconfig", "Resourceful Config", "resourceful-config"},
            {"net.blay09.mods.balm", "Balm", "balm"},
            {"glitchcore", "GlitchCore", "glitchcore"},
            {"terrablender", "TerraBlender", "terrablender"},
            {"com.terraformersmc.modmenu", "Mod Menu", "modmenu"},
            {"dev.isxander.yacl3", "YetAnotherConfigLib", "yacl"},
            {"fuzs.puzzleslib", "Puzzles Lib", "puzzles-lib"},
            {"fuzs.forgeconfigapiport", "Forge Config API Port", "forge-config-api-port"},
            {"thedarkcolour.kotlinforforge", "Kotlin for Forge", "kotlin-for-forge"},
            {"kotlin.", "Kotlin (Fabric Language Kotlin or Kotlin for Forge)", "fabric-language-kotlin"},
            {"com.electronwill.nightconfig", "Night Config (bundled with Forge)", ""},
    };

    /** Mod ids that are libraries: shown as their name. */
    static final Map<String, String> PRETTY = Map.ofEntries(
            Map.entry("fabric-api", "Fabric API"), Map.entry("fabric", "Fabric API"), Map.entry("glitchcore", "GlitchCore"),
            Map.entry("terrablender", "TerraBlender"), Map.entry("geckolib", "GeckoLib"), Map.entry("architectury", "Architectury API"),
            Map.entry("cloth-config", "Cloth Config API"), Map.entry("cloth_config", "Cloth Config API"), Map.entry("balm", "Balm"),
            Map.entry("forgeconfigapiport", "Forge Config API Port"), Map.entry("libipn", "libIPN"), Map.entry("collective", "Collective"),
            Map.entry("kotlinforforge", "Kotlin for Forge"), Map.entry("fabric-language-kotlin", "Fabric Language Kotlin"),
            Map.entry("terralith", "Terralith"), Map.entry("curios", "Curios API"));

    // ------------------------------------------------------------------ entry points

    public static Result diagnose(String text) {
        String t = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        List<Hit> hits = signatures(t);
        Hit used = null;
        Result res = null;
        for (Hit h : hits) if (!h.note() && signatureFirst(h.id())) { used = h; res = fromHit(h, null); break; }
        if (res == null) res = rules(t);
        if (!res.specific()) {
            for (Hit h : hits) if (!h.note()) { used = h; res = fromHit(h, res); break; }
        }
        List<Hit> rest = new ArrayList<>(hits);
        if (used != null) rest.remove(used);
        return res.with(used == null ? "" : used.id(), rest);
    }

    static Result rules(String t) {
        for (Rule r : RULES) {
            Result x = r.apply(t);
            if (x != null) return x;
        }
        return new Result(Kind.UNKNOWN, List.of(), "The game stopped, but Alloy could not tell why from this log.",
                "Send the crash report and alloy-report.txt to whoever helps you with this setup.");
    }

    /** a signature hit as the headline; {@code generic} is what the built-in rules found (UNKNOWN or MOD_CRASH), or null */
    static Result fromHit(Hit h, Result generic) {
        // "forge" or the game jar (game-srg.jar) as the first frame is no mod to blame
        List<String> gm = generic == null ? List.of() : generic.mods().stream()
                .filter(x -> !x.equals("forge") && !x.equals("minecraft") && !x.startsWith("game-") && !PLATFORM_JAR.matcher(x).find()).toList();
        if (generic != null && generic.kind() == Kind.MOD_CRASH && !gm.isEmpty()) {
            generic = new Result(generic.kind(), gm, generic.line(), generic.fix());
            Set<String> mods = new LinkedHashSet<>(h.mods());
            mods.addAll(generic.mods());
            if (h.kind() == Kind.MOD_CRASH) return new Result(Kind.MOD_CRASH, List.copyOf(mods), generic.line() + " " + h.cause(), h.fix());
            String first = generic.mods().get(0);
            return new Result(h.kind(), List.copyOf(mods), h.cause() + (h.mods().isEmpty() ? " The first mod in the stack trace is " + first + "." : ""), h.fix());
        }
        return new Result(h.kind(), h.mods(), h.cause(), h.fix());
    }

    public static Result diagnoseFile(Path p) {
        try {
            byte[] b = Files.readAllBytes(p);
            return diagnose(cut(new String(b, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return new Result(Kind.UNKNOWN, List.of(), "Could not read " + p.getFileName() + ".", "");
        }
    }

    /** a huge latest.log: the start (mod resolution) and the end (the crash) are what matter */
    public static String cut(String s) {
        return s.length() > 600_000 ? s.substring(0, 200_000) + "\n" + s.substring(s.length() - 400_000) : s;
    }

    /**
     * FILE...                  file, kind, mods, headline (then "+id" lines for the other matching signatures)
     * --signatures FILE...     every matching signature: file, id, note|cause, matched text (for tests and false-positive checks)
     * --check                  load the signature file and print "N signatures" plus the entries that do not compile (exit 1)
     */
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--check")) {
            List<Sig> s = sigs();
            for (String e : SIG_ERRORS) System.out.println("BAD\t" + e);
            System.out.println(s.size() + " signatures");
            System.exit(SIG_ERRORS.isEmpty() && !s.isEmpty() ? 0 : 1);
        }
        boolean only = args.length > 0 && args[0].equals("--signatures");
        List<String> files = new ArrayList<>();
        for (String a : only ? Arrays.copyOfRange(args, 1, args.length) : args) {
            if (!a.startsWith("@")) { files.add(a); continue; }
            try { for (String l : Files.readAllLines(Path.of(a.substring(1)), StandardCharsets.UTF_8)) if (!l.isBlank()) files.add(l.trim()); }   // @LIST: one file per line
            catch (Exception e) { System.err.println("cannot read " + a); }
        }
        for (String a : files) {
            String name = Path.of(a).getFileName().toString();
            if (only) {
                String text;
                try { text = cut(new String(Files.readAllBytes(Path.of(a)), StandardCharsets.UTF_8)); } catch (Exception e) { text = ""; }
                for (Hit h : signatures(text))
                    System.out.println(a + "\t" + h.id() + "\t" + (h.note() ? "note" : "cause") + "\t" + oneLine(h.matched()).replace('\t', ' '));
                continue;
            }
            Result r = diagnoseFile(Path.of(a));
            System.out.println(name + "\t" + r.kind() + "\t" + String.join(",", r.mods()) + "\t" + r.headline());
            for (Hit h : r.also()) System.out.println(name + "\t+" + h.id() + "\t\t" + h.text());
        }
    }

    // ------------------------------------------------------------------ crash signatures (alloy/report/crash-signatures.json)

    record Sig(String id, Kind kind, boolean note, boolean first, int lines, int mod, Pattern p, Pattern unless, Pattern when, List<String> replaces,
               String cause, String fix) { }

    private static volatile List<Sig> SIGS;
    static final List<String> SIG_ERRORS = new ArrayList<>();
    /** a log line's level: matches on INFO/DEBUG/TRACE (and WARN, unless the signature allows it) lines are not crash causes */
    private static final Pattern LEVEL = Pattern.compile("^\\[[^\\]\\n]*\\] \\[[^\\]\\n]*/(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\]");
    private static final Pattern PLACE = Pattern.compile("\\{(\\d)(?:\\|([^{}]*(?:\\{\\d\\}[^{}]*)*))?\\}");

    /** The loaded signatures. -Dalloy.crashSignatures=FILE reads that file instead of the bundled one. */
    static List<Sig> sigs() {
        List<Sig> s = SIGS;
        if (s != null) return s;
        synchronized (SIG_ERRORS) {
            if (SIGS != null) return SIGS;
            List<Sig> out = new ArrayList<>();
            try {
                String json = null;
                String file = System.getProperty("alloy.crashSignatures");
                if (file != null) json = Files.readString(Path.of(file), StandardCharsets.UTF_8);
                else try (var in = LogDiagnosis.class.getResourceAsStream("/alloy/report/crash-signatures.json")) {
                    if (in != null) json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (json != null) {
                    Object doc = new MiniJson(json).value();
                    Object list = doc instanceof Map<?, ?> m ? m.get("signatures") : doc;
                    if (list instanceof List<?> l) for (Object o : l) {
                        if (!(o instanceof Map<?, ?> e)) continue;
                        String id = str(e.get("id"));
                        try {
                            Kind k = Kind.valueOf(str(e.get("kind")));
                            boolean note = "note".equals(str(e.get("level")));
                            String lines = e.get("lines") == null ? "error" : str(e.get("lines"));
                            int lv = lines.equals("any") ? 2 : lines.equals("warn") ? 1 : 0;
                            int mod = e.get("mod") instanceof Number n ? n.intValue() : 0;
                            Pattern un = e.get("unless") == null ? null : Pattern.compile(str(e.get("unless")));
                            Pattern wh = e.get("when") == null ? null : Pattern.compile(str(e.get("when")));
                            List<String> rp = new ArrayList<>();
                            if (e.get("replaces") instanceof List<?> r) for (Object x : r) rp.add(str(x));
                            out.add(new Sig(id, note ? Kind.HINT : k, note, Boolean.TRUE.equals(e.get("first")), lv, mod,
                                    Pattern.compile(str(e.get("pattern"))), un, wh, List.copyOf(rp), str(e.get("cause")), str(e.get("fix"))));
                        } catch (RuntimeException x) { SIG_ERRORS.add(id + ": " + oneLine(String.valueOf(x.getMessage()))); }
                    }
                }
            } catch (Exception x) { SIG_ERRORS.add("crash-signatures.json: " + x); }
            SIGS = List.copyOf(out);
            return SIGS;
        }
    }

    static boolean signatureFirst(String id) {
        for (Sig s : sigs()) if (s.id().equals(id)) return s.first();
        return false;
    }

    /**
     * every signature that matches the text (one hit per signature: its first match that passes the line rule), in file order.
     * A hit drops the hits of the signatures its entry lists in "replaces" (a narrower reading of the same text).
     */
    public static List<Hit> signatures(String text) {
        String t = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        List<Hit> hits = new ArrayList<>();
        Set<String> replaced = new HashSet<>();
        for (Sig s : sigs()) {
            if (s.unless() != null && s.unless().matcher(t).find()) continue;
            if (s.when() != null && !s.when().matcher(t).find()) continue;
            Matcher m = s.p().matcher(t);
            while (m.find()) {
                if (s.lines() < 2) {
                    String lv = lineLevel(t, m.start());
                    if (lv != null && (lv.equals("TRACE") || lv.equals("DEBUG") || lv.equals("INFO") || (lv.equals("WARN") && s.lines() == 0))) continue;
                }
                String[] g = new String[m.groupCount() + 1];
                for (int i = 0; i <= m.groupCount(); i++) g[i] = m.group(i) == null ? "" : modmixinsOwner(m.group(i).trim(), m.group());
                List<String> mods = new ArrayList<>();
                if (s.mod() > 0 && s.mod() < g.length && !g[s.mod()].isEmpty()) mods.add(g[s.mod()]);
                hits.add(new Hit(s.id(), s.kind(), s.note(), fill(s.cause(), g), fill(s.fix(), g), List.copyOf(mods), shorten(m.group(), 300)));
                replaced.addAll(s.replaces());
                break;
            }
        }
        if (!replaced.isEmpty()) hits.removeIf(h -> replaced.contains(h.id()));
        return hits;
    }

    /**
     * The log level of the line at {@code pos}: its own "[time] [thread/LEVEL]" prefix, else (a stack trace or message
     * line) the prefix of the nearest log line above it. null = no log line (crash report text, a launcher log).
     */
    static String lineLevel(String t, int pos) {
        int ls = pos <= 0 ? 0 : t.lastIndexOf('\n', pos - 1) + 1;
        for (int n = 0; n < 400; n++) {
            int le = t.indexOf('\n', ls);
            String line = t.substring(ls, le < 0 ? t.length() : le);
            if (line.startsWith("---- Minecraft Crash Report ----")) return null;
            if (line.startsWith("[")) {
                Matcher lv = LEVEL.matcher(line);
                if (lv.lookingAt()) return lv.group(1);
            }
            if (ls == 0) return null;
            ls = ls < 2 ? 0 : t.lastIndexOf('\n', ls - 2) + 1;
        }
        return null;
    }

    /** {N} = regex group N ("(see the log)" when empty), {N|text} = text with {N} filled in, or nothing when group N is empty */
    static String fill(String text, String[] groups) {
        Matcher p = PLACE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (p.find()) {
            int g = p.group(1).charAt(0) - '0';
            String v = g < groups.length ? shorten(groups[g], 160) : "";
            String rep;
            if (p.group(2) == null) rep = v.isEmpty() ? "(see the log)" : v;
            else if (p.group(2).contains("{" + g + "}")) rep = v.isEmpty() ? "" : p.group(2).replace("{" + g + "}", v);
            else rep = v.isEmpty() ? p.group(2) : v;   // {N|fallback}
            p.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        p.appendTail(sb);
        return sb.toString().replaceAll(" {2,}", " ").replace(" .", ".");
    }

    /** Alloy loads Forge mods' mixins under one made-up mod id (alloy_modmixins): name the mixin config's owner instead */
    private static final Pattern MIXIN_CONFIG = Pattern.compile("([\\w-]+(?:\\.[\\w-]+)*?)[.-]mixins?\\.json");

    static String modmixinsOwner(String v, String matched) {
        if (!v.endsWith("_modmixins")) return v;
        Matcher c = MIXIN_CONFIG.matcher(matched);
        return c.find() ? c.group(1).replaceAll("\\.(forge|common|client)$", "") : "a Forge mod";
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    /** just enough JSON for the signature file: objects, arrays, strings, numbers, true/false/null */
    static final class MiniJson {
        private final String s;
        private int i;
        MiniJson(String s) { this.s = s; }
        Object value() {
            ws();
            if (i >= s.length()) throw err("end of text");
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (peek('}')) { i++; return m; }
                while (true) {
                    ws();
                    String k = string();
                    ws();
                    expect(':');
                    m.put(k, value());
                    ws();
                    if (peek(',')) { i++; continue; }
                    expect('}');
                    return m;
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (peek(']')) { i++; return l; }
                while (true) {
                    l.add(value());
                    ws();
                    if (peek(',')) { i++; continue; }
                    expect(']');
                    return l;
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw err("unexpected '" + c + "'");
            String n = s.substring(st, i);
            return n.contains(".") || n.contains("e") || n.contains("E") ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n);
        }
        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err("open string");
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> b.append(e);
                }
            }
        }
        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }
        private void expect(char c) { if (!peek(c)) throw err("expected '" + c + "'"); i++; }
        private IllegalArgumentException err(String what) { return new IllegalArgumentException("JSON: " + what + " at " + i); }
    }

    // ------------------------------------------------------------------ rules (first match wins, most specific first)

    private interface Rule { Result apply(String t); }

    private static final List<Rule> RULES = List.of(
            LogDiagnosis::jvmJava,
            LogDiagnosis::fabricResolution,
            LogDiagnosis::outOfMemory,
            LogDiagnosis::forgeFailures,
            LogDiagnosis::invalidRange,
            LogDiagnosis::neoForge,
            LogDiagnosis::knownRealLoaderBugs,
            LogDiagnosis::frozenRegistry,
            LogDiagnosis::mixin,
            LogDiagnosis::missingClass,
            LogDiagnosis::linkage,
            LogDiagnosis::genericCrash);

    private static final Pattern UCVE = Pattern.compile("UnsupportedClassVersionError: (\\S+) has been compiled by a more recent version of the Java Runtime "
            + "\\(class file version (\\d+)(?:\\.\\d+)?\\), this version of the Java Runtime only recognizes class file versions up to (\\d+)");
    private static final Pattern ALLOY_JAVA = Pattern.compile("Alloy needs Java (\\d+) or newer\\. (?:You have|This game runs on) Java (\\S+?)\\.");

    static Result jvmJava(String t) {
        Matcher m = UCVE.matcher(t);
        if (m.find()) {
            int need = Integer.parseInt(m.group(2)) - 44, have = Integer.parseInt(m.group(3)) - 44;
            String cls = m.group(1).replace('/', '.');
            String who = cls.startsWith("net.minecraft") || cls.startsWith("alloy.") || cls.startsWith("net.fabricmc") || cls.startsWith("net.minecraftforge")
                    ? "Minecraft " + MC + " with Alloy" : "A mod (class " + cls + ")";
            return new Result(Kind.WRONG_JAVA, List.of(), who + " needs Java " + need + ", but the game was started with Java " + have + ".",
                    javaFix());
        }
        m = ALLOY_JAVA.matcher(t);
        if (m.find()) return new Result(Kind.WRONG_JAVA, List.of(), "Alloy needs Java " + m.group(1) + ", but the game was started with Java " + m.group(2) + ".", javaFix());
        return null;
    }

    static String javaFix() {
        return "In your launcher, set this instance's Java to Java " + JAVA + " (for example Eclipse Temurin " + JAVA + ": https://adoptium.net/temurin/releases/?version=" + JAVA + ").";
    }

    // Fabric Loader's "Some of your mods are incompatible" block: one detail line per problem
    private static final Pattern FAB_LINE = Pattern.compile("^\\s*- Mod '([^']+)' \\(([^)]+)\\)(?: (\\S+))? (.+)$", Pattern.MULTILINE);
    private static final Pattern FAB_DEP = Pattern.compile("requires (.+?) of (?:mod )?(?:'([^']+)' \\(([^)]+)\\)|'?([\\w.-]+)'?),? (?:which is missing|but only the wrong version is present: (\\S+?))!?$");

    static Result fabricResolution(String t) {
        if (!t.contains("Some of your mods are incompatible") && !t.contains("Incompatible mods found") && !t.contains("Mod resolution failed")) return null;
        List<Result> found = new ArrayList<>();
        Matcher m = FAB_LINE.matcher(t);
        while (m.find()) {
            String name = m.group(1), id = m.group(2), rest = m.group(4).trim();
            String who = "Fabric mod " + name;
            Matcher d = FAB_DEP.matcher(rest);
            if (d.find()) {
                String need = d.group(1).replace("any version", "any version").trim();
                String depId = d.group(3) != null ? d.group(3) : d.group(4);
                String depName = d.group(2) != null ? d.group(2) : pretty(depId);
                String have = d.group(5);
                if (depId.equals("java")) {
                    found.add(new Result(Kind.WRONG_JAVA, List.of(id), (id.equals("minecraft") ? "Minecraft " + MC : who) + " needs Java " + need.replace("version ", "")
                            + ", but the game was started with Java " + have + ".", javaFix()));
                } else if (depId.equals("minecraft")) {
                    found.add(new Result(Kind.WRONG_VERSION, List.of(id), who + " was made for Minecraft " + need.replace("version ", "") + ", not " + (have == null ? MC : have) + ".",
                            "Download the Minecraft " + MC + " build of " + name + " (Fabric), or remove it from mods/."));
                } else if (depId.equals("fabricloader")) {
                    found.add(new Result(Kind.WRONG_VERSION, List.of(id), who + " needs Fabric Loader " + need.replace("version ", "") + ", Alloy has " + have + ".",
                            "Use an older build of " + name + " or wait for an Alloy update."));
                } else if (have == null) {
                    found.add(new Result(Kind.MISSING_DEPENDENCY, List.of(id, depId), who + " needs " + depName + " (" + need + "), which is not installed.",
                            "Download " + depName + " for Minecraft " + MC + " (Fabric) from " + link(depId) + " and put it in mods/."));
                } else {
                    found.add(new Result(Kind.MISSING_DEPENDENCY, List.of(id, depId), who + " needs " + depName + " " + need.replace("version ", "") + ", but " + have + " is installed.",
                            "Replace " + depName + " in mods/ with a matching version: " + link(depId)));
                }
            } else if (rest.contains("present multiple times") || rest.contains("multiple versions") || rest.toLowerCase(Locale.ROOT).contains("duplicate")) {
                String files = rest.contains(": ") ? rest.substring(rest.indexOf(": ") + 2) : "";
                found.add(new Result(Kind.DUPLICATE_MOD, List.of(id), "Fabric mod " + name + " is installed more than once" + (files.isEmpty() ? "" : " (" + files.replaceAll("[.!]$", "") + ")") + ".",
                        "Keep only the newest jar of " + name + " in mods/ and delete the others."));
            } else if (rest.contains("incompatible with") || rest.contains("breaks")) {
                Matcher o = Pattern.compile("(?:mod )?'([^']+)'").matcher(rest);
                String other = o.find() ? o.group(1) : "another mod";
                found.add(new Result(Kind.INCOMPATIBLE_MODS, List.of(id), who + " says it does not work together with " + other + ".",
                        "Remove one of the two from mods/."));
            }
        }
        if (found.isEmpty()) {
            Matcher dup = Pattern.compile("(?i)duplicate mods?[^\\n]*?[`'\"]([\\w.-]+)[`'\"]").matcher(t);
            if (dup.find()) return new Result(Kind.DUPLICATE_MOD, List.of(dup.group(1)), "Mod " + dup.group(1) + " is installed more than once.",
                    "Keep only the newest jar of " + dup.group(1) + " and delete the others.");
            return new Result(Kind.INCOMPATIBLE_MODS, List.of(), "Fabric Loader found mods that do not fit together (see the list under \"Incompatible mods found\").",
                    "Read the lines that start with \"- Mod\" in the log: each one names a mod and what it needs.");
        }
        // the blocker that explains most: Java first, then Minecraft version, then missing mods
        found.sort(Comparator.comparingInt(r -> List.of(Kind.WRONG_JAVA, Kind.WRONG_VERSION, Kind.MISSING_DEPENDENCY, Kind.DUPLICATE_MOD, Kind.INCOMPATIBLE_MODS).indexOf(r.kind())));
        Result first = found.get(0);
        if (found.size() > 1) return new Result(first.kind(), first.mods(), first.line() + " (" + (found.size() - 1) + " more problem(s) listed in the log.)", first.fix());
        return first;
    }

    static Result outOfMemory(String t) {
        if (!t.contains("java.lang.OutOfMemoryError")) return null;
        return new Result(Kind.OUT_OF_MEMORY, List.of(), "Minecraft ran out of memory.",
                "Give the instance more RAM in your launcher (for example 6 GB for a big pack), and close other programs.");
    }

    // Forge: "Failure message: ..." blocks in a mod-loading crash report, or the same text in the log
    private static final Pattern F_LANG = Pattern.compile("Mod File (\\S+?\\.jar) needs language provider (\\S+?):(\\S+) or above to load(?:\\s*\\n\\s*We have found (\\S+))?");
    private static final Pattern F_REQ = Pattern.compile("Mod (\\S+) requires (\\S+) ([^\\n]+?)\\s*\\n\\s*Currently, \\S+ is ([^\\n]+)");
    private static final Pattern F_REQ1 = Pattern.compile("Mod (\\S+) requires (\\S+) ([^\\n]+)");
    private static final Pattern F_RANGE = Pattern.compile("acceptable version range for : (\\S+?):(\\S+?), but no jar was provided");
    private static final Pattern F_FAIL = Pattern.compile("Failure message: (.+?) \\((\\S+)\\) (?:encountered an error during the (\\S+) event phase|has failed to load correctly)");
    private static final Pattern F_CLASSLOAD = Pattern.compile("Failure message: (.+?) has class loading errors");
    private static final Pattern F_EXMSG = Pattern.compile("Exception message: ([^\\n]+)");
    private static final Pattern F_DUP = Pattern.compile("(?:Found duplicate mods|Mod (?:ID: )?'?([\\w-]+)'? is present in multiple files)[^\\n]*?(?:[`'\"]([\\w-]+)[`'\"])?[^\\n]*");

    private static final Pattern F_BADFILE = Pattern.compile("InvalidModFileException: ([^\\n]+?) \\(([^()\\n]+?\\.jar)\\)");

    static Result forgeFailures(String t) {
        Matcher m = F_BADFILE.matcher(t);
        if (m.find()) {
            String jar = m.group(2).replaceAll(".*[\\\\/]", "");
            return new Result(Kind.FAILS_ON_REAL_LOADER, List.of(jar), "Forge mod " + jar + " has a broken mods.toml (" + shorten(m.group(1), 120)
                    + "), so Forge stops scanning mods. Plain Forge refuses this file too.",
                    "Remove " + jar + " from forgemods/ or get a fixed version, and report it to its author.");
        }
        if (t.contains("Failed to find system mod: ")) {
            Matcher s = Pattern.compile("Failed to find system mod: (\\S+)").matcher(t);
            String id = s.find() ? s.group(1) : "forge";
            return new Result(Kind.BROKEN_INSTALL, List.of(), "Forge could not find its own " + id + " files while scanning mods.",
                    "Look for an earlier error about a mod file in the log. If there is none, re-run the Alloy installer (the install is incomplete).");
        }
        m = F_LANG.matcher(t);
        if (m.find()) {
            String jar = m.group(1), need = m.group(3), have = m.group(4);
            return new Result(Kind.WRONG_VERSION, List.of(jar), "Forge mod " + jar + " was made for a newer Forge (it needs " + m.group(2) + " " + need
                    + (have == null ? "" : ", this game has " + have) + "), so Forge did not load it.",
                    "Download the Minecraft " + MC + " / Forge 64 build of that mod, or remove " + jar + " from forgemods/.");
        }
        List<Result> reqs = new ArrayList<>();
        m = F_REQ.matcher(t);
        Set<String> seen = new HashSet<>();
        while (m.find()) {
            String mod = m.group(1), dep = m.group(2), range = m.group(3).trim(), state = m.group(4).trim();
            if (!seen.add(mod + ">" + dep)) continue;
            reqs.add(forgeReq(mod, dep, range, state));
        }
        if (reqs.isEmpty()) {
            m = F_REQ1.matcher(t);
            while (m.find()) {
                if (!t.contains("Failure message: Mod " + m.group(1))) continue;
                if (!seen.add(m.group(1) + ">" + m.group(2))) continue;
                reqs.add(forgeReq(m.group(1), m.group(2), m.group(3).trim(), "?"));
            }
        }
        if (!reqs.isEmpty()) {
            reqs.sort(Comparator.comparingInt(r -> r.kind() == Kind.WRONG_VERSION ? 0 : 1));
            Result f = reqs.get(0);
            if (reqs.size() > 1) {
                Set<String> mods = new LinkedHashSet<>();
                for (Result r : reqs) mods.addAll(r.mods());
                return new Result(f.kind(), List.copyOf(mods), f.line() + " (" + (reqs.size() - 1) + " more missing or wrong-version mod(s) are listed in the crash report.)", f.fix());
            }
            return f;
        }
        m = F_RANGE.matcher(t);
        if (m.find()) {
            String lib = m.group(2);
            String name = lib.startsWith("mixinextras") ? "MixinExtras" : lib;
            return new Result(Kind.MISSING_DEPENDENCY, List.of(lib), "Forge mods need the library " + name + " (" + m.group(1) + ":" + lib + "), but no matching jar was found.",
                    "Re-run the Alloy installer (it adds " + name + "), or add the " + name + " jar a mod's page asks for to forgemods/.");
        }
        m = F_DUP.matcher(t);
        if (m.find()) {
            String id = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : "?";
            return new Result(Kind.DUPLICATE_MOD, List.of(id), "Forge mod " + id + " is installed more than once (" + oneLine(m.group()) + ").",
                    "Keep only the newest jar of " + id + " in forgemods/ and delete the others.");
        }
        m = F_FAIL.matcher(t);
        if (m.find()) {
            String name = m.group(1), id = m.group(2), phase = m.group(3);
            Matcher ex = F_EXMSG.matcher(t);
            String exMsg = ex.find(m.end()) ? ex.group(1).trim() : "";
            if (exMsg.contains("Only a single listener found")) return singleListener(name + " (" + id + ")");
            if (!exMsg.isEmpty() && !exMsg.startsWith("MISSING")) {
                Result inner = diagnoseCause(exMsg);
                if (inner != null) return inner;
            }
            return new Result(Kind.MOD_CRASH, List.of(id), "Forge mod " + name + " crashed while loading" + (phase == null ? "" : " (" + phase.replace('_', ' ') + " step)")
                    + (exMsg.isEmpty() || exMsg.startsWith("MISSING") ? "." : ": " + shorten(exMsg, 220)),
                    "Update " + name + " or remove it from forgemods/. If it also crashes on plain Forge, report it to its author.");
        }
        m = F_CLASSLOAD.matcher(t);
        if (m.find()) {
            String name = m.group(1).trim();
            return new Result(Kind.WRONG_VERSION, List.of(name), "Forge mod " + name + " could not load its classes; it was most likely made for another Minecraft or Forge version.",
                    "Download its Minecraft " + MC + " / Forge 64 build, or remove it from forgemods/.");
        }
        return null;
    }

    static Result forgeReq(String mod, String dep, String range, String state) {
        boolean missing = state.startsWith("not installed") || state.equals("?");
        String need = range.replace(" or above", "+").replace(", and below ", ", below ");
        if (dep.equals("minecraft") || dep.equals("forge") || dep.equals("javafml") || dep.equals("fml")) {
            String what = dep.equals("minecraft") ? "Minecraft " : "Forge ";
            return new Result(Kind.WRONG_VERSION, List.of(mod), "Forge mod " + mod + " was made for " + what + need + ", but this game is Minecraft " + MC + " with Forge 64.1.",
                    "Download the Minecraft " + MC + " / Forge 64 build of " + mod + ", or remove it from forgemods/.");
        }
        String dn = pretty(dep);
        if (missing) return new Result(Kind.MISSING_DEPENDENCY, List.of(mod, dep), "Forge mod " + mod + " needs " + dn + " " + need + ", which is not installed.",
                "Download " + dn + " for Minecraft " + MC + " (Forge) from " + link(dep) + " and put it in forgemods/.");
        return new Result(Kind.MISSING_DEPENDENCY, List.of(mod, dep), "Forge mod " + mod + " needs " + dn + " " + need + ", but the installed one is " + state.replaceAll("[.]$", "") + ".",
                "Replace " + dn + " in forgemods/ with a matching version: " + link(dep));
    }

    /** A one-line exception message from a Forge mod section: reuse the rules that work on plain text. */
    static Result diagnoseCause(String msg) {
        for (Rule r : List.<Rule>of(LogDiagnosis::knownRealLoaderBugs, LogDiagnosis::frozenRegistry, LogDiagnosis::neoForge, LogDiagnosis::missingClass)) {
            Result x = r.apply(msg);
            if (x != null) return x;
        }
        return null;
    }

    private static final Pattern RANGE = Pattern.compile("Failed to parse version spec (\\S+)|InvalidVersionSpecificationException: ([^\\n]+)");
    private static final Pattern RANGE_JAR = Pattern.compile("Forge mod (\\S+\\.jar) has an invalid version range");

    static Result invalidRange(String t) {
        Matcher m = RANGE.matcher(t);
        if (!m.find()) return null;
        String spec = m.group(1) != null ? m.group(1) : m.group(2).replaceAll(".*: ", "");
        Matcher j = RANGE_JAR.matcher(t);
        String jar = j.find() ? j.group(1) : null;
        return new Result(Kind.FAILS_ON_REAL_LOADER, jar == null ? List.of() : List.of(jar),
                (jar == null ? "A Forge mod" : "Forge mod " + jar) + " has a broken version range (" + spec + ") in its mods.toml, so Forge stops before loading any mod. Plain Forge fails the same way.",
                jar == null ? "Run Mod Doctor (it names the jar), or remove the Forge mod you added last. Report it to that mod's author."
                        : "Remove " + jar + " from forgemods/ or get a fixed version, and report it to its author.");
    }

    static Result neoForge(String t) {
        Matcher m = Pattern.compile("(?:NoClassDefFoundError|ClassNotFoundException): (net[./]neoforged[./]\\S+)").matcher(t);
        if (!m.find()) return null;
        String jar = firstModJar(t, 0);
        return new Result(Kind.NEOFORGE_MOD, jar == null ? List.of() : List.of(jar), (jar == null ? "A mod" : "Mod " + jar) + " is made for NeoForge. Alloy runs Forge and Fabric mods, not NeoForge mods.",
                "Remove " + (jar == null ? "the NeoForge mod" : jar) + " and look for its Forge or Fabric build.");
    }

    static Result knownRealLoaderBugs(String t) {
        Matcher m = Pattern.compile("Only a single listener found in class (\\S+?)\\.? ").matcher(t);
        if (m.find()) {
            Matcher f = Pattern.compile("Failure message: (.+?) \\((\\S+)\\) encountered").matcher(t);
            String who = f.find() ? f.group(1) + " (" + f.group(2) + ")" : ownerHint(m.group(1));
            return singleListener(who);
        }
        m = Pattern.compile("AccessDeniedException: ([^\\n]*?([\\w.-]+\\.toml))\\.new\\.tmp").matcher(t);
        if (m.find()) {
            String file = m.group(2);
            return new Result(Kind.FAILS_ON_REAL_LOADER, List.of(file), "Windows blocked a write to the config file " + file + " on the first start. Plain Forge does the same; it is not a broken mod.",
                    "Start the game again (the second start works). If it keeps happening, exclude the game folder from your antivirus or delete " + file + ".");
        }
        m = Pattern.compile("UnsupportedPlatformException: Forge Mod Loader detected").matcher(t);
        if (m.find()) {
            Matcher who = Pattern.compile("provided by '([^']+)'").matcher(t);
            String id = who.find() ? who.group(1) : "wthit";
            return new Result(Kind.NOT_SUPPORTED_ON_ALLOY, List.of(id), "Fabric mod " + id + " stops on purpose when it sees Forge, so its Fabric build cannot run on Alloy.",
                    "Use its Forge build in forgemods/ instead (or Jade), and remove the Fabric jar from mods/.");
        }
        return null;
    }

    static Result singleListener(String who) {
        return new Result(Kind.FAILS_ON_REAL_LOADER, List.of(who), "Forge mod " + who + " registers its events in a way Forge 64 refuses (\"Only a single listener found\"). Plain Forge fails the same way.",
                "Remove that mod from forgemods/, or wait for a fixed build from its author.");
    }

    private static final Pattern FROZEN = Pattern.compile("(?:Registry (\\S+) is already frozen|Can not register to a locked registry|Attempted to register object \\S+ twice)");

    static Result frozenRegistry(String t) {
        Matcher m = FROZEN.matcher(t);
        if (!m.find()) return null;
        String mod = firstModAfter(t, m.start());
        if (mod == null) mod = firstModJar(t, 0);
        String reg = m.group(1) != null ? m.group(1).replace("minecraft:", "") : "game";
        String who = mod == null ? "A mod" : "Mod " + mod;
        return new Result(Kind.FROZEN_REGISTRY, mod == null ? List.of() : List.of(mod),
                who + " tried to add " + reg + " entries after the game had finished registering them (the registry is closed).",
                "This is usually a knock-on error: fix the first error above it in the log first. If it is the only error, update or remove "
                        + (mod == null ? "the mod named in the stack trace" : mod) + ".");
    }

    private static final Pattern INJ = Pattern.compile("Critical injection failure: (?:[\\w ]+? )?(\\S+?)\\(.*?\\)\\S* in (\\S+?\\.json):(\\S+?) from mod (\\S+?) ");
    private static final Pattern APPLY = Pattern.compile("Mixin apply for mod (\\S+) failed (\\S+?\\.json):(\\S+?) from mod (\\S+) -> (\\S+?):");
    private static final Pattern TRANSFORM = Pattern.compile("Mixin transformation of (\\S+) failed");
    private static final Pattern CAST = Pattern.compile("ClassCastException: class (\\S+) cannot be cast to class (net\\.fabricmc\\.fabric\\.\\S+)");
    private static final Pattern DEFAULTS = Pattern.compile("Conflicting default methods: (\\S+) (\\S+)");

    static Result mixin(String t) {
        Matcher tr = TRANSFORM.matcher(t);
        String target = tr.find() ? simple(tr.group(1)) : null;
        Matcher m = INJ.matcher(t);
        if (m.find()) return mixinResult(m.group(2), m.group(3), m.group(4), target);
        m = APPLY.matcher(t);
        if (target != null && m.find()) return mixinResult(m.group(2), m.group(3), m.group(4), simple(m.group(5)));
        m = CAST.matcher(t);
        if (m.find()) {
            String cls = simple(m.group(1));
            Matcher h = Pattern.compile("handler\\$\\w+\\$([\\w-]+)\\$").matcher(t);
            String mod = h.find() ? h.group(1) : "fabric-api";
            return new Result(Kind.MIXIN_CONFLICT, List.of(mod), "Fabric API (" + mod + ") could not patch Minecraft's " + cls + " because Forge or a Forge mod changed it first, and then crashed using it.",
                    "Update Alloy and Fabric API. If it still happens, remove the mod you added last (it most likely also patches " + cls + ") and send alloy-report.txt to the Alloy developer.");
        }
        m = DEFAULTS.matcher(t);
        if (m.find()) {
            return new Result(Kind.MIXIN_CONFLICT, List.of(), "A Forge interface and a Fabric interface add the same method (" + simple(m.group(1)) + " and " + simple(m.group(2)) + "), so the class cannot load.",
                    "Remove the Fabric or Forge mod you added last, and send alloy-report.txt to the Alloy developer.");
        }
        if (target != null) {
            String mod = firstModAfter(t, tr.start());
            return new Result(Kind.MIXIN_CONFLICT, mod == null ? List.of() : List.of(mod), "A mod's code patch (mixin) could not be applied to Minecraft's " + target + (mod == null ? "" : "; the first mod in the trace is " + mod) + ".",
                    "Remove or update the mod that patches " + target + " (usually the one you added last).");
        }
        return null;
    }

    static Result mixinResult(String config, String mixin, String fromMod, String target) {
        boolean forgeMod = fromMod.endsWith("_modmixins");
        String owner = forgeMod ? config.replaceAll("[.-]?mixins?\\.json$", "").replaceAll("\\.(forge|common|client)$", "") : fromMod;
        String side = forgeMod ? "Forge mod " : "Mod ";
        String where = target == null ? "" : " to Minecraft's " + target;
        return new Result(Kind.MIXIN_CONFLICT, List.of(owner), side + owner + " has a code patch (" + config + ":" + simple(mixin) + ") that could not be applied" + where
                + ". Another mod, or Forge itself, already changed that code.",
                "Update " + owner + ", or remove it. If it works on plain " + (forgeMod ? "Forge" : "Fabric") + ", the clash is with a mod from the other loader: remove that one instead.");
    }

    private static final Pattern MISSING = Pattern.compile("(?:NoClassDefFoundError|ClassNotFoundException): ([\\w$./]+)");

    static Result missingClass(String t) {
        Matcher m = MISSING.matcher(t);
        String cls = null;
        while (m.find()) cls = m.group(1).replace('/', '.');   // the last one is the root cause
        if (cls == null) return null;
        if (cls.startsWith("alloy.hidden.")) {   // isolation renamed a reference to the other loader's class
            String rest = cls.substring("alloy.hidden.".length());
            String real = rest.contains(".") ? rest.substring(rest.indexOf('.') + 1) : rest;
            String mod = firstModJar(t, 0);
            return new Result(Kind.ALLOY_BUG, mod == null ? List.of() : List.of(mod), "Alloy's isolation hid class " + real + " from "
                    + (mod == null ? "a mod" : mod) + ". Nothing is missing: this is an Alloy bug.",
                    "Send alloy-report.txt to the Alloy developer. To play now, start the game with -Dalloy.isolation=warn.");
        }
        for (String[] lib : KNOWN_LIBS) {
            if (!cls.startsWith(lib[0])) continue;
            Matcher who = Pattern.compile("provided by '([^']+)'").matcher(t);
            String mod = who.find() ? who.group(1) : firstModJar(t, 0);
            boolean fabric = cls.startsWith("net.fabricmc");
            String fix = lib[2].isEmpty() ? "Re-run the Alloy installer." : "Download " + lib[1] + " for Minecraft " + MC + " from https://modrinth.com/mod/" + lib[2]
                    + " and put it in " + (fabric ? "mods/" : "the folder of the mod that needs it (mods/ for Fabric, forgemods/ for Forge)") + ".";
            return new Result(Kind.MISSING_DEPENDENCY, mod == null ? List.of() : List.of(mod, lib[2]),
                    (mod == null ? "A mod" : "Mod " + mod) + " needs " + lib[1] + ", which is not installed (class " + cls + " is missing).", fix);
        }
        if (cls.startsWith("net.minecraft.") || cls.startsWith("net.minecraftforge.") || cls.startsWith("com.mojang.")) {
            String mod = firstModJar(t, 0);
            return new Result(Kind.WRONG_VERSION, mod == null ? List.of() : List.of(mod), (mod == null ? "A mod" : "Mod " + mod) + " uses a game class that does not exist in Minecraft " + MC
                    + " (" + simple(cls) + "); it was made for another version.", "Download the Minecraft " + MC + " build of " + (mod == null ? "that mod" : mod) + ", or remove it.");
        }
        return null;
    }

    private static final Pattern LINK = Pattern.compile("java\\.lang\\.(NoSuchMethodError|NoSuchFieldError|AbstractMethodError|IncompatibleClassChangeError|IllegalAccessError): ([^\\n]+)");

    static Result linkage(String t) {
        Matcher m = LINK.matcher(t);
        if (!m.find()) return null;
        String mod = firstModAfter(t, m.start());
        if (mod == null) mod = firstModJar(t, 0);
        return new Result(Kind.WRONG_VERSION, mod == null ? List.of() : List.of(mod), (mod == null ? "A mod" : "Mod " + mod) + " calls code that is not there (" + m.group(1) + ": "
                + shorten(m.group(2), 160) + "). It was most likely made for another version of Minecraft or of a library it uses.",
                "Update " + (mod == null ? "that mod" : mod) + " and its required libraries to their Minecraft " + MC + " builds.");
    }

    private static final Pattern ROOT = Pattern.compile("^Caused by: ([\\w.$]+(?:Exception|Error|Throwable))(?:: ([^\\n]*))?", Pattern.MULTILINE);
    private static final Pattern FIRST_EX = Pattern.compile("^([\\w.$]+(?:Exception|Error)): ?([^\\n]*)", Pattern.MULTILINE);
    private static final Pattern DUP_LINE = Pattern.compile("Alloy: mod '([\\w-]+)' is installed twice: Forge (\\S+) in forgemods/ and Fabric (\\S+) in mods/");

    static Result genericCrash(String t) {
        boolean crash = t.contains("---- Minecraft Crash Report ----") || t.contains("Caught exception") || t.contains("Exception in thread") || t.contains("Caused by: ");
        if (!crash) return null;
        String ex = null, msg = "";
        int at = 0;
        Matcher m = ROOT.matcher(t);
        while (m.find()) { ex = m.group(1); msg = m.group(2) == null ? "" : m.group(2); at = m.start(); }
        if (ex == null) {
            m = FIRST_EX.matcher(t);
            if (m.find()) { ex = m.group(1); msg = m.group(2); at = m.start(); }
        }
        String mod = ex == null ? null : firstModAfter(t, at);
        if (mod == null) mod = firstModJar(t, 0);
        // a mod installed twice (Forge build + Fabric build) and the crash is in one of the two copies
        Matcher d = DUP_LINE.matcher(t);
        while (d.find()) {
            if (mod != null && (mod.equals(d.group(2)) || mod.equals(d.group(3)) || mod.equals(d.group(1))))
                return new Result(Kind.DUPLICATE_MOD, List.of(d.group(1)), "Mod " + d.group(1) + " is installed twice (Forge build " + d.group(2) + " and Fabric build " + d.group(3)
                        + ") and one copy crashed.", "Keep one build: delete " + d.group(3) + " from mods/ or " + d.group(2) + " from forgemods/.");
        }
        String what = ex == null ? "an error" : simple(ex) + (msg.isBlank() ? "" : ": " + shorten(msg.trim(), 160));
        if (mod == null) return new Result(Kind.UNKNOWN, List.of(), "Minecraft crashed (" + what + ") and no mod jar is named in the stack trace.",
                "Send the crash report and alloy-report.txt to whoever helps you with this setup.");
        return new Result(Kind.MOD_CRASH, List.of(mod), "Mod " + mod + " crashed (" + what + ").",
                "Update or remove " + mod + ". If it also crashes on plain Forge or Fabric, report it to its author; if not, send alloy-report.txt to the Alloy developer.");
    }

    // ------------------------------------------------------------------ helpers

    private static final Pattern FRAME = Pattern.compile("^\\s*at (\\S+?)\\(.*?\\)(?: ~?\\[([^\\]]*)\\])?", Pattern.MULTILINE);
    private static final Pattern HANDLER = Pattern.compile("\\$(?:handler|redirect|modify\\w*|wrapOperation)\\$\\w+\\$([\\w-]+)\\$|(?:handler|redirect|wrapOperation)\\$\\w+\\$([\\w-]+)\\$");
    private static final Pattern PLATFORM_JAR = Pattern.compile("(?i)^(forge-|fmlloader|fmlcore|fmlearlydisplay|javafmllanguage|lowcodelanguage|mclanguage|eventbus|fabric-loader|"
            + "sponge-mixin|mixinextras|alloy|forgefab|bootstraplauncher|securejarhandler|modlauncher|datafixerupper|brigadier|netty|guava|gson|log4j|jopt|unsafe|"
            + "night-?config|core-|toml|lwjgl|blaze3d|authlib|client-|server-|minecraft|.*_game\\.jar|game_client|game_server|hybrid_|fabric-api|fabric-[a-z-]+-v\\d|"
            + "jtracy|oshi|jna|commons-|fastutil|asm|accesstransformers|coremods|nashorn|java)");

    /** first mod named by a stack frame at or after {@code from}: a mixin handler's mod id, else a non-platform jar */
    static String firstModAfter(String t, int from) {
        Matcher m = FRAME.matcher(t);
        if (!m.find(Math.max(0, from))) return null;
        do {
            String method = m.group(1), jar = m.group(2);
            Matcher h = HANDLER.matcher(method);
            if (h.find()) {
                String id = h.group(1) != null ? h.group(1) : h.group(2);
                if (!id.startsWith("alloy") && !id.startsWith("forgefab") && !id.startsWith("fabric-")) return id;
            }
            if (jar != null) {
                String j = jar.replaceAll("[!:].*$", "");
                if (j.endsWith(".jar") && !PLATFORM_JAR.matcher(j).find()) return j.replaceAll("-(?:alloy|forgefab)\\.jar$", ".jar");
            }
        } while (m.find());
        return null;
    }

    static String firstModJar(String t, int from) { return firstModAfter(t, from); }

    static String ownerHint(String cls) {
        String[] p = cls.split("\\.");
        return p.length > 2 ? p[p.length > 3 ? 2 : 1] : cls;
    }

    static String pretty(String id) { return id == null ? "?" : PRETTY.getOrDefault(id, id); }

    static String link(String id) {
        for (String[] lib : KNOWN_LIBS) if (lib[2].equals(id)) return "https://modrinth.com/mod/" + id;
        return "https://modrinth.com/mods?q=" + id;
    }

    static String simple(String cls) {
        if (cls == null) return "?";
        String c = cls.replace('/', '.');
        int i = Math.max(c.lastIndexOf('.'), c.lastIndexOf('$'));
        return c.substring(i + 1);
    }

    static String oneLine(String s) { return s == null ? "" : s.replaceAll("\\s+", " ").trim(); }

    static String shorten(String s, int n) { s = oneLine(s); return s.length() <= n ? s : s.substring(0, n - 3) + "..."; }
}
