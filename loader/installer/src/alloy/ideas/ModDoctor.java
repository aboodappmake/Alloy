package alloy.ideas;

import alloy.ideas.ModMeta.*;
import java.nio.file.*;
import java.util.*;

/**
 * Alloy Mod Doctor (prototype): a static, offline pre-launch check of a game dir's mods/ (Fabric) and
 * forgemods/ (Forge) folders. Nothing is loaded or launched; nothing is sent anywhere.
 *
 * It explains, in plain words, the problems that otherwise show up as a crash a minute into the launch:
 * wrong folder, NeoForge-only jars, missing or wrong-version dependencies, a Fabric mod depending on a Forge mod
 * (impossible under side isolation), two copies of one mod, packs put in mods/, declared incompatibilities.
 * It then gives a per-pack compatibility score built from Alloy's own test matrix (known.tsv).
 *
 *   java -cp modcheck.jar alloy.ideas.ModDoctor GAMEDIR [--side client|server] [--known known.tsv]
 *        [--mc 26.1.2] [--forge 64.1.6] [--fabric-loader 0.20.1] [--json report.json]
 * Exit code: 0 ok, 1 warnings, 2 blockers (the game will very likely not start).
 */
public final class ModDoctor {
    /** Mod ids that Alloy stands in for on the Fabric side when they exist only as a Forge mod (alloy.installer.Prep.forgeStubs). */
    public static final java.util.List<String> FORGE_STUB_IDS = java.util.List.of("jei", "roughlyenoughitems", "emi", "terrablender");
    /** Fabric Sodium add-ons checked on Forge Embeddium 0.3 (1.20.1): with Embeddium and no Fabric Sodium, Alloy adds a
     *  "sodium" stub for them (Prep.embeddiumSodiumStub). Their option pages show under their own tab (compat120/embeddium). */
    public static final Set<String> EMBEDDIUM_SODIUM_ADDONS = Set.of("sodium-extra", "reeses-sodium-options");
    /** Sodium version Embeddium 0.3 is based on, for the stub; Sodium Extra 0.5.9 asks for >=0.5.6, Reese's 1.7 for >=0.5.8 */
    static final String EMBEDDIUM_SODIUM_BASE = "0.5.8";

    /** true when the Fabric mods needing sodium are all tested Embeddium add-ons (and there is at least one) */
    public static boolean embeddiumStandsInForSodium(Set<String> needSodium) {
        return !needSodium.isEmpty() && EMBEDDIUM_SODIUM_ADDONS.containsAll(needSodium);
    }

    /** the stub's version: Sodium's base version, Embeddium's own as build metadata (ignored by version ranges) */
    public static String embeddiumSodiumVersion(String embeddium) {
        return EMBEDDIUM_SODIUM_BASE + "+embeddium." + (embeddium == null ? "0" : embeddium.replaceAll("[^0-9A-Za-z.-]", "."));
    }

    enum Sev { BLOCKER, WARN, INFO }
    record Finding(Sev sev, String jar, String what, String fix) {}
    record Known(String side, String verdict, String note) {}

    final List<Finding> findings = new ArrayList<>();
    /** defaults for --mc/--forge/--fabric-loader; the installer sets them to the selected Minecraft version */
    public static String defaultMc = "26.1.2", defaultForge = "64.1.6", defaultFabricLoader = "0.20.1";
    String mc = defaultMc, forge = defaultForge, fabricLoader = defaultFabricLoader, side = "client";
    final Map<String, List<Known>> known = new HashMap<>();
    /** known.tsv "#total" lines: group (forge-server, forge-client, fabric) -> {tested, pass, fails on real Forge too, fail}, + Minecraft */
    final Map<String, int[]> totals = new HashMap<>();
    String totalsMc;
    /** Sodium versions (without +mc...) Alloy's 1.20.1 Sodium compat was statically verified against (build_sodium120.py verify) */
    public static final Set<String> SODIUM120 = Set.of("0.5.8", "0.5.13");
    /** Sodium versions Alloy's 1.21.1 compat for Sodium 0.6+ (alloy-sodium121.jar) was checked against, in a world */
    public static final Set<String> SODIUM121 = Set.of("0.6.13", "0.8.13");
    /**
     * Per Minecraft version with an Alloy Sodium compat: the Sodium lines it is made for (its mixins target those classes),
     * the versions it was checked against in a world, and the one to suggest. 1.20.x: alloy-sodium120.jar (Sodium 0.5);
     * 1.21.x: alloy-sodium121.jar, one build per version (bridge/src/alloy/compat121/sodium, mc<version>/ overlays).
     */
    static final Map<String, List<String>> SODIUM_LINES = Map.of(
            "1.20.1", List.of("0.5."), "1.20.2", List.of("0.5."), "1.20.4", List.of("0.5."),
            "1.21.1", List.of("0.6.", "0.8."), "1.21.4", List.of("0.6."), "1.21.8", List.of("0.7."), "1.21.11", List.of("0.8."));
    static final Map<String, Set<String>> SODIUM_CHECKED = Map.of(
            "1.20.1", SODIUM120, "1.20.2", Set.of("0.5.5"), "1.20.4", Set.of("0.5.8"), "1.21.1", SODIUM121, "1.21.4", Set.of("0.6.13"), "1.21.8", Set.of("0.7.3"), "1.21.11", Set.of("0.8.14"));
    static final Map<String, String> SODIUM_ADVICE = Map.of("1.20.1", "0.5.13", "1.20.2", "0.5.5", "1.20.4", "0.5.8", "1.21.1", "0.6.13", "1.21.4", "0.6.13", "1.21.8", "0.7.3", "1.21.11", "0.8.14");
    static Set<String> sodiumChecked(String mc) { return SODIUM_CHECKED.getOrDefault(mc, Set.of()); }
    static String sodiumAdvice(String mc) { return SODIUM_ADVICE.getOrDefault(mc, "?"); }
    /** True when Alloy's compat for this Minecraft version is made for this Sodium (version without +mc...) */
    public static boolean sodiumFamily(String mc, String v) {
        for (String p : SODIUM_LINES.getOrDefault(mc, List.of())) if (v.startsWith(p)) return true;
        return false;
    }
    /** "0.6.x and 0.8.x" */
    public static String sodiumMade(String mc) {
        List<String> l = new ArrayList<>();
        for (String p : SODIUM_LINES.getOrDefault(mc, List.of())) l.add(p + "x");
        return String.join(" and ", l);
    }
    /** Forge renderer ports that packs build on (Forge mods hard-require them); Mod Doctor never suggests swapping them out */
    static final Set<String> RENDER_PORTS = Set.of("embeddium", "rubidium", "oculus");
    /** "fabricId+forgeId" pairs already warned by a mod_risks PORT or PREFER row (the known.tsv PAIR row for them is then
     *  skipped), with that warning (dropped again when a BLOCK row for the pair fires) */
    final Map<String, Finding> portPairs = new HashMap<>();

    /** Fabric mods that only work with Sodium (its add-ons and Iris); one of them installed means Sodium has to stay */
    static final Set<String> SODIUM_ADDONS = Set.of("iris", "sodium-extra", "sodiumextra", "reeses-sodium-options", "reeses_sodium_options", "indium");

    /** the Sodium add-ons installed on the Fabric side (ids, sorted, comma separated), or "" when there are none */
    static String sodiumAddons(List<Placed> placed) {
        Set<String> out = new TreeSet<>();
        for (Placed pl : placed) if (pl.as == ModMeta.Loader.FABRIC) for (Mod m : pl.jar.mods) if (m.loader == pl.as && SODIUM_ADDONS.contains(m.id)) out.add(m.id);
        return String.join(", ", out);
    }

    /** Forge mods (ids, at most 3) that declare a mandatory dependency on one of these ids, or null if none */
    static String requiredBy(List<Placed> placed, Set<String> ids) {
        Set<String> out = new java.util.TreeSet<>();
        for (Placed pl : placed) if (pl.as == ModMeta.Loader.FORGE) for (Mod m : pl.jar.mods)
            if (m.loader == pl.as && !ids.contains(m.id)) for (Dep d : m.deps) if (d.mandatory() && !d.incompatible() && ids.contains(d.id())) out.add(m.id);
        if (out.isEmpty()) return null;
        List<String> l = new ArrayList<>(out);
        return l.size() > 3 ? String.join(", ", l.subList(0, 3)) + " and " + (l.size() - 3) + " more" : String.join(", ", l);
    }
    /** what prep does with each jar in mods/ (Forge mods there are mirrored into forgemods/); empty = old folder rules */
    final Map<String, alloy.installer.ForgeInMods.Entry> routes = new HashMap<>();

    /** mods whose two builds are known to register the same registry content without blockstates (TerraBlender: "Adding duplicate key") */
    static final Set<String> CONTENT_TWINS = Set.of("terrablender");

    /**
     * Does a mod that is installed for both loaders register content in both builds? Read from the jars: block states or
     * item models under assets/ (blocks and items of its own). Returns what was found, or null. Only twins are read again.
     */
    static String registersContent(List<Placed> placed, String id, String fabJar, String forgeJar) {
        if (CONTENT_TWINS.contains(id)) return "known: both register the same keys";
        String a = contentOf(placed, fabJar), b = contentOf(placed, forgeJar);
        return a != null && b != null ? a : null;
    }

    static final java.util.regex.Pattern CONTENT = java.util.regex.Pattern.compile("assets/[^/]+/(blockstates/[^/]+|models/item/[^/]+)\\.json");

