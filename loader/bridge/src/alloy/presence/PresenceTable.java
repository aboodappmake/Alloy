package alloy.presence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "Is mod X loaded?" across Alloy's two sides, as plain data and rules (no loader classes, so it is unit tested on its own,
 * see loader/tests/test_presence.py). {@link Presence} fills it from Fabric Loader and FML and asks it on the miss path of
 * Forge {@code ModList.isLoaded} and Fabric {@code FabricLoader.isModLoaded}.
 *
 * <p>Rules:
 * <ul>
 * <li>Data (mod loaded conditions and predicates): yes when the id, or an alias of it, is loaded on either side.</li>
 * <li>Code (boolean checks): an alias on the caller's own side is a yes. A mod on the other side is a yes only when the
 *     caller could use it: the id is on a bridged list, the target jar has no classes, or the target's classes are
 *     linkable from the caller under isolation. Otherwise no, and the answer says it "would have been yes".</li>
 * <li>Lists (getAllMods, getMods, getModFiles, ...) are never touched; this table is not a mod list.</li>
 * </ul>
 * Ids are compared as they are. "cloth-config" and "cloth_config" are both real ids, so the only normalisation is the
 * alias table.
 */
public final class PresenceTable {

    public enum Side {
        FABRIC, FORGE;

        public Side other() { return this == FABRIC ? FORGE : FABRIC; }

        public String title() { return this == FABRIC ? "Fabric" : "Forge"; }
    }

    /** One mod id on one side. {@code provided}: the id comes from another mod's "provides" list. */
    public record Entry(String id, Side side, String version, String owner, boolean provided) {
        public String describe() {
            return side.title() + " " + id + (version == null || version.isEmpty() ? "" : " " + version)
                    + (provided && owner != null && !owner.equals(id) ? " (provided by " + owner + ")" : "");
        }
    }

    /** What the runtime knows about a target mod and the caller asking for it. */
    public interface Linker {
        /** The id is bridged by Alloy for a caller on {@code asker} (recipe viewers through ViewerBridge, ...). */
        boolean bridged(Side asker, String id);

        /** The target's jar has class files (a pure data mod such as Terralith has none). */
        boolean hasClasses(Entry target);

        /** The caller may link to the target's classes (isolation allows it: both-sides mods, Fabric aware Forge mods). */
        boolean linkable(Entry target);

        /**
         * The target is the caller's own mod. A mod that asks for an alias of itself is looking for the other mod of that
         * group (Fabric Sodium's isModLoaded("embeddium") is its "Embeddium is installed, do not apply" check).
         */
        default boolean isCaller(Entry target) { return false; }
    }

    /**
     * @param yes       the answer to give
     * @param via       the entry that decided it (null when nothing is installed under the id or its aliases)
     * @param reason    short text for the log and alloy-presence.txt
     * @param wouldBeYes false answer, but the id is installed on the other side (the "would have been yes" report line)
     */
    public record Answer(boolean yes, Entry via, String reason, boolean wouldBeYes) {
        static final Answer NONE = new Answer(false, null, "not installed", false);
    }

    /** Alias groups from the research report (section 5.4 and the scan): every id of a group answers for the others. */
    public static final List<List<String>> DEFAULT_ALIASES = List.of(
            List.of("cloth-config", "cloth-config2", "cloth_config"),
            List.of("fabric-api", "fabric_api"),
            List.of("sodium", "embeddium", "rubidium"),
            List.of("iris", "oculus"),
            List.of("farmersdelight", "farmers_delight"));

    /**
     * Stack frames that make a check a data check (class name prefixes): Fabric API's resource conditions, Forge's
     * crafting conditions (forge:mod_loaded, also used by loot and biome modifiers), Lithostitched's mod_loaded worldgen
     * predicate and Moonlight's forge:mod_loaded condition for Fabric.
     */
    public static final List<String> DEFAULT_DATA_FRAMES = List.of(
            "net.fabricmc.fabric.impl.resource.conditions.",
            "net.minecraftforge.common.crafting.conditions.",
            "dev.worldgen.lithostitched.worldgen.modifier.predicate.",
            "net.mehvahdjukaar.moonlight.fabric.ResourceConditionsBridge");

