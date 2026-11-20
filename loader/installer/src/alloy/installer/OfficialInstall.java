package alloy.installer;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static alloy.installer.Installer.*;
import static alloy.installer.Util.*;

/**
 * --install-official MCDIR --profile-dir DIR [--game-dir DIR] [--no-profile]
 * Installs the launcher format into an official Minecraft Launcher folder, the way the Forge installer does:
 *   1. vanilla MC version (json + client jar) into MCDIR/versions/MC/ when missing (from Mojang, SHA-1 checked),
 *   2. install_profile libraries into MCDIR/libraries (Alloy's own jars are taken from DIR/maven when present),
 *   3. the client processors (Forge patches, Alloy loader jars),
 *   4. MCDIR/versions/ID/ID.json = DIR/version.json, plus a profile entry in launcher_profiles.json
 *      and launcher_profiles_microsoft_store.json, whichever exist (each backed up; see LauncherProfiles).
 * The launcher downloads the remaining libraries (they all have URLs) on the first start.
 */
final class OfficialInstall {
    private OfficialInstall() { }

    static void run(Path mc, Path dir, Path gameDir, boolean writeProfile) throws Exception {
        mc = mc.toAbsolutePath().normalize();
        var prof = ProfileRunner.load(dir.resolve("install_profile.json"));
        var ver = ProfileRunner.load(dir.resolve("version.json"));
        String id = (String) ver.get("id"), mcv = (String) prof.get("minecraft");
        if (Installer.supports(mcv)) Installer.select(mcv);
        if (!MC.equals(mcv)) throw new IllegalArgumentException("profile is for Minecraft " + mcv + ", this installer for " + MC);
        Path libDir = mc.resolve("libraries");
        if (writeProfile) LauncherProfiles.check(mc);   // before any download
        String fp = writeProfile && gameDir != null ? LauncherProfiles.flatpakProblem(mc, gameDir) : null;
        if (fp != null) throw new IllegalStateException(fp);

        // 1. vanilla
        Path vdir = mc.resolve("versions").resolve(mcv), vjar = vdir.resolve(mcv + ".jar"), vjson = vdir.resolve(mcv + ".json");
        if (!Files.isRegularFile(vjar) || !Files.isRegularFile(vjson)) {
            Installer in = new Installer("client", mc.resolve(".alloy-installer"), mc.resolve(".alloy-installer/cache"));
            in.stepMojang(false, true);
            Files.createDirectories(vdir);
            if (!Files.isRegularFile(vjson)) Files.copy(in.versionJson, vjson);
            if (!Files.isRegularFile(vjar)) Files.copy(in.clientJar, vjar);
        }

        // 2. libraries (local copies of Alloy's own artifacts first: they may not be uploaded yet)
        List<Object> all = new ArrayList<>(Json.arr(prof.get("libraries")));
        all.addAll(Json.arr(ver.get("libraries")));
        for (Object o : all) {
            String path = (String) Json.obj(Json.obj(Json.obj(o).get("downloads")).get("artifact")).get("path");
            Path src = dir.resolve("maven").resolve(path), dst = libDir.resolve(path);
            if (Files.isRegularFile(src) && !(Files.exists(dst) && digest(dst, "SHA-1").equals(digest(src, "SHA-1")))) {
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        String inst = (String) Json.obj(prof.get("alloy")).get("alloyVersion");
        ProfileRunner r = new ProfileRunner(prof, "client", libDir, vjar, mc, libDir.resolve(Maven.path(LauncherProfile.installerCoord(inst))));
        log("libraries: " + r.libraries() + " downloaded");

        // 3. processors
        int[] n = r.processors();
        log("processors: " + n[0] + " ran, " + n[1] + " up to date");

        // 4. version + profile
        Path idir = mc.resolve("versions").resolve(id);
        Files.createDirectories(idir);
        Files.copy(dir.resolve("version.json"), idir.resolve(id + ".json"), StandardCopyOption.REPLACE_EXISTING);
        if (writeProfile) {
            String now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).withNano(0).toString();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", "Alloy " + MC);
            p.put("type", "custom");
            p.put("created", now);
            p.put("lastUsed", now);
            p.put("icon", "Furnace");
            p.put("lastVersionId", id);
            if (gameDir != null) p.put("gameDir", gameDir.toAbsolutePath().normalize().toString());
            LauncherProfiles.write(mc, "alloy-" + MC, p);
        }
        log("installed " + id + " into " + mc + (writeProfile ? " (launcher profile 'Alloy " + MC + "')" : ""));
    }
}
