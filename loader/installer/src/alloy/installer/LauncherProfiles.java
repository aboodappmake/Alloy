package alloy.installer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

import static alloy.installer.Util.*;

/**
 * The official Minecraft Launcher's profile files. The classic launcher reads launcher_profiles.json, the Microsoft
 * Store / new Windows launcher reads launcher_profiles_microsoft_store.json. Like the Forge installer, Alloy writes its
 * profile into every one of them that exists, refuses when none exists (the launcher never ran in that folder) and
 * refuses while the launcher runs (it rewrites the file on exit and would drop the new entry).
 */
final class LauncherProfiles {
    private LauncherProfiles() { }

    static final String[] FILES = {"launcher_profiles.json", "launcher_profiles_microsoft_store.json"};

    /** replaced in tests; true when a Minecraft Launcher process runs */
    static BooleanSupplier launcherRunning = LauncherProfiles::detectLauncher;

    static final String FLATPAK_ID = "com.mojang.Minecraft";

    /**
     * The official launcher's folder on this system (Fabric's installer picks the same): Windows %APPDATA%\.minecraft,
     * macOS ~/Library/Application Support/minecraft, Linux ~/.minecraft, or, when that does not exist and the Flathub
     * launcher's does, ~/.var/app/com.mojang.Minecraft/.minecraft (Steam Deck).
     */
    static Path defaultMcDir(String os, String appdata, Path home) {
        if (os.equals("windows")) return appdata != null && !appdata.isBlank() ? Path.of(appdata, ".minecraft") : home.resolve(".minecraft");
        if (os.equals("osx")) return home.resolve("Library").resolve("Application Support").resolve("minecraft");
        Path plain = home.resolve(".minecraft"), flat = home.resolve(".var").resolve("app").resolve(FLATPAK_ID).resolve(".minecraft");
        return !Files.isDirectory(plain) && Files.isDirectory(flat) ? flat : plain;
    }

    /** the Flatpak sandbox folder (~/.var/app/com.mojang.Minecraft) when MC belongs to the Flathub launcher, else null */
    static Path flatpakApp(Path mc) {
        Path p = mc.toAbsolutePath().normalize();
        for (int i = 0; i + 2 < p.getNameCount(); i++)
            if (p.getName(i).toString().equals(".var") && p.getName(i + 1).toString().equals("app") && p.getName(i + 2).toString().equals(FLATPAK_ID))
                return p.getRoot() == null ? p.subpath(0, i + 3) : p.getRoot().resolve(p.subpath(0, i + 3));
        return null;
    }

    /**
     * The Flathub launcher only sees its own folder (--persist=.minecraft, the app folder, read-only Downloads), so a profile
     * whose game dir is elsewhere (the default ~/Alloy-client) starts without Alloy. A message when DIR (the install folder,
     * which also holds the game jar) is outside that folder, else null.
     */
    static String flatpakProblem(Path mc, Path dir) {
        Path app = flatpakApp(mc);
        if (app == null || inside(app, dir)) return null;
        return "The Minecraft folder " + mc + " belongs to the Flatpak Minecraft Launcher (Flathub " + FLATPAK_ID + "). It can only see "
                + app + ", so a game folder at " + dir.toAbsolutePath().normalize() + " would not work from it. Pick an install folder inside it, "
                + "for example " + app.resolve(".minecraft").resolve("Alloy-client") + " (or use Prism Launcher instead).";
    }

    /** the profile files present in MC, in FILES order */
    static List<Path> present(Path mc) {
        List<Path> out = new ArrayList<>();
        for (String f : FILES) if (Files.isRegularFile(mc.resolve(f))) out.add(mc.resolve(f));
        return out;
    }

    /** Stops the install (before anything is downloaded) when the profile cannot be written safely. */
    static void check(Path mc) {
        if (present(mc).isEmpty())
            throw new IllegalStateException("No Minecraft Launcher profile file in " + mc + " (launcher_profiles.json or "
                    + "launcher_profiles_microsoft_store.json). Run the Minecraft Launcher once first: open it, sign in, close it, "
                    + "then run this installer again. Or pick the Minecraft folder the launcher uses.");
        if (launcherRunning.getAsBoolean())
            throw new IllegalStateException("The Minecraft Launcher is running. Close it completely, then run this installer "
                    + "again. While it runs it can overwrite its profile file and the Alloy profile would be lost.");
    }

    /**
     * Adds or replaces the profile KEY in every present profile file (each one backed up first), then reads each file
     * back and checks the entry is there. Returns the files written.
     */
    static List<Path> write(Path mc, String key, Map<String, Object> profile) throws IOException {
        check(mc);
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());   // ROOT: an Arabic or Persian system locale gives non-ASCII digits
        List<Path> done = new ArrayList<>();
        for (Path lp : present(mc)) {
            Path bak = lp.resolveSibling(lp.getFileName() + ".alloy-backup-" + stamp);
            Files.copy(lp, bak, StandardCopyOption.REPLACE_EXISTING);
            String text = Files.readString(lp, StandardCharsets.UTF_8);
            Map<String, Object> root = text.isBlank() ? new LinkedHashMap<>(Map.of("version", 3L)) : Json.obj(Json.parse(text));
            Map<String, Object> profiles = root.get("profiles") == null ? new LinkedHashMap<>() : Json.obj(root.get("profiles"));
            profiles.put(key, profile);
            root.put("profiles", profiles);
            Files.writeString(lp, Json.write(root), StandardCharsets.UTF_8);
            verify(lp, key, profile);
            log("added profile '" + profile.get("name") + "' (" + key + ") to " + lp + " (old file: " + bak.getFileName() + ")");
            done.add(lp);
        }
        log("Open the Minecraft Launcher and pick '" + profile.get("name") + "' next to Play. "
                + "Tick Modded in Installations if you don't see it.");
        return done;
    }

    /** reads the file back: the profile must be there with our version id */
    static void verify(Path lp, String key, Map<String, Object> profile) throws IOException {
        Object got = null;
        try {
            Map<String, Object> root = Json.obj(Json.parse(Files.readString(lp, StandardCharsets.UTF_8)));
            Object ps = root.get("profiles");
            if (ps != null) got = Json.obj(ps).get(key);
        } catch (RuntimeException e) {
            throw new IOException(lp + " is not valid JSON after writing it: " + e.getMessage());
        }
        if (got == null || !Objects.equals(Json.obj(got).get("lastVersionId"), profile.get("lastVersionId")))
            throw new IOException("profile " + key + " is missing in " + lp + " after writing it (is the launcher running, "
                    + "or is the file read-only?)");
    }

    /** Minecraft Launcher processes: MinecraftLauncher.exe and the Store launcher's Minecraft.exe, minecraft-launcher, macOS Minecraft.app */
    static boolean detectLauncher() {
        try {
            return ProcessHandle.allProcesses().anyMatch(p -> isLauncher(p.info().command().orElse("")));
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean isLauncher(String command) {
        if (command == null || command.isEmpty()) return false;
        String c = command.replace('\\', '/').toLowerCase(Locale.ROOT);
        String base = c.substring(c.lastIndexOf('/') + 1);
        return base.equals("minecraftlauncher.exe") || base.equals("minecraft.exe") || base.equals("minecraft-launcher")
                || c.contains("/minecraft.app/contents/macos/");
    }
}
