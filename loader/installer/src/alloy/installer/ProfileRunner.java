package alloy.installer;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

import static alloy.installer.Util.*;

/**
 * Runs a Forge-style install profile (install_profile.json, "spec": 1) the way the Forge installer, ATLauncher and the
 * Modrinth App do: download its libraries into a maven-layout libraries dir, then run each processor for this side
 * as  java -cp JAR;CLASSPATH MAIN-CLASS ARGS  with {KEY} replaced from "data" and [coord] replaced by library paths.
 * Built-in keys: SIDE, MINECRAFT_JAR, MINECRAFT_VERSION, ROOT, INSTALLER, LIBRARY_DIR.
 * A processor whose "outputs" all exist with the listed SHA-1 is skipped (Forge installer behaviour).
 *
 * Used by Alloy's own installer (--install-official), by the launch wrapper when a launcher did not run the
 * processors (Prism), and by the offline tests (--run-processors).
 */
final class ProfileRunner {
    final Map<String, Object> profile;
    final String side;
    final Path libDir, minecraftJar, root, installer;
    Path java = ownJava();
    boolean download = true;
    boolean skipKnown;   // launch time: skip Alloy processors whose output files exist

    ProfileRunner(Map<String, Object> profile, String side, Path libDir, Path minecraftJar, Path root, Path installer) {
        this.profile = profile;
        this.side = side;
        this.libDir = libDir.toAbsolutePath().normalize();
        this.minecraftJar = minecraftJar == null ? null : minecraftJar.toAbsolutePath().normalize();
        this.root = root.toAbsolutePath().normalize();
        this.installer = installer == null ? null : installer.toAbsolutePath().normalize();
    }

    static Map<String, Object> load(Path json) throws IOException {
        return Json.obj(Json.parse(Files.readString(json)));
    }

    Path lib(String coord) { return libDir.resolve(Maven.path(coord)); }

    /** profile libraries: present with the right SHA-1, or downloaded. Returns how many were fetched. */
    int libraries() throws IOException {
        int n = 0;
        for (Object o : Json.arr(profile.get("libraries"))) {
            var l = Json.obj(o);
            var a = Json.obj(Json.obj(l.get("downloads")).get("artifact"));
            String coord = (String) l.get("name");
            Path dst = libDir.resolve((String) a.getOrDefault("path", Maven.path(coord)));
            String url = (String) a.get("url"), sha1 = (String) a.get("sha1");
            String rel = (String) a.getOrDefault("path", Maven.path(coord));
            if (sha1 == null && Pins.get(rel) != null) {   // no SHA-1 in the profile: the build-time SHA-256 pin decides
                if (Files.exists(dst) && digest(dst, "SHA-256").equals(Pins.get(rel))) continue;
                if (url == null || url.isEmpty() || !download) throw new IOException("library missing or changed: " + dst);
                String u = url.replace("%2B", "+");
                String base = u.endsWith("/" + rel) ? u.substring(0, u.length() - rel.length() - 1) : null;
                if (base == null) throw new IOException("library " + coord + ": its URL does not end in its maven path, cannot check it: " + url);
                fetchPinned(List.of(base), rel, dst, coord);
                n++;
                continue;
            }
            if (Files.exists(dst) && sha1 != null && digest(dst, "SHA-1").equals(sha1)) continue;
            if (url == null || url.isEmpty()) {
                if (Files.exists(dst) && sha1 == null) continue;   // made on this PC by an earlier step (patched jars), not downloaded
                throw new IOException("library " + coord + " has no download URL and is missing: " + dst);
            }
            if (!download) throw new IOException("library missing (downloads off): " + dst);
            fetch(List.of(url), dst, sha1, a.get("size") instanceof Long s ? s : null, coord);   // refuses when sha1 is null
            n++;
        }
        return n;
    }

    /** data value for this side with [coord] resolved and 'literal' unquoted. */
    Map<String, String> data() {
        Map<String, String> d = new LinkedHashMap<>();
        Object pd = profile.get("data");
        if (pd != null) for (var e : Json.obj(pd).entrySet()) {
            Object v = Json.obj(e.getValue()).get(side);
            if (v != null) d.put(e.getKey(), resolveValue((String) v));
        }
        d.put("SIDE", side);
        if (minecraftJar != null) d.put("MINECRAFT_JAR", minecraftJar.toString());
        d.put("MINECRAFT_VERSION", String.valueOf(profile.get("minecraft")));
        d.put("ROOT", root.toString());
        if (installer != null) d.put("INSTALLER", installer.toString());
        d.put("LIBRARY_DIR", libDir.toString());
        return d;
    }

