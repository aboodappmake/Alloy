package alloy.ideas;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Mod Doctor check: data pack files whose load conditions name a mod that is not installed.
 *
 * A recipe, loot table or worldgen file can carry load conditions: Forge's "conditions" (recipes) and "forge:conditions"
 * (data pack registries such as worldgen), NeoForge's "neoforge:conditions" and Fabric API's "fabric:load_conditions".
 * Each condition has a type id such as "apotheosis:module". Only the mod that registers the type can read it. When that
 * mod is missing, Forge throws "Unknown condition type": the MMC4 pack ships
 * Paxi data packs gated by apotheosis:module but not Apotheosis, and Forge 47 stops world creation, plain Forge too).
 *
 * Read offline from the mod jars' data/ folders, the global data pack folders of the loaders the pack uses (Paxi, Open
 * Loader, Global Packs, KubeJS) and the worlds' datapacks/ folders. Per archive results are cached by file stamp in
 * GAME/.alloy/datacond.cache, so a second check only reads archives that changed.
 *
 * Forge reads a top level condition list in order and stops at the first false one, so a file that first asks for
 * forge:mod_loaded of the missing mod is safe and not reported. Nested and/or/not conditions are parsed in full.
 */
public final class DataConditions {
    private DataConditions() { }

    /** Condition type namespaces that every Alloy game knows (vanilla, Forge, Fabric API, NeoForge's own key, Alloy). */
    public static final Set<String> BUILTIN = Set.of("minecraft", "forge", "fabric", "neoforge", "alloy");

    /** Data pack registry folders of Minecraft 1.20.x and Forge 47 (read by RegistryDataLoader, where an error is fatal). */
    static final Set<String> REGISTRY_DIRS = Set.of("dimension_type", "dimension", "damage_type", "chat_type", "trim_pattern", "trim_material",
            "forge/biome_modifier", "forge/structure_modifier");

    /** Files larger than this are skipped (no condition file is that big). */
    static final long MAX_JSON = 4L << 20;

    /**
     * One element of a top level condition list. {@code guard}: "all:a+b", "any:a+b" (a mod_loaded check) or "false",
     * null for an ordinary condition. {@code types}: the condition type and every nested type.
     */
    public record Item(String guard, List<String> types) {}

    /** The condition list of one file: entry path ("data/ns/..."), the JSON key and its items. */
    public record Cond(String entry, String key, List<Item> items) {}

    /** Something that holds data: a jar or zip (dir false) or a folder whose data/ subfolder is read. */
    public record Source(Path path, String label, boolean dir, boolean optional, boolean modJar) {}

    /** A condition type whose mod is missing, with every file of the source that uses it. */
    public record Hit(Source source, String type, String mod, List<String> entries, Set<String> keys, boolean registry) {}

    // ------------------------------------------------------------------------------------------------ sources

    /** The data pack folders the installed loaders read. {@code installed}: is this mod id installed (either side). */
    public static List<Source> packSources(Path game, Predicate<String> installed) {
        List<Source> out = new ArrayList<>();
        if (installed.test("paxi")) folder(out, game, "config/paxi/datapacks", false);
        if (installed.test("openloader")) {
            folder(out, game, "config/openloader/data", false);
            folder(out, game, "config/openloader/packs", false);
        }
        if (installed.test("globalpacks")) {
            folder(out, game, "global_packs/required_data", false);
            folder(out, game, "global_packs/optional_data", true);
        }
        if (installed.test("kubejs") && Files.isDirectory(game.resolve("kubejs/data")))
            out.add(new Source(game.resolve("kubejs"), "kubejs/data", true, false, false));
        Path saves = game.resolve("saves");
        if (Files.isDirectory(saves)) try (var st = Files.list(saves)) {
            for (Path w : st.sorted().toList()) if (Files.isDirectory(w)) folder(out, game, "saves/" + w.getFileName() + "/datapacks", false);
        } catch (IOException ignored) { }
        return out;
    }

