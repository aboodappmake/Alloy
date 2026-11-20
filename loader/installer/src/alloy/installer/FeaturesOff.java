package alloy.installer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The player-facing list of Fabric mod features the fitter turned off (stripped handlers and disabled mixins), read from
 * the per-mod fit reports (.alloy/fit/NAME.txt). Written to .alloy/features-off.txt as tab-separated lines; the bridge puts
 * it into alloy-report.txt and the log, the Mod Doctor shows one line for mods with many of them.
 *
 * Not counted as a lost feature: entries an Alloy compat mixin re-implements (compat_replaces), data generation mixins
 * (they never run in a normal game), access fixes (relaxed accessors, retargeted accessors, access widener edits), and a
 * nested Fabric API copy's entry that the top-level Fabric API already lists (Fabric Loader loads only one of them).
 *
 * Only java.* is used, so tests can compile this file alone (tests/test_features_off.py).
 */
public final class FeaturesOff {
    private FeaturesOff() { }

    public static final String FILE = "features-off.txt";

    /** One feature that is off. */
    public record Item(String mod, String version, String jar, String kind, String key, String target, String area, String words) {
        String line() { return String.join("\t", mod, version, jar, kind, key, target, area, words); }
    }

    /** All items plus the entries that were left out on purpose. */
    public static final class Summary {
        public final List<Item> items = new ArrayList<>();
        public int replaced, datagen, duplicates;

        public int mods() {
            Set<String> s = new HashSet<>();
            for (Item i : items) s.add(i.mod);
            return s.size();
        }

        /** "23 small parts of 7 Fabric mods are switched off on Alloy", or "" when nothing is off. */
        public String headline() {
            if (items.isEmpty()) return "";
            int m = mods();
            return items.size() + (items.size() == 1 ? " small part" : " small parts") + " of " + m + " Fabric mod" + (m == 1 ? "" : "s") + " "
                    + (items.size() == 1 ? "is" : "are") + " switched off on Alloy";
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            sb.append("# Alloy: parts of Fabric mods that are turned off because they do not fit the changes Forge makes to Minecraft.\n");
            sb.append("# Written by the fitter from .alloy/fit/*.txt. Columns: mod, version, jar, kind, mixin, target, area, plain words.\n");
            sb.append("#meta\toff=").append(items.size()).append("\tmods=").append(mods()).append("\treplaced=").append(replaced)
                    .append("\tdatagen=").append(datagen).append("\tduplicates=").append(duplicates).append('\n');
            for (Item i : items) sb.append(i.line()).append('\n');
            return sb.toString();
        }
    }

    static final Pattern HEAD = Pattern.compile("^== (.+?) \\((\\S+) (.*)\\) -> .+ \\[(\\w+)\\]\\s*$");
    static final Pattern STRIP = Pattern.compile("^\\s+(?:injector stripped|invoker turned off): (\\S+?)#(\\S+)(?:\\s+\\((.*)\\))?\\s*$");
    static final Pattern DISABLE = Pattern.compile("^\\s+mixin disabled: (\\S+?):(\\S+)(?:\\s+\\((.*)\\))?\\s*$");
    static final Pattern PREFIT = Pattern.compile("^\\s+fixed: (\\S+?)\\.class: injector\\(s\\) \\[(.*?)\\] stripped.*$");
    static final Pattern ARROW = Pattern.compile("^([\\w$]+) -> ([\\w$]+):");

    public static Summary parse(Collection<String> reports) { return parse(reports, null); }

