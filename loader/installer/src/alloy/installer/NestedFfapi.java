package alloy.installer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Forgified Fabric API modules nested inside Forge jars (Forge JarJar). Accessories for Forge (88 packs) carries four of
 * them, ClickSigns all of FFAPI. The pack import drops the top-level FFAPI, but Forge's JarJar would still load these on the
 * Forge side, next to the real Fabric API on the Fabric side: two sets of Fabric API mixins in the same Minecraft classes. They are found by their Maven group in META-INF/jarjar/metadata.json.
 */
public final class NestedFfapi {
    private NestedFfapi() { }

    static final String META = "META-INF/jarjar/metadata.json";
    /** left in a stripped copy: the removed module names, one per line. alloy-prep reads it, so the mod still links to the real Fabric API (fabric-aware.txt) */
    static final String REMOVED = "META-INF/alloy-ffapi-removed.txt";

    /** FFAPI's Maven group (and the renamed Sinytra one, if it ever moves) */
    static boolean ffapiGroup(String group, String artifact) {
        if (group == null) return false;
        return group.equals("dev.su5ed.sinytra.fabric-api") || group.equals("dev.su5ed.sinytra") && artifact != null && artifact.startsWith("fabric-")
                || group.startsWith("org.sinytra") && (group.contains("fabric-api") || artifact != null && artifact.startsWith("fabric-"));
    }

    /** paths (META-INF/jarjar/x.jar) of the FFAPI jars this jar nests, in metadata order; empty when none or unreadable */
    public static List<String> find(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry(META);
            if (e == null) return List.of();
            try (InputStream in = z.getInputStream(e)) {
                return find(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException ex) {
            return List.of();
        }
    }

    static List<String> find(String metadata) {
        List<String> out = new ArrayList<>();
        Object jars = Json.obj(Json.parse(metadata)).get("jars");
        if (jars == null) return out;
        for (Object o : Json.arr(jars)) {
            Map<String, Object> j = Json.obj(o);
            Map<String, Object> id = j.get("identifier") == null ? Map.of() : Json.obj(j.get("identifier"));
            if (ffapiGroup(str(id.get("group")), str(id.get("artifact"))) && j.get("path") instanceof String p) out.add(p);
        }
        return out;
    }

    private static String str(Object o) { return o instanceof String s ? s : null; }

    /** copies JAR to OUT without the nested FFAPI jars and their metadata.json entries; returns the paths removed */
    static List<String> strip(Path jar, Path out) throws IOException {
        List<String> drop = find(jar);
        if (drop.isEmpty()) return drop;
        Set<String> gone = new HashSet<>(drop);
        Files.createDirectories(out.getParent());
        Path tmp = out.resolveSibling(out.getFileName() + ".part");
        try (ZipFile z = new ZipFile(jar.toFile()); ZipOutputStream zo = new ZipOutputStream(Files.newOutputStream(tmp))) {
            for (var en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (gone.contains(e.getName()) || e.getName().equals(REMOVED)) continue;
                byte[] b;
                try (InputStream in = z.getInputStream(e)) { b = in.readAllBytes(); }
                if (e.getName().equals(META)) b = withoutEntries(new String(b, StandardCharsets.UTF_8), gone).getBytes(StandardCharsets.UTF_8);
                ZipEntry ne = new ZipEntry(e.getName());
                ne.setTime(e.getTime());
                zo.putNextEntry(ne);
                zo.write(b);
                zo.closeEntry();
            }
            zo.putNextEntry(new ZipEntry(REMOVED));
            StringBuilder sb = new StringBuilder();
            for (String d : drop) sb.append(d.substring(d.lastIndexOf('/') + 1)).append('\n');
            zo.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            zo.closeEntry();
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        return drop;
    }

    static String withoutEntries(String metadata, Set<String> gone) {
        Map<String, Object> root = Json.obj(Json.parse(metadata));
        List<Object> keep = new ArrayList<>();
        for (Object o : Json.arr(root.get("jars"))) if (!(Json.obj(o).get("path") instanceof String p && gone.contains(p))) keep.add(o);
        root.put("jars", keep);
        return Json.write(root);
    }
}
