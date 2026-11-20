package alloy.world;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * What a world holds, by namespace, and which of it a mod set cannot load. No Minecraft classes: the game uses it at
 * world open (level.dat and data/fabricRegistry.dat only), the installer's --check-world uses the full scan.
 *
 * Read from a world:
 *  - level.dat: fml.LoadingModList (Forge mods), alloy.FabricMods (Fabric mods, written by Alloy), fml.Registries
 *    (Forge's registry snapshot: every content id by registry)
 *  - data/fabricRegistry.dat (Fabric API's own id map on 1.20.1)
 *  - full scan only: player files, every data/*.dat, region and entities files: block palettes, biomes, block entities,
 *    entities, item stacks, the keys under ForgeCaps, fabric:attachments, cardinal_components, neoforge:attachments,
 *    and dimensions/&lt;namespace&gt;/ folders.
 * Grading (Mod Doctor words): BLOCKER = placed blocks, block entities, entities or a dimension of a namespace no mod
 * provides (lost on the next save); WARN = registered content, items or mod data (caps, attachments, components) of a
 * missing namespace; INFO = a mod in the saved mod list that is gone but left no content.
 */
public final class WorldCheck {
    /** namespaces that are always there (Minecraft, Forge, Fabric conventions, the loaders) */
    public static final Set<String> BUILTIN = Set.of("minecraft", "forge", "c", "fabric", "brigadier", "fml", "java",
            "fabricloader", "alloyloader", "realms", "mixinextras");
    /** NBT keys whose sub keys are owned by a mod (namespace before the ':') */
    public static final List<String> OWNED_KEYS = List.of("ForgeCaps", "fabric:attachments", "cardinal_components", "neoforge:attachments");

    public enum Sev { BLOCKER, WARN, INFO }
    public record Finding(Sev sev, String ns, String what, String fix) {}
    public record SavedMod(String id, String version, String loader) {}

    /** Everything counted for one namespace. */
    public static final class Use {
        public final Map<String, Integer> registry = new TreeMap<>();   // registry name -> entries in the saved snapshots
        public int blocks, biomes, blockEntities, entities, items, dimensions;
        public final Map<String, Integer> keys = new TreeMap<>();       // "ForgeCaps curios:inventory" -> count
        public final List<String> where = new ArrayList<>();            // a few places (chunk or player)
        void at(String w) { if (where.size() < 8 && !where.contains(w)) where.add(w); }
        public int registryTotal() { int n = 0; for (int v : registry.values()) n += v; return n; }
        public boolean placed() { return blocks + blockEntities + entities + dimensions > 0; }
        public boolean any() { return placed() || biomes + items > 0 || !keys.isEmpty() || !registry.isEmpty(); }
    }

    /** A world's inventory. */
    public static final class Inventory {
        public final Path dir;
        public final Map<String, SavedMod> savedMods = new TreeMap<>();
        public final Map<String, Use> ns = new TreeMap<>();
        public boolean hasLevelDat, hasAlloyTag;
        public String alloyFingerprint, alloyVersion, mcVersion;
        public int players, dataFiles, regionFiles, chunks, skippedChunks, unreadable;
        public final List<String> notes = new ArrayList<>();
        public Map<String, Object> levelDat;
        Inventory(Path dir) { this.dir = dir; }
        public Use use(String n) { return ns.computeIfAbsent(n, k -> new Use()); }
    }

    // ------------------------------------------------------------------ reading a world

    /** level.dat and data/fabricRegistry.dat only: fast enough for every world open. */
    public static Inventory quick(Path world) {
        Inventory inv = new Inventory(world);
        Path ld = world.resolve("level.dat");
        if (!Files.isRegularFile(ld)) return inv;
        try {
            Map<String, Object> root = Nbt.readFile(ld);
            inv.hasLevelDat = true;
            inv.levelDat = root;
            readLevel(inv, root);
        } catch (IOException | RuntimeException e) {
            inv.notes.add("level.dat could not be read: " + e);
            inv.unreadable++;
        }
        for (String n : List.of("fabricRegistry.dat", "fabricRegistry.dat.1", "fabricRegistry.dat.2")) {
            Path f = world.resolve("data").resolve(n);
            if (!Files.isRegularFile(f)) continue;
            try { readFabricRegistry(inv, Nbt.readFile(f)); } catch (IOException | RuntimeException e) { inv.notes.add(n + " could not be read: " + e); }
            break;   // the newest one is enough; .1 and .2 are Fabric's own older copies
        }
        return inv;
    }

    static void readLevel(Inventory inv, Map<String, Object> root) {
        Map<String, Object> fml = Nbt.compound(root, "fml");
        Nbt.NList lml = Nbt.list(fml, "LoadingModList");
        if (lml != null) for (Object o : lml.items) {
            Map<String, Object> m = Nbt.compound(o);
            String id = Nbt.string(m, "ModId");
            if (id != null) inv.savedMods.put(id, new SavedMod(id, Objects.toString(Nbt.string(m, "ModVersion"), "?"), "Forge"));
        }
        Map<String, Object> regs = Nbt.compound(fml, "Registries");
        if (regs != null) for (var r : regs.entrySet()) {
            Nbt.NList ids = Nbt.list(Nbt.compound(r.getValue()), "ids");
            if (ids == null) continue;
            for (Object o : ids.items) {
                String k = Nbt.string(Nbt.compound(o), "K");
                if (k != null) inv.use(Nbt.namespace(k)).registry.merge(r.getKey(), 1, Integer::sum);
            }
        }
        Map<String, Object> alloy = Nbt.compound(root, WorldMods.TAG);
        if (alloy != null) {
            inv.hasAlloyTag = true;
            inv.alloyFingerprint = Nbt.string(alloy, "Fingerprint");
            inv.alloyVersion = Nbt.string(alloy, "Version");
            inv.mcVersion = Nbt.string(alloy, "Minecraft");
            Nbt.NList fm = Nbt.list(alloy, "FabricMods");
            if (fm != null) for (Object o : fm.items) {
                Map<String, Object> m = Nbt.compound(o);
                String id = Nbt.string(m, "ModId");
                if (id != null) inv.savedMods.putIfAbsent(id, new SavedMod(id, Objects.toString(Nbt.string(m, "ModVersion"), "?"), "Fabric"));
            }
        }
        // 1.20.1 and older: dimension list in level.dat (26.x keeps it in data/minecraft/world_gen_settings.dat)
        Map<String, Object> dims = Nbt.compound(root, "Data", "WorldGenSettings", "dimensions");
        if (dims != null) for (String d : dims.keySet()) if (!"minecraft".equals(Nbt.namespace(d))) {
            Use u = inv.use(Nbt.namespace(d));
            u.dimensions++;
            u.at("dimension " + d);
        }
        // the single player's own data sits in level.dat on 1.20.1
        Map<String, Object> player = Nbt.compound(root, "Data", "Player");
        if (player != null) walk(inv, player, "", "player in level.dat");
    }

    static void readFabricRegistry(Inventory inv, Map<String, Object> root) {
        Map<String, Object> regs = Nbt.compound(root, "registries");
        if (regs == null) regs = root;
        for (var r : regs.entrySet()) {
            Map<String, Object> m = Nbt.compound(r.getValue());
            if (m == null) continue;
            for (String k : m.keySet()) inv.use(Nbt.namespace(k)).registry.merge(r.getKey() + " (Fabric)", 1, Integer::sum);
        }
    }

    /** quick() plus player files, data files and region files (regions = false skips the region and entities files). */
    public static Inventory full(Path world, boolean regions) throws IOException {
        Inventory inv = quick(world);
        List<Path> files = new ArrayList<>();
        try (var s = Files.walk(world)) { s.filter(Files::isRegularFile).forEach(files::add); }
        files.sort(Comparator.naturalOrder());
        Set<String> dims = new HashSet<>();
        for (Path f : files) {
            Path rel = world.relativize(f);
            String r = rel.toString().replace('\\', '/');
            String name = f.getFileName().toString();
            List<String> parts = new ArrayList<>();
            for (Path p : rel) parts.add(p.toString());
            // dimensions/<ns>/<path>/... (1.16+ modded dimensions, every dimension on 26.x)
            if (parts.size() > 3 && parts.get(0).equals("dimensions") && !parts.get(1).equals("minecraft") && dims.add(parts.get(1) + "/" + parts.get(2))) {
                Use u = inv.use(parts.get(1));
                u.dimensions++;
                u.at("folder dimensions/" + parts.get(1) + "/" + parts.get(2));
            }
            try {
                if (name.endsWith(".mca") && regions && !parts.contains("poi") && (parts.contains("region") || parts.contains("entities"))) {
                    inv.regionFiles++;
                    int[] skipped = {0};
                    for (Nbt.Chunk c : Nbt.readRegion(f, skipped)) {
                        inv.chunks++;
                        walk(inv, c.root(), "", "chunk " + c.x() + "," + c.z() + " in " + r.substring(0, r.lastIndexOf('/')));
                    }
                    inv.skippedChunks += skipped[0];
                } else if (name.endsWith(".dat") && (parts.contains("playerdata") || parts.contains("players"))) {
                    inv.players++;
                    walk(inv, Nbt.readFile(f), "", "player " + name.substring(0, name.length() - 4));
                } else if (name.endsWith(".dat") && parts.contains("data") && !name.startsWith("fabricRegistry")) {
                    inv.dataFiles++;
                    walk(inv, Nbt.readFile(f), "", r);
                }
            } catch (IOException | RuntimeException e) {
                inv.unreadable++;
                if (inv.notes.size() < 20) inv.notes.add(r + " could not be read: " + e.getMessage());
            }
        }
        return inv;
    }

    /** Count namespaces in one NBT tree. ctx = the key this value sits under. */
    @SuppressWarnings("unchecked")
    static void walk(Inventory inv, Object o, String ctx, String where) {
        if (o instanceof Nbt.NList l) {
            for (Object x : l.items) walk(inv, x, ctx, where);
            return;
        }
        if (!(o instanceof Map)) return;
        Map<String, Object> m = (Map<String, Object>) o;
        String id = Nbt.string(m, "id");
        if (id != null && !id.isEmpty()) {
            String n = Nbt.namespace(id);
            if (ctx.equals("block_entities") || ctx.equals("TileEntities")) { Use u = inv.use(n); u.blockEntities++; u.at(where); }
            else if (ctx.equals("Entities") || ctx.equals("Passengers")) { Use u = inv.use(n); u.entities++; u.at(where); }
            else if (m.containsKey("Count") || m.containsKey("count")) { Use u = inv.use(n); u.items++; u.at(where); }
        }
        for (var e : m.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (k.equals("block_states") && v instanceof Map) {
                Nbt.NList pal = Nbt.list(Nbt.compound(v), "palette");
                // up to 26.2 every entry is {Name, Properties}; 26.3 writes a list of plain strings when no entry has properties
                if (pal != null) for (Object p : pal.items) {
                    String name = p instanceof String s ? s : Nbt.string(Nbt.compound(p), "Name");
                    if (name != null) { Use u = inv.use(Nbt.namespace(name)); u.blocks++; u.at(where); }
                }
                continue;
            }
            if (k.equals("biomes") && v instanceof Map) {
                Nbt.NList pal = Nbt.list(Nbt.compound(v), "palette");
                if (pal != null) for (Object p : pal.items) if (p instanceof String s) inv.use(Nbt.namespace(s)).biomes++;
                continue;
            }
            if (OWNED_KEYS.contains(k) && v instanceof Map) {
                for (String sub : ((Map<String, Object>) v).keySet()) {
                    if (sub.indexOf(':') < 0) continue;
                    Use u = inv.use(Nbt.namespace(sub));
                    u.keys.merge(k + " " + sub, 1, Integer::sum);
                    u.at(where);
                }
            }
            walk(inv, v, k, where);
        }
    }

    // ------------------------------------------------------------------ a mod set

    /** Namespaces and mod ids of a game folder's mods/ and forgemods/ (nested jars too), plus BUILTIN. */
    public static final class Pack {
        public final Set<String> namespaces = new TreeSet<>(BUILTIN);
        public final Map<String, String> mods = new TreeMap<>();   // id -> "Fabric 1.2.3" / "Forge 1.2.3"
        public int fabricJars, forgeJars;
        public Path dir;
    }

    static final Pattern FMJ_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    static final Pattern FMJ_VER = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]*)\"");
    static final Pattern FMJ_PROVIDES = Pattern.compile("\"provides\"\\s*:\\s*\\[([^\\]]*)\\]");
    static final Pattern TOML_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*[\"']([^\"']+)[\"']");

    public static Pack pack(Path game) {
        Pack p = new Pack();
        p.dir = game;
        for (String d : List.of("mods", "forgemods")) {
            Path dir = game.resolve(d);
            if (!Files.isDirectory(dir)) continue;
            try (var s = Files.list(dir)) {
                for (Path j : s.filter(x -> x.toString().endsWith(".jar")).sorted().toList()) {
                    try {
                        boolean forge = jar(Files.readAllBytes(j), p, 0);
                        if (forge || d.equals("forgemods")) p.forgeJars++; else p.fabricJars++;
                    } catch (IOException | RuntimeException ignored) { }
                }
            } catch (IOException ignored) { }
        }
        return p;
    }

    /** Reads one jar into the pack; true when it is a Forge mod (mods.toml). */
    static boolean jar(byte[] data, Pack p, int depth) throws IOException {
        boolean forge = false;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                if (n.startsWith("data/") || n.startsWith("assets/")) {
                    int a = n.indexOf('/'), b = n.indexOf('/', a + 1);
                    if (b > a + 1) p.namespaces.add(n.substring(a + 1, b));
                } else if (n.equals("fabric.mod.json")) {
                    String t = new String(z.readAllBytes(), StandardCharsets.UTF_8);
                    Matcher m = FMJ_ID.matcher(t), v = FMJ_VER.matcher(t);
                    if (m.find()) {
                        String id = m.group(1);
                        p.mods.putIfAbsent(id, "Fabric " + (v.find() ? v.group(1) : "?"));
                        p.namespaces.add(id);
                    }
                    Matcher pr = FMJ_PROVIDES.matcher(t);
                    if (pr.find()) for (String s : pr.group(1).split(",")) { s = s.trim().replace("\"", ""); if (!s.isEmpty()) p.namespaces.add(s); }
                } else if (n.equals("META-INF/mods.toml") || n.equals("META-INF/neoforge.mods.toml")) {
                    forge = true;
                    Matcher m = TOML_ID.matcher(new String(z.readAllBytes(), StandardCharsets.UTF_8));
                    while (m.find()) { p.mods.putIfAbsent(m.group(1), "Forge"); p.namespaces.add(m.group(1)); }
                } else if (depth < 3 && n.endsWith(".jar") && (n.startsWith("META-INF/jars/") || n.startsWith("META-INF/jarjar/"))) {
                    jar(z.readAllBytes(), p, depth + 1);
                }
            }
        }
        return forge;
    }

    // ------------------------------------------------------------------ comparing

    /**
     * Findings for a world against the namespaces a mod set provides.
     * registryIsContent: in the game a missing namespace with registry entries is a BLOCKER (its items and blocks are
     * dropped at once); offline, without a region scan, it is the same. With a full scan only placed content blocks.
     */
    public static List<Finding> compare(Inventory inv, Set<String> present, boolean scanned) {
        List<Finding> out = new ArrayList<>();
        for (var e : inv.ns.entrySet()) {
            String n = e.getKey();
            Use u = e.getValue();
            if (present.contains(n) || !u.any()) continue;
            SavedMod sm = inv.savedMods.get(n);
            String who = sm != null ? "mod '" + n + "' " + sm.version() + " (" + sm.loader() + ")" : "namespace '" + n + "'";
            List<String> what = new ArrayList<>();
            if (u.blocks > 0) what.add(u.blocks + " block palette entr" + (u.blocks == 1 ? "y" : "ies"));
            if (u.blockEntities > 0) what.add(u.blockEntities + " block entit" + (u.blockEntities == 1 ? "y" : "ies"));
            if (u.entities > 0) what.add(u.entities + " entit" + (u.entities == 1 ? "y" : "ies"));
            if (u.dimensions > 0) what.add(u.dimensions + " dimension" + (u.dimensions == 1 ? "" : "s"));
            if (u.items > 0) what.add(u.items + " item stack" + (u.items == 1 ? "" : "s"));
            if (u.biomes > 0) what.add(u.biomes + " biome palette entr" + (u.biomes == 1 ? "y" : "ies"));
            if (!u.registry.isEmpty()) what.add(u.registryTotal() + " registered id" + (u.registryTotal() == 1 ? "" : "s") + " (" + regs(u.registry) + ")");
            if (!u.keys.isEmpty()) what.add("mod data " + String.join(", ", u.keys.keySet()));
            boolean content = scanned ? u.placed() : (u.placed() || !u.registry.isEmpty());
            Sev sev = content ? Sev.BLOCKER : Sev.WARN;
            String lose = content ? "Its blocks, items and entities are removed when the world is saved without it."
                    : !u.keys.isEmpty() && u.registry.isEmpty() && u.items == 0 ? "Its saved data on players or entities is dropped on the next save (Alloy's keeper holds player data)."
                    : "Items and data of it are dropped when the world is saved without it.";
            out.add(new Finding(sev, n, who + " is not installed. The world has " + String.join(", ", what) + ". " + lose
                    + (u.where.isEmpty() ? "" : " Seen at: " + String.join("; ", u.where) + "."),
                    sm != null ? "put " + n + " " + sm.version() + " back into " + (sm.loader().equals("Fabric") ? "mods/" : "forgemods/") + " before loading the world"
                            : "install the mod that adds '" + n + "' before loading the world"));
        }
        for (SavedMod sm : inv.savedMods.values()) {
            if (present.contains(sm.id()) || inv.ns.containsKey(sm.id()) && inv.ns.get(sm.id()).any()) continue;
            out.add(new Finding(Sev.INFO, sm.id(), "mod '" + sm.id() + "' " + sm.version() + " (" + sm.loader() + ") was in the world's mod list and is not installed now; no content of it was found",
                    "nothing to do, unless the mod keeps data under another name"));
        }
        out.sort(Comparator.comparing(Finding::sev).thenComparing(Finding::ns));
        return out;
    }

    static String regs(Map<String, Integer> r) {
        List<String> l = new ArrayList<>();
        for (var e : r.entrySet()) l.add(e.getKey().replace("minecraft:", "") + " " + e.getValue());
        return l.size() > 4 ? String.join(", ", l.subList(0, 4)) + " and " + (l.size() - 4) + " more" : String.join(", ", l);
    }

    public static boolean hasBlocker(List<Finding> f) { return f.stream().anyMatch(x -> x.sev() == Sev.BLOCKER); }

    // ------------------------------------------------------------------ report

    /** Mod Doctor style text. pack may be null (only an inventory is printed). */
    public static String format(Inventory inv, Pack pack, List<Finding> findings, boolean scanned) {
        StringBuilder sb = new StringBuilder();
        long blockers = findings.stream().filter(f -> f.sev() == Sev.BLOCKER).count(), warns = findings.stream().filter(f -> f.sev() == Sev.WARN).count();
        sb.append("Alloy World Check - ").append(inv.dir.getFileName()).append(" - ").append(inv.dir.toAbsolutePath()).append('\n');
        if (blockers > 0) {
            Finding top = findings.get(0);
            sb.append("STOP: ").append(blockers == 1 ? "1 missing mod has" : blockers + " missing mods have").append(" content in this world (first: ").append(top.ns())
                    .append("). Loading it now removes that content for good.\n");
        }
        if (!inv.hasLevelDat) sb.append("No level.dat in this folder: is it a world folder?\n");
        if (pack != null) sb.append("Mods checked against: ").append(pack.dir.toAbsolutePath()).append(" (").append(pack.fabricJars).append(" Fabric jar(s), ")
                .append(pack.forgeJars).append(" Forge jar(s))\n");
        else sb.append("No mods folder given or found (--game GAMEDIR): listing what the world holds, no verdict\n");
        sb.append("Scanned: level.dat").append(scanned ? ", " + inv.players + " player file(s), " + inv.dataFiles + " data file(s), " + inv.regionFiles
                + " region file(s) with " + inv.chunks + " chunk(s)" : " and data/fabricRegistry.dat only (--quick)");
        if (inv.skippedChunks > 0) sb.append(", ").append(inv.skippedChunks).append(" chunk(s) not readable");
        sb.append(". World mod list: ").append(inv.savedMods.size()).append(" mod(s)").append(inv.hasAlloyTag ? " (with Alloy's Fabric list)" : " (no Alloy Fabric list: last saved without Alloy or before this check existed)").append(".\n\n");
        for (Sev s : Sev.values()) for (Finding f : findings) {
            if (f.sev() != s) continue;
            sb.append('[').append(s).append("] ").append(f.ns()).append('\n');
            sb.append("    ").append(f.what()).append('\n');
            sb.append("    fix: ").append(f.fix()).append('\n');
        }
        if (findings.isEmpty() && pack != null) sb.append("No problems found: every namespace in the world is provided by an installed mod.\n");
        if (pack == null) {
            sb.append("Namespaces in the world:\n");
            for (var e : inv.ns.entrySet()) if (!BUILTIN.contains(e.getKey())) sb.append("    ").append(e.getKey()).append(": ").append(summary(e.getValue())).append('\n');
        }
        for (String n : inv.notes) sb.append("note: ").append(n).append('\n');
        sb.append('\n').append("Result: ").append(blockers).append(" blocker(s), ").append(warns).append(" warning(s). Nothing was changed or sent anywhere.\n");
        return sb.toString();
    }

    static String summary(Use u) {
        List<String> l = new ArrayList<>();
        if (u.blocks > 0) l.add("blocks " + u.blocks);
        if (u.blockEntities > 0) l.add("block entities " + u.blockEntities);
        if (u.entities > 0) l.add("entities " + u.entities);
        if (u.items > 0) l.add("items " + u.items);
        if (u.biomes > 0) l.add("biomes " + u.biomes);
        if (u.dimensions > 0) l.add("dimensions " + u.dimensions);
        if (!u.registry.isEmpty()) l.add("registered " + u.registryTotal());
        if (!u.keys.isEmpty()) l.add("data " + String.join(", ", u.keys.keySet()));
        return String.join(", ", l);
    }

    /** The game folder a world folder belongs to: saves/&lt;world&gt; -&gt; the folder above saves; a server world -&gt; its parent. */
    public static Path guessGame(Path world) {
        Path w = world.toAbsolutePath().normalize();
        Path up = w.getParent();
        if (up != null && up.getFileName() != null && up.getFileName().toString().equals("saves")) up = up.getParent();
        if (up != null && (Files.isDirectory(up.resolve("mods")) || Files.isDirectory(up.resolve("forgemods")))) return up;
        return null;
    }

    /** --check-world WORLD [--game GAMEDIR] [--quick] [--out FILE]. Exit 0 ok, 1 warnings, 2 blockers, 64 usage, 3 not a world. */
    public static int cli(String[] args) throws IOException {
        Path world = null, game = null, out = null;
        boolean quick = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--game" -> game = Path.of(args[++i]);
                case "--quick" -> quick = true;
                case "--out" -> out = Path.of(args[++i]);
                default -> {
                    if (args[i].startsWith("--") || world != null) { System.err.println("usage: --check-world WORLD [--game GAMEDIR] [--quick] [--out FILE]"); return 64; }
                    world = Path.of(args[i]);
                }
            }
        }
        if (world == null) { System.err.println("usage: --check-world WORLD [--game GAMEDIR] [--quick] [--out FILE]"); return 64; }
        if (!Files.isRegularFile(world.resolve("level.dat"))) { System.err.println("not a world folder (no level.dat): " + world); return 3; }
        if (game == null) game = guessGame(world);
        Inventory inv = quick ? quick(world) : full(world, true);
        Pack pack = game == null ? null : pack(game);
        List<Finding> f = pack == null ? List.of() : compare(inv, pack.namespaces, !quick);
        String text = format(inv, pack, f, !quick);
        System.out.print(text);
        if (out != null) Files.writeString(out, text, StandardCharsets.UTF_8);
        return hasBlocker(f) ? 2 : f.stream().anyMatch(x -> x.sev() == Sev.WARN) ? 1 : 0;
    }

    public static void main(String[] args) throws IOException { System.exit(cli(args)); }
}
