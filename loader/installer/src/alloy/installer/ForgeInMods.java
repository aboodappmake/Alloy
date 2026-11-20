package alloy.installer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.Manifest;
import java.util.zip.*;

/**
 * Forge mods in mods/. Launcher mod downloaders (Prism, Modrinth App, CurseForge) and pack formats (.mrpack, CurseForge
 * packs) put every mod in mods/. Alloy's FML reads forgemods/, so each prep (before anything else) mirrors the Forge jars
 * found in mods/ into forgemods/ as {@code frommods_<name>.jar}. The jar in mods/ stays where the launcher put it, so the
 * launcher can still update or remove it; a mirror whose source is gone or changed is removed or refreshed. Everything
 * after this step (PrepGame ATs, jar-in-jar extraction, alloy-modmixins, Collide, the bridge, isolation) sees the
 * mirror as an ordinary forgemods/ jar. Fabric Loader ignores the original in mods/: a jar without fabric.mod.json is
 * only listed as a "non-fabric mod" (ModDiscoverer.computeJarFile), its jar-in-jar is never read.
 *
 * Rules for a jar in mods/ (top level of the jar only):
 *   fabric.mod.json + META-INF/mods.toml   multi-loader: Fabric (it already loads there today), unless the config says forge
 *   META-INF/mods.toml only                Forge
 *   fabric.mod.json only                   Fabric
 *   neither, but a ModLauncher/FML service (ITransformationService, IModLocator, IDependencyLocator)
 *   or an FMLModType manifest attribute    Forge (FML-only jars: Fabric cannot use them)
 *   anything else                          left alone (library, NeoForge-only, pack, ...)
 * Overrides: config/alloy-sides.properties, lines {@code key = forge|fabric|none}; key is a jar file name (glob with *)
 * or a mod id. A multi-loader jar set to forge is moved to forgemods/ (Fabric Loader would load it from mods/ too).
 * When a Forge mod in mods/ has a mod id that a jar the user put in forgemods/ also has, the forgemods/ jar wins.
 */
public final class ForgeInMods {
    private ForgeInMods() { }

    public static final String PREFIX = "frommods_";
    public static final String CONFIG = "config/alloy-sides.properties";
    static final String STATE = ".alloy/forge-in-mods.properties";
    static final List<String> SERVICES = List.of("META-INF/services/cpw.mods.modlauncher.api.ITransformationService",
            "META-INF/services/net.minecraftforge.forgespi.locating.IModLocator",
            "META-INF/services/net.minecraftforge.forgespi.locating.IDependencyLocator");

    public enum Side { FORGE, FABRIC, NONE }

    /** what one jar is; ids = top-level mod ids (Forge [[mods]] modId and the fabric.mod.json id) */
    public record Verdict(Side side, String why, boolean multi, boolean overridden, Set<String> forgeIds, Set<String> fabricIds) { }

    public enum Action { COPY, MOVE, DUPLICATE, NONE }

    /** decision for one jar in mods/ */
    public record Entry(String name, Verdict verdict, Action action, String note, String replaces) { }

    // ------------------------------------------------------------------ classification