    private static void folder(List<Source> out, Path game, String rel, boolean optional) {
        Path d = game.resolve(rel);
        if (!Files.isDirectory(d)) return;
        try (var st = Files.list(d)) {
            for (Path p : st.sorted().toList()) {
                String n = p.getFileName().toString();
                if (Files.isRegularFile(p) && (n.endsWith(".zip") || n.endsWith(".jar"))) out.add(new Source(p, rel + "/" + n, false, optional, false));
                else if (Files.isDirectory(p.resolve("data"))) out.add(new Source(p, rel + "/" + n, true, optional, false));
            }
        } catch (IOException ignored) { }
    }

    /** Stamps of every data pack source, for Mod Doctor's report cache key (sorted lines). */
    public static String stampKey(Path game, Predicate<String> installed) {
        StringBuilder sb = new StringBuilder();
        for (Source s : packSources(game, installed)) sb.append("D|").append(s.label()).append('|').append(stampOf(s)).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------ reading

    /** All condition lists of a jar or zip that use a type outside {@link #BUILTIN}. */
    public static List<Cond> scanZip(Path zip) throws IOException {
        List<Cond> out = new ArrayList<>();
        try (ZipFile z = new ZipFile(zip.toFile())) {
            for (var en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !n.startsWith("data/") || !n.endsWith(".json") || e.getSize() > MAX_JSON) continue;
                try (InputStream in = z.getInputStream(e)) { read(n, in.readAllBytes(), out); }
            }
        }
        return out;
    }