    static String contentOf(List<Placed> placed, String jarName) {
        Placed pl = placed.stream().filter(p -> p.jar.name.equals(jarName)).findFirst().orElse(null);
        if (pl == null) return null;
        try (var z = new java.util.zip.ZipFile(pl.jar.file.toFile())) {
            for (var en = z.entries(); en.hasMoreElements(); ) {
                String n = en.nextElement().getName();
                if (CONTENT.matcher(n).matches()) return n.contains("/blockstates/") ? "blocks" : "items";
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** libraries whose Forge and Fabric builds were seen working side by side on Alloy (Cloth Config on 1.20.1: no mixins, services or registries) */
    public static final Set<String> SAFE_TWINS = Set.of("cloth-config", "cloth_config", "cloth-config2");

    /** Fabric mods whose mixin targets Forge changed (static_fabric_check, Round 13): expect lost features. */
    static final Set<String> FABRIC_RISKY = Set.of("modmenu", "architectury", "fancymenu", "sodium", "lithium", "iris");

    public static void main(String[] args) throws Exception {
        System.exit(cli(args));
    }

    /** same as main, but returns the exit code (0 ok, 1 warnings, 2 blockers) instead of exiting. */
    public static int cli(String[] args) throws Exception {
        if (args.length == 0 || args[0].startsWith("--")) { usage(); return 64; }
        ModDoctor d = new ModDoctor();
        Path game = Path.of(args[0]);
        Path mods = game.resolve("mods"), forgemods = game.resolve("forgemods"), json = null, cache = null;
        boolean ownKnown = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--side" -> d.side = args[++i];
                case "--cache" -> cache = Path.of(args[++i]);
                case "--known" -> { d.loadKnown(Path.of(args[++i])); ownKnown = true; }
                case "--mc" -> d.mc = args[++i];
                case "--forge" -> d.forge = args[++i];
                case "--fabric-loader" -> d.fabricLoader = args[++i];
                case "--mods" -> mods = Path.of(args[++i]);
                case "--forgemods" -> forgemods = Path.of(args[++i]);
                case "--json" -> json = Path.of(args[++i]);
                default -> { System.err.println("unknown option " + args[i]); usage(); return 64; }
            }
        }
        // --cache FILE (prep, every launch): the report is a pure function of the jars (stamps), the installer, the settings,
        // the memory setting and the last crash. When none of them changed, print the stored report instead of reading every jar.
        String key = cache != null && json == null && !ownKnown && mods.equals(game.resolve("mods")) && forgemods.equals(game.resolve("forgemods"))
                ? d.cacheKey(game) : null;
        if (key != null && Files.isRegularFile(cache)) try {
            byte[] b = Files.readAllBytes(cache);
            int nl1 = indexOf(b, 0), nl2 = nl1 < 0 ? -1 : indexOf(b, nl1 + 1);
            if (nl2 > 0 && new String(b, 0, nl1, java.nio.charset.StandardCharsets.US_ASCII).equals(key)) {
                int rc = Integer.parseInt(new String(b, nl1 + 1, nl2 - nl1 - 1, java.nio.charset.StandardCharsets.US_ASCII).trim());
                System.out.write(b, nl2 + 1, b.length - nl2 - 1);
                System.out.flush();
                return rc;
            }
        } catch (Exception e) { /* full check */ }
        java.io.PrintStream old = System.out;
        java.io.ByteArrayOutputStream cap = new java.io.ByteArrayOutputStream();
        if (cache != null) System.setOut(new java.io.PrintStream(new java.io.OutputStream() {
            @Override public void write(int b) { old.write(b); cap.write(b); }
            @Override public void write(byte[] b, int o, int l) { old.write(b, o, l); cap.write(b, o, l); }
            @Override public void flush() { old.flush(); }
        }, true, outCharset()));
        int rc;
        boolean planned = true;
        try {
            if (mods.equals(game.resolve("mods"))) try {
                for (var e : alloy.installer.ForgeInMods.plan(game)) d.routes.put(e.name(), e);
            } catch (Exception e) { System.err.println("Forge mods in mods/ not checked: " + e); planned = false; }
            if (d.known.isEmpty()) {   // default: known.tsv bundled in the installer jar, else next to the cwd
                try (var in = ModDoctor.class.getResourceAsStream("/known.tsv")) {
                    if (in != null) d.loadKnownText(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    else if (Files.exists(Path.of("known.tsv"))) { d.loadKnown(Path.of("known.tsv")); planned = false; }
                }
            }
            // rows for this Minecraft version only (installer/res/known.<mc>.tsv): mods that clash there, e.g. Sodium and
            // Embeddium on 1.20.1. Only PAIR and NOTE rows: test verdicts come from known.tsv.
            try (var in = ModDoctor.class.getResourceAsStream("/known." + d.mc + ".tsv")) {
                if (in != null) d.loadVersionRows(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
            rc = d.run(mods, forgemods, json);
        } finally {
            System.out.flush();
            System.setOut(old);
        }
        if (cache != null) try {
            String after = key == null ? null : d.cacheKey(game);
            if (planned && key != null && key.equals(after)) {
                java.io.ByteArrayOutputStream f = new java.io.ByteArrayOutputStream();
                f.write((key + "\n" + rc + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                cap.writeTo(f);
                Path tmp = cache.resolveSibling(cache.getFileName() + ".tmp");
                Files.write(tmp, f.toByteArray());
                Files.move(tmp, cache, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else Files.deleteIfExists(cache);
        } catch (Exception e) { /* only a cache */ }
        return rc;
    }

    private static int indexOf(byte[] b, int from) {
        for (int i = from; i < b.length && i < from + 200; i++) if (b[i] == '\n') return i;
        return -1;
    }

    /** the console's charset (PrintStream.charset() needs Java 18; the installer runs on 17 for Minecraft 1.20.1) */
    static java.nio.charset.Charset outCharset() {
        try { return java.nio.charset.Charset.forName(System.getProperty("stdout.encoding", System.getProperty("sun.stdout.encoding", ""))); }
        catch (Exception e) { return java.nio.charset.Charset.defaultCharset(); }
    }

    /** everything the report depends on, or null when something cannot be read (then no cache) */
    String cacheKey(Path game) {
        try {
            game = game.toAbsolutePath().normalize();
            StringBuilder sb = new StringBuilder("doctor1|").append(alloy.installer.StatCache.self()).append('|').append(side).append('|').append(mc).append('|')
                    .append(forge).append('|').append(fabricLoader).append('|').append(Runtime.version().feature()).append('|').append(heapMb(game)).append('\n');
            sb.append("crash|").append(lastCrash(game, game.resolve("mods"), game.resolve("forgemods"))).append('\n');
            for (String f : List.of(alloy.installer.ForgeInMods.CONFIG, ".alloy/forge-in-mods.properties"))
                sb.append(f).append('|').append(alloy.installer.StatCache.stampOrDash(game.resolve(f))).append('\n');
            sb.append(alloy.installer.StatCache.listing("M", game.resolve("mods"), ".jar"));
            sb.append(alloy.installer.StatCache.listing("F", game.resolve("forgemods"), ".jar"));
            sb.append(DataConditions.stampKey(game, id -> true));   // data pack folders (Paxi, Open Loader, ...), whether or not their loader is installed
            return alloy.installer.StatCache.sha1Text(sb.toString());
        } catch (Exception e) { return null; }
    }

    static void usage() {
        System.out.println("usage: ModDoctor GAMEDIR [--side client|server] [--known known.tsv] [--mc V] [--forge V] [--fabric-loader V] [--json out.json]");
    }

    void loadKnown(Path p) throws Exception {
        loadKnownText(Files.readString(p));
    }

    void loadKnownText(String text) {
        for (String line : text.split("\\R")) {
            if (line.startsWith("#total\t")) {   // "#total<TAB>group<TAB>tested<TAB>pass<TAB>na<TAB>fail<TAB>minecraft"
                String[] c = line.split("\t");
                try {
                    if (c.length >= 6) totals.put(c[1], new int[]{Integer.parseInt(c[2]), Integer.parseInt(c[3]), Integer.parseInt(c[4]), Integer.parseInt(c[5])});
                    if (c.length >= 7) totalsMc = c[6];
                } catch (NumberFormatException ignored) { }
                continue;
            }
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] c = line.split("\t", 4);
            if (c.length < 3) continue;
            known.computeIfAbsent(c[0], k -> new ArrayList<>()).add(new Known(c[1], c[2], c.length > 3 ? c[3] : ""));
        }
    }

    /** PAIR, BLOCK (a pair that must never load together) and NOTE rows of a version file (known.1.20.1.tsv); other verdicts there are ignored */
    void loadVersionRows(String text) {
        StringBuilder keep = new StringBuilder();
        for (String line : text.split("\\R")) {
            String[] c = line.split("\t", 4);
            if (!line.startsWith("#") && c.length >= 3 && (c[2].equals("PAIR") || c[2].equals("BLOCK") || c[2].equals("NOTE"))) keep.append(line).append('\n');
        }
        loadKnownText(keep.toString());
    }

    /** true when Alloy's test data (known.tsv) is from another Minecraft version than the one checked (e.g. 1.20.1 vs 26.1.2) */
    /** a known.tsv note for players: notes that only name Alloy's own test files ("matrix results_x.json (mod)") are left out */
    static String plainNote(String note) {
        if (note == null) return "";
        String n = note.strip();
        return n.matches("(?i).*(results_\\w*\\.json|\\bmatrix\\b|\\.json\\b|\\bround \\d|team/|run/).*") ? "" : n;
    }

    boolean otherMc() { return totalsMc != null && mc != null && !totalsMc.equals(mc); }

    /** "338/355 server + 221/230 client tested Forge mods worked on Minecraft 26.1.2", from known.tsv (#total lines, else its rows). */
    String testedText(String loader) {
        String on = totalsMc != null ? " on Minecraft " + totalsMc : "";
        List<String> parts = new ArrayList<>();
        if (loader.equals("forge")) {
            for (String[] g : new String[][]{{"forge-server", "server"}, {"forge-client", "client"}}) {
                int[] t = totals.get(g[0]);
                if (t != null && t[0] > 0) parts.add(t[1] + "/" + t[0] + " " + g[1]);
            }
            if (!parts.isEmpty()) return String.join(" + ", parts) + " tested Forge mods worked" + on + failNote("forge-server", "forge-client");
        } else {
            int[] t = totals.get("fabric");
            if (t != null && t[0] > 0) return t[1] + "/" + t[0] + " tested Fabric mods worked" + on + failNote("fabric");
        }
        if (known.isEmpty()) return "no Alloy test data bundled";
        // no #total lines (older known.tsv): count mod ids with a PASS row
        int srv = 0, cli = 0;
        for (List<Known> ks : known.values()) {
            if (ks.stream().anyMatch(k -> k.verdict.equals("PASS") && k.side.equals("server"))) srv++;
            if (ks.stream().anyMatch(k -> k.verdict.equals("PASS") && k.side.equals("client"))) cli++;
        }
        return srv + " mod ids passed Alloy's server tests and " + cli + " its client tests";
    }

    private String failNote(String... groups) {
        int na = 0;
        for (String g : groups) { int[] t = totals.get(g); if (t != null) na += t[2]; }
        return na > 0 ? "; " + na + " of the rest fail on real Forge or Fabric too" : "";
    }

    // ------------------------------------------------------------------------------------------------
    record Placed(JarInfo jar, ModMeta.Loader as, String folder) {}

    /** "ALLOY: ..." line about the previous start, when it failed (null otherwise) */
    String lastCrash;

    static String headline(Finding f, long blockers) {
        String what = f.what.endsWith(".") ? f.what.substring(0, f.what.length() - 1) : f.what;
        return "ALLOY: the game will not start: " + f.jar + ": " + what + ". Fix: " + f.fix + "."
                + (blockers > 1 ? " (" + (blockers - 1) + " more blocker(s) below.)" : "");
    }

    /**
     * The newest crash report, or logs/latest.log when Fabric stopped before any crash report (wrong Java, missing
     * Fabric dependency), read by the same rules the game uses. Only a failed start in the last 7 days is reported.
     */
    public static String lastCrash(Path game, Path... modDirs) {
        try {
            Path newest = null;
            long t = 0;
            Path cr = game.resolve("crash-reports");
            if (Files.isDirectory(cr)) try (var st = Files.list(cr)) {
                for (Path p : st.filter(x -> x.getFileName().toString().endsWith(".txt")).toList()) {
                    long m = Files.getLastModifiedTime(p).toMillis();
                    if (m > t) { t = m; newest = p; }
                }
            }
            Path log = game.resolve("logs/latest.log");
            if (Files.isRegularFile(log) && Files.getLastModifiedTime(log).toMillis() > t + 60_000) {
                String s = Files.readString(log, java.nio.charset.StandardCharsets.UTF_8);
                if (s.contains("Incompatible mods found") || s.contains("Some of your mods are incompatible") || s.contains("UnsupportedClassVersionError")
                        || s.contains("Alloy could not start") || s.contains("Alloy needs Java")) { newest = log; t = Files.getLastModifiedTime(log).toMillis(); }
                else newest = null;   // the last start got further than the newest crash report
            }
            if (newest == null || System.currentTimeMillis() - t > 7L * 24 * 3600 * 1000) return null;
            alloy.report.LogDiagnosis.Result r = alloy.report.LogDiagnosis.diagnoseFile(newest);
            long changed = 0;
            for (Path d : modDirs) if (d != null && Files.isDirectory(d)) try (var st = Files.list(d)) {
                for (Path p : st.toList()) changed = Math.max(changed, Files.getLastModifiedTime(p).toMillis());
            }
            String when = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().withNano(0).toString().replace('T', ' ');
            return "Last start failed (" + when + ", " + newest.getFileName() + "): " + r.headline()
                    + (changed > t ? " (mods were changed since then; this may already be fixed)" : "")
                    + r.also().stream().map(h -> "\n    also: " + h.text()).collect(java.util.stream.Collectors.joining());
        } catch (Exception e) {
            return null;
        }
    }

    int run(Path modsDir, Path forgeDir, Path jsonOut) throws Exception {
        lastCrash = lastCrash(modsDir.toAbsolutePath().normalize().getParent(), modsDir, forgeDir);
        List<Placed> placed = new ArrayList<>();
        int scanned = 0;
        List<String> folders = new ArrayList<>();
        List<Path> paths = new ArrayList<>();
        for (var e : List.of(Map.entry("mods", modsDir), Map.entry("forgemods", forgeDir))) {
            for (Path p : ModMeta.jars(e.getValue())) {
                String n = p.getFileName().toString();
                if (e.getKey().equals("mods") && ModMeta.isAlloyGenerated(n)) continue;
                if (e.getKey().equals("forgemods") && n.startsWith(alloy.installer.ForgeInMods.PREFIX)) continue;   // mirror of a mods/ jar
                folders.add(e.getKey());
                paths.add(p);
            }
        }
        List<JarInfo> infos = ModMeta.readAll(paths);   // read on a few threads, same order
        for (int i = 0; i < paths.size(); i++) {
            scanned++;
            JarInfo ji = infos.get(i);
            ModMeta.Loader as = placement(ji, folders.get(i));
            if (as != null) placed.add(new Placed(ji, as, folders.get(i)));
        }

        optifine(infos, placed, modsDir.toAbsolutePath().normalize().getParent());

        // providers per side (as if the folder fixes above were applied)
        Map<String, String> fabricIds = new HashMap<>(), forgeIds = new HashMap<>();   // id -> version
        for (String b : List.of("minecraft", "java", "fabricloader", "fabric-loader", "mixinextras")) fabricIds.put(b, null);
        for (String b : List.of("minecraft", "forge")) forgeIds.put(b, null);
        fabricIds.put("minecraft", mc); forgeIds.put("minecraft", mc); forgeIds.put("forge", forge);
        fabricIds.put("fabricloader", fabricLoader); fabricIds.put("java", String.valueOf(Runtime.version().feature()));
        Map<String, List<String>> topOwners = new HashMap<>();   // "side:id" -> jars (duplicate check)
        for (Placed pl : placed) {
            Map<String, String> ids = pl.as == ModMeta.Loader.FABRIC ? fabricIds : forgeIds;
            for (Mod m : pl.jar.allMods()) {
                if (m.loader != pl.as) continue;
                keepNewest(ids, m.id, m.version);
                for (String pr : m.provides) keepNewest(ids, pr, m.version);
            }
            for (Mod m : pl.jar.mods) if (m.loader == pl.as) topOwners.computeIfAbsent(pl.as + ":" + m.id, k -> new ArrayList<>()).add(pl.jar.name);
        }

        // duplicates on one side
        topOwners.forEach((k, jars0) -> {
            // a fitted copy (X-alloy.jar) is derived from its original X.jar: not a second copy
            List<String> jars = new ArrayList<>(jars0);
            jars.removeIf(j -> j.endsWith("-alloy.jar") && jars0.contains(j.substring(0, j.length() - "-alloy.jar".length()) + ".jar"));
            if (jars.size() > 1) add(Sev.BLOCKER, String.join(", ", jars), "two copies of mod '" + k.substring(k.indexOf(':') + 1) + "' (" + k.substring(0, k.indexOf(':')) + ")",
                    "keep only the newest jar");
        });
        // same mod installed as both a Fabric and a Forge build: say which copy to keep
        Map<String, Set<String>> neededBy = new HashMap<>();   // "FABRIC:id" / "FORGE:id" -> mods on that side that require it
        for (Placed pl : placed) for (Mod m : pl.jar.mods) {
            if (m.loader != pl.as) continue;
            for (Dep d : m.deps) if (d.mandatory() && !d.incompatible()) neededBy.computeIfAbsent(pl.as + ":" + d.id(), x -> new TreeSet<>()).add(m.id);
        }
        for (String k : topOwners.keySet()) {
            if (!k.startsWith("FABRIC:")) continue;
            String id = k.substring(7);
            if (!topOwners.containsKey("FORGE:" + id)) continue;
            String fab = topOwners.get(k).get(0), frg = topOwners.get("FORGE:" + id).get(0);
            Set<String> fabUsers = neededBy.getOrDefault("FABRIC:" + id, Set.of()), frgUsers = neededBy.getOrDefault("FORGE:" + id, Set.of());
            // both builds put the same blocks or items into Minecraft's shared registries: a duplicate key stops the start
            String content = registersContent(placed, id, fab, frg);
            if (content != null) {
                String sides = fabUsers.isEmpty() && frgUsers.isEmpty() ? "" : " (Fabric mods " + fabUsers + " use the Fabric build, Forge mods " + frgUsers + " the Forge build)";
                add(Sev.BLOCKER, fab + " + " + frg, "mod '" + id + "' is installed for both loaders and both builds register game content (" + content
                        + "). Minecraft refuses the second copy of the same registry key, so the game stops at start" + sides,
                        "keep one build: delete " + (!fabUsers.isEmpty() && frgUsers.isEmpty() ? frg : fab)
                                + (fabUsers.isEmpty() || frgUsers.isEmpty() ? "" : ", and use the other loader's build of the mods that needed it"));
                continue;
            }
            if (!fabUsers.isEmpty() && !frgUsers.isEmpty()) {
                String both = "library '" + id + "' is installed for both loaders. Fabric mods " + fabUsers + " need the Fabric copy and Forge mods "
                        + frgUsers + " need the Forge copy";
                if (SAFE_TWINS.contains(id)) add(Sev.INFO, fab + " + " + frg, both + ". Both copies of this library are known to work side by side", "keep both copies");
                else add(Sev.WARN, fab + " + " + frg, both + ". Alloy has one class space: one copy wins per class name, and the two can clash on mixins,"
                        + " services and registrations (GeckoLib and TerraBlender twins crashed this way)",
                        "if the game fails, keep one copy and use the other loader's build of the mods that need the copy you removed");
                continue;
            }
            String keep, drop, why;
            if (!fabUsers.isEmpty()) { keep = fab; drop = frg; why = "Fabric mods " + fabUsers + " need it and no Forge mod does"; }
            else if (!frgUsers.isEmpty()) { keep = frg; drop = fab; why = "Forge mods " + frgUsers + " need it and no Fabric mod does"; }
            else {
                boolean forgeTested = forLoader(known.getOrDefault(id, List.of()), ModMeta.Loader.FORGE).stream().anyMatch(x -> x.verdict.equals("PASS"));
                boolean fabricTested = forLoader(known.getOrDefault(id, List.of()), ModMeta.Loader.FABRIC).stream().anyMatch(x -> x.verdict.equals("PASS"));
                if (fabricTested && !forgeTested) { keep = fab; drop = frg; why = "only the Fabric build is tested on Alloy"; }
                else { keep = frg; drop = fab; why = forgeTested ? "the Forge build is tested on Alloy" : "neither build is tested; the Forge build sees Forge's events"; }
            }
            add(Sev.WARN, fab + " + " + frg, "mod '" + id + "' is installed twice: a Fabric build in mods/ and a Forge build in forgemods/. Both load and often double features or crash",
                    "keep " + keep + " and delete " + drop + " (" + why + ")");
        }

        // mods Alloy treats specially (installer/res/mod_risks.tsv)
        Set<String> fabricPresent = new HashSet<>();
        for (Placed pl : placed) if (pl.as == ModMeta.Loader.FABRIC) for (Mod m : pl.jar.allMods()) fabricPresent.add(m.id);
        Set<String> riskSeen = new HashSet<>();
        portPairs.clear();
        for (Placed pl : placed) for (ModRisks.Risk r : ModRisks.load()) {   // service jars without a mod id (Connector): by file name
            if (r.action() != ModRisks.Action.BLOCK || !r.appliesToMc(mc)) continue;
            String n = pl.jar.name.toLowerCase(Locale.ROOT);
            if (r.ids().stream().anyMatch(i -> i.startsWith("file:") && n.startsWith(i.substring(5)) || i.startsWith("re:") && n.matches(i.substring(3)))
                    && riskSeen.add(pl.jar.name + "|BLOCK"))
                add(Sev.BLOCKER, pl.jar.name, r.message(), "remove " + pl.jar.name);
        }
        // Forgified Fabric API modules nested in a Forge jar (Accessories, ClickSigns): Forge's JarJar loads them next to the
        // real Fabric API. Only jars with a nested Forge mod named like an FFAPI module are opened again.
        for (Placed pl : placed) {
            if (pl.as != ModMeta.Loader.FORGE || pl.jar.nested.stream().noneMatch(m -> m.loader == ModMeta.Loader.FORGE && m.id.startsWith("fabric_"))) continue;
            List<String> ff = alloy.installer.NestedFfapi.find(pl.jar.file);
            if (!ff.isEmpty() && riskSeen.add(pl.jar.name + "|FFAPI"))
                add(Sev.WARN, pl.jar.name, "carries " + ff.size() + " Forgified Fabric API module(s) inside it (" + String.join(", ", ff.stream().map(s -> s.substring(s.lastIndexOf('/') + 1)).toList())
                        + "). Forge loads them next to Alloy's real Fabric API, so two copies of Fabric API patch the same game code",
                        "use the Fabric build of this mod in mods/, or import the pack again (the pack import removes them)");
        }
        // Sinytra Connector's jar cache, left from a Connector pack: nothing reads it on Alloy
        for (Path d : List.of(modsDir, forgeDir)) if (d != null && Files.isDirectory(d.resolve(".connector")))
            add(Sev.INFO, d.getFileName() + "/.connector", "Sinytra Connector's cache of remapped Fabric jars. Alloy does not use it",
                    "optional: delete the folder " + d.resolve(".connector") + " to save space");
        for (Placed pl : placed) for (Mod m : pl.jar.mods) {
            if (m.loader != pl.as) continue;
            for (ModRisks.Risk r : ModRisks.load()) {
                if (!r.ids().contains(m.id) || !r.appliesTo(pl.as, mc) || !riskSeen.add(pl.jar.name + "|" + r.action())) continue;
                switch (r.action()) {
                    case BLOCK -> add(Sev.BLOCKER, pl.jar.name, r.message(), "remove " + pl.jar.name);
                    case RISK -> add(Sev.WARN, pl.jar.name, "high risk: " + r.message(), r.fix().isEmpty() ? "use the other loader's build of the mods that need it, if there is one" : r.fix());
                    case NOTE -> add(Sev.INFO, pl.jar.name, side(pl.as) + " " + m.id + ": " + r.message(), r.fix().isEmpty() ? "nothing to do; report problems with alloy-report.txt" : r.fix());
                    case PREFER -> {
                        // the Forge port is the safer pick here (Radium Re-Reforged on 1.20.1): the Fabric original should go
                        Placed orig = null;
                        for (Placed o : placed) if (o.as == ModMeta.Loader.FABRIC && o.jar.mods.stream().anyMatch(x -> r.originals().contains(x.id))) { orig = o; break; }
                        if (orig != null) {
                            add(Sev.WARN, pl.jar.name + " + " + orig.jar.name, m.id + " " + r.message() + " Both patch the same game code, so keep only the port",
                                    "delete " + orig.jar.name + " from mods/");
                            for (Mod x : orig.jar.mods) if (r.originals().contains(x.id)) portPairs.put(x.id + "+" + m.id, findings.get(findings.size() - 1));
                        } else add(Sev.INFO, pl.jar.name, m.id + " " + r.message(), r.fix().isEmpty() ? "nothing to do" : r.fix());
                    }
                    case PORT -> {
                        String orig = r.originals().stream().filter(fabricPresent::contains).findFirst().orElse(null);
                        // Embeddium and Oculus: many Forge mods require them (464 of 752 Embeddium packs), so never advise a swap
                        Set<String> ids = new HashSet<>();   // all ids of this jar in the row (Embeddium also declares "rubidium")
                        for (Mod x : pl.jar.mods) if (r.ids().contains(x.id)) ids.add(x.id);
                        String needs = RENDER_PORTS.contains(m.id) ? requiredBy(placed, ids) : null;
                        if (orig != null) {
                            String addons = !m.id.equals("oculus") && RENDER_PORTS.contains(m.id) && r.appliesToMc(mc) && "1.20".equals(r.mc())
                                    ? sodiumAddons(placed) : null;
                            if (needs != null)
                                add(Sev.WARN, pl.jar.name, m.id + " and the Fabric " + orig + " are both installed. Two renderers in one game crash or draw wrong. "
                                        + m.id + " is needed by the Forge mods " + needs, "remove " + orig + " from mods/ (and the Fabric mods that need it)");
                            else if (addons != null && addons.isEmpty())
                                // 1.20.x: Embeddium is the safer renderer; Sodium stays only for its Fabric add-ons (Iris, Sodium Extra, ...)
                                add(Sev.WARN, pl.jar.name, m.id + " " + r.message() + " The Fabric " + orig + " is installed too and no Fabric mod here needs it",
                                        "remove " + orig + " from mods/ and keep " + pl.jar.name);
                            else if (addons != null)
                                add(Sev.WARN, pl.jar.name, m.id + " " + r.message() + " The Fabric " + orig + " is installed too, and the Fabric mods " + addons + " need it",
                                        "delete " + pl.jar.name + " from forgemods/ (or remove " + addons + " and " + orig + " and keep " + m.id + ")");
                            else add(Sev.WARN, pl.jar.name, m.id + " " + r.message() + " The Fabric original (" + orig + ") is installed too", "delete " + pl.jar.name + " from forgemods/");
                            for (String i : ids) portPairs.put(orig + "+" + i, findings.get(findings.size() - 1));
                        }
                        else if (RENDER_PORTS.contains(m.id))
                            add(Sev.INFO, pl.jar.name, m.id + " is the " + (m.id.equals("oculus") ? "Forge shader mod" : "Forge renderer") + " of this game and runs on Alloy as it is"
                                    + (needs != null ? " (needed by the Forge mods " + needs + ")" : "") + ". Do not add Sodium or Iris next to it",
                                    "nothing to do");
                        else add(Sev.INFO, pl.jar.name, m.id + " " + r.message().replaceAll("Keep the real ([^.]+?) and remove the port\\.", "Alloy can run the real $1 instead."),
                                "optional: use the Fabric original instead");
                    }
                }
            }
        }
        int jarCount = placed.size();
        heapCheck(jarCount, modsDir.toAbsolutePath().normalize().getParent());
        long forgeJars = count(placed, ModMeta.Loader.FORGE);
        if (forgeJars >= 150 && !forgeIds.containsKey("modernfix"))
            add(Sev.INFO, "memory", forgeJars + " Forge mods and no ModernFix: big Forge packs start slower and use more memory without it", "optional: add ModernFix (Forge build) to forgemods/");

        // Forge mods.toml problems that stop Forge for every mod (plain Forge too): broken ranges, other Forge versions
        Set<String> rangeChecked = new HashSet<>();
        for (Placed pl : placed) {
            if (pl.as != ModMeta.Loader.FORGE || !rangeChecked.add(pl.jar.name)) continue;
            for (String tp : pl.jar.tomlProblems)
                add(Sev.BLOCKER, pl.jar.name, "broken mods.toml: " + tp + ". Forge refuses this file and then stops scanning every mod (plain Forge too)",
                        "remove " + pl.jar.name + " from forgemods/ or get a fixed version; report it to the mod's author");
            String lv = pl.jar.loaderVersion, ml = pl.jar.modLoader;
            String lvErr = Versions.mavenRangeError(lv);
            if (lvErr != null) add(Sev.BLOCKER, pl.jar.name, "broken loaderVersion \"" + lv + "\" in its mods.toml (" + lvErr + "). Forge stops before loading any mod, plain Forge too",
                    "remove " + pl.jar.name + " from forgemods/ or get a fixed version; report it to the mod's author");
            else if (lv != null && bracketed(lv) && ("javafml".equals(ml) || "lowcodefml".equals(ml))) {
                // FML compares against its own version, which is the Forge version (65.1.6 in the 26.2 libIPN crash)
                String major = forge.contains(".") ? forge.substring(0, forge.indexOf('.')) : forge;
                if (Boolean.FALSE.equals(Versions.matches(lv, forge)))
                    add(Sev.BLOCKER, pl.jar.name, "made for another Forge version: it needs " + ml + " " + lv + ", this game has Forge " + forge + ". Forge will not load it",
                            "download the Minecraft " + mc + " / Forge " + major + " build of this mod, or remove it from forgemods/");
            }
            for (Mod m : pl.jar.mods) for (Dep d : m.deps) {
                String err = Versions.mavenRangeError(d.range());
                if (err != null) add(Sev.BLOCKER, pl.jar.name, m.id + " has a broken version range \"" + d.range() + "\" for " + d.id() + " in its mods.toml (" + err
                        + "). Forge stops before loading any mod, plain Forge too", "remove " + pl.jar.name + " from forgemods/ or get a fixed version; report it to the mod's author");
            }
        }

        // dependencies, incompatibilities, versions
        Set<String> connectorPorts = new HashSet<>();   // Forge jars already reported as Connector builds
        Set<String> needSodium = new TreeSet<>();   // Fabric mods that need sodium (Prep.embeddiumSodiumStub decides on the same set)
        if (!fabricIds.containsKey("sodium"))
            for (Placed pl : placed) if (pl.as == ModMeta.Loader.FABRIC) for (Mod m : pl.jar.mods)
                if (m.loader == pl.as && m.deps.stream().anyMatch(d -> d.id().equals("sodium") && d.mandatory() && !d.incompatible())) needSodium.add(m.id);
        for (Placed pl : placed) {
            Map<String, String> mine = pl.as == ModMeta.Loader.FABRIC ? fabricIds : forgeIds;
            Map<String, String> other = pl.as == ModMeta.Loader.FABRIC ? forgeIds : fabricIds;
            for (Mod m : pl.jar.mods) {
                if (m.loader != pl.as) continue;
                if (side.equals("server") && m.env.equals("client"))
                    add(Sev.INFO, pl.jar.name, m.id + " is client-only; the server skips it", "nothing to do (or remove it from the server)");
                for (Dep d : m.deps) {
                    if (pl.as == ModMeta.Loader.FORGE && !d.side().equalsIgnoreCase("BOTH") && !d.side().equalsIgnoreCase(side)) continue;
                    boolean present = mine.containsKey(d.id());
                    String have = mine.get(d.id());
                    if (!present && pl.as == ModMeta.Loader.FABRIC && FORGE_STUB_IDS.contains(d.id()) && other.containsKey(d.id())) {
                        present = true;   // Alloy adds a placeholder Fabric mod with the Forge mod's id and version (Prep.forgeStubs)
                        have = other.get(d.id());
                    }
                    if (!present && pl.as == ModMeta.Loader.FABRIC && d.id().equals("sodium") && other.containsKey("embeddium")) {
                        if (embeddiumStandsInForSodium(needSodium)) {
                            present = true;   // Prep.embeddiumSodiumStub: a sodium stub stands in, backed by Embeddium
                            have = embeddiumSodiumVersion(other.get("embeddium"));
                        } else if (d.mandatory() && !d.incompatible()) {
                            Set<String> untested = new TreeSet<>(needSodium);
                            untested.removeAll(EMBEDDIUM_SODIUM_ADDONS);
                            add(Sev.BLOCKER, pl.jar.name, m.id + " (Fabric) needs Sodium. The Forge Embeddium here stands in for Sodium only for the add-ons Alloy tested on it ("
                                    + String.join(", ", new TreeSet<>(EMBEDDIUM_SODIUM_ADDONS)) + "); " + String.join(", ", untested)
                                    + (untested.size() == 1 ? " is" : " are") + " not tested, so none of them get Sodium",
                                    EMBEDDIUM_SODIUM_ADDONS.contains(m.id) ? "remove " + String.join(", ", untested) + " from mods/ (or use its Forge port for Embeddium)"
                                            : "remove " + pl.jar.name + " from mods/, or use its Forge port for Embeddium if there is one");
                            continue;
                        }
                    }
                    if (d.incompatible()) {
                        if (present && (have == null || !Boolean.FALSE.equals(Versions.matches(d.range(), have))))
                            add(Sev.BLOCKER, pl.jar.name, m.id + " declares it is incompatible with " + d.id() + (d.range() == null ? "" : " " + d.range()), "remove one of the two");
                        continue;
                    }
                    if (!d.mandatory()) continue;
                    if (pl.as == ModMeta.Loader.FORGE && Versions.mavenRangeError(d.range()) != null) continue;   // already a blocker above
                    if (!present && pl.as == ModMeta.Loader.FORGE && connectorDep(d.id())) {
                        // a Fabric mod packed for Sinytra Connector (Continuity's "forge" file): Connector and Forgified
                        // Fabric API are refused on Alloy, so "install connectormod" would be the wrong advice
                        if (connectorPorts.add(pl.jar.name))
                            add(Sev.BLOCKER, pl.jar.name, m.id + " in this jar is the Sinytra Connector build of a Fabric mod (it needs Connector and Forgified Fabric API). "
                                    + "Alloy runs Fabric mods directly and does not use Connector",
                                    "remove " + pl.jar.name + " from forgemods/ and put the Fabric build of " + m.id + " into mods/");
                        continue;
                    }
                    if (!present) {
                        if (other.containsKey(d.id()))
                            add(Sev.BLOCKER, pl.jar.name, m.id + " (" + side(pl.as) + ") needs " + d.id() + ", which is only installed as a " + side(other(pl.as))
                                    + " mod. " + side(pl.as) + " mods cannot use " + side(other(pl.as)) + " mods",
                                    "install the " + side(pl.as) + " build of " + d.id() + " into " + folder(pl.as) + "/");
                        else
                            add(Sev.BLOCKER, pl.jar.name, m.id + " needs " + d.id() + rangeText(d.range()) + ", which is not installed",
                                    "install " + d.id() + " (" + side(pl.as) + " build) into " + folder(pl.as) + "/");
                        continue;
                    }
                    // Maven: a range without brackets ("1", "1.0.5-26.1,") is only a soft wish, any version passes
                    Boolean ok = have == null || pl.as == ModMeta.Loader.FORGE && !bracketed(d.range()) ? null : Versions.matches(d.range(), have);
                    if (Boolean.FALSE.equals(ok)) {
                        boolean platform = Set.of("minecraft", "forge", "fabricloader", "java").contains(d.id());
                        // Forge refuses a required mod in the wrong version (Maven ranges are exact); Fabric ranges are
                        // read leniently here, so a Fabric mismatch stays a warning
                        boolean forgeDep = pl.as == ModMeta.Loader.FORGE;
                        add(platform || forgeDep ? Sev.BLOCKER : Sev.WARN, pl.jar.name,
                                m.id + " wants " + d.id() + rangeText(d.range()) + " but " + have + " is installed"
                                        + (d.id().equals("minecraft") ? " (this jar is built for another Minecraft version)" : forgeDep && !platform ? ". Forge will refuse to start" : ""),
                                d.id().equals("minecraft") ? "download the " + mc + " build of " + m.id
                                        : platform ? "use a build of " + m.id + " made for " + d.id() + " " + have
                                        : "replace " + d.id() + " in " + folder(pl.as) + "/ with a version in" + rangeText(d.range()) + ", or update " + m.id);
                    }
                }
            }
        }

        // Alloy's Sodium compat: alloy-sodium120.jar (Sodium 0.5) is checked against Sodium 0.5.8 and 0.5.13 (1.20.1) and
        // 0.5.5 (1.20.2) and 0.5.8 (1.20.4); alloy-sodium121.jar (Sodium 0.6+, 1.21.1) against 0.6.13 and 0.8.13. A server does not render, so it skips this.
        if (SODIUM_LINES.containsKey(mc) && !side.equals("server")) for (Placed pl : placed) for (Mod m : pl.jar.mods) {
            if (!m.id.equals("sodium") || pl.as != ModMeta.Loader.FABRIC || m.version == null) continue;
            String v = m.version.replaceAll("[+].*", "");
            Set<String> ok = sodiumChecked(mc);
            if (ok.contains(v)) continue;
            String list = String.join(" and ", new TreeSet<>(ok));
            if (sodiumFamily(mc, v))
                add(Sev.WARN, pl.jar.name, "Sodium " + v + ": Alloy's Forge rendering fixes for Sodium on Minecraft " + mc + " were checked against " + list + " only. They are used, but Forge mod blocks may render wrong",
                        "use Sodium " + sodiumAdvice(mc) + " for Minecraft " + mc);
            else
                add(Sev.WARN, pl.jar.name, "Sodium " + v + ": Alloy has no Forge rendering fixes for this Sodium (made for " + sodiumMade(mc) + "). Forge mods' blocks, fluids and models will likely render wrong or crash",
                        "use Sodium " + sodiumAdvice(mc) + " for Minecraft " + mc);
        }

        frapiExtras(placed);
        dataConditions(placed, fabricIds, forgeIds, modsDir.toAbsolutePath().normalize().getParent());
        modernFixVehicle(placed, modsDir.toAbsolutePath().normalize().getParent());
        c2meWorldgen(placed, modsDir.toAbsolutePath().normalize().getParent());

        // score
        List<String> lines = new ArrayList<>();
        double sum = 0; int n = 0, verified = 0;
        for (Placed pl : placed) {
            for (Mod m : pl.jar.mods) {
                if (m.loader != pl.as) continue;
                double c; String why;
                List<Known> ks = forLoader(known.getOrDefault(m.id, List.of()), pl.as);
                Known pass = ks.stream().filter(k -> k.verdict.equals("PASS") && (k.side.equals(side) || k.side.equals("both"))).findFirst().orElse(null);
                Known passOther = ks.stream().filter(k -> k.verdict.equals("PASS")).findFirst().orElse(null);
                Known fail = ks.stream().filter(k -> k.verdict.equals("FAIL") && (k.side.equals(side) || k.side.equals("both"))).findFirst().orElse(null);
                Known na = ks.stream().filter(k -> k.verdict.equals("NA")).findFirst().orElse(null);
                Known note = ks.stream().filter(k -> k.verdict.equals("NOTE")).findFirst().orElse(null);
                if (otherMc() && (pass != null || passOther != null || fail != null || na != null)) {
                    // known.tsv was measured on another Minecraft version: a result there is a hint, not a test of this mod build
                    Known k = fail != null ? fail : na != null ? na : pass != null ? pass : passOther;
                    c = k.verdict.equals("PASS") ? 0.85 : 0.6;
                    String kn = plainNote(k.note);
                    why = (k.verdict.equals("PASS") ? "worked in Alloy's Minecraft " + totalsMc + " tests" : k.verdict.equals("NA") ? "failed on real Forge in Alloy's Minecraft " + totalsMc + " tests"
                            : "failed in Alloy's Minecraft " + totalsMc + " tests") + (kn.isEmpty() ? "" : " (" + kn + ")") + "; not tested on its own on " + mc;
                }
                else if (fail != null) { c = 0; why = "FAILS on Alloy (" + fail.side + "): " + fail.note; add(Sev.WARN, pl.jar.name, m.id + " is known to fail on Alloy: " + fail.note, "remove it, or check for a newer version"); }
                else if (pass != null) { c = 1; why = "tested on Alloy (" + pass.side + ")"; verified++; }
                else if (na != null && pl.as == ModMeta.Loader.FORGE) { c = 0.2; why = "fails on real Forge too: " + na.note; add(Sev.WARN, pl.jar.name, m.id + " fails on real Forge as well: " + na.note, "not an Alloy problem; try another version"); }
                else if (passOther != null) { c = 0.9; why = "tested on Alloy " + passOther.side + " side only"; }
                else if (pl.as == ModMeta.Loader.FORGE) { c = 0.85; why = (otherMc() ? "Forge mod not tested on Minecraft " + mc + " (" : "untested Forge mod (") + testedText("forge") + ")"; }
                else if (FABRIC_RISKY.contains(m.id)) { c = 0.5; why = "Fabric mod whose mixin targets Forge changed: some features may be off"; }
                else { c = 0.75; why = (otherMc() ? "Fabric mod not tested on Minecraft " + mc + " (" : "untested Fabric mod (") + testedText("fabric") + ")"; }
                if (note != null && !plainNote(note.note).isEmpty()) why += "; note: " + plainNote(note.note);
                sum += c; n++;
                lines.add(String.format(Locale.ROOT, "  %3.0f%%  %-8s %-28s %s", c * 100, side(pl.as), trunc(m.id + " " + Objects.toString(m.version, ""), 28), why));
            }
        }
        // known bad pairs (known.tsv rows "a+b<TAB>side<TAB>PAIR<TAB>note"): both installed -> warn
        Set<String> present = new HashSet<>();
        for (Placed pl : placed) for (Mod m : pl.jar.mods) if (m.loader == pl.as) present.add(m.id);
        // Embeddium declares the old id "rubidium" too: the Rubidium rows are about the real old Rubidium jar
        for (Placed pl : placed) if (pl.jar.mods.stream().anyMatch(m -> m.id.equals("embeddium")) && pl.jar.mods.stream().anyMatch(m -> m.id.equals("rubidium"))
                && placed.stream().noneMatch(o -> o != pl && o.jar.mods.stream().anyMatch(m -> m.id.equals("rubidium")))) present.remove("rubidium");
        for (var e : known.entrySet()) {
            if (!e.getKey().contains("+")) continue;
            List<String> parts = List.of(e.getKey().split("[+]"));
            if (!present.containsAll(parts)) continue;
            String ab = parts.size() == 2 ? parts.get(0) + "+" + parts.get(1) : "", ba = parts.size() == 2 ? parts.get(1) + "+" + parts.get(0) : "";
            boolean portWarned = portPairs.containsKey(ab) || portPairs.containsKey(ba);   // a mod_risks PORT/PREFER row already warned
            for (Known k : e.getValue()) {
                if (!k.side.equals(side) && !k.side.equals("both")) continue;
                if (k.verdict.equals("BLOCK")) {
                    // two mods that patch the same classes (Sodium and Embeddium, Iris and Oculus): one must go
                    Finding pf = portPairs.get(ab) != null ? portPairs.get(ab) : portPairs.get(ba);   // its fix says which one to keep
                    findings.remove(portPairs.get(ab));
                    findings.remove(portPairs.get(ba));
                    add(Sev.BLOCKER, String.join(" + ", parts), String.join(" and ", parts) + " together: " + k.note, pf != null ? pf.fix() : "remove one of the two");
                } else if (k.verdict.equals("PAIR") && !portWarned)
                    add(Sev.WARN, String.join(" + ", parts), String.join(" and ", parts) + " together: " + k.note, "known mod-vs-mod issue, not an Alloy problem");
            }
        }
        // open Alloy bug on 1.20.1: Fabric Architectury's LiquidBlockAccessor.getFluid replaces Forge's LiquidBlock.getFluid, so
        // Biomes O' Plenty's supplier-made liquid blocks return a null fluid at registry freeze (crash) or the world creation hangs
        if ("1.20.1".equals(mc)
                && placed.stream().anyMatch(pl -> pl.as == ModMeta.Loader.FABRIC && pl.jar.mods.stream().anyMatch(m -> m.loader == ModMeta.Loader.FABRIC && m.id.equals("architectury")))
                && present.contains("biomesoplenty"))
            add(Sev.WARN, "architectury + biomesoplenty", "Fabric Architectury with Biomes O' Plenty on 1.20.1 crashes or hangs at world creation (known open Alloy bug)",
                    "use the Forge build of Architectury instead of the Fabric one while Biomes O' Plenty is installed");
        featuresOff(modsDir);
        long blockers = findings.stream().filter(f -> f.sev == Sev.BLOCKER).count();
        long warns = findings.stream().filter(f -> f.sev == Sev.WARN).count();
        int score = n == 0 ? 100 : (int) Math.round(100 * sum / n);
        score = Math.max(0, score - (int) (5 * warns));
        if (blockers > 0) score = 0;
        String grade = blockers > 0 ? "WILL NOT START" : score >= 90 ? "A (very likely works)" : score >= 75 ? "B (likely works)" : score >= 60 ? "C (expect some issues)" : "D (risky)";

        // ---------------- report ----------------
        System.out.println("Alloy Mod Doctor - " + side + " - Minecraft " + mc + ", Forge " + forge + ", Fabric Loader " + fabricLoader);
        Finding top = findings.stream().filter(f -> f.sev == Sev.BLOCKER).findFirst().orElse(null);
        if (top != null) System.out.println(headline(top, blockers));   // the one line a player reads first
        if (lastCrash != null) System.out.println(lastCrash);
        System.out.println("Scanned " + scanned + " jar(s): " + count(placed, ModMeta.Loader.FABRIC) + " Fabric, " + count(placed, ModMeta.Loader.FORGE) + " Forge. "
                + (otherMc() ? "Alloy's mod-by-mod tests are from Minecraft " + totalsMc + "; on " + mc + " Alloy was tested with sets of mods (COMPATIBILITY.md), so the lines below are estimates."
                        : verified + " of " + n + " mods were tested on Alloy one by one."));
        System.out.println();
        for (Sev s : Sev.values()) {
            for (Finding f : findings) if (f.sev == s) {
                System.out.println("[" + s + "] " + f.jar);
                System.out.println("    " + f.what);
                System.out.println("    fix: " + f.fix);
            }
        }
        if (findings.isEmpty()) System.out.println("No problems found.");
        System.out.println();
        System.out.println("Per-mod confidence:");
        lines.forEach(System.out::println);
        System.out.println();
        System.out.println("Compatibility score: " + score + "/100  " + grade + "  (" + blockers + " blocker(s), " + warns + " warning(s))");
        System.out.println("The score is an estimate from Alloy's own test runs, not a guarantee. Nothing was sent anywhere.");

        if (jsonOut != null) writeJson(jsonOut, score, grade, n, verified);
        return blockers > 0 ? 2 : warns > 0 ? 1 : 0;
    }

    /** version without its "+mc1.20.1" part, compared with Versions.cmp; 0 when it cannot be read */
    public static int cmpBase(String version, String than) {
        if (version == null) return 0;
        try { return Versions.cmp(version.replaceAll("[+].*", ""), than); } catch (IllegalArgumentException e) { return 0; }
    }

    /** the Embeddium version this Embeddium build has Fabric Rendering API support built in from */
    public static final String EMBEDDIUM_FRAPI = "0.3.20";

    /**
     * Embeddium 0.3.20+ has Fabric Rendering API support built in (it reuses Fabric API's Indigo). Lazurite (the old Forge
     * port of Indium) is then not needed, and Embeddium itself asks to remove it. Indium (Sodium's FRAPI renderer, Fabric)
     * next to Embeddium registers itself as the renderer and turns Indigo off, which Embeddium's FRAPI code still expects.
     */
    void frapiExtras(List<Placed> placed) {
        Placed emb = null;
        boolean sodium = false;
        for (Placed pl : placed) for (Mod m : pl.jar.mods) {
            if (m.loader != pl.as) continue;
            if (m.id.equals("embeddium") && pl.as == ModMeta.Loader.FORGE && m.version != null && cmpBase(m.version, EMBEDDIUM_FRAPI) >= 0) emb = pl;
            if (m.id.equals("sodium") && pl.as == ModMeta.Loader.FABRIC) sodium = true;
        }
        if (emb == null) return;
        for (Placed pl : placed) for (Mod m : pl.jar.mods) {
            if (m.loader != pl.as) continue;
            if (m.id.equals("lazurite") && pl.as == ModMeta.Loader.FORGE)
                add(Sev.WARN, pl.jar.name, "Lazurite is not needed: " + emb.jar.name + " (Embeddium " + EMBEDDIUM_FRAPI + " or newer) has Fabric Rendering API support built in"
                        + " and asks to remove Lazurite", "delete " + pl.jar.name + " from forgemods/");
            if (m.id.equals("indium") && pl.as == ModMeta.Loader.FABRIC && !sodium)
                add(Sev.WARN, pl.jar.name, "Indium is Sodium's Fabric Rendering API add-on. Next to Embeddium it takes over the renderer and breaks Embeddium's built-in"
                        + " support (models of Fabric mods render wrong or crash)", "delete " + pl.jar.name + " from mods/");
        }
    }

    /** the switch that lets a player keep OptiFine anyway (unsupported): config/alloy.properties or -D */
    public static final String OPTIFINE_OVERRIDE = "allowUnsupported.optifine";
    /** mods that cannot run next to OptiFine: renderers, shader mods, Fabric's FRAPI renderer and the mods built on it */
    static final Set<String> OPTIFINE_CLASH = new LinkedHashSet<>(List.of("sodium", "embeddium", "rubidium", "iris", "oculus", "indium", "continuity"));

    /** true when the player turned the OptiFine block off (allowUnsupported.optifine=true in config/alloy.properties, or -Dalloy.allowUnsupported.optifine=true) */
    static boolean optifineAllowed(Path game) {
        if (Boolean.getBoolean("alloy." + OPTIFINE_OVERRIDE)) return true;
        try {
            Path f = game == null ? null : game.resolve("config/alloy.properties");
            if (f == null || !Files.isRegularFile(f)) return false;
            Properties p = new Properties();
            try (var r = Files.newBufferedReader(f)) { p.load(r); }
            return "true".equalsIgnoreCase(p.getProperty(OPTIFINE_OVERRIDE, "").trim());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * OptiFine (found by its jar contents, not its file name) and OptiFabric, before launch.
     * OptiFine swaps whole vanilla classes through a ModLauncher service that Fabric's mixins and Alloy cannot see: a crash at
     * start or broken rendering, usually blamed on another mod. A BLOCKER unless the player set the override, then a WARN.
     */
    void optifine(List<JarInfo> infos, List<Placed> placed, Path game) {
        List<String> of = new ArrayList<>();
        for (JarInfo ji : infos) if (ji.optifine) of.add(ji.name);
        String fabricator = null;
        Set<String> clash = new LinkedHashSet<>();
        for (Placed pl : placed) for (Mod m : pl.jar.mods) {
            if (m.loader != pl.as) continue;
            if (m.id.equals("optifabric") && pl.as == ModMeta.Loader.FABRIC) fabricator = pl.jar.name;
            if (OPTIFINE_CLASH.contains(m.id)) clash.add(m.id);
        }
        if (of.isEmpty() && fabricator == null) return;
        boolean allowed = optifineAllowed(game);
        Sev sev = allowed ? Sev.WARN : Sev.BLOCKER;
        String also = clash.isEmpty() ? "" : " OptiFine is also installed together with " + String.join(", ", clash) + ". These cannot run at the same time.";
        String unsupported = allowed ? " OptiFine present (unsupported override): Alloy will try to start, but crashes and graphics bugs with it are not supported." : "";
        String fix = allowed ? "remove OptiFine to get support again (then delete " + OPTIFINE_OVERRIDE + " from config/alloy.properties)"
                : "remove OptiFine and use these instead: FPS Embeddium or Sodium (one), shaders Oculus (with Embeddium) or Iris (with Sodium), OptiFine resource packs"
                + " Entity Model Features, Entity Texture Features, Continuity, CIT Resewn, Polytone. To try anyway (unsupported): " + OPTIFINE_OVERRIDE + "=true in config/alloy.properties";
        for (String n : of)
            add(sev, n, "OptiFine found: not supported on Alloy. OptiFine replaces Minecraft's rendering code in a way that breaks Fabric API and most performance"
                    + " and shader mods, which usually means a crash at start or missing graphics. It is closed source, so Alloy cannot make it fit." + also + unsupported, fix);
        if (fabricator != null) {
            boolean refuses = clash.contains("sodium") || clash.contains("iris");
            add(sev, fabricator, "OptiFabric is a bridge for loading OptiFine on Fabric. It is not supported on Alloy either"
                    + (refuses ? "; Sodium and Iris refuse to start next to it (Fabric Loader stops with its incompatible mod screen)" : ", and Sodium and Iris refuse to start next to it")
                    + (of.isEmpty() ? "" : ". Forge would also start OptiFine's own service on the same jar") + unsupported,
                    "remove " + fabricator + " from mods/" + (of.isEmpty() ? "" : " together with OptiFine"));
        }
    }

    /** Forge mods that add biomes, structures or terrain: their world generation code was written for the server thread only */
    static final Set<String> FORGE_WORLDGEN = Set.of("terrablender", "yungsapi", "tectonic", "terralith", "biomesoplenty", "regions_unexplored",
            "byg", "biomeswevegone", "integrated_api", "repurposed_structures", "when_dungeons_arise", "betterdungeons", "betterstrongholds");

    /**
     * Fabric C2ME's threaded world generation runs Forge worldgen mods' features and structure code on worker threads
     * (C2meGuard only turns its chunk IO off). Forge worldgen mods were never written for that. WARN, unless
     * config/c2me.toml already has threadedWorldGen.enabled = false.
     */
    void c2meWorldgen(List<Placed> placed, Path game) {
        Placed c2me = null;
        Set<String> gen = new TreeSet<>();
        for (Placed pl : placed) for (Mod m : pl.jar.mods) {
            if (m.loader != pl.as) continue;
            if (pl.as == ModMeta.Loader.FABRIC && m.id.equals("c2me")) c2me = pl;
            if (pl.as == ModMeta.Loader.FORGE && FORGE_WORLDGEN.contains(m.id)) gen.add(m.id);
        }
        if (c2me == null || gen.isEmpty()) return;
        if (c2meWorldgenOff(game)) {
            add(Sev.INFO, c2me.jar.name, "C2ME's threaded world generation is off in config/c2me.toml, so the Forge worldgen mods " + gen + " generate on the server thread", "nothing to do");
            return;
        }
        add(Sev.WARN, c2me.jar.name, "C2ME's threaded world generation runs the Forge worldgen mods " + gen + " on worker threads. They were written for Forge's"
                        + " single server thread: expect rare crashes or broken structures in new chunks",
                "set threadedWorldGen.enabled = false in config/c2me.toml (or remove C2ME and use FastChunkGen, Forge)");
    }

    /** true when config/c2me.toml sets threadedWorldGen.enabled = false (table form or dotted key) */
    static boolean c2meWorldgenOff(Path game) {
        try {
            Path f = game == null ? null : game.resolve("config/c2me.toml");
            if (f == null || !Files.isRegularFile(f)) return false;
            String table = "";
            for (String line : Files.readAllLines(f)) {
                String t = line.strip();
                if (t.startsWith("[")) { table = t.replaceAll("^\\[+\\s*|\\s*\\]+.*$", ""); continue; }
                String v = t.replaceAll("\\s*#.*$", "").replace(" ", "").replace("\t", "");
                if (table.equals("threadedWorldGen") && v.equals("enabled=false") || table.isEmpty() && v.equals("threadedWorldGen.enabled=false")) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** what the last data condition check read: sources, sources read again (not from the cache), milliseconds */
    int[] dataCondStats;

    /**
     * Data packs (mod jars' data/, Paxi, Open Loader, Global Packs, KubeJS, world datapacks/) whose load conditions use a
     * condition type of a mod that is not installed on either side. On Forge 47 to 49 such a condition in
     * a worldgen or other data pack registry file stops world creation ("Unknown condition type"): a BLOCKER. In recipes and
     * loot tables Forge logs it and skips the file: a WARN. -Dalloy.doctor.datacond.off=true turns the check off.
     */
    void dataConditions(List<Placed> placed, Map<String, String> fabricIds, Map<String, String> forgeIds, Path game) {
        if (Boolean.getBoolean("alloy.doctor.datacond.off") || game == null) return;
        long t0 = System.nanoTime();
        alloy.presence.PresenceTable table = new alloy.presence.PresenceTable();
        for (var e : fabricIds.entrySet()) table.add(new alloy.presence.PresenceTable.Entry(e.getKey(), alloy.presence.PresenceTable.Side.FABRIC, e.getValue(), e.getKey(), false));
        for (var e : forgeIds.entrySet()) table.add(new alloy.presence.PresenceTable.Entry(e.getKey(), alloy.presence.PresenceTable.Side.FORGE, e.getValue(), e.getKey(), false));
        Set<String> ids = new TreeSet<>(fabricIds.keySet());
        ids.addAll(forgeIds.keySet());
        java.util.function.Predicate<String> loaded = id -> table.data(id).yes();
        Map<String, String> owners = new HashMap<>();
        java.util.function.Function<String, String> owner = ns -> owners.computeIfAbsent(ns, n -> Objects.toString(DataConditions.owner(n, table, ids), ""));
        java.util.function.Function<String, String> ownerOrNull = ns -> owner.apply(ns).isEmpty() ? null : owner.apply(ns);

        List<DataConditions.Source> sources = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Placed pl : placed) names.add(pl.jar.name);
        for (Placed pl : placed) {
            String n = pl.jar.name;
            if (n.endsWith("-alloy.jar") && names.contains(n.substring(0, n.length() - "-alloy.jar".length()) + ".jar")) continue;   // fitted copy
            sources.add(new DataConditions.Source(pl.jar.file, pl.folder + "/" + n, false, false, true));
        }
        sources.addAll(DataConditions.packSources(game, loaded));
        boolean cacheOn = !Boolean.getBoolean("alloy.statcache.off") && Files.isDirectory(game.resolve(".alloy"));
        DataConditions.Cache cache = new DataConditions.Cache(cacheOn ? game.resolve(".alloy/datacond.cache") : null);
        List<List<DataConditions.Cond>> found = new ArrayList<>();
        int n = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() - 1));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n, r -> {
            Thread th = new Thread(r, "alloy-datacond"); th.setDaemon(true); th.setPriority(Thread.MIN_PRIORITY); return th;
        });
        try {
            List<java.util.concurrent.Future<List<DataConditions.Cond>>> fs = new ArrayList<>();
            for (DataConditions.Source s : sources) fs.add(pool.submit(() -> cache.get(s)));
            for (var f : fs) {
                try { found.add(f.get()); } catch (Exception e) { found.add(List.of()); }   // an unreadable jar has its own finding
            }
        } finally { pool.shutdownNow(); }
        cache.save();

        String major = forge == null ? "" : forge.contains(".") ? forge.substring(0, forge.indexOf('.')) : forge;
        boolean fatalForge = major.equals("47") || major.equals("48") || major.equals("49");
        for (int i = 0; i < sources.size(); i++) {
            DataConditions.Source s = sources.get(i);
            for (DataConditions.Hit h : DataConditions.missing(s, found.get(i), loaded, ownerOrNull)) {
                String file = h.entries().get(0) + (h.entries().size() > 1 ? " (and " + (h.entries().size() - 1) + " more file(s))" : "");
                String what = (s.modJar() ? "the mod's own data file " : "data pack file ") + file + " has the load condition " + h.type()
                        + ", but the mod " + h.mod() + " is not installed (on either side). Nothing registers that condition";
                String fix = "add the mod " + h.mod() + (s.modJar() ? "" : " (it is the mod this data pack was made for)")
                        + ", or remove " + (s.modJar() ? s.label() + " (its data needs " + h.mod() + ")" : "the data pack " + s.label());
                if (h.keys().equals(Set.of("neoforge:conditions")))
                    add(Sev.INFO, s.label(), what + ". Forge and Fabric do not read neoforge:conditions, so the file loads anyway", "nothing to do unless that content is broken");
                else if (h.registry() && fatalForge && !s.optional())
                    add(Sev.BLOCKER, s.label(), what + ", so Forge stops world creation with \"Unknown condition type: " + h.type()
                            + "\". Plain Forge does the same: this is the pack, not Alloy", fix);
                else if (h.registry())
                    add(Sev.WARN, s.label(), what + ". Forge reads it while loading the world's registries and will likely stop world creation with \"Unknown condition type\""
                            + (s.optional() ? " once this optional data pack is turned on" : ""), fix);
                else
                    add(Sev.WARN, s.label(), what + (h.keys().contains("fabric:load_conditions") ? ". Fabric API logs an error and skips the file" : ". Forge logs \"Unknown condition type\" and skips the file")
                            + " (a recipe or loot table goes missing)" + (s.optional() ? "; this data pack is optional" : ""), fix);
            }
        }
        dataCondStats = new int[]{sources.size(), cache.reads, (int) ((System.nanoTime() - t0) / 1_000_000)};
    }

    /** ModernFix below this crashes at launch on Forge 47.3.1 and newer (its vehicle packet mixin, fixed in 5.18.0) */
    public static final String MODERNFIX_FIXED = "5.18.0", FORGE_VEHICLE = "47.3.1";

    void modernFixVehicle(List<Placed> placed, Path game) {
        if (forge == null || !forge.startsWith("47.") || cmpBase(forge, FORGE_VEHICLE) < 0) return;
        for (Placed pl : placed) for (Mod m : pl.jar.mods) {
            if (!m.id.equals("modernfix") || pl.as != ModMeta.Loader.FORGE || m.loader != pl.as || m.version == null || cmpBase(m.version, MODERNFIX_FIXED) >= 0) continue;
            boolean off = false;
            try {
                Path cfg = game == null ? null : game.resolve("config/modernfix-mixins.properties");
                if (cfg != null && Files.isRegularFile(cfg))
                    off = Files.readAllLines(cfg).stream().anyMatch(l -> l.replace(" ", "").equals("mixin.bugfix.forge_vehicle_packets=false"));
            } catch (Exception ignored) { }
            if (off) add(Sev.INFO, pl.jar.name, "ModernFix " + m.version + " has a mixin that crashes on Forge " + FORGE_VEHICLE + "+; it is turned off in config/modernfix-mixins.properties",
                    "optional: update ModernFix to " + MODERNFIX_FIXED + " or newer");
            else add(Sev.BLOCKER, pl.jar.name, "ModernFix " + m.version + " crashes at launch on Forge " + forge + " (Critical injection failure in its vehicle packet mixin;"
                    + " fixed in ModernFix " + MODERNFIX_FIXED + "). Plain Forge crashes the same way",
                    "update ModernFix to " + MODERNFIX_FIXED + " or newer (Minecraft " + mc + " build), or add mixin.bugfix.forge_vehicle_packets=false to config/modernfix-mixins.properties");
        }
    }

    /** One id can come from several jars (nested copies of MixinExtras or Fabric API modules). Fabric Loader loads the
     *  newest one, so the check must compare against the newest too, not against whichever jar was read first. */
    static void keepNewest(Map<String, String> ids, String id, String version) {
        if (!ids.containsKey(id)) { ids.put(id, version); return; }
        String have = ids.get(id);
        if (version == null) return;
        if (have == null) {
            // platform entries are set before the jars are read: keep those (Fabric Loader bundles MixinExtras 0.5.5)
            if (!Set.of("minecraft", "java", "fabricloader", "fabric-loader", "forge", "mixinextras").contains(id)) ids.put(id, version);
            return;
        }
        try { if (Versions.cmp(version, have) > 0) ids.put(id, version); } catch (IllegalArgumentException e) { // not comparable: keep the first
        }
    }

    /** Where does this jar actually load? Adds folder findings. null = not loaded. */
    ModMeta.Loader placement(JarInfo ji, String folder) {
        boolean inMods = folder.equals("mods");
        var L = ji.loaders;
        if (ji.error != null) { add(Sev.BLOCKER, ji.name, "cannot read this jar: " + ji.error, "re-download it (the file is damaged or not a mod)"); return null; }
        if (ji.optifine) return null;   // its own finding (optifine), no folder advice
        var route = inMods ? routes.get(ji.name) : null;
        if (route != null) switch (route.action()) {
            case COPY, MOVE -> { add(Sev.INFO, ji.name, "Forge mod in mods/: " + route.note(), "nothing to do"); return ModMeta.Loader.FORGE; }
            case DUPLICATE -> {
                add(Sev.WARN, ji.name, route.note(), "remove one of the two copies");
                return route.verdict().multi() ? ModMeta.Loader.FABRIC : null;
            }
            case NONE -> {
                if (route.verdict().overridden() && route.verdict().side() == alloy.installer.ForgeInMods.Side.NONE) {
                    add(Sev.INFO, ji.name, route.verdict().why(), "nothing to do");
                    return route.verdict().multi() ? ModMeta.Loader.FABRIC : null;
                }
            }
        }
        if (L.isEmpty()) {
            // a jar-in-jar container with no mods.toml of its own (Kotlin for Forge 4.x "-all": kfflang, kfflib, kffmod in
            // META-INF/jarjar): Forge loads the nested mods, so their ids are installed
            if (!inMods && ji.nested.stream().anyMatch(m -> m.loader == ModMeta.Loader.FORGE)) return ModMeta.Loader.FORGE;
            if (ji.hasPackMcmeta && !ji.hasClasses) add(Sev.WARN, ji.name, "this is a data pack or resource pack, not a mod", "put it in resourcepacks/ or the world's datapacks/ folder");
            else if (ji.hasModLauncherService) { if (inMods) add(Sev.WARN, ji.name, "ModLauncher service jar (Forge-style) in mods/", "move it to forgemods/"); else return ModMeta.Loader.FORGE; }
            else add(Sev.WARN, ji.name, "no fabric.mod.json or mods.toml: not a mod (a library?)", "libraries are normally bundled inside mods; remove it unless a mod's page says otherwise");
            return null;
        }
        if (inMods) {
            if (L.contains(ModMeta.Loader.FABRIC)) {
                if (ji.isMulti()) add(Sev.INFO, ji.name, "multi-loader jar: loads as a Fabric mod from mods/",
                        "to use its Forge build instead, add '" + ji.name + " = forge' to " + alloy.installer.ForgeInMods.CONFIG);
                return ModMeta.Loader.FABRIC;
            }
            if (L.contains(ModMeta.Loader.FORGE)) { add(Sev.WARN, ji.name, "Forge mod in mods/ (the Fabric folder): it will not load from here", "move it to forgemods/"); return ModMeta.Loader.FORGE; }
        } else {
            if (L.contains(ModMeta.Loader.FORGE)) {
                if (ji.isMulti()) add(Sev.INFO, ji.name, "multi-loader jar: loads only as a Forge mod from forgemods/", "nothing to do");
                return ModMeta.Loader.FORGE;
            }
            if (L.contains(ModMeta.Loader.FABRIC)) { add(Sev.WARN, ji.name, "Fabric mod in forgemods/ (the Forge folder): it will not load from here", "move it to mods/"); return ModMeta.Loader.FABRIC; }
        }
        if (L.contains(ModMeta.Loader.NEOFORGE)) { add(Sev.BLOCKER, ji.name, "NeoForge-only mod. Alloy runs MinecraftForge and Fabric mods, not NeoForge mods", "look for a Forge or Fabric build of this mod (many big mods are NeoForge-only on 26.1.2)"); return null; }
        if (L.contains(ModMeta.Loader.QUILT)) { add(Sev.WARN, ji.name, "Quilt-only mod: not supported", "look for a Fabric build"); return null; }
        return null;
    }

    /**
     * known.tsv rows are per mod id, but one id can have a Fabric and a Forge build (Cherished Worlds: the Fabric build
     * passes, the Forge build crashes on real Forge too). Keep the rows that are about the build that is installed.
     */
    static List<Known> forLoader(List<Known> ks, ModMeta.Loader as) {
        List<Known> out = new ArrayList<>();
        for (Known k : ks) {
            boolean fabricRow = k.note.contains("results_fabric");
            boolean forgeRow = k.note.matches(".*results(_server|_client|\\d|_fixcheck).*") || k.note.contains("real Forge");
            if (as == ModMeta.Loader.FORGE && fabricRow) continue;
            if (as == ModMeta.Loader.FABRIC && forgeRow && !fabricRow) continue;
            out.add(k);
        }
        return out;
    }

    /** Rough heap need: 2 GB for the game plus about 20 MB per mod jar (100 mods about 4 GB, 300 mods about 8 GB). */
    static long heapNeedMb(int jars) { return 2048 + 20L * jars; }

    void heapCheck(int jars, Path game) {
        long need = heapNeedMb(jars), have = heapMb(game);
        String needText = String.format(Locale.ROOT, "%.0f GB", Math.ceil(need / 1024.0));
        if (have > 0 && have + 64 < need)
            add(Sev.WARN, "memory", jars + " mod jars usually need about " + needText + " of memory, but the game gets only " + have + " MB (-Xmx)",
                    "raise the instance's maximum memory to " + needText + " or more in your launcher");
        else if (have <= 0 && jars >= 100)
            add(Sev.INFO, "memory", jars + " mod jars usually need about " + needText + " of memory", "make sure the launcher gives the game at least " + needText);
    }

    /** The game's -Xmx in MB: set by the launch step (it runs in the game JVM), else read from the start scripts / Prism instance.cfg. 0 = unknown. */
    static long heapMb(Path game) {
        String p = System.getProperty("alloy.doctor.heapMb");
        if (p != null) try { return Long.parseLong(p.trim()); } catch (NumberFormatException ignored) { }
        try {
            List<Path> files = new ArrayList<>();
            if (Files.isDirectory(game)) try (var st = Files.list(game)) {
                st.filter(f -> f.getFileName().toString().matches("start.*\\.(cmd|sh|bat)")).forEach(files::add);
            }
            Path prism = game.getParent() == null ? null : game.getParent().resolve("instance.cfg");
            if (prism != null && Files.isRegularFile(prism)) {
                for (String l : Files.readAllLines(prism, java.nio.charset.StandardCharsets.UTF_8))
                    if (l.startsWith("MaxMemAlloc=")) return Long.parseLong(l.substring(12).trim());
            }
            for (Path f : files) {
                var m = java.util.regex.Pattern.compile("-Xmx(\\d+)([gGmMkK]?)").matcher(Files.readString(f, java.nio.charset.StandardCharsets.UTF_8));
                if (m.find()) {
                    long v = Long.parseLong(m.group(1));
                    return switch (m.group(2).toLowerCase(Locale.ROOT)) { case "g" -> v * 1024; case "k" -> v / 1024; case "" -> v >> 20; default -> v; };
                }
            }
        } catch (Exception ignored) { }
        return 0;
    }

    void add(Sev s, String jar, String what, String fix) { findings.add(new Finding(s, jar, what, fix)); }

    /** a mod with this many features off gets its own line (the fitter's list from the last start, .alloy/features-off.txt) */
    static final int MANY_OFF = 5;

    /** Fabric mods with many features off on Alloy (from the last fit; a mod that is no longer installed is skipped). */
    void featuresOff(Path modsDir) {
        try {
            Path game = modsDir.toAbsolutePath().normalize().getParent();
            if (game == null) return;
            var s = alloy.installer.FeaturesOff.read(game.resolve(".alloy"));
            for (var e : alloy.installer.FeaturesOff.byMod(s).entrySet()) {
                var l = e.getValue();
                String jar = l.get(0).jar();
                if (l.size() < MANY_OFF || !(Files.exists(modsDir.resolve(jar)) || Files.exists(game.resolve(".alloy/originals").resolve(jar)))) continue;
                Map<String, Integer> areas = new LinkedHashMap<>();
                for (var i : l) if (!i.area().isEmpty() && !i.area().equals("other")) areas.merge(i.area(), 1, Integer::sum);
                List<String> top = new ArrayList<>();
                areas.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(3).forEach(x -> top.add(x.getKey()));
                add(Sev.INFO, jar, l.size() + " small parts of " + e.getKey() + " are switched off on Alloy" + (top.isEmpty() ? "" : " (" + String.join(", ", top) + ")")
                        + " because they do not fit Forge's changes. The mod loads and works",
                        "nothing to do; the list is in alloy-report.txt (SWITCHED OFF ON ALLOY)");
            }
        } catch (Exception e) { /* the list is optional */ }
    }
    static String side(ModMeta.Loader l) { return l == ModMeta.Loader.FABRIC ? "Fabric" : "Forge"; }
    static ModMeta.Loader other(ModMeta.Loader l) { return l == ModMeta.Loader.FABRIC ? ModMeta.Loader.FORGE : ModMeta.Loader.FABRIC; }
    static String folder(ModMeta.Loader l) { return l == ModMeta.Loader.FABRIC ? "mods" : "forgemods"; }
    static boolean bracketed(String r) { return r != null && (r.trim().startsWith("[") || r.trim().startsWith("(")); }
    /** Forge dependency ids that only Sinytra Connector packs provide (Connector, Connector Extras, Forgified Fabric API and its modules) */
    static boolean connectorDep(String id) {
        return id.equals("connectormod") || id.equals("connector") || id.equals("connectorextras") || id.equals("fabric_api")
                || id.equals("forgified_fabric_api") || id.startsWith("fabric_");
    }

    static String rangeText(String r) { return r == null || r.isBlank() || r.equals("*") ? "" : " " + r; }
    static String trunc(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "~"; }
    static long count(List<Placed> p, ModMeta.Loader l) { return p.stream().filter(x -> x.as == l).count(); }

    void writeJson(Path out, int score, String grade, int n, int verified) throws Exception {
        StringBuilder b = new StringBuilder("{\n");
        b.append("  \"side\": ").append(ModMeta.MiniJson.quote(side)).append(",\n");
        b.append("  \"score\": ").append(score).append(",\n  \"grade\": ").append(ModMeta.MiniJson.quote(grade)).append(",\n");
        b.append("  \"mods\": ").append(n).append(",\n  \"verified\": ").append(verified).append(",\n  \"findings\": [\n");
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            b.append("    {\"severity\": ").append(ModMeta.MiniJson.quote(f.sev.name())).append(", \"jar\": ").append(ModMeta.MiniJson.quote(f.jar))
             .append(", \"what\": ").append(ModMeta.MiniJson.quote(f.what)).append(", \"fix\": ").append(ModMeta.MiniJson.quote(f.fix)).append("}")
             .append(i + 1 < findings.size() ? ",\n" : "\n");
        }
        b.append("  ]\n}\n");
        Files.writeString(out, b);
    }
}