    public static Verdict classify(Path jar, Map<String, String> overrides) {
        boolean fmj = false, toml = false, neo = false, service = false, fmlType = false, forgeDep = false;
        Set<String> forgeIds = new TreeSet<>(), fabricIds = new TreeSet<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry("fabric.mod.json");
            if (e != null) {
                fmj = true;
                Map<String, Object> m = fabricJson(read(z, e));
                if (m.get("id") instanceof String s) fabricIds.add(s);
                if (m.get("depends") instanceof Map<?, ?> d) forgeDep = d.containsKey("forge") || d.containsKey("minecraftforge");
            }
            e = z.getEntry("META-INF/mods.toml");
            if (e != null) { toml = true; forgeIds.addAll(tomlIds(read(z, e))); }
            neo = z.getEntry("META-INF/neoforge.mods.toml") != null;
            for (String s : SERVICES) if (z.getEntry(s) != null) service = true;
            e = z.getEntry("META-INF/MANIFEST.MF");
            if (e != null) try (InputStream in = z.getInputStream(e)) {
                fmlType = new Manifest(in).getMainAttributes().getValue("FMLModType") != null;
            } catch (IOException ignored) { }
        } catch (IOException ex) {
            return new Verdict(Side.NONE, "cannot read the jar (" + ex.getMessage() + ")", false, false, Set.of(), Set.of());
        }
        String o = override(overrides, jar.getFileName().toString(), forgeIds, fabricIds);
        boolean forgeMeta = toml || service || fmlType;
        String what = toml ? "Forge mod (META-INF/mods.toml)" : service ? "ModLauncher/FML service jar" : "FML library jar (FMLModType manifest)";
        if (fmj && forgeMeta) {   // multi-loader: one jar, one side (both would put the same classes on Knot's classpath twice)
            String kind = "multi-loader jar (fabric.mod.json + " + (toml ? "mods.toml" : service ? "ModLauncher service" : "FMLModType") + ")";
            if ("forge".equals(o)) return new Verdict(Side.FORGE, kind + ", set to forge in " + CONFIG, true, true, forgeIds, fabricIds);
            if ("fabric".equals(o)) return new Verdict(Side.FABRIC, kind + ", set to fabric in " + CONFIG, true, true, forgeIds, fabricIds);
            if ("none".equals(o)) return new Verdict(Side.NONE, kind + ", set to none in " + CONFIG + " (Fabric Loader still reads it)", true, true, forgeIds, fabricIds);
            if (forgeDep) return new Verdict(Side.FORGE, kind + " marked Forge-only (its fabric.mod.json depends on forge)", true, false, forgeIds, fabricIds);
            if (wrongLoaderStub(fabricIds))   // Forge JourneyMap: a stub fabric.mod.json that only says "wrong loader" at preLaunch
                return new Verdict(Side.FORGE, kind + " marked Forge-only (its fabric.mod.json is a '-wrongloader' stub)", true, false, forgeIds, fabricIds);
            return new Verdict(Side.FABRIC, kind + ": Fabric by default in mods/", true, false, forgeIds, fabricIds);
        }
        if (forgeMeta) {
            if ("fabric".equals(o) || "none".equals(o))
                return new Verdict(Side.NONE, what + ", kept off the Forge side by " + CONFIG, false, true, forgeIds, fabricIds);
            return new Verdict(Side.FORGE, what, false, false, forgeIds, fabricIds);
        }
        if (fmj) return new Verdict(Side.FABRIC, "Fabric mod" + ("forge".equals(o) ? " (forge in " + CONFIG + " ignored: it has no Forge build)" : ""),
                false, false, forgeIds, fabricIds);
        return new Verdict(Side.NONE, neo ? "NeoForge-only jar" : "no fabric.mod.json or mods.toml: a library or not a mod", false, false, forgeIds, fabricIds);
    }

    /** a fabric.mod.json id ending in "-wrongloader" (Forge JourneyMap's journeymap-wrongloader): the jar is a Forge mod */
    static boolean wrongLoaderStub(Set<String> fabricIds) {
        return fabricIds.stream().anyMatch(s -> s.endsWith("-wrongloader"));
    }

    /** true when Fabric Loader reads this mods/ jar as a Fabric mod (it has a fabric.mod.json and is not routed to Forge) */
    public static boolean loadsOnFabric(Path jar, Map<String, String> overrides) {
        Verdict v = classify(jar, overrides);
        return v.side() == Side.FABRIC || (v.multi() && v.side() == Side.NONE);
    }

    private static String read(ZipFile z, ZipEntry e) throws IOException {
        try (InputStream in = z.getInputStream(e)) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private static Map<String, Object> fabricJson(String json) {
        try {
            Map<String, Object> m = Json.obj(Json.parse(json));
            return m == null ? Map.of() : m;
        } catch (Exception e) { return Map.of(); }
    }

    private static final java.util.regex.Pattern MOD_ID = java.util.regex.Pattern.compile("\\bmodId\\s*=\\s*([\"'])([^\"']*)\\1");

    /**
     * modId values of the mods only: [[mods]] tables and the inline form {@code mods = [ { modId = '...' }, ]}
     * (Modrinth's generated datapack mods). Dependency entries have a modId too and are skipped.
     */
    static Set<String> tomlIds(String toml) {
        Set<String> ids = new TreeSet<>();
        boolean table = false, inline = false;
        for (String raw : toml.split("\\R")) {
            String l = raw.strip();
            if (l.startsWith("#")) continue;
            if (inline) {
                var m = MOD_ID.matcher(l);
                while (m.find()) ids.add(m.group(2));
                if (l.startsWith("]")) inline = false;
                continue;
            }
            if (l.startsWith("[")) { table = l.replace(" ", "").startsWith("[[mods]]"); continue; }
            if (!table && l.matches("mods\\s*=\\s*\\[.*")) {
                var m = MOD_ID.matcher(l);
                while (m.find()) ids.add(m.group(2));
                inline = !l.endsWith("]");
                continue;
            }
            if (table && l.startsWith("modId")) {
                var m = MOD_ID.matcher(l);
                if (m.find()) ids.add(m.group(2));
            }
        }
        ids.remove("");
        return ids;
    }

    private static String override(Map<String, String> ov, String file, Set<String> a, Set<String> b) {
        if (ov == null || ov.isEmpty()) return null;
        for (var e : ov.entrySet()) {   // file names first: they are the more specific key
            String k = e.getKey();
            if ((k.contains("*") || k.toLowerCase(Locale.ROOT).endsWith(".jar")) && glob(k, file)) return e.getValue();
        }
        for (String id : a) if (ov.containsKey(id)) return ov.get(id);
        for (String id : b) if (ov.containsKey(id)) return ov.get(id);
        return null;
    }

    static boolean glob(String pattern, String name) {
        StringBuilder re = new StringBuilder();
        for (char c : pattern.toLowerCase(Locale.ROOT).toCharArray()) re.append(c == '*' ? ".*" : java.util.regex.Pattern.quote(String.valueOf(c)));
        return name.toLowerCase(Locale.ROOT).matches(re.toString());
    }

    /** config/alloy-sides.properties: {@code key = forge|fabric|none}, '#' comments; other values are ignored with a log line */
    public static Map<String, String> overrides(Path game) {
        Map<String, String> m = new LinkedHashMap<>();
        Path f = game.resolve(CONFIG);
        if (!Files.isRegularFile(f)) return m;
        try {
            for (String raw : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String l = raw.strip();
                if (l.isEmpty() || l.startsWith("#")) continue;
                int eq = l.lastIndexOf('=');
                if (eq <= 0) continue;
                String k = l.substring(0, eq).strip(), v = l.substring(eq + 1).strip().toLowerCase(Locale.ROOT);
                if (Set.of("forge", "fabric", "none").contains(v)) m.put(k, v);
                else Util.log("alloy-mods: " + CONFIG + ": '" + raw + "' ignored (the value must be forge, fabric or none)");
            }
        } catch (IOException e) { Util.log("alloy-mods: cannot read " + CONFIG + " (" + e + ")"); }
        return m;
    }

    // ------------------------------------------------------------------ Distant Horizons

    public static final String DH_ID = "distanthorizons";

    /** where Distant Horizons should load and why */
    public record DhRoute(Side side, String why) { }

    /**
     * Distant Horizons for 1.20.1 is one jar with a Fabric and a Forge half. Each half only talks to the renderer and
     * shader mod of its own loader: Oculus looks for DH in Forge's mod list, Iris in Fabric's. So DH goes next to the
     * shader mod, else next to the renderer. null = nothing decides it (keep the default).
     */
    public static DhRoute dhRoute(Set<String> forgeIds, Set<String> fabricIds) {
        boolean oculus = forgeIds.contains("oculus"), iris = fabricIds.contains("iris");
        boolean embeddium = forgeIds.contains("embeddium") || forgeIds.contains("rubidium"), sodium = fabricIds.contains("sodium");
        if (oculus && !iris) return new DhRoute(Side.FORGE, "Oculus (Forge) is the shader mod");
        if (iris && !oculus) return new DhRoute(Side.FABRIC, "Iris (Fabric) is the shader mod");
        if (oculus) return null;   // both shader mods: Mod Doctor blocks that pair
        if (embeddium && !sodium) return new DhRoute(Side.FORGE, "Embeddium (Forge) is the renderer");
        if (sodium && !embeddium) return new DhRoute(Side.FABRIC, "Sodium (Fabric) is the renderer");
        return null;
    }

    // ------------------------------------------------------------------ plan (pure: reads, never writes)

    static boolean ours(String n) {   // files Alloy writes into mods/ itself (bridge, modmixins, fitted copies)
        return n.startsWith("alloy-") || n.startsWith("alloy_") || n.endsWith("-alloy.jar");
    }

    /** decision for every jar in mods/ (Alloy's own files left out), in file name order */
    public static List<Entry> plan(Path game) throws IOException {
        game = game.toAbsolutePath().normalize();
        Map<String, String> ov = overrides(game);
        Path fmods = game.resolve("forgemods");
        Map<String, String> userForgeIds = new HashMap<>();   // id -> forgemods/ jar the user put there (or prep moved there)
        Properties st = state(game);
        for (Path p : Util.jars(fmods)) {
            String n = p.getFileName().toString();
            if (n.startsWith(PREFIX)) continue;
            for (String id : classify(p, Map.of()).forgeIds()) userForgeIds.putIfAbsent(id, n);
        }
        // renderers on each side, for Distant Horizons (dhRoute)
        Map<Path, Verdict> verdicts = new LinkedHashMap<>();
        Set<String> forgeSide = new TreeSet<>(userForgeIds.keySet()), fabricSide = new TreeSet<>();
        for (Path p : Util.jars(fmods)) if (p.getFileName().toString().startsWith(PREFIX)) forgeSide.addAll(classify(p, Map.of()).forgeIds());
        for (Path p : Util.jars(game.resolve("mods"))) {
            if (ours(p.getFileName().toString())) continue;
            Verdict v = classify(p, ov);
            verdicts.put(p, v);
            if (v.side() == Side.FORGE) forgeSide.addAll(v.forgeIds());
            else if (v.side() == Side.FABRIC && !v.fabricIds().contains(DH_ID)) fabricSide.addAll(v.fabricIds());
        }
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<Path, Verdict> pv : verdicts.entrySet()) {
            Path p = pv.getKey();
            String n = p.getFileName().toString();
            Verdict v = pv.getValue();
            if (v.multi() && !v.overridden() && v.side() == Side.FABRIC && (v.fabricIds().contains(DH_ID) || v.forgeIds().contains(DH_ID))) {
                DhRoute r = dhRoute(forgeSide, fabricSide);
                if (r != null && r.side() == Side.FORGE)
                    v = new Verdict(Side.FORGE, "Distant Horizons (one jar for both loaders) goes to the Forge side: " + r.why(), true, false, v.forgeIds(), v.fabricIds());
            }
            if (v.side() != Side.FORGE) { out.add(new Entry(n, v, Action.NONE, v.why(), null)); continue; }
            String dupJar = null, dup = null;
            for (String id : v.forgeIds()) if (userForgeIds.containsKey(id)) { dupJar = userForgeIds.get(id); dup = dupJar + " (mod id " + id + ")"; break; }
            if (dup == null && Files.exists(fmods.resolve(n))) { dupJar = n; dup = n + " (same file name)"; }
            if (v.multi() && (dupJar == null || st.containsKey(MOVED + dupJar)))   // a newer download of a jar prep moved earlier replaces it
                out.add(new Entry(n, v, Action.MOVE, "moved to forgemods/ (" + v.why() + ")" + (dupJar == null ? "" : ", replaces forgemods/" + dupJar), dupJar));
            else if (dup != null) out.add(new Entry(n, v, Action.DUPLICATE, "the same Forge mod is in forgemods/ as " + dup + ": that one is used on the Forge side, "
                    + (v.multi() ? "this jar still loads as a Fabric mod from mods/" : "this one is ignored"), null));
            else out.add(new Entry(n, v, Action.COPY, "used on the Forge side as forgemods/" + PREFIX + n, null));
        }
        return out;
    }

    // ------------------------------------------------------------------ route (writes forgemods/ mirrors)

    static final String MOVED = "moved:";   // state key prefix: forgemods/ jars prep moved out of mods/ (multi-loader jars set to Forge)

    static Properties state(Path game) throws IOException {
        Properties st = new Properties();
        Path f = game.resolve(STATE);
        if (Files.exists(f)) try (Reader r = Files.newBufferedReader(f)) { st.load(r); }
        return st;
    }

    private static String stat(Path p) throws IOException {
        return Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis();
    }

    /** Brings forgemods/ mirrors in line with mods/. Returns the plan it applied. Never deletes a file it did not write. */
    static List<Entry> route(Path game) throws IOException {
        game = game.toAbsolutePath().normalize();
        Path mods = game.resolve("mods"), fmods = game.resolve("forgemods"), stateFile = game.resolve(STATE);
        if (!Files.isDirectory(mods)) return List.of();
        List<Entry> plan = plan(game);
        Properties st = state(game);
        boolean dirty = false;
        Set<String> wanted = new HashSet<>();
        List<String> used = new ArrayList<>();
        for (Entry e : plan) {
            Path src = mods.resolve(e.name());
            switch (e.action()) {
                case COPY -> {
                    String cn = PREFIX + e.name();
                    Path copy = fmods.resolve(cn);
                    wanted.add(cn);
                    used.add(e.name());
                    String s = stat(src);
                    if (Files.exists(copy) && s.equals(st.getProperty(cn))) break;   // the mirror may be Collide's converted copy: keep it
                    Files.createDirectories(fmods);
                    Path tmp = fmods.resolve(cn + ".tmp");
                    Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    Files.move(tmp, copy, StandardCopyOption.REPLACE_EXISTING);
                    st.setProperty(cn, s);
                    dirty = true;
                    Util.log("alloy-mods: " + e.name() + " is a Forge mod in mods/ -> forgemods/" + cn);
                }
                case MOVE -> {
                    Path dst = fmods.resolve(e.name());
                    if (e.replaces() != null && st.containsKey(MOVED + e.replaces())) {
                        Files.deleteIfExists(fmods.resolve(e.replaces()));
                        st.remove(MOVED + e.replaces());
                        dirty = true;
                        Util.log("alloy-mods: forgemods/" + e.replaces() + " removed (replaced by " + e.name() + " from mods/)");
                    }
                    if (Files.exists(dst)) { Util.log("alloy-mods: " + e.name() + " not moved: forgemods/ already has a file with that name"); break; }
                    Files.createDirectories(fmods);
                    Files.move(src, dst);
                    dropFabricFit(game, e.name());
                    st.setProperty(MOVED + e.name(), stat(dst));
                    dirty = true;
                    used.add(e.name());
                    Util.log("alloy-mods: " + e.name() + " moved from mods/ to forgemods/ (" + e.verdict().why() + ")");
                }
                case DUPLICATE -> Util.log("alloy-mods: WARNING " + e.name() + " in mods/: " + e.note());
                case NONE -> { }
            }
        }
        for (Path p : Util.jars(fmods)) {   // mirrors whose source left mods/ (removed, updated to a new name, or set to none)
            String n = p.getFileName().toString();
            if (!n.startsWith(PREFIX) || wanted.contains(n)) continue;
            Files.delete(p);
            st.remove(n);
            dirty = true;
            Util.log("alloy-mods: forgemods/" + n + " removed (its jar is no longer a Forge mod in mods/)");
        }
        for (String k : new ArrayList<>(st.stringPropertyNames())) {
            boolean keep = k.startsWith(MOVED) ? Files.exists(fmods.resolve(k.substring(MOVED.length()))) : wanted.contains(k);
            if (!keep) { st.remove(k); dirty = true; }
        }
        if (dirty) {
            Files.createDirectories(stateFile.getParent());
            try (Writer w = Files.newBufferedWriter(stateFile)) { st.store(w, "Forge mods from mods/: forgemods name = size|mtime of the mods/ jar; moved:name = jar moved from mods/"); }
        }
        if (!used.isEmpty()) Util.log("alloy-mods: " + used.size() + " Forge mod(s) from mods/ on the Forge side: " + used);
        return plan;
    }

    /**
     * A multi-loader jar that an earlier prep fitted as a Fabric mod has a fitted copy in mods/ (Fabric would still load it)
     * and an entry in Fit's state: both go when the jar moves to the Forge side.
     */
    private static void dropFabricFit(Path game, String name) {
        try {
            Path work = game.resolve(".alloy"), sf = work.resolve("fit-state.properties");
            Properties fs = new Properties();
            if (Files.exists(sf)) try (Reader r = Files.newBufferedReader(sf)) { fs.load(r); }
            String v = fs.getProperty(name);
            String out = v == null ? "" : v.split("\\|", -1).length >= 3 ? v.split("\\|", -1)[2] : "";
            if (out.isEmpty()) out = Fit.stem(name) + "-alloy.jar";
            if (Files.deleteIfExists(game.resolve("mods").resolve(out))) Util.log("alloy-mods: mods/" + out + " removed (its jar now loads on the Forge side)");
            Files.deleteIfExists(work.resolve("originals").resolve(name));
            Files.deleteIfExists(work.resolve("fit").resolve(name + ".txt"));
            if (v != null) {
                fs.remove(name);
                try (Writer w = Files.newBufferedWriter(sf)) { fs.store(w, "alloy-fit state: jar = sha1|fingerprint|output|modid|status"); }
            }
        } catch (IOException ex) { Util.log("alloy-mods: could not drop the Fabric fit of " + name + " (" + ex + ")"); }
    }

    /** prep stamp lines for the mirrored sources (empty when there are none, so older instances keep their stamp) */
    static String stampLines(Path game) {
        StringBuilder sb = new StringBuilder();
        try {
            Properties st = state(game);
            for (String k : new TreeSet<>(st.stringPropertyNames())) sb.append("R|").append(k).append('|').append(st.getProperty(k)).append('\n');
        } catch (IOException e) { /* the mirrors themselves are in the stamp too */ }
        return sb.toString();
    }

    // ------------------------------------------------------------------ CLI: --classify JAR_OR_DIR ... [--game GAMEDIR]

    static void cli(List<String> args) throws IOException {
        Map<String, String> ov = Map.of();
        List<Path> jars = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("--game")) { ov = overrides(Path.of(args.get(++i))); continue; }
            Path p = Path.of(args.get(i));
            if (Files.isDirectory(p)) jars.addAll(Util.jars(p)); else jars.add(p);
        }
        for (Path j : jars) {
            Verdict v = classify(j, ov);
            System.out.println(v.side() + "\t" + (v.multi() ? "multi" : "single") + "\t" + j.getFileName() + "\t" + v.why()
                    + "\tforge=" + v.forgeIds() + " fabric=" + v.fabricIds());
        }
    }
}