    /** All condition lists under ROOT/data (a folder data pack, or the kubejs folder). */
    public static List<Cond> scanDir(Path root) throws IOException {
        List<Cond> out = new ArrayList<>();
        Path data = root.resolve("data");
        if (!Files.isDirectory(data)) return out;
        try (var st = Files.walk(data)) {
            for (Path p : st.filter(f -> f.getFileName().toString().endsWith(".json") && Files.isRegularFile(f)).sorted().toList()) {
                if (Files.size(p) > MAX_JSON) continue;
                read(root.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p), out);
            }
        }
        return out;
    }

    private static final byte[] MARK = "conditions".getBytes(StandardCharsets.US_ASCII);

    /** Adds the file's condition lists to out (only lists with a non builtin type). Bad JSON is skipped: not this check's job. */
    static void read(String entry, byte[] b, List<Cond> out) {
        if (!contains(b, MARK)) return;
        Object root;
        try { root = ModMeta.MiniJson.parse(new String(b, StandardCharsets.UTF_8)); } catch (RuntimeException e) { return; }
        if (!(root instanceof Map<?, ?> m)) return;
        for (String key : List.of("forge:conditions", "conditions", "neoforge:conditions", "fabric:load_conditions")) {
            Object v = m.get(key);
            if (v == null) continue;
            String field = key.equals("fabric:load_conditions") ? "condition" : "type";
            List<Object> list = v instanceof List<?> l ? new ArrayList<>(l) : v instanceof Map ? List.of(v) : List.of();
            List<Item> items = new ArrayList<>();
            boolean foreign = false;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> c) || !(c.get(field) instanceof String t)) continue;   // vanilla loot conditions use "condition"
                List<String> types = new ArrayList<>();
                nested(c, field, types, 0);
                for (String x : types) if (!BUILTIN.contains(ns(x))) foreign = true;
                items.add(new Item(guard(t, c), types));
            }
            if (foreign) out.add(new Cond(entry, key, items));
        }
    }

    /** "all:a+b" for mod_loaded checks, "false" for forge:false, else null */
    static String guard(String type, Map<?, ?> c) {
        switch (type) {
            case "forge:mod_loaded", "neoforge:mod_loaded" -> { return c.get("modid") instanceof String id ? "all:" + id : null; }
            case "fabric:all_mods_loaded", "fabric:any_mod_loaded" -> {
                if (!(c.get("values") instanceof List<?> l)) return null;
                List<String> ids = new ArrayList<>();
                for (Object o : l) if (o instanceof String s) ids.add(s);
                return ids.isEmpty() ? null : (type.endsWith("all_mods_loaded") ? "all:" : "any:") + String.join("+", ids);
            }
            case "forge:false", "neoforge:false" -> { return "false"; }
            default -> { return null; }
        }
    }

    private static void nested(Map<?, ?> c, String field, List<String> out, int depth) {
        if (depth > 16) return;
        if (c.get(field) instanceof String t && !out.contains(t)) out.add(t);
        for (String k : List.of("values", "value", "conditions")) {
            Object v = c.get(k);
            if (v instanceof Map<?, ?> m) nested(m, field, out, depth + 1);
            else if (v instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) nested(m, field, out, depth + 1);
        }
    }

    static String ns(String type) {
        int i = type.indexOf(':');
        return i < 0 ? "minecraft" : type.substring(0, i);
    }

    private static boolean contains(byte[] b, byte[] m) {
        outer:
        for (int i = 0, n = b.length - m.length; i <= n; i++) {
            for (int k = 0; k < m.length; k++) if (b[i + k] != m[k]) continue outer;
            return true;
        }
        return false;
    }

    /** true for data/NS/worldgen/..., data/NS/dimension/... and the other data pack registries */
    static boolean registryEntry(String entry) {
        String[] p = entry.split("/", 3);
        if (p.length < 3 || !p[0].equals("data")) return false;
        String rest = p[2];
        if (rest.startsWith("worldgen/")) return true;
        for (String d : REGISTRY_DIRS) if (rest.startsWith(d + "/")) return true;
        return false;
    }

    // ------------------------------------------------------------------------------------------------ checking

    /**
     * The missing condition types of one source. {@code modLoaded}: what a mod_loaded check answers (Alloy's data rule:
     * the id or an alias on either side). {@code owner}: the installed mod that owns a namespace, or null.
     */
    public static List<Hit> missing(Source s, List<Cond> conds, Predicate<String> modLoaded, java.util.function.Function<String, String> owner) {
        Map<String, Hit> byType = new LinkedHashMap<>();
        for (Cond c : conds) {
            String hit = firstMissing(c, modLoaded, owner);
            if (hit == null) continue;
            Hit h = byType.computeIfAbsent(hit, t -> new Hit(s, t, ns(t), new ArrayList<>(), new TreeSet<>(), false));
            if (!h.entries().contains(c.entry())) h.entries().add(c.entry());
            h.keys().add(c.key());
            if (c.key().equals("forge:conditions") && registryEntry(c.entry()) && !h.registry())
                byType.put(hit, h = new Hit(s, h.type(), h.mod(), h.entries(), h.keys(), true));
        }
        for (Hit h : byType.values()) Collections.sort(h.entries());   // the named file does not depend on the archive's entry order
        return new ArrayList<>(byType.values());
    }

    /** the first condition type the game would fail on, or null (a false guard before it makes the rest unreachable) */
    static String firstMissing(Cond c, Predicate<String> modLoaded, java.util.function.Function<String, String> owner) {
        for (Item it : c.items()) {
            if (it.guard() != null && !guardHolds(it.guard(), modLoaded)) return null;
            for (String t : it.types()) {
                String n = ns(t);
                if (!BUILTIN.contains(n) && owner.apply(n) == null) return t;
            }
        }
        return null;
    }

    static boolean guardHolds(String g, Predicate<String> modLoaded) {
        if (g.equals("false")) return false;
        boolean all = g.startsWith("all:");
        for (String id : g.substring(4).split("\\+")) {
            boolean y = modLoaded.test(id);
            if (all && !y) return false;
            if (!all && y) return true;
        }
        return all;
    }

    /**
     * The installed mod that owns namespace ns: the id itself or an alias (Alloy's presence table, data rule), the id with
     * '-' and '_' swapped, or a mod whose id starts with "ns_" or "ns-" (Porting Lib's porting_lib_* modules register
     * porting_lib: conditions). null when none.
     */
    public static String owner(String ns, alloy.presence.PresenceTable table, Collection<String> ids) {
        var a = table.data(ns);
        if (a.yes()) return a.via().id();
        for (String v : List.of(ns.replace('-', '_'), ns.replace('_', '-')))
            if (!v.equals(ns) && table.data(v).yes()) return v;
        for (String id : ids) if (id.startsWith(ns + "_") || id.startsWith(ns + "-")) return id;
        return null;
    }

    // ------------------------------------------------------------------------------------------------ cache

    static String stampOf(Source s) {
        try {
            if (!s.dir()) return alloy.installer.StatCache.stamp(s.path());
            // a folder: its JSON files' names and stamps, hashed
            StringBuilder sb = new StringBuilder();
            Path data = s.path().resolve("data");
            if (Files.isDirectory(data)) try (var st = Files.walk(data)) {
                for (Path p : st.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList())
                    sb.append(data.relativize(p)).append('|').append(alloy.installer.StatCache.stamp(p)).append('\n');
            }
            return "dir:" + alloy.installer.StatCache.sha1Text(sb.toString());
        } catch (Exception e) {
            return "?" + System.nanoTime();
        }
    }

    static final String HEAD = "alloy datacond cache 1";

    /** Per source condition lists, remembered by file stamp (GAME/.alloy/datacond.cache). */
    public static final class Cache {
        final Path file;
        final Map<String, String> stamps = new HashMap<>();
        final Map<String, List<Cond>> conds = new HashMap<>();
        final Set<String> used = new HashSet<>();
        boolean dirty;
        public int hits, reads;

        /** file null: no cache (every source is read) */
        public Cache(Path file) {
            this.file = file;
            if (file == null || !Files.isRegularFile(file)) return;
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (lines.isEmpty() || !lines.get(0).equals(HEAD)) return;
                String cur = null;
                for (String l : lines.subList(1, lines.size())) {
                    String[] c = l.split("\t", -1);
                    if (c[0].equals("F") && c.length == 3) { cur = c[1]; stamps.put(cur, c[2]); conds.put(cur, new ArrayList<>()); }
                    else if (c[0].equals("C") && c.length == 4 && cur != null) conds.get(cur).add(new Cond(c[1], c[2], decode(c[3])));
                }
            } catch (Exception e) { stamps.clear(); conds.clear(); }
        }

        /** the source's condition lists, from the cache when its stamp is unchanged */
        public List<Cond> get(Source s) throws IOException {
            String key = s.path().toAbsolutePath().normalize().toString();
            String st = stampOf(s);
            synchronized (this) {
                used.add(key);
                if (st.equals(stamps.get(key))) { hits++; return conds.get(key); }
                reads++;
            }
            List<Cond> l = s.dir() ? scanDir(s.path()) : scanZip(s.path());
            if (!st.startsWith("?") && st.equals(stampOf(s))) synchronized (this) { stamps.put(key, st); conds.put(key, l); dirty = true; }   // not while it is being written
            return l;
        }

        /** writes the entries used in this check (when something changed) */
        public synchronized void save() {
            if (file == null) return;
            if (!dirty && used.containsAll(stamps.keySet())) return;
            try {
                if (!Files.isDirectory(file.getParent())) return;
                StringBuilder sb = new StringBuilder(HEAD).append('\n');
                for (String k : new TreeSet<>(used)) {
                    if (!stamps.containsKey(k)) continue;
                    sb.append("F\t").append(k).append('\t').append(stamps.get(k)).append('\n');
                    for (Cond c : conds.get(k)) sb.append("C\t").append(c.entry()).append('\t').append(c.key()).append('\t').append(encode(c.items())).append('\n');
                }
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, sb, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) { /* only a cache */ }
        }
    }

    /** items as "guard|type,type;guard|type" ("-" for no guard) */
    static String encode(List<Item> items) {
        StringJoiner j = new StringJoiner(";");
        for (Item it : items) j.add((it.guard() == null ? "-" : it.guard()) + "|" + String.join(",", it.types()));
        return j.toString();
    }

    static List<Item> decode(String s) {
        List<Item> out = new ArrayList<>();
        if (s.isEmpty()) return out;
        for (String part : s.split(";")) {
            int bar = part.indexOf('|');
            if (bar < 0) continue;
            String g = part.substring(0, bar);
            String t = part.substring(bar + 1);
            out.add(new Item(g.equals("-") ? null : g, t.isEmpty() ? List.of() : List.of(t.split(","))));
        }
        return out;
    }
}