    /**
     * Frames skipped when looking for the caller: Alloy itself, the presence APIs, Mixin, the JDK (streams and method
     * references sit between a mod and its check).
     */
    public static final List<String> SKIP_FRAMES = List.of(
            "alloy.presence.", "alloy.compat.viewers.", "alloy.compat120.presence.", "alloy.viewers.",
            "net.minecraftforge.fml.ModList", "net.fabricmc.loader.", "org.spongepowered.",
            "java.", "jdk.", "sun.", "com.sun.");

    /** How many frames after the caller still count for the data rule (Lithostitched: helper, then the predicate). */
    public static final int DATA_DEPTH = 4;

    private final Map<String, List<Entry>> byId = new HashMap<>();
    private final Map<String, Set<String>> alias = new HashMap<>();
    private final List<String> dataFrames = new ArrayList<>(DEFAULT_DATA_FRAMES);

    public PresenceTable() {
        for (List<String> g : DEFAULT_ALIASES) addAlias(g);
    }

    // ------------------------------------------------------------------------------------------------ building

    public void add(Entry e) {
        if (e == null || e.id() == null || e.id().isEmpty()) return;
        List<Entry> l = byId.computeIfAbsent(e.id(), k -> new ArrayList<>(1));
        for (Entry o : l) if (o.side() == e.side() && !o.provided() && e.provided()) return;   // a real mod beats a provides line
        l.removeIf(o -> o.side() == e.side() && o.provided() && !e.provided());
        for (Entry o : l) if (o.side() == e.side()) return;   // one entry per id and side (twins, duplicates)
        l.add(e);
    }

    /** Joins the given ids into one alias group (groups that share an id merge). */
    public void addAlias(Collection<String> ids) {
        Set<String> g = new LinkedHashSet<>();
        for (String id : ids) if (id != null && !id.isBlank()) g.add(id.trim());
        if (g.size() < 2) return;
        for (String id : List.copyOf(g)) {
            Set<String> old = alias.get(id);
            if (old != null) g.addAll(old);
        }
        for (String id : g) alias.put(id, g);
    }

    public void addDataFrame(String prefix) {
        if (prefix != null && !prefix.isBlank() && !dataFrames.contains(prefix.trim())) dataFrames.add(prefix.trim());
    }

    /**
     * Reads config/alloy-presence.txt lines: "alias a b c" (one more alias group), "data com.example.Condition" (a class
     * name prefix whose checks count as data). '#' starts a comment. Unknown lines are returned for the log.
     */
    public List<String> readConfig(List<String> lines) {
        List<String> bad = new ArrayList<>();
        for (String raw : lines) {
            String l = raw;
            int hash = l.indexOf('#');
            if (hash >= 0) l = l.substring(0, hash);
            l = l.trim();
            if (l.isEmpty()) continue;
            String[] p = l.split("\\s+");
            String key = p[0].toLowerCase(Locale.ROOT);
            if (key.equals("alias") && p.length >= 3) addAlias(List.of(p).subList(1, p.length));
            else if (key.equals("data") && p.length == 2) addDataFrame(p[1]);
            else bad.add(raw);
        }
        return bad;
    }

    // ------------------------------------------------------------------------------------------------ lookups

    /** The id and its aliases, the id first. */
    public Set<String> candidates(String id) {
        Set<String> out = new LinkedHashSet<>();
        out.add(id);
        Set<String> g = alias.get(id);
        if (g != null) out.addAll(g);
        return out;
    }

    public Set<String> aliasesOf(String id) {
        Set<String> c = candidates(id);
        c.remove(id);
        return c;
    }