    /**
     * Reads the per-mod report texts (one "== jar ..." block each). compatList: the fitter's compat_replaces text; the
     * prefit Fabric API's own lists carry no reasons, so its entries that Alloy re-implements are recognised from it.
     */
    public static Summary parse(Collection<String> reports, String compatList) {
        Summary s = new Summary();
        DATAGEN.get().clear();
        COUNTED.get().clear();
        Map<String, Item> seen = new LinkedHashMap<>();   // mod|key -> item (first wins, a compat reason on any copy wins)
        Set<String> compat = new HashSet<>();
        Set<String> listed = compatKeys(compatList);
        for (String rep : reports) {
            String jar = "", mod = "", ver = "";
            for (String l : rep.split("\\R")) {
                Matcher h = HEAD.matcher(l);
                if (h.matches()) {
                    jar = h.group(1); mod = h.group(2); ver = h.group(3);
                    for (String k : listed) compat.add(mod + "|" + k);
                    continue;
                }
                if (mod.isEmpty()) continue;
                Matcher m;
                if ((m = STRIP.matcher(l)).matches()) {
                    String simple = m.group(1), handler = m.group(2), why = m.group(3) == null ? "" : m.group(3);
                    add(s, seen, compat, new Item(mod, ver, jar, "handler", simple + "#" + handler, target(simple, why), "", ""), why, simple, "");
                } else if ((m = DISABLE.matcher(l)).matches()) {
                    String cfg = m.group(1), entry = m.group(2), why = m.group(3) == null ? "" : m.group(3);
                    String simple = entry.substring(entry.lastIndexOf('.') + 1);
                    add(s, seen, compat, new Item(mod, ver, jar, "mixin", simple, target(simple, why), "", ""), why, simple, cfg + ":" + entry);
                } else if ((m = PREFIT.matcher(l)).matches()) {
                    String path = m.group(1), simple = path.substring(path.lastIndexOf('/') + 1);
                    for (String hd : m.group(2).split(",")) {
                        hd = hd.trim().replace("'", "");
                        if (hd.isEmpty()) continue;
                        add(s, seen, compat, new Item(mod, ver, jar, "handler", simple + "#" + hd, target(simple, ""), "", ""), "", simple, path);
                    }
                }
            }
        }
        // a nested Fabric API copy (inside another mod) that repeats what the top-level Fabric API lists
        Set<String> fapi = new HashSet<>();
        for (Item i : seen.values()) if (i.mod.equals("fabric-api") && !compat.contains(i.mod + "|" + i.key)) fapi.add(i.key);
        Set<String> whole = new HashSet<>();   // mod|Mixin disabled as a whole: its stripped handlers are part of that one entry
        for (Item i : seen.values()) if (i.kind.equals("mixin")) whole.add(i.mod + "|" + i.key);
        for (var e : seen.entrySet()) {
            Item i = e.getValue();
            if (compat.contains(e.getKey())) continue;
            int hash = i.key.indexOf('#');
            if (hash > 0 && (whole.contains(i.mod + "|" + i.key.substring(0, hash)) || compat.contains(i.mod + "|" + i.key.substring(0, hash)))) continue;
            if (!i.mod.equals("fabric-api") && fapi.contains(i.key) && (i.key.contains("#") || i.kind.equals("mixin"))) { s.duplicates++; continue; }
            s.items.add(i);
        }
        return s;
    }

    /**
     * compat_replaces lines -> "Mixin" / "Mixin#handler" keys (simple names). Lines after the feature-off marker are hand
     * strips, not compat, except the ones between Fit.RESTORED and Fit.END_RESTORED (same strings here, this file also runs on its own): an Alloy compat mixin brings those back.
     */
    static Set<String> compatKeys(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        boolean off = false, restored = false;
        for (String l : text.split("\\R")) {
            l = l.strip();
            if (l.equals("#alloy: feature-off")) { off = true; continue; }
            if (l.startsWith("#alloy: restored by ")) { restored = true; continue; }
            if (l.equals("#alloy: end-restored")) { restored = false; continue; }
            if (off && !restored) continue;
            if (l.isEmpty() || l.startsWith("#") || l.indexOf(':') < 0) continue;
            String e = l.substring(l.indexOf(':') + 1), h = "";
            if (e.contains("#")) { h = e.substring(e.indexOf('#')); e = e.substring(0, e.indexOf('#')); }
            out.add(e.substring(e.lastIndexOf('.') + 1) + h);
        }
        return out;
    }

    private static final ThreadLocal<Set<String>> DATAGEN = ThreadLocal.withInitial(HashSet::new), COUNTED = ThreadLocal.withInitial(HashSet::new);

