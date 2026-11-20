package alloy.report;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Package -> mod/jar ownership for both sides, built lazily (only when something needs explaining), from Fabric's
 * ModContainers and FML's ModFileInfos. Same idea as the Isolation ownership index, but it keeps names and jar files
 * and is built even when Isolation is off.
 */
public final class ModIndex {
    public static final class Ref {
        public final String side, id, name, version, jar;
        Ref(String side, String id, String name, String version, String jar) {
            this.side = side; this.id = id; this.name = name == null || name.isBlank() ? id : name; this.version = version; this.jar = jar;
        }
        /** "Biomes O Plenty (biomesoplenty 26.1.2.0.22)" */
        public String label() { return name.equals(id) ? id + " " + version : name + " (" + id + " " + version + ")"; }
    }

    private static volatile ModIndex instance;
    private static final Pattern JAR_IN_TRACE = Pattern.compile("~?\\[([^\\]:/\\\\]+\\.jar):");

    private final Map<String, Ref> pkgOwner = new HashMap<>();      // internal package (a/b/c) -> owner
    private final Map<String, Ref> byId = new LinkedHashMap<>();    // "forge:id" / "fabric:id"
    private final Map<String, Ref> byJar = new HashMap<>();

    public static ModIndex get() {
        ModIndex i = instance;
        if (i == null) synchronized (ModIndex.class) { if ((i = instance) == null) instance = i = new ModIndex(); }
        return i;
    }

    /** Throw the index away (mods can be added by locators later). */
    public static void reset() { instance = null; }

    private ModIndex() {
        try {
            for (ModContainer mc : FabricLoader.getInstance().getAllMods()) {
                String id = mc.getMetadata().getId();
                if (id.equals("java")) continue;
                ModOrigin o = mc.getOrigin();
                String jar = "";
                List<Path> roots = new ArrayList<>();
                if (o.getKind() == ModOrigin.Kind.PATH) {
                    for (Path p : o.getPaths()) { roots.add(p); if (jar.isEmpty()) jar = p.getFileName() == null ? p.toString() : p.getFileName().toString(); }
                } else if (o.getKind() == ModOrigin.Kind.NESTED) {
                    ModContainer parent = FabricLoader.getInstance().getModContainer(o.getParentModId()).orElse(null);
                    String pj = "";
                    if (parent != null && parent.getOrigin().getKind() == ModOrigin.Kind.PATH && !parent.getOrigin().getPaths().isEmpty())
                        pj = parent.getOrigin().getPaths().get(0).getFileName().toString();
                    jar = pj + "!" + o.getParentSubLocation();
                    roots.addAll(mc.getRootPaths());
                } else {
                    jar = "(" + o.getKind().name().toLowerCase(Locale.ROOT) + ")";
                    roots.addAll(mc.getRootPaths());
                }
                Ref r = new Ref("Fabric", id, mc.getMetadata().getName(), mc.getMetadata().getVersion().getFriendlyString(), jar);
                byId.put("fabric:" + id, r);
                byJar.putIfAbsent(jar, r);
                for (Path p : roots) addPackages(p, r);
            }
        } catch (Throwable t) { /* no Fabric loader (self test) */ }
        try {
            for (ModFileInfo mfi : alloy.Fml.modFiles()) {   // static on Forge 64, instance on Forge 47
                Path p = mfi.getFile().getFilePath();
                var mods = mfi.getMods();
                if (mods.isEmpty()) continue;
                var m0 = mods.get(0);
                String jar = p.getFileName() == null ? p.toString() : p.getFileName().toString();
                Ref r = new Ref("Forge", m0.getModId(), m0.getDisplayName(), String.valueOf(m0.getVersion()), jar);
                for (var m : mods) byId.put("forge:" + m.getModId(), new Ref("Forge", m.getModId(), m.getDisplayName(), String.valueOf(m.getVersion()), jar));
                byJar.putIfAbsent(jar, r);
                addPackages(p, r);
            }
        } catch (Throwable t) { /* FML not started */ }
    }

    private void addPackages(Path root, Ref r) {
        try {
            if (root.getFileSystem() == FileSystems.getDefault() && Files.isRegularFile(root)) {
                try (ZipFile z = new ZipFile(root.toFile())) {
                    for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) addClass(e.nextElement().getName(), r);
                }
            } else if (Files.isDirectory(root)) {
                try (Stream<Path> s = Files.walk(root)) {
                    s.filter(f -> f.toString().endsWith(".class")).forEach(f -> addClass(root.relativize(f).toString().replace('\\', '/'), r));
                }
            }
        } catch (IOException | RuntimeException ignored) { }
    }

    private void addClass(String entry, Ref r) {
        if (!entry.endsWith(".class") || entry.startsWith("META-INF/")) return;
        int s = entry.lastIndexOf('/');
        if (s <= 0) return;
        pkgOwner.putIfAbsent(entry.substring(0, s), r);
    }

    // ---- lookups ----

    public Ref forgeMod(String id) { return byId.get("forge:" + id); }
    public Ref fabricMod(String id) { return byId.get("fabric:" + id); }
    public Ref byJarName(String jar) { return byJar.get(jar); }
    public Collection<Ref> all() { return byId.values(); }

    /** Owner of a class (dotted or internal name), by longest package prefix (at least two segments). Null if no mod owns it. */
    public Ref ownerOf(String cls) {
        if (cls == null) return null;
        String pkg = cls.replace('.', '/');
        int s = pkg.lastIndexOf('/');
        if (s < 0) return null;
        pkg = pkg.substring(0, s);
        while (true) {
            Ref r = pkgOwner.get(pkg);
            if (r != null) return r;
            int c = pkg.lastIndexOf('/');
            if (c < 0 || pkg.indexOf('/') == c) return null;   // do not climb to a single segment like "com"
            pkg = pkg.substring(0, c);
        }
    }

    /** First stack frame (from the top) that belongs to a mod. */
    public Ref firstModFrame(Throwable t) {
        int guard = 0;
        for (Throwable c = t; c != null && guard++ < 20; c = c.getCause() == c ? null : c.getCause())
            for (StackTraceElement e : c.getStackTrace()) {
                if (isPlatform(e.getClassName())) continue;
                Ref r = ownerOf(e.getClassName());
                if (r != null) return r;
            }
        return null;
    }

    /** Owner by a "[name.jar:?]" marker in text crash-report lines (used when only crash text is available). */
    public Ref firstJarInText(String text) {
        Matcher m = JAR_IN_TRACE.matcher(text == null ? "" : text);
        while (m.find()) {
            Ref r = byJar.get(m.group(1));
            if (r != null && !r.id.equals("forge") && !r.id.equals("minecraft")) return r;
        }
        return null;
    }

    /** Who a platform package belongs to ("Minecraft", "Forge", "Fabric Loader", "Mixin", ...) or null for ordinary code. */
    public static String platformName(String cls) {
        if (cls == null) return null;
        if (cls.startsWith("net.minecraftforge.")) return "Forge";
        if (cls.startsWith("net.minecraft.") || cls.startsWith("com.mojang.")) return "Minecraft";
        if (cls.startsWith("net.fabricmc.fabric.")) return "Fabric API";
        if (cls.startsWith("net.fabricmc.")) return "Fabric Loader";
        if (cls.startsWith("org.spongepowered.") || cls.startsWith("com.llamalad7.")) return "Mixin";
        if (cls.startsWith("alloy.")) return "Alloy";
        return null;
    }

    static boolean isPlatform(String cls) {
        return platformName(cls) != null || cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun.") || cls.startsWith("cpw.");
    }
}