    String resolveValue(String v) {
        if (v.startsWith("[") && v.endsWith("]")) return lib(v.substring(1, v.length() - 1)).toString();
        if (v.startsWith("'") && v.endsWith("'") && v.length() >= 2) return v.substring(1, v.length() - 1);
        if (v.startsWith("/")) throw new IllegalArgumentException("data value " + v + " points into an installer jar: not supported here");
        return v;
    }

    String arg(String a, Map<String, String> d) {
        if (a.startsWith("[") && a.endsWith("]")) return lib(a.substring(1, a.length() - 1)).toString();
        for (var e : d.entrySet()) a = a.replace("{" + e.getKey() + "}", e.getValue());
        if (a.contains("{") && a.matches(".*\\{[A-Z_]+}.*")) throw new IllegalArgumentException("unknown data key in processor argument " + a);
        return a;
    }

    /** runs the processors for this side; returns {ran, skipped}. */
    int[] processors() throws Exception {
        Map<String, String> d = data();
        int ran = 0, skipped = 0;
        for (Object o : Json.arr(profile.get("processors"))) {
            var p = Json.obj(o);
            if (p.get("sides") != null && !Json.arr(p.get("sides")).contains(side)) continue;
            Map<String, String> outs = new LinkedHashMap<>();
            if (p.get("outputs") != null)
                for (var e : Json.obj(p.get("outputs")).entrySet()) outs.put(arg(e.getKey(), d), arg((String) e.getValue(), d));
            if (!outs.isEmpty() && outputsOk(outs)) { skipped++; continue; }
            if (outs.isEmpty() && skipKnown && alloyOutputsExist(p)) { skipped++; continue; }
            Path jar = lib((String) p.get("jar"));
            String main;
            try (JarFile jf = new JarFile(jar.toFile())) { main = jf.getManifest().getMainAttributes().getValue("Main-Class"); }
            if (main == null) throw new IOException("processor jar has no Main-Class: " + jar);
            List<String> cp = new ArrayList<>(List.of(jar.toString()));
            if (p.get("classpath") != null) for (Object c : Json.arr(p.get("classpath"))) cp.add(lib((String) c).toString());
            List<String> cmd = new ArrayList<>(List.of(java.toString()));
            cmd.addAll(JVM_PROPS);
            cmd.addAll(List.of("-cp", String.join(File.pathSeparator, cp), main));
            for (Object a : Json.arr(p.get("args"))) cmd.add(arg((String) a, d));
            log("processor " + p.get("jar") + " (" + main + ")");
            int rc = run(cmd, null, root);
            if (rc != 0) throw new IOException("processor " + p.get("jar") + " failed (exit " + rc + ")");
            for (var e : outs.entrySet()) {
                Path f = Path.of(e.getKey());
                if (!Files.exists(f)) throw new IOException("processor output missing: " + f);
                if (!digest(f, "SHA-1").equalsIgnoreCase(e.getValue()))
                    throw new IOException("processor output " + f.getFileName() + " has SHA-1 " + digest(f, "SHA-1") + ", expected " + e.getValue());
            }
            ran++;
        }
        return new int[]{ran, skipped};
    }

    /**
     * Alloy's own processors list no SHA-1 outputs (PrepFml output bytes depend on the JDK's zip writer), so the
     * launch-time runner skips one when every file it writes is already there (for example hosted patched jars).
     */
    boolean alloyOutputsExist(Map<String, Object> p) {
        if (!String.valueOf(p.get("jar")).startsWith(LauncherProfile.ALLOY_GROUP + ":alloy-installer:")) return false;
        List<Object> args = Json.arr(p.get("args"));
        List<Path> outs = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String a = (String) args.get(i);
            if (a.equals("--output") && i + 1 < args.size()) outs.add(lib((String) args.get(i + 1)));
            else if (a.contains("=") && !a.startsWith("-")) outs.add(lib(a.substring(a.indexOf('=') + 1)));
        }
        return !outs.isEmpty() && outs.stream().allMatch(Files::isRegularFile);
    }

    static boolean outputsOk(Map<String, String> outs) throws IOException {
        for (var e : outs.entrySet()) {
            Path f = Path.of(e.getKey());
            if (!Files.exists(f) || !digest(f, "SHA-1").equalsIgnoreCase(e.getValue())) return false;
        }
        return true;
    }
}