    private static void add(Summary s, Map<String, Item> seen, Set<String> compat, Item raw, String why, String simple, String where) {
        String k = raw.mod + "|" + raw.key;
        if (why.startsWith("compat_replaces") || compat.contains(k)) {
            compat.add(k);
            if (COUNTED.get().add(k)) s.replaced++;
            return;
        }
        if (datagen(simple, where, raw.target)) { if (DATAGEN.get().add(k)) s.datagen++; return; }
        if (seen.containsKey(k)) return;
        String handler = raw.key.contains("#") ? raw.key.substring(raw.key.indexOf('#') + 1) : "";
        String words = handler.isEmpty() ? "all changes to " + raw.target : words(handler);
        seen.put(k, new Item(raw.mod, raw.version, raw.jar, raw.kind, raw.key, raw.target, area(raw.mod, raw.target, simple, where, words), words));
    }

    static boolean datagen(String simple, String where, String target) {
        String w = where.toLowerCase(Locale.ROOT);
        return w.contains("data-generation") || w.contains("/datagen/") || w.contains(".datagen.")
                || simple.matches(".*(TagsProvider|TagProvider|TagAppender|ModelProvider|AdvancementBuilder|DataGenerator|RecipeProvider|LootTableProvider).*")
                || target.matches(".*(TagsProvider|TagAppender|ModelProvider|DataGenerator).*");
    }

    /** "Mixin -> Target: ..." in the reason, else the mixin's own name without Mixin / Accessor / Invoker */
    static String target(String simple, String why) {
        Matcher a = ARROW.matcher(why);
        if (a.find()) return a.group(2);
        String t = simple;
        int d = t.lastIndexOf('$');
        if (d > 0 && t.substring(d + 1).endsWith("Mixin")) t = t.substring(d + 1);
        t = t.replaceAll("(Mixin|Accessor|Invoker)$", "");
        if (t.startsWith("Mixin") && t.length() > 5) t = t.substring(5);
        return t.isEmpty() ? simple : t;
    }

    private static final Set<String> VERBS = Set.of("on", "inject", "injected", "redirect", "wrap", "modify", "handle", "invoke", "hook", "mixin", "alloy", "fabric", "callback");