    /** Entries for the id or an alias of it on {@code side} (null = both sides). */
    public List<Entry> present(String id, Side side) {
        List<Entry> out = new ArrayList<>();
        for (String c : candidates(id)) {
            List<Entry> l = byId.get(c);
            if (l == null) continue;
            for (Entry e : l) if (side == null || e.side() == side) out.add(e);
        }
        return out;
    }

    /**
     * Fast path for the hooks, after the asker's own loader said no: could any rule turn this into a yes? True when the id
     * or an alias is on the other side, or an alias (or a provides line) is on the asker's own side.
     */
    public boolean elsewhere(String id, Side asker) {
        for (String c : candidates(id)) {
            List<Entry> l = byId.get(c);
            if (l == null) continue;
            for (Entry e : l) if (e.side() != asker || !c.equals(id) || e.provided()) return true;
        }
        return false;
    }

    public List<String> dataFrames() { return List.copyOf(dataFrames); }

    public int size() {
        int n = 0;
        for (List<Entry> l : byId.values()) n += l.size();
        return n;
    }

    // ------------------------------------------------------------------------------------------------ rules

    /** Data rule: yes when the id or an alias is loaded on either side. */
    public Answer data(String id) {
        List<Entry> all = present(id, null);
        if (all.isEmpty()) return Answer.NONE;
        Entry e = all.get(0);
        return new Answer(true, e, "data condition, " + e.describe(), false);
    }

    /** Code rule for a caller on {@code asker} whose own loader answered no. */
    public Answer code(String id, Side asker, Linker l) {
        Entry self = null;
        for (Entry e : present(id, asker)) {
            if (l.isCaller(e)) { self = e; continue; }   // the caller's own mod is not "another mod of the group"
            return new Answer(true, e, "alias on the same side, " + e.describe(), false);
        }
        List<Entry> other = present(id, asker.other());
        if (other.isEmpty() && self != null) return new Answer(false, self, "alias of the caller's own mod, " + self.describe(), false);
        if (other.isEmpty()) return Answer.NONE;
        for (Entry e : other) if (l.bridged(asker, e.id())) return new Answer(true, e, e.describe() + ", bridged by Alloy", false);
        for (Entry e : other) if (!l.hasClasses(e)) return new Answer(true, e, e.describe() + ", no classes", false);
        for (Entry e : other) if (l.linkable(e)) return new Answer(true, e, e.describe() + ", linkable from the caller", false);
        Entry e = other.get(0);
        return new Answer(false, e, e.describe() + " has classes the caller cannot link", true);
    }

    // ------------------------------------------------------------------------------------------------ caller

    /** The caller of a presence check and whether the check is a data check. */
    public record Caller(String className, boolean data) {}

    /**
     * Finds the caller in a stack (innermost frame first, class names): the first frame not on {@link #SKIP_FRAMES}. The
     * check is data when that frame or one of the next {@link #DATA_DEPTH} frames matches a data frame prefix.
     */
    public Caller classify(List<String> frames) {
        int i = 0, n = frames.size();
        while (i < n && skipped(frames.get(i))) i++;
        if (i == n) return new Caller(null, false);
        String caller = frames.get(i);
        for (int k = i; k < n && k <= i + DATA_DEPTH; k++) if (isDataFrame(frames.get(k))) return new Caller(caller, true);
        return new Caller(caller, false);
    }

    static boolean skipped(String cls) {
        for (String p : SKIP_FRAMES) if (cls.startsWith(p)) return true;
        return false;
    }

    boolean isDataFrame(String cls) {
        for (String p : dataFrames) if (cls.startsWith(p)) return true;
        return false;
    }

    /** id -> entries, for the report (sorted by id). */
    public Map<String, List<Entry>> snapshot() {
        Map<String, List<Entry>> out = new LinkedHashMap<>();
        byId.keySet().stream().sorted().forEach(k -> out.put(k, List.copyOf(byId.get(k))));
        return out;
    }
}
