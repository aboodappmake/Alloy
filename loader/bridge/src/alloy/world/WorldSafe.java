package alloy.world;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.nio.file.*;
import java.util.*;

/**
 * World safety in the game: the glue between Minecraft's world loading (alloy.mixin.WorldSafeMixins, WorldOpenMixin)
 * and the plain code in this package.
 *
 * At world open: read level.dat (WorldCheck.quick), back up the world when it was last saved by another Alloy build or
 * mod list (WorldBackup), and list content namespaces the loaded mods do not provide. Client: a confirm screen
 * (WorldSafeClient). Dedicated server: refuse to start unless -Dalloy.allowMissingMods=true.
 * At level.dat save: write the alloy compound (WorldMods.tag). Player data: Keeper.
 *
 * Switches: -Dalloy.backup=false (no backups), -Dalloy.backup.keep=N, -Dalloy.keeper=false, -Dalloy.allowMissingMods=true.
 * Everything here catches its own errors: world safety must never be the reason a world does not open, except the
 * deliberate server refusal.
 */
public final class WorldSafe {
    private WorldSafe() {}

    public static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("Alloy");

    /** What the open check found for one world. */
    public static final class Check {
        public Path dir;
        public WorldCheck.Inventory inv;
        public List<WorldCheck.Finding> findings = List.of();
        public String backupReason;
        public WorldBackup.Result backup;
        public boolean blocker() { return WorldCheck.hasBlocker(findings); }
        /** one line about the backup, for screens and logs */
        public String backupNote() {
            if (backup == null) return backupReason == null ? "No backup was needed (same Alloy build and mods as the last save)." : "No backup was made (switched off with -Dalloy.backup=false).";
            if (backup.made || backup.skippedSame) return "A backup was made at " + backup.zip.toAbsolutePath();
            return "WARNING: no backup was made: " + backup.warning;
        }
    }

    /** worlds whose check already ran in this session (path -> result), so the access hook does not repeat the UI's work */
    static final Map<Path, Check> DONE = new HashMap<>();
    static String build, fingerprint;
    static Set<String> known;

    // ------------------------------------------------------------------ what is loaded

    public static boolean server() {
        try { return FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER; } catch (Throwable t) { return false; }
    }