    /** handler name in plain words: "slib$modifyDestroySpeed" -> "destroy speed", "invokeKeyPressedEvents" -> "key pressed events" */
    static String words(String handler) {
        String h = handler;
        int p = h.indexOf('(');
        if (p > 0) h = h.substring(0, p);
        int d = h.lastIndexOf('$');
        if (d >= 0 && d < h.length() - 1) h = h.substring(d + 1);
        List<String> w = new ArrayList<>();
        for (String part : h.split("_|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) if (!part.isBlank()) w.add(part.toLowerCase(Locale.ROOT));
        while (w.size() > 1 && VERBS.contains(w.get(0))) w.remove(0);
        String out = String.join(" ", w).trim();
        return out.isEmpty() ? handler : out;
    }

    private static final Set<String> SPEED = Set.of("lithium", "c2me", "servercore", "modernfix", "ferritecore", "krypton", "noisium",
            "scalablelux", "starlight", "vmp", "ksyxis", "moreculling", "radium", "canary");
    private static final String[][] AREAS = {
            {"rendering", "Render|Model|Camera|Particle|Shader|Texture|Sprite|Sheets|SectionCompiler|ChunkBuilder|BlockMarker|Lighting|Sky|Fog|Font|Overlay|ItemColors|BlockColors|Atlas|Tesselat|Vertex|BufferBuilder|Frustum|Culling|Animation"},
            {"screens and input", "Screen|Gui|Keyboard|Mouse|Menu|Hud|Chat|Toast|Options|KeyMapping|Button|Widget|Title|Tooltip|Window|Input"},
            {"sound", "Sound|Music|Audio"},
            {"world generation", "Structure|Feature|Biome|Noise|Beardifier|ChunkGenerator|WorldGen|Template|Jigsaw|Carver|Surface|Terrain"},
            {"data loading", "Tag|Registry|Recipe|Loot|Language|Resource|Reload|Advancement|Codec|Pack|DataFix"},
            {"networking", "Packet|Connection|Network|Payload|ServerCommon|ClientCommon|PacketListener"},
            {"entities", "Entity|Player|Mob|Living|Villager|Boat|Minecart|Projectile|Animal|Monster|Goal|Spawner|Phantom|Cat"},
            {"blocks and items", "Block|Item|Bucket|Fence|Door|Hopper|Furnace|Brewing|Container|Enchant|Tool|Axe|Pickaxe|Rail|Piston"},
            {"world", "Level|Chunk|Explosion|Server|Weather|World|Dimension|Portal|GameMode"},
    };

    static String area(String mod, String target, String simple, String where, String words) {
        if (SPEED.contains(mod)) return "speed-up only";
        if (Pattern.compile("\\b(sound|sounds|music|volume)\\b").matcher(words).find()) return "sound";
        String t = target + " " + simple + " " + where;
        for (String[] a : AREAS) if (Pattern.compile(a[1]).matcher(t).find()) return a[0];
        return "other";
    }

    /** writes .alloy/features-off.txt from the per-mod reports and returns the summary */
    static Summary write(Path work, Collection<String> reports, String compatList) throws IOException {
        Summary s = parse(reports, compatList);
        Path f = work.resolve(FILE), tmp = work.resolve(FILE + ".tmp");
        Files.writeString(tmp, s.text(), StandardCharsets.UTF_8);
        Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return s;
    }

    /** reads .alloy/features-off.txt (empty summary when there is none) */
    public static Summary read(Path work) {
        Summary s = new Summary();
        try {
            Path f = work.resolve(FILE);
            if (!Files.isRegularFile(f)) return s;
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (l.startsWith("#meta")) {
                    for (String kv : l.split("\t")) {
                        int e = kv.indexOf('=');
                        if (e < 0) continue;
                        int v;
                        try { v = Integer.parseInt(kv.substring(e + 1)); } catch (NumberFormatException x) { continue; }
                        switch (kv.substring(0, e)) { case "replaced" -> s.replaced = v; case "datagen" -> s.datagen = v; case "duplicates" -> s.duplicates = v; default -> { } }
                    }
                    continue;
                }
                if (l.startsWith("#") || l.isBlank()) continue;
                String[] p = l.split("\t", -1);
                if (p.length >= 8) s.items.add(new Item(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]));
            }
        } catch (IOException e) { /* the list is optional */ }
        return s;
    }

    /** mod id -> items, in the order they were listed */
    public static Map<String, List<Item>> byMod(Summary s) {
        Map<String, List<Item>> m = new LinkedHashMap<>();
        for (Item i : s.items) m.computeIfAbsent(i.mod, k -> new ArrayList<>()).add(i);
        return m;
    }

    /** dev / test: java FeaturesOff.java [--compat compat_replaces.txt] REPORT.txt... -> prints features-off.txt to stdout */
    public static void main(String[] args) throws IOException {
        List<String> reps = new ArrayList<>();
        String compat = null;
        for (int ai = 0; ai < args.length; ai++) {
            String a = args[ai];
            if (a.equals("--compat")) { compat = Files.readString(Path.of(args[++ai])); continue; }
            Path p = Path.of(a);
            if (Files.isDirectory(p)) {
                try (var st = Files.list(p)) { for (Path x : st.sorted().toList()) if (x.toString().endsWith(".txt")) reps.add(Files.readString(x)); }
            } else {
                // a whole fit-report.txt: split it into its "== jar" blocks
                StringBuilder cur = new StringBuilder();
                for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    if (l.startsWith("== ") && cur.length() > 0) { reps.add(cur.toString()); cur.setLength(0); }
                    cur.append(l).append('\n');
                }
                if (cur.length() > 0) reps.add(cur.toString());
            }
        }
        Summary s = parse(reps, compat);
        System.out.print(s.text());
        System.out.println("#headline\t" + s.headline());
    }
}
