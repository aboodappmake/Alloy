package alloy.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.*;
import java.util.*;

/**
 * The client side of world safety: runs before the world list opens a world (alloy.worldsafe.WorldOpenMixin).
 *  - nothing to do (same build and mods, nothing missing): the world opens as usual, no extra screen
 *  - backup needed: "Alloy: backing up ..." while the zip is written on a background thread
 *  - content mods missing: a confirm screen naming them and the backup; Cancel goes back, nothing is touched
 *  - backup needed but it failed (disk full, ...): a short warning with Continue / Cancel
 * Confirming re-runs the same open call with a pass for this world, so vanilla's own screens follow as usual.
 */
public final class WorldSafeClient {
    private WorldSafeClient() {}

    static final Set<Path> PASS = new HashSet<>();

    /** true = Alloy took over (the caller cancels the vanilla call); proceed / cancel run later on the game thread. */
    public static boolean intercept(Path dir, Runnable proceed, Runnable cancel) {
        try {
            Path key = dir.toAbsolutePath().normalize();
            if (PASS.remove(key)) return false;
            if (!Files.isRegularFile(key.resolve("level.dat"))) return false;
            WorldSafe.Check c = WorldSafe.check(key);
            WorldSafe.logFindings(c);
            boolean needBackup = c.backupReason != null && WorldSafe.backupsOn();
            if (!needBackup && !c.blocker()) {
                WorldSafe.markDone(c);
                return false;
            }
            Minecraft mc = Minecraft.getInstance();
            Runnable go = () -> {
                PASS.add(key);
                WorldSafe.markDone(c);
                proceed.run();
            };
            Runnable after = () -> {
                if (c.blocker()) alloy.Screens.set(missingScreen(c, go, cancel));
                else if (c.backup != null && !c.backup.made && !c.backup.skippedSame) alloy.Screens.set(backupWarning(c, go, cancel));
                else go.run();
            };
            if (!needBackup) {
                after.run();
                return true;
            }
            alloy.Screens.set(WorldSafeScreens.message("Alloy: backing up the world \"" + key.getFileName() + "\" before it loads..."));
            Thread t = new Thread(() -> {
                WorldSafe.backup(c);
                mc.execute(after);
            }, "Alloy world backup");
            t.setDaemon(true);
            t.start();
            return true;
        } catch (Throwable t) {
            WorldSafe.LOG.warn("[Alloy] world safety screen failed (the world opens as usual): {}", t.toString());
            return false;
        }
    }

    static Screen missingScreen(WorldSafe.Check c, Runnable go, Runnable cancel) {
        StringBuilder sb = new StringBuilder();
        List<String> lines = WorldMods.missingLines(c.findings);
        sb.append(lines.size() == 1 ? "A mod with content in this world is not installed:" : lines.size() + " mods with content in this world are not installed:");
        int shown = 0;
        for (WorldCheck.Finding f : c.findings) {
            if (f.sev() != WorldCheck.Sev.BLOCKER) continue;
            if (shown++ == 6) { sb.append("\n... and ").append(lines.size() - 6).append(" more (see the log)"); break; }
            sb.append("\n- ").append(shortWhat(f));
        }
        sb.append("\n\nIf you load it now, their blocks, items and entities are removed when the world is saved.");
        sb.append("\n").append(c.backupNote());
        WorldSafe.LOG.warn("[Alloy] world safety screen shown for {}: {}", c.dir.getFileName(), sb.toString().replace('\n', ' '));
        return new ConfirmScreen(yes -> { if (yes) go.run(); else cancel.run(); },
                Component.literal("Alloy: mods are missing from this world"), Component.literal(sb.toString()),
                Component.literal("Load anyway"), Component.literal("Cancel"));
    }

    static String shortWhat(WorldCheck.Finding f) {
        String w = f.what();
        int i = w.indexOf(" Its blocks");
        if (i < 0) i = w.indexOf(" Items and data");
        if (i < 0) i = w.indexOf(" Its saved data");
        return i > 0 ? w.substring(0, i) : w;
    }

    static Screen backupWarning(WorldSafe.Check c, Runnable go, Runnable cancel) {
        return new ConfirmScreen(yes -> { if (yes) go.run(); else cancel.run(); },
                Component.literal("Alloy: no backup of this world"),
                Component.literal("The world was last saved with other mods or another Alloy build, and the backup did not work:\n"
                        + c.backup.warning + "\n\nYou can still load it. Make a copy of the world folder first if it matters to you."),
                Component.literal("Continue"), Component.literal("Cancel"));
    }
}
