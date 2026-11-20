package alloy.installer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Install profile processors (launcher format, see docs/launchers.md). A launcher runs them as
 *   java -cp alloy-installer.jar alloy.Launch --processor TASK ARGS...
 * (the jar's Main-Class), some in a child JVM (Modrinth App, Alloy's own runner), some in their own JVM through a
 * class loader (Forge installer, ATLauncher). So this class never calls System.exit: it throws on failure.
 *
 * Every path argument is a plain maven coordinate resolved against --libraries (the launcher's {LIBRARY_DIR}),
 * not a "[coord]" argument: ATLauncher refuses "[coord]" arguments whose file does not exist yet.
 *
 *   extract --libraries DIR --archive COORD --entry PATH_IN_ZIP --output COORD
 *       copy one entry out of a zip (Forge's binary patches out of the official Forge installer jar)
 *   fml --libraries DIR IN_COORD=OUT_COORD ...
 *       tools/PrepFml on each Forge loader jar: OUT is IN with Alloy's loader hooks baked in
 */
public final class Proc {
    private Proc() { }

    public static void main(String[] a) throws Exception {
        if (a.length == 0) throw new IllegalArgumentException("processor task missing (extract | fml)");
        String task = a[0];
        Path lib = null;
        Map<String, String> o = new HashMap<>();
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < a.length; i++) {
            if (a[i].startsWith("--") && i + 1 < a.length) o.put(a[i].substring(2), a[++i]);
            else rest.add(a[i]);
        }
        if (o.get("libraries") == null) throw new IllegalArgumentException("--libraries missing");
        lib = Path.of(o.get("libraries")).toAbsolutePath().normalize();
        switch (task) {
            case "extract" -> extract(lib.resolve(Maven.path(need(o, "archive"))), need(o, "entry"), lib.resolve(Maven.path(need(o, "output"))));
            case "fml" -> {
                for (String pair : rest) {
                    int eq = pair.indexOf('=');
                    if (eq < 0) throw new IllegalArgumentException("fml wants IN_COORD=OUT_COORD, got " + pair);
                    fml(lib.resolve(Maven.path(pair.substring(0, eq))), lib.resolve(Maven.path(pair.substring(eq + 1))));
                }
            }
            default -> throw new IllegalArgumentException("unknown processor task " + task);
        }
    }

    private static String need(Map<String, String> o, String k) {
        String v = o.get(k);
        if (v == null) throw new IllegalArgumentException("--" + k + " missing");
        return v;
    }

    static void extract(Path archive, String entry, Path out) throws IOException {
        if (!Files.isRegularFile(archive)) throw new IOException("missing " + archive);
        try (ZipFile z = new ZipFile(archive.toFile())) {
            ZipEntry e = z.getEntry(entry);
            if (e == null) throw new IOException(entry + " not found in " + archive);
            Files.createDirectories(out.getParent());
            Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
            try (InputStream in = z.getInputStream(e)) { Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING); }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("alloy-processor: extracted " + entry + " -> " + out.getFileName());
    }

    /** runs tools/PrepFml (default package, so by reflection) into a scratch dir, then moves the result to out. */
    static void fml(Path in, Path out) throws Exception {
        if (!Files.isRegularFile(in)) throw new IOException("missing " + in);
        Path scratch = Files.createTempDirectory(out.getParent() == null ? null : Files.createDirectories(out.getParent()), ".alloy-fml");
        try {
            String name = in.getFileName().toString();
            try {
                Class.forName("PrepFml").getMethod("main", String[].class)
                        .invoke(null, (Object) new String[]{scratch.toString(), in.getParent().toString(), name});
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() instanceof Exception x ? x : e;
            }
            Files.move(scratch.resolve(name), out, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            try (var s = Files.walk(scratch)) { for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
        System.out.println("alloy-processor: " + in.getFileName() + " -> " + out);
    }
}
