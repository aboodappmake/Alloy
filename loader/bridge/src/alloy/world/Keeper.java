package alloy.world;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Keeps mod data of missing mods on players instead of letting it drop.
 *
 * Forge writes only the capabilities that are attached now (ForgeCaps), Fabric API only the attachment types that are
 * registered (fabric:attachments), Cardinal Components only the components it knows (cardinal_components). So when a
 * mod like Curios or Trinkets is missing, its slots (and the items in them) vanish on the next save. The keeper
 * remembers every entry under those keys whose namespace no loaded mod provides when a player is read, and writes it
 * back unchanged when that player is saved. Putting the mod back finds its data where it left it.
 *
 * Entries of mods that are loaded are never touched (the mod decides what it saves). Players are matched by the
 * "UUID" int array in their tag. -Dalloy.keeper=false turns it off. No Minecraft classes.
 */
public final class Keeper {
    private Keeper() {}

    public static boolean enabled = !"false".equals(System.getProperty("alloy.keeper"));
    /** player uuid -> owned key (ForgeCaps, ...) -> sub key -> kept value */
    static final Map<String, Map<String, Map<String, Object>>> KEPT = new ConcurrentHashMap<>();

    public static boolean isEmpty() { return KEPT.isEmpty(); }

    public static void clear() { KEPT.clear(); }

    /** Remember the unknown entries of one player tag (as read from disk). Returns how many entries are kept for it. */
    public static int remember(Map<String, Object> player, Predicate<String> known) {
        if (!enabled || player == null) return 0;
        String id = Nbt.uuid(player.get("UUID"));
        if (id == null) return 0;
        Map<String, Map<String, Object>> keep = new LinkedHashMap<>();
        int n = 0;
        for (String key : WorldCheck.OWNED_KEYS) {
            Map<String, Object> c = Nbt.compound(player.get(key));
            if (c == null) continue;
            for (var e : c.entrySet()) {
                if (e.getKey().indexOf(':') < 0 || known.test(Nbt.namespace(e.getKey()))) continue;
                keep.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(e.getKey(), Nbt.copy(e.getValue()));
                n++;
            }
        }
        if (n == 0) KEPT.remove(id); else KEPT.put(id, keep);
        return n;
    }

    /** Put kept entries back into a player tag about to be written, where the game left them out. Returns entries added. */
    public static int restore(Map<String, Object> player) {
        if (!enabled || player == null || KEPT.isEmpty()) return 0;
        String id = Nbt.uuid(player.get("UUID"));
        Map<String, Map<String, Object>> keep = id == null ? null : KEPT.get(id);
        if (keep == null) return 0;
        int n = 0;
        for (var k : keep.entrySet()) {
            Map<String, Object> c = Nbt.compound(player.get(k.getKey()));
            if (c == null) {
                c = new LinkedHashMap<>();
                player.put(k.getKey(), c);
            }
            for (var e : k.getValue().entrySet()) if (!c.containsKey(e.getKey())) { c.put(e.getKey(), Nbt.copy(e.getValue())); n++; }
        }
        return n;
    }

    /** "ForgeCaps curios:inventory, fabric:attachments ns:id" for one player, for the log ("" when nothing is kept). */
    public static String describe(String uuid) {
        Map<String, Map<String, Object>> m = uuid == null ? null : KEPT.get(uuid);
        if (m == null) return "";
        List<String> l = new ArrayList<>();
        m.forEach((k, v) -> v.keySet().forEach(s -> l.add(k + " " + s)));
        return String.join(", ", l);
    }
}
