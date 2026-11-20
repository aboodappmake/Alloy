package alloy.world;

import java.util.*;

/**
 * The "alloy" compound Alloy writes into level.dat, next to Forge's "fml" compound:
 * <pre>
 * alloy: { Version: "0.5.4-beta", Build: "sha1 of the bridge jar (12)", Minecraft: "1.20.1", Fingerprint: "...",
 *          Saved: long (ms), FabricMods: [ { ModId: "...", ModVersion: "..." }, ... ] }
 * </pre>
 * FabricMods uses the same keys as Forge's fml.LoadingModList, so one reader handles both. Forge does not list Fabric
 * mods (they are not FML mods), so without this a removed Fabric mod leaves no trace in the save.
 * The fingerprint covers the Alloy build and every mod id and version of both loaders: when it differs from the one
 * in level.dat, the world is backed up before it loads. No Minecraft classes.
 */
public final class WorldMods {
    private WorldMods() {}

    public static final String TAG = "alloy";

    /** sha256 over the build and the sorted "loader:id@version" lines; first 16 hex digits are enough to compare */
    public static String fingerprint(String build, String mc, Collection<WorldCheck.SavedMod> mods) {
        List<String> lines = new ArrayList<>();
        for (WorldCheck.SavedMod m : mods) lines.add(m.loader() + ":" + m.id() + "@" + m.version());
        Collections.sort(lines);
        return WorldBackup.sha256(build + "\n" + mc + "\n" + String.join("\n", lines)).substring(0, 16);
    }

    public static Map<String, Object> tag(String version, String build, String mc, String fingerprint, Collection<WorldCheck.SavedMod> fabricMods, long now) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("Version", version);
        t.put("Build", build);
        t.put("Minecraft", mc);
        t.put("Fingerprint", fingerprint);
        t.put("Saved", now);
        Nbt.NList l = new Nbt.NList(Nbt.COMPOUND);
        List<WorldCheck.SavedMod> sorted = new ArrayList<>(fabricMods);
        sorted.sort(Comparator.comparing(WorldCheck.SavedMod::id));
        for (WorldCheck.SavedMod m : sorted) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("ModId", m.id());
            e.put("ModVersion", m.version());
            l.items.add(e);
        }
        t.put("FabricMods", l);
        return t;
    }

    /** Why a world needs a backup before it loads, or null when it does not. */
    public static String backupReason(WorldCheck.Inventory inv, String fingerprint) {
        if (!inv.hasLevelDat) return null;
        if (!inv.hasAlloyTag) return "first load with Alloy world safety (no alloy tag in level.dat)";
        if (!fingerprint.equals(inv.alloyFingerprint)) return "Alloy build or mod list changed since the last save (" + inv.alloyFingerprint + " -> " + fingerprint + ")";
        return null;
    }

    /** The BLOCKER texts, for screens and logs. */
    public static List<String> missingLines(List<WorldCheck.Finding> findings) {
        List<String> out = new ArrayList<>();
        for (WorldCheck.Finding f : findings) if (f.sev() == WorldCheck.Sev.BLOCKER) out.add(f.what());
        return out;
    }

    /** What a dedicated server does with a world: refuse = stop the start; text = what to log (null when all is fine). */
    public record ServerDecision(boolean refuse, String text) {}

    /** allow = -Dalloy.allowMissingMods=true. */
    public static ServerDecision serverDecision(String world, List<WorldCheck.Finding> findings, String backupNote, boolean allow) {
        if (!WorldCheck.hasBlocker(findings)) return new ServerDecision(false, null);
        String nl = "\n";
        StringBuilder sb = new StringBuilder("ALLOY: world '" + world + "' has content from mods that are not installed.");
        for (WorldCheck.Finding f : findings) if (f.sev() == WorldCheck.Sev.BLOCKER) sb.append(nl).append("  - ").append(f.what()).append(nl).append("    fix: ").append(f.fix());
        sb.append(nl).append(backupNote);
        if (allow) {
            sb.append(nl).append("-Dalloy.allowMissingMods=true is set: loading anyway. That content is removed on the next save.");
            return new ServerDecision(false, sb.toString());
        }
        sb.append(nl).append("The server is not started, so nothing is lost. Put the mods back, or start with -Dalloy.allowMissingMods=true to load the world without them.");
        return new ServerDecision(true, sb.toString());
    }
}