    static String alloyVersion() {
        return FabricLoader.getInstance().getModContainer("alloyloader").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    static String mcVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    /** sha1 (12 hex digits) of the bridge jar: a new Alloy build has another one */
    static synchronized String build() {
        if (build != null) return build;
        String b = alloyVersion();
        try {
            ModContainer mc = FabricLoader.getInstance().getModContainer("alloyloader").orElse(null);
            if (mc != null) for (Path p : mc.getOrigin().getPaths()) if (Files.isRegularFile(p)) b = WorldBackup.sha256(p).substring(0, 12);
        } catch (Throwable ignored) { }
        return build = b;
    }

    public static List<WorldCheck.SavedMod> fabricMods() {
        List<WorldCheck.SavedMod> out = new ArrayList<>();
        for (ModContainer mc : FabricLoader.getInstance().getAllMods()) {
            var md = mc.getMetadata();
            String id = md.getId();
            if (id.equals("java") || id.equals("minecraft") || id.equals("alloy_modmixins") || md.containsCustomValue("alloy:forge-stub")) continue;
            out.add(new WorldCheck.SavedMod(id, md.getVersion().getFriendlyString(), "Fabric"));
        }
        return out;
    }

    public static List<WorldCheck.SavedMod> forgeMods() {
        List<WorldCheck.SavedMod> out = new ArrayList<>();
        try {
            for (Object o : alloy.Fml.mods()) {
                String id = String.valueOf(o.getClass().getMethod("getModId").invoke(o));
                String v = String.valueOf(o.getClass().getMethod("getVersion").invoke(o));
                out.add(new WorldCheck.SavedMod(id, v, "Forge"));
            }
        } catch (Throwable t) {
            LOG.debug("[Alloy] world safety: Forge mod list not readable: {}", t.toString());
        }
        return out;
    }

    static synchronized String fingerprint() {
        if (fingerprint != null) return fingerprint;
        List<WorldCheck.SavedMod> all = new ArrayList<>(fabricMods());
        all.addAll(forgeMods());
        return fingerprint = WorldMods.fingerprint(build(), mcVersion(), all);
    }

    /** Namespaces the running game provides: every live registry's ids plus all mod ids of both loaders. */
    static synchronized Set<String> known() {
        if (known != null) return known;
        Set<String> s = new HashSet<>(WorldCheck.BUILTIN);
        for (WorldCheck.SavedMod m : fabricMods()) s.add(m.id());
        for (WorldCheck.SavedMod m : forgeMods()) s.add(m.id());
        try {
            for (net.minecraft.core.Registry<?> r : net.minecraft.core.registries.BuiltInRegistries.REGISTRY)
                for (Object k : r.keySet()) s.add(Nbt.namespace(String.valueOf(k)));
        } catch (Throwable t) {
            LOG.warn("[Alloy] world safety: live registries not readable ({}); only mod ids are compared", t.toString());
            return s;   // not cached: registries may be ready later
        }
        return known = s;
    }

    // ------------------------------------------------------------------ world open

    static boolean backupsOn() { return !"false".equals(System.getProperty("alloy.backup")); }

    /** The fast part: read level.dat, compare, decide about a backup. Never throws. */
    public static Check check(Path dir) {
        Check c = new Check();
        c.dir = dir;
        try {
            c.inv = WorldCheck.quick(dir);
            if (!c.inv.hasLevelDat) return c;
            Set<String> k = known();
            c.findings = WorldCheck.compare(c.inv, k, false);
            c.backupReason = WorldMods.backupReason(c.inv, fingerprint());
            // the single player's own data is in level.dat on 1.20.1: the keeper takes its unknown entries from there
            Map<String, Object> player = Nbt.compound(c.inv.levelDat, "Data", "Player");
            if (player != null) {
                int n = Keeper.remember(player, k::contains);
                if (n > 0) LOG.info("[Alloy] keeper: {} mod data entr{} of missing mods on the level.dat player kept", n, n == 1 ? "y" : "ies");
            }
        } catch (Throwable t) {
            LOG.warn("[Alloy] world safety check of {} failed (the world opens anyway): {}", dir, t.toString());
        }
        return c;
    }

    /** The slow part: the backup, when check() said one is needed. Never throws. */
    public static void backup(Check c) {
        if (c.backupReason == null || !backupsOn() || c.backup != null) return;
        try {
            WorldBackup wb = new WorldBackup(FabricLoader.getInstance().getGameDir());
            c.backup = wb.backup(c.dir, fingerprint(), c.backupReason);
            if (c.backup.made) LOG.info("[Alloy] world backup: {} ({} files, {} MB) because: {}", c.backup.zip, c.backup.files, c.backup.bytes >> 20, c.backupReason);
            else if (c.backup.skippedSame) LOG.info("[Alloy] world backup: same world and mods already backed up in {}", c.backup.zip);
            else LOG.warn("[Alloy] world backup: {}", c.backup.warning);
        } catch (Throwable t) {
            c.backup = new WorldBackup.Result();
            c.backup.warning = t.toString();
            LOG.warn("[Alloy] world backup failed (the world opens anyway): {}", t.toString());
        }
    }

    static void logFindings(Check c) {
        for (WorldCheck.Finding f : c.findings)
            if (f.sev() != WorldCheck.Sev.INFO) LOG.warn("[Alloy] world {}: [{}] {}", c.dir.getFileName(), f.sev(), f.what());
    }

    /** Called by the client screen hook before its own flow, so the access hook below knows it is handled. */
    public static synchronized void markDone(Check c) { DONE.put(c.dir.toAbsolutePath().normalize(), c); }

    /**
     * LevelStorageSource.validateAndCreateAccess HEAD (client and dedicated server). On the client the screen hook has
     * usually done the work already; otherwise (another mod opened the world) the backup is made and findings logged.
     * Dedicated server: throws to stop the start when content mods are missing and -Dalloy.allowMissingMods is not set.
     */
    public static void beforeAccess(Path dir) {
        Path key = dir.toAbsolutePath().normalize();
        if (!Files.isRegularFile(key.resolve("level.dat"))) return;
        Check c;
        synchronized (WorldSafe.class) { c = DONE.remove(key); }
        if (c != null) return;
        c = check(key);
        backup(c);
        logFindings(c);
        if (!server()) return;
        WorldMods.ServerDecision d = WorldMods.serverDecision(key.getFileName().toString(), c.findings, c.backupNote(), Boolean.getBoolean("alloy.allowMissingMods"));
        if (d.text() != null) for (String l : d.text().split("\n")) LOG.error(l);
        if (d.refuse()) throw new IllegalStateException("Alloy stopped the server start: the world has content of missing mods (see the lines above). "
                + "Start with -Dalloy.allowMissingMods=true to load it anyway.");
    }

    // ------------------------------------------------------------------ save

    /** level.dat root about to be written: add the alloy compound; put kept player data back (1.20.1 keeps the player there). */
    public static void onLevelSave(Map<String, Object> root) {
        root.put(WorldMods.TAG, WorldMods.tag(alloyVersion(), build(), mcVersion(), fingerprint(), fabricMods(), System.currentTimeMillis()));
        Map<String, Object> player = Nbt.compound(root, "Data", "Player");
        if (player != null) {
            int n = Keeper.restore(player);
            if (n > 0) LOG.info("[Alloy] keeper: {} kept mod data entr{} written back to the level.dat player", n, n == 1 ? "y" : "ies");
        }
    }

    /** A player file just read: remember entries of missing mods. */
    public static void onPlayerRead(Map<String, Object> player) {
        String id = Nbt.uuid(player.get("UUID"));
        String before = Keeper.describe(id);
        int n = Keeper.remember(player, known()::contains);
        String after = Keeper.describe(id);
        // the game reads a player file more than once per join on 26.x: log only when what is kept changes
        if (n > 0 && !after.equals(before)) LOG.info("[Alloy] keeper: {} mod data entr{} of missing mods kept for player {}: {}", n, n == 1 ? "y" : "ies", id, after);
    }

    /** A player file about to be written: true when kept entries were added. */
    public static boolean onPlayerWrite(Map<String, Object> player) {
        int n = Keeper.restore(player);
        if (n > 0) LOG.debug("[Alloy] keeper: {} kept entr{} written back for player {}", n, n == 1 ? "y" : "ies", Nbt.uuid(player.get("UUID")));
        return n > 0;
    }
}
