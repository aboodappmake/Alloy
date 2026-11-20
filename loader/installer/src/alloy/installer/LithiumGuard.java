package alloy.installer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Fabric Lithium next to real Forge 47 (Minecraft 1.20.x): a few Lithium options skip Forge hooks. player_chunk_tick skips Forge's chunk watch events, skip_equipment_change_check misses
 * modded equipment storage, the two collision options do not see Forge multipart entities. alloc.blockstate clashes with
 * FerriteCore, whose Forge build asks Lithium to turn it off in mods.toml, which Fabric Lithium never reads. Forge mods can
 * ask for more options off the same way ("lithium:options" in their mods.toml); Fabric Lithium ignores those too.
 *
 * So whenever a Lithium jar is in mods/ on 1.20.x, config/lithium.properties gets those keys set to false on every launch,
 * the same way C2meGuard keeps C2ME's async chunk IO off. Other keys and comments are kept. Radium Re-Reforged (Forge) is the
 * better pick; this only makes Fabric Lithium less wrong.
 */
final class LithiumGuard {
    private LithiumGuard() { }

    static final String FILE = "config/lithium.properties";
    static final List<String> KEYS = List.of("mixin.world.player_chunk_tick", "mixin.entity.skip_equipment_change_check",
            "mixin.entity.collisions.intersection", "mixin.entity.collisions.movement");
    static final String FERRITE_KEY = "mixin.alloc.blockstate";
    /** Lithium's hopper rewrite and the inventory change tracking it relies on: Forge 47 to 49 add ChestBlockEntity.setBlockState
     *  (capability invalidation), so Lithium's @Intrinsic chest mixin cannot apply (offline check, 1.20.2 Lithium 0.12.0), and
     *  Forge reshaped HopperBlockEntity.ejectItems. 1.20.1's prefit strip list removes the whole group; 1.20.2 and 1.20.4 had
     *  no such lines, so the hopper rewrite ran without chest tracking. Turned off as options on every 1.20.x; hoppers then
     *  run Forge's code with its IItemHandler hooks. */
    static final List<String> HOPPER_KEYS = List.of("mixin.block.hopper", "mixin.util.inventory_change_listening");

    static boolean is120(String mc) { return mc != null && (mc.equals("1.20") || mc.startsWith("1.20.")); }

    /** the jars in DIR, empty when the folder is missing */
    static List<Path> jars(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.filter(p -> lower(p).endsWith(".jar")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    static String lower(Path p) { return p.getFileName().toString().toLowerCase(Locale.ROOT); }

    /** true when mods/ has a Lithium jar (Lithium ships as lithium-fabric-*.jar; Alloy's fitted copy keeps the name) */
    static boolean present(Path game) {
        return jars(game.resolve("mods")).stream().anyMatch(p -> lower(p).startsWith("lithium"));
    }

    /** the keys to force false for this game: the fixed four, the hopper group, alloc.blockstate with FerriteCore, and Forge
     *  mods' lithium:options */
    static Set<String> keys(Path game) {
        Set<String> out = new LinkedHashSet<>(KEYS);
        out.addAll(HOPPER_KEYS);
        boolean ferrite = false;
        for (String d : List.of("mods", "forgemods")) for (Path p : jars(game.resolve(d))) if (lower(p).startsWith("ferritecore")) ferrite = true;
        if (ferrite) out.add(FERRITE_KEY);
        for (Path p : jars(game.resolve("forgemods"))) {
            try (ZipFile z = new ZipFile(p.toFile())) {
                var e = z.getEntry("META-INF/mods.toml");
                if (e == null) continue;
                String toml = new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
                out.addAll(tomlOptions(toml));
            } catch (Exception ignored) { }   // a broken jar is the Mod Doctor's business
        }
        return out;
    }

    private static final Pattern INLINE = Pattern.compile("[\"']?lithium:options[\"']?\\s*=\\s*\\{([^}]*)}");
    private static final Pattern PAIR = Pattern.compile("[\"']?(mixin[\\w.]*)[\"']?\\s*=\\s*(true|false)");

    /** the Lithium options a mods.toml asks to turn off (inline table or [modproperties.x."lithium:options"] table); only false values count */
    static Set<String> tomlOptions(String toml) {
        Set<String> out = new LinkedHashSet<>();
        if (toml == null || !toml.contains("lithium:options")) return out;
        Matcher m = INLINE.matcher(toml);
        while (m.find()) addFalse(m.group(1), out);
        boolean in = false;
        for (String line : toml.split("\\R")) {
            String t = line.strip();
            if (t.startsWith("[")) { in = t.contains("lithium:options"); continue; }
            if (in) addFalse(t.replaceAll("\\s*#.*$", ""), out);
        }
        return out;
    }

    private static void addFalse(String text, Set<String> out) {
        Matcher p = PAIR.matcher(text);
        while (p.find()) if (p.group(2).equals("false")) out.add(p.group(1));
    }

    /** sets the keys when Lithium is installed on 1.20.x; returns true when the file was changed */
    static boolean apply(Path game, String mc) {
        if (!is120(mc) || !present(game)) return false;
        Set<String> keys = keys(game);
        Path f = game.resolve(FILE);
        try {
            String old = Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
            String now = fix(old, keys);
            if (now.equals(old)) return false;
            Files.createDirectories(f.getParent());
            Files.writeString(f, now, StandardCharsets.UTF_8);
            Util.log("alloy-lithium: " + FILE + ": " + String.join(", ", keys) + " set to false. These Lithium options skip Forge hooks"
                    + " (chunk watch events, multipart entities, modded equipment, Forge's hopper and chest hooks); Radium Re-Reforged (Forge) is the better pick on 1.20.1");
            return true;
        } catch (IOException e) {
            Util.log("alloy-lithium: could not write " + f + " (" + e.getMessage() + "); Fabric Lithium may skip Forge's chunk watch events");
            return false;
        }
    }

    /** the properties text with every given key set to false (a new file when text is null); other lines are kept */
    static String fix(String text, Collection<String> keys) {
        String nl = text != null && text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(text == null || text.isEmpty() ? List.of() : Arrays.asList(text.split("\\r?\\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        Set<String> done = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).strip();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue;
            int eq = t.indexOf('=');
            int colon = t.indexOf(':');
            int sep = eq < 0 ? colon : colon < 0 ? eq : Math.min(eq, colon);
            if (sep <= 0) continue;
            String k = t.substring(0, sep).strip();
            if (!keys.contains(k)) continue;
            lines.set(i, k + "=false");
            done.add(k);
        }
        boolean header = false;
        for (String k : keys) if (!done.contains(k)) {
            if (!header) {
                if (!lines.isEmpty()) lines.add("");
                lines.add("# set by Alloy: these Lithium options skip Forge hooks on Minecraft 1.20.x");
                header = true;
            }
            lines.add(k + "=false");
        }
        return String.join(nl, lines) + nl;
    }
}
