package alloy;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Side isolation (lazy cross-side renaming): Knot keeps defining every class, but a class owned
 * by one side (Fabric mod / Forge mod) that references a class owned by the other side gets that reference renamed to
 * a non-existent name, so the JVM throws NoClassDefFoundError lazily, only if that code path runs. Lookups by name
 * (Class.forName, ClassLoader.loadClass, ServiceLoader.load, Lookup.findClass) in mod-owned classes are rerouted to
 * the static wrappers below, which use a filtered per-side {@link SideView}.
 *
 * -Dalloy.isolation=off (default) | warn (rewrite nothing, log cross-side references to
 * alloy-isolation-report.txt) | enforce (remap + reroute).
 * Allowlist: alloy-isolation.txt in the game dir, lines "allow &lt;modid&gt; both", "allow &lt;modid&gt; lookup" (the
 * mod keeps its side and stays hidden from the other side, but its own by-name lookups see both sides; built in for
 * the script mods in {@link #LOOKUP_DEFAULT}), "api &lt;prefix&gt;", "internal &lt;prefix&gt;" and "share &lt;prefix&gt;"
 * (cross-side API, see {@link #crossApi}).
 */
public final class Isolation {
    private Isolation() {}

    public enum Side {
        /** Minecraft, launch-classpath libraries, non-mod library jars: visible to everyone (also: not indexed) */
        SHARED,
        FABRIC, FORGE,
        /** Fabric Loader + Fabric API modules */
        FABRIC_PLATFORM,
        /** net.minecraftforge.**, cpw.mods.** and the Forge jar */
        FORGE_PLATFORM,
        /** alloy.** and mods allowlisted with "both" */
        BRIDGE,
        /** internal: package with classes of different owners; resolved per class */
        SPLIT;

        boolean fabricFamily() { return this == FABRIC || this == FABRIC_PLATFORM; }
    }

    public enum Mode { OFF, WARN, ENFORCE }

    // default "enforce" since 2026-11-12: server 185/185, client 29/30 (1 = broken on real Forge), Fabric 44/44 with it;
    // -Dalloy.isolation=off|warn to relax
    public static final Mode MODE = parseMode(System.getProperty("alloy.isolation", "enforce"));
    private static final String HIDDEN = "alloy/hidden/";

    private static Mode parseMode(String s) {
        return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "enforce", "on", "true" -> Mode.ENFORCE;
            case "warn" -> Mode.WARN;
            default -> Mode.OFF;
        };
    }

    // ---------------------------------------------------------------------------------------------------- index

    private static volatile boolean ready;
    private static Map<String, Side> pkgOwner = Map.of();     // internal package name -> owner (SPLIT => see classOwner)
    private static Map<String, Side> classOwner = Map.of();   // internal class name, split packages only
    private static final Map<Path, Side> jarSide = new HashMap<>();
    /** Forge script mods whose by-name lookups see both sides: KubeJS scripts' Java.loadClass, Rhino's package lookups */
    static final Set<String> LOOKUP_DEFAULT = Set.of("kubejs", "rhino");
    private static Set<String> lookupPkgs = Set.of();         // packages of "lookup" mods: their lookups are not rerouted
    /**
     * Forge mods built to run next to Fabric API (Sinytra Connector packs): Embeddium's Fabric rendering API support (its
     * Indigo block context, FRAPI models such as Continuity and Enhanced Block Entities in Embeddium's chunk meshes, the
     * FabricBlockView interfaces on its WorldSlice) and Oculus's Indigo mixin. Their classes may link to and look up Fabric
     * API and Fabric Loader (FABRIC_PLATFORM), not other Fabric mods, and stay hidden from Fabric mods. More with
     * "aware &lt;modid&gt;" lines in alloy-isolation.txt.
     */
    static final Set<String> FABRIC_AWARE_DEFAULT = Set.of("embeddium", "oculus");
    /** Forge mods both sides may link to (Sodium / Iris stand-ins in Connector packs); more with "allow &lt;modid&gt; both" */
    static final Set<String> BOTH_DEFAULT = Set.of("embeddium", "oculus");
    private static Set<String> awarePkgs = Set.of();
    /** mod id -> side of its jar, and whether the jar has classes (alloy.presence asks; per loader, Fabric and Forge) */
    private static final Map<String, Side> fabricModSide = new ConcurrentHashMap<>(), forgeModSide = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> fabricModClasses = new ConcurrentHashMap<>(), forgeModClasses = new ConcurrentHashMap<>();
    private static final List<String> dual = new ArrayList<>();
    private static ClassLoader knot;
    private static volatile SideView fabricView, forgeView;
    private static Path reportFile;
    private static BufferedWriter report;

    private static final class Jar {
        final String id; final Side side; final List<String> classes;
        Jar(String id, Side side, List<String> classes) { this.id = id; this.side = side; this.classes = classes; }
    }

    /**
     * Builds the ownership index. Call once from Bridge.run() after the mod-file loop (so jars found by a locator are
     * in LoadingModList) and before ForgeLaunchPlugins.install(). No-op in mode off.
     */
    public static synchronized void init(Path game) {
        if (MODE == Mode.OFF || ready) return;
        long t0 = System.nanoTime();
        knot = Isolation.class.getClassLoader();
        gameRoot = norm(game);
        Set<String> allowed = readAllowlist(game);
        // Embeddium and Oculus stand in for Sodium and Iris in Connector packs: Fabric add-ons for Sodium mix into and link
        // to their classes (Chunks Fade In into Embeddium's RenderRegion in Zombie Invade: NoClassDefFoundError
        // alloy/hidden/fabric/.../GlBuffer). Both sides may link to them, as under Connector.
        allowed.addAll(BOTH_DEFAULT);
        Set<String> lookup = new HashSet<>(LOOKUP_DEFAULT);
        lookup.addAll(readLookupList(game));
        Set<String> lookupP = new HashSet<>();
        Set<String> aware = new HashSet<>(FABRIC_AWARE_DEFAULT);
        aware.addAll(readList(game, "aware"));
        Set<String> awareJars = readAwareJars(game);
        Set<String> awareP = new HashSet<>();
        List<Jar> jars = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        try {
            for (ModContainer mc : FabricLoader.getInstance().getAllMods()) {
                String id = mc.getMetadata().getId();
                Side side = fabricSide(id);
                if (side == null) continue;
                // plain libraries a Fabric mod nests (Loom writes this flag into their generated fabric.mod.json, e.g. the
                // Kotlin stdlib inside Fabric Language Kotlin) stay shared, like Forge jar-in-jar libraries in .nested:
                // a Forge mod that ships the same library must still link to it
                if (mc.getMetadata().containsCustomValue("fabric-loom:generated")) continue;
                if (allowed.contains(id)) side = Side.BRIDGE;
                List<String> cls = new ArrayList<>();
                ModOrigin o = mc.getOrigin();
                if (o.getKind() == ModOrigin.Kind.PATH) {
                    for (Path p : o.getPaths()) { cls.addAll(listClasses(p)); jarSide.put(norm(p), side); seen.add(norm(p)); }
                } else {
                    for (Path r : mc.getRootPaths()) cls.addAll(listClasses(r));
                }
                jars.add(new Jar(id, side, cls));
                fabricModSide.put(id, side);
                fabricModClasses.put(id, !cls.isEmpty());
            }
        } catch (Throwable t) {
            Bridge.LOG.warn("Alloy isolation: Fabric mod scan failed: {}", t.toString());
        }
        try {
            for (ModFileInfo mfi : Fml.modFiles()) {
                List<String> ids = mfi.getMods().stream().map(m -> m.getModId()).toList();
                if (ids.contains("minecraft")) continue;
                Side side = ids.contains("forge") ? Side.FORGE_PLATFORM : Side.FORGE;
                for (String id : ids) if (allowed.contains(id)) side = Side.BRIDGE;
                Path p = mfi.getFile().getFilePath();
                Jar jar = new Jar(ids.isEmpty() ? p.getFileName().toString() : ids.get(0), side, listClasses(p));
                jars.add(jar);
                for (String id : ids) { forgeModSide.put(id, side); forgeModClasses.put(id, !jar.classes.isEmpty()); }
                if (side == Side.FORGE && ids.stream().anyMatch(lookup::contains))
                    for (String c : jar.classes) lookupP.add(pkgOf(c));
                if (side == Side.FORGE && (ids.stream().anyMatch(aware::contains) || awareJars.contains(p.getFileName().toString())))
                    for (String c : jar.classes) awareP.add(pkgOf(c));
                if (p.getFileSystem() == FileSystems.getDefault()) { jarSide.put(norm(p), side); seen.add(norm(p)); }
            }
            Path nested = game.resolve("forgemods").resolve(".nested");
            if (Files.isDirectory(nested)) {
                try (var ds = Files.newDirectoryStream(nested, "*.jar")) {
                    for (Path p : ds) {
                        if (seen.contains(norm(p)) || !hasEntry(p, "META-INF/mods.toml")) continue;   // libraries stay shared
                        jars.add(new Jar(p.getFileName().toString(), Side.FORGE, listClasses(p)));
                        jarSide.put(norm(p), Side.FORGE);
                    }
                }
            }
        } catch (Throwable t) {
            Bridge.LOG.warn("Alloy isolation: Forge mod scan failed: {}", t.toString());
        }

        Set<String> libPkgs = launchLibraryPackages(game);
        Map<String, Side> pkgs = new HashMap<>();
        List<String> crossHits = new ArrayList<>();
        int crossCount = 0;
        for (Jar j : jars)
            for (String c : j.classes) {
                if ((j.side == Side.FABRIC || j.side == Side.FORGE) && onLaunchPath(c)) continue;   // a mod's shaded copy of a launch library (Axiom: org.slf4j) stays shared
                boolean cross = crossApi(j.side, c);
                if (cross) { crossCount++; if (crossHits.size() < 20) crossHits.add(c.replace('/', '.') + " (" + j.id + ")"); }
                if (cross || sharedApi(j.side, c) || libPkgs.contains(pkgOf(c)) || IsolationLibs.common(c)) pkgs.put(pkgOf(c), Side.SPLIT);   // class-level only: never let an API copy claim a whole package
                else pkgs.merge(pkgOf(c), j.side, Isolation::merge);
            }
        Map<String, Side> cls = new HashMap<>();
        for (Jar j : jars)
            for (String c : j.classes) {
                if (pkgs.get(pkgOf(c)) != Side.SPLIT) continue;
                Side eff = sharedApi(j.side, c) || crossApi(j.side, c) || libPkgs.contains(pkgOf(c)) || IsolationLibs.common(c) ? Side.SHARED : j.side;
                Side prev = cls.putIfAbsent(c, eff);
                if (prev == null || prev == eff) continue;
                if (prev == Side.SHARED || eff == Side.SHARED) { cls.put(c, Side.SHARED); continue; }   // shared API overlaps the platform's copy: fine
                if (!(prev.fabricFamily() && eff.fabricFamily())) {
                    dual.add(c + " (" + prev + " and " + eff + ", " + j.id + ")");
                    cls.put(c, Side.SHARED);
                }
            }
        pkgOwner = pkgs;
        classOwner = cls;
        lookupPkgs = lookupP;
        awarePkgs = awareP;
        ready = true;
        try {
            reportFile = game.resolve("alloy-isolation-report.txt");
            report = Files.newBufferedWriter(reportFile, StandardCharsets.UTF_8);
            report("# Alloy isolation report, mode=" + MODE.name().toLowerCase(java.util.Locale.ROOT)
                    + ", " + jars.size() + " jar(s) indexed, " + pkgs.size() + " package(s), "
                    + cls.size() + " class(es) in split packages");
            for (String d : dual) report("DUAL-OWNED " + d.replace('/', '.'));
            report("CROSS-API " + crossCount + " class(es) shared by prefix " + CROSS_API_PREFIXES);
            for (String h : crossHits) report("CROSS-API " + h);
        } catch (IOException e) {
            Bridge.LOG.warn("Alloy isolation: cannot write report: {}", e.toString());
        }
        warmUp();
        Bridge.LOG.info("Alloy isolation [{}]: indexed {} jar(s), {} package(s), {} split-package class(es), {} dual-owned, in {} ms",
                MODE.name().toLowerCase(java.util.Locale.ROOT), jars.size(), pkgs.size(), cls.size(), dual.size(), (System.nanoTime() - t0) / 1_000_000);
    }

    // ---- launch libraries ------------------------------------------------------------------------------------
    // A mod that shades a library which is also on the launch class path (Axiom: org.slf4j) must not claim its package:
    // the launch copy is what every class links to, and claiming it hid org.slf4j.LoggerFactory from Forge mods
    // (Fusion died with NoClassDefFoundError alloy/hidden/forge/org/slf4j/LoggerFactory). Checked once per package,
    // by asking the system class loader (the launch class path) for that class.
    private static final Map<String, Boolean> LAUNCH_PKG = new HashMap<>();

    private static final List<String> LOADER_PREFIXES = List.of("net/minecraftforge/", "cpw/mods/", "net/minecraft/", "net/fabricmc/", "com/mojang/", "alloy/");

    static boolean onLaunchPath(String internal) {
        for (String p : LOADER_PREFIXES) if (internal.startsWith(p)) return false;   // loader and game packages keep their own rules
        String pkg = pkgOf(internal);
        Boolean b = LAUNCH_PKG.get(pkg);
        if (b == null) {
            ClassLoader sys = ClassLoader.getSystemClassLoader();
            URL u = sys == knot ? null : sys.getResource(internal + ".class");
            // a mod jar in the game folder is not a launch library even when a test launcher (launch120.py) put forgemods/ on
            // the JVM class path: that made every Forge mod class SHARED there, so isolation never applied to Forge mods
            b = u != null && !inGameDir(u);
            LAUNCH_PKG.put(pkg, b);
        }
        return b;
    }

    private static Path gameRoot;

    private static boolean inGameDir(URL u) {
        if (gameRoot == null) return false;
        try {
            String s = u.toString();
            if (s.startsWith("jar:")) s = s.substring(4);
            int bang = s.indexOf("!/");
            if (bang >= 0) s = s.substring(0, bang);
            return s.startsWith("file:") && norm(Path.of(new java.net.URI(s))).startsWith(gameRoot);
        } catch (Exception e) {
            return false;
        }
    }

    public static List<String> dualOwned() { return List.copyOf(dual); }

    /**
     * Packages of the launch class path libraries (slf4j, Guava, Netty, ...: jars outside the game folder). A mod that shades
     * one of them unrelocated (Axiom 6.1.5 ships org.slf4j, Jackson, Apache, ...) must not become its owner: else every Forge
     * mod's reference to org.slf4j.Logger is hidden (Create: NoSuchMethodError LogUtils.getLogger() in Create.&lt;clinit&gt;).
     */
    private static Set<String> launchLibraryPackages(Path game) {
        Set<String> out = new HashSet<>();
        Path g = norm(game);
        for (String e : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            if (!e.endsWith(".jar")) continue;
            Path p;
            try { p = norm(Path.of(e)); } catch (Throwable t) { continue; }
            if (p.startsWith(g) || !Files.isRegularFile(p)) continue;
            try (ZipFile z = new ZipFile(p.toFile())) {
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    String n = en.nextElement().getName();
                    if (n.endsWith(".class") && !n.startsWith("META-INF/") && n.lastIndexOf('/') > 0
                            && !n.startsWith("net/minecraft/") && !n.startsWith("com/mojang/") && !n.startsWith("net/minecraftforge/")
                            && !n.startsWith("net/fabricmc/") && !n.startsWith("cpw/mods/") && !n.startsWith("alloy/"))
                        out.add(n.substring(0, n.lastIndexOf('/')));
                }
            } catch (Throwable t) { /* not a zip */ }
        }
        return out;
    }

    private static Side fabricSide(String id) {
        if (id.equals("minecraft") || id.equals("java") || id.startsWith("mixinextras") || id.equals("mixin")) return null;
        if (id.startsWith("alloy")) return Side.BRIDGE;
        if (id.equals("fabricloader") || id.startsWith("fabric-")) return Side.FABRIC_PLATFORM;
        return Side.FABRIC;
    }

    private static Side merge(Side a, Side b) {
        if (a == b) return a;
        if (a.fabricFamily() && b.fabricFamily()) return Side.FABRIC_PLATFORM;
        return Side.SPLIT;
    }

    // ---- shared API rule -------------------------------------------------------------------------------------
    // A Fabric-owned jar that itself ships classes in a Forge-API package (e.g. Forge Config API Port: net.minecraftforge.common.*,
    // net.minecraftforge.fml.config.IConfigSpec) owns them as SHARED API: visible, by reference and by name, to both sides.
    // Forge LOADER internals stay Forge-only: a copy in a Fabric jar is not shared, and the platform's own classes keep
    // being hidden from Fabric by-name lookups (that is what keeps WTHIT working). Both lists are data: extend them in
    // alloy-isolation.txt with "api <internal/package/prefix/>" and "internal <internal/package/prefix/>".
    // Iris's public API (net.irisshaders.iris.api.v0): Fabric Iris ships it, and on Forge Oculus ships the same package, so Forge
    // mods (Flywheel, Create) call it to ask whether a shader pack is on.
    private static final List<String> API_PREFIXES = new ArrayList<>(List.of("net/minecraftforge/", "net/irisshaders/iris/api/"));
    private static final List<String> INTERNAL_PREFIXES = new ArrayList<>(List.of(
            "net/minecraftforge/fml/loading/", "net/minecraftforge/fml/javafmlmod/", "net/minecraftforge/forgespi/",
            "net/minecraftforge/fml/Mod",              // ModList, ModLoader, ModContainer, ModLoadingContext, ...
            "net/minecraftforge/fml/ForgeI18n", "net/minecraftforge/fml/CrashReportCallables", "net/minecraftforge/fml/IModStateProvider",
            "cpw/mods/"));

    static boolean sharedApi(Side jarSide, String internal) {
        if (jarSide != Side.FABRIC) return false;
        boolean api = false;
        for (String p : API_PREFIXES) if (internal.startsWith(p)) { api = true; break; }
        if (!api) return false;
        for (String p : INTERNAL_PREFIXES) if (internal.startsWith(p)) return false;
        return true;
    }

    // ---- cross-side API rule ---------------------------------------------------------------------------------
    // APIs that mods of BOTH loaders compile against, whatever loader the jar that ships them belongs to: recipe viewers
    // (JEI, REI, EMI), TerraBlender, Team Reborn Energy. On 26.1.2 only Fabric JEI/REI exist, and Forge mods ship plugins
    // for them (Balm, Time in a Bottle, Modonomicon: @JeiPlugin; Paladin's Furniture: @REIPluginCommon). Without this rule
    // the Forge plugin's reference to mezz/jei/api/IModPlugin (owned by the Fabric JEI jar) is hidden and the plugin dies
    // with NoClassDefFoundError. Class-level SHARED like sharedApi, so it never claims a whole package for one side, and
    // only the API packages: the viewer's internals stay with their own loader. Extend with "share <prefix>" lines.
    static final List<String> CROSS_API_PREFIXES = new ArrayList<>(List.of(
            "mezz/jei/api/",
            "me/shedaniel/rei/api/",
            "me/shedaniel/rei/plugin/common/",     // REI's built-in plugin types addons use (BuiltinPlugin, default categories)
            "me/shedaniel/rei/forge/REIPlugin",    // REI's Forge plugin annotations (not in Fabric REI; harmless when missing)
            "me/shedaniel/math/",                  // REI API types (Rectangle, Point)
            "me/shedaniel/clothconfig2/api/",      // REI addons use Cloth Config's API (Paladin's Furniture: animator)
            "dev/emi/emi/api/",
            "terrablender/api/",
            "team/reborn/energy/api/"));

    /** -Dalloy.isolation.crossApi=false turns the rule off (to compare against the old behaviour). */
    static final boolean CROSS_API = !"false".equals(System.getProperty("alloy.isolation.crossApi"));

    static boolean crossApi(Side jarSide, String internal) {
        if (!CROSS_API || (jarSide != Side.FABRIC && jarSide != Side.FORGE)) return false;
        for (String p : CROSS_API_PREFIXES) if (internal.startsWith(p)) return true;
        return false;
    }

    private static Set<String> readAllowlist(Path game) {
        Set<String> out = new HashSet<>();
        Path f = game.resolve("alloy-isolation.txt");
        try {
            if (Files.exists(f))
                for (String line : Files.readAllLines(f)) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length >= 3 && p[0].equals("allow") && p[2].equals("both")) out.add(p[1]);
                    else if (p.length >= 2 && p[0].equals("api") && !API_PREFIXES.contains(p[1])) API_PREFIXES.add(p[1]);
                    else if (p.length >= 2 && p[0].equals("internal") && !INTERNAL_PREFIXES.contains(p[1])) INTERNAL_PREFIXES.add(p[1]);
                    else if (p.length >= 2 && p[0].equals("share") && !CROSS_API_PREFIXES.contains(p[1])) CROSS_API_PREFIXES.add(p[1]);
                }
        } catch (IOException e) {
            Bridge.LOG.warn("Alloy isolation: cannot read {}: {}", f, e.toString());
        }
        // libraries a Fabric mod nests in an older version than the Forge copy in forgemods/ (written by alloy-prep):
        // only the Forge copy is loaded, and the Fabric mods link to it, as under Connector
        Path twins = game.resolve(".alloy").resolve("twins-both.txt");
        try {
            if (Files.exists(twins)) for (String line : Files.readAllLines(twins)) if (!line.isBlank()) out.add(line.trim());
        } catch (IOException e) {
            Bridge.LOG.warn("Alloy isolation: cannot read {}: {}", twins, e.toString());
        }
        if (!out.isEmpty()) Bridge.LOG.info("Alloy isolation: allowlisted for both sides: {}", out);
        return out;
    }

    /**
     * Forge mod jar names that nest Forgified Fabric API (written by alloy-prep, .alloy/fabric-aware.txt): their nested
     * copies are not installed, so these mods link to the real Fabric API like Embeddium does
     */
    private static Set<String> readAwareJars(Path game) {
        Path f = game.resolve(".alloy").resolve("fabric-aware.txt");
        try {
            if (Files.exists(f)) {
                Set<String> out = new HashSet<>();
                for (String line : Files.readAllLines(f)) if (!line.isBlank()) out.add(line.trim());
                return out;
            }
        } catch (IOException ignored) {
        }
        return Set.of();
    }

    /** mod ids of "&lt;key&gt; &lt;modid&gt;" lines in alloy-isolation.txt */
    private static Set<String> readList(Path game, String key) {
        Set<String> out = new HashSet<>();
        Path f = game.resolve("alloy-isolation.txt");
        try {
            if (Files.exists(f))
                for (String line : Files.readAllLines(f)) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length >= 2 && p[0].equals(key)) out.add(p[1]);
                }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static Set<String> readLookupList(Path game) {
        Set<String> out = new HashSet<>();
        Path f = game.resolve("alloy-isolation.txt");
        try {
            if (Files.exists(f))
                for (String line : Files.readAllLines(f)) {
                    String[] p = line.trim().split("\s+");
                    if (p.length >= 3 && p[0].equals("allow") && p[2].equals("lookup")) out.add(p[1]);
                }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static Path norm(Path p) { return p.toAbsolutePath().normalize(); }

    private static String pkgOf(String internal) {
        int i = internal.lastIndexOf('/');
        return i < 0 ? "" : internal.substring(0, i);
    }

    static boolean hasEntry(Path jar, String entry) {
        if (jar.getFileSystem() != FileSystems.getDefault()) return false;
        try (ZipFile z = new ZipFile(jar.toFile())) {
            return z.getEntry(entry) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Internal names of all classes in a jar, directory or zip-file-system root (no module-info, no multi-release copies). */
    static List<String> listClasses(Path p) {
        List<String> out = new ArrayList<>();
        try {
            if (Files.isDirectory(p)) {
                try (var s = Files.walk(p)) {
                    s.filter(x -> x.toString().endsWith(".class")).forEach(x -> {
                        String rel = p.relativize(x).toString().replace('\\', '/');
                        add(out, rel);
                    });
                }
            } else if (p.getFileSystem() == FileSystems.getDefault()) {
                try (ZipFile z = new ZipFile(p.toFile())) {
                    for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) add(out, en.nextElement().getName());
                }
            } else {
                try (ZipInputStream z = new ZipInputStream(Files.newInputStream(p))) {
                    for (ZipEntry e; (e = z.getNextEntry()) != null; ) add(out, e.getName());
                }
            }
        } catch (IOException | RuntimeException e) {
            Bridge.LOG.warn("Alloy isolation: cannot list {}: {}", p, e.toString());
        }
        return out;
    }

    private static void add(List<String> out, String entry) {
        if (!entry.endsWith(".class") || entry.startsWith("META-INF/") || entry.equals("module-info.class")) return;
        out.add(entry.substring(0, entry.length() - 6));
    }

    // ---------------------------------------------------------------------------------------------------- presence

    /** The ownership index exists (isolation is on and init ran). */
    public static boolean indexed() { return ready; }

    /** Mods Alloy lets both sides link to by default (Embeddium, Oculus), also without isolation. */
    public static boolean bothDefault(String modId) { return BOTH_DEFAULT.contains(modId); }

    /** Side of the jar of a mod id on one loader (forge = FML's list, else Fabric Loader's); null when not indexed. */
    public static Side modSide(String modId, boolean forge) { return modId == null ? null : (forge ? forgeModSide : fabricModSide).get(modId); }

    /** Whether that jar has class files; null when not indexed. */
    public static Boolean modHasClasses(String modId, boolean forge) { return modId == null ? null : (forge ? forgeModClasses : fabricModClasses).get(modId); }

    /**
     * May the class {@code callerInternal} link to code of a jar owned by {@code to}? Platform and shared classes count as
     * the side that asked (forgeAsker: Forge's ModList, else Fabric Loader). Fabric aware Forge mods (Embeddium, Oculus)
     * may link to Fabric API and Fabric Loader.
     */
    public static boolean canLink(String callerInternal, boolean forgeAsker, Side to) {
        Side from = sideOf(callerInternal);
        if (from != Side.FABRIC && from != Side.FORGE && from != Side.BRIDGE) from = forgeAsker ? Side.FORGE : Side.FABRIC;
        if (from == Side.FORGE && to == Side.FABRIC_PLATFORM && awarePkgs.contains(pkgOf(callerInternal))) return true;
        return refAllowed(from, to);
    }

    // ---------------------------------------------------------------------------------------------------- rules

    /** Owner of a class (internal name); SHARED when unknown. */
    public static Side sideOf(String internal) {
        // Fabric Loader's side annotations (Environment, EnvType, EnvironmentInterface): Forge mods built for both loaders
        // carry or shade them (CreativeCore in Dwellers); they never do anything on Forge, so every side may link to them
        if (internal.startsWith("net/fabricmc/api/Env")) return Side.SHARED;
        Side s = pkgOwner.get(pkgOf(internal));
        if (s == Side.SPLIT) {
            Side c = classOwner.get(internal);
            return c != null ? c : prefixSide(internal);
        }
        return s != null ? s : prefixSide(internal);
    }

    private static Side prefixSide(String in) {
        if (in.startsWith("alloy/")) return Side.BRIDGE;
        if (in.startsWith("net/minecraftforge/") || in.startsWith("cpw/mods/")) return Side.FORGE_PLATFORM;
        if (in.startsWith("net/fabricmc/")) return Side.FABRIC_PLATFORM;
        return Side.SHARED;
    }

    /** May code owned by {@code from} link (by reference) to a class owned by {@code to}? */
    static boolean refAllowed(Side from, Side to) {
        return switch (from) {
            case FABRIC -> to != Side.FORGE;                                   // patched MC exposes IForge*: platform by reference is fine
            case FORGE -> to != Side.FABRIC && to != Side.FABRIC_PLATFORM;
            default -> true;
        };
    }

    /** May code owned by {@code from} look up (by name) a class owned by {@code to}? */
    static boolean nameAllowed(Side from, Side to) {
        if (from == Side.FABRIC && to == Side.FORGE_PLATFORM) return false;
        return refAllowed(from, to);
    }

    static boolean viewAllows(Side view, String dotted) {
        if (dotted.startsWith("alloy.hidden.")) return false;
        return nameAllowed(view, sideOf(dotted.replace('.', '/')));
    }

    // ---------------------------------------------------------------------------------------------------- views

    public static SideView view(Side s) {
        if (knot == null) knot = Isolation.class.getClassLoader();
        if (s == Side.FABRIC) {
            SideView v = fabricView;
            if (v == null) fabricView = v = new SideView(Side.FABRIC, knot);
            return v;
        }
        SideView v = forgeView;
        if (v == null) forgeView = v = new SideView(Side.FORGE, knot);
        return v;
    }

    /** Loader FML should use for its own by-name lookups (BridgeHooks.load/forName): the Forge view when enforcing. */
    public static ClassLoader forgeLoader() {
        return MODE == Mode.ENFORCE && ready ? view(Side.FORGE) : BridgeLayers.loader();
    }

    /** ClassLoader(null) delegating to Knot, refusing classes and resources the side may not see. */
    public static final class SideView extends ClassLoader {
        static { ClassLoader.registerAsParallelCapable(); }
        final Side side;
        final ClassLoader knot;

        SideView(Side side, ClassLoader knot) {
            super("alloy-" + side.name().toLowerCase(java.util.Locale.ROOT) + "-view", null);
            this.side = side;
            this.knot = knot;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (ready && !viewAllows(side, name))
                throw new ClassNotFoundException(name + " is hidden from " + side + " code by Alloy isolation");
            return Class.forName(name, false, knot);
        }

        private boolean resourceAllowed(String name, URL u) {
            if (!ready) return true;
            if (name.endsWith(".class") && !viewAllows(side, name.substring(0, name.length() - 6).replace('/', '.'))) return false;
            Side owner = urlSide(u);
            return owner == null || nameAllowed(side, owner);
        }

        @Override
        public URL getResource(String name) {
            URL u = knot.getResource(name);
            return u != null && resourceAllowed(name, u) ? u : null;
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            List<URL> out = new ArrayList<>();
            for (Enumeration<URL> en = knot.getResources(name); en.hasMoreElements(); ) {
                URL u = en.nextElement();
                if (resourceAllowed(name, u)) out.add(u);
            }
            return Collections.enumeration(out);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            URL u = getResource(name);
            try {
                return u == null ? null : u.openStream();
            } catch (IOException e) {
                return null;
            }
        }
    }

    /** Owner of the jar/directory a resource URL points into, null if not a mod/platform jar we know. */
    private static Side urlSide(URL u) {
        try {
            String s = u.toString();
            if (s.startsWith("jar:")) s = s.substring(4);
            int bang = s.indexOf("!/");
            if (bang >= 0) s = s.substring(0, bang);
            if (!s.startsWith("file:")) return null;
            Path p = norm(Path.of(new java.net.URI(s)));
            Side side = jarSide.get(p);
            if (side != null) return side;
            for (Map.Entry<Path, Side> e : jarSide.entrySet())      // directory origins (dev environment)
                if (Files.isDirectory(e.getKey()) && p.startsWith(e.getKey())) return e.getValue();
        } catch (Exception ignored) {
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------- wrappers
    // Targets of the rewritten by-name lookups. The int is Side.ordinal() of the calling class.

    private static ClassLoader viewFor(int side) { return view(side == Side.FABRIC.ordinal() ? Side.FABRIC : Side.FORGE); }

    private static boolean isKnot(ClassLoader l) { return l == null || l == knot || l instanceof SideView; }

    /** replaces Class.forName(String) */
    public static Class<?> forName(String name, int side) throws ClassNotFoundException {
        return Class.forName(name, true, MODE == Mode.ENFORCE && ready ? viewFor(side) : Isolation.class.getClassLoader());
    }

    /** replaces Class.forName(String, boolean, ClassLoader) */
    public static Class<?> forName(String name, boolean init, ClassLoader l, int side) throws ClassNotFoundException {
        if (MODE == Mode.ENFORCE && ready && isKnot(l)) l = viewFor(side);
        return Class.forName(name, init, l);
    }

    /** replaces ClassLoader.loadClass(String) */
    public static Class<?> loadClass(ClassLoader l, String name, int side) throws ClassNotFoundException {
        if (MODE == Mode.ENFORCE && ready && isKnot(l)) return viewFor(side).loadClass(name);
        return l.loadClass(name);
    }

    /** replaces ServiceLoader.load(Class) (context class loader) */
    public static <S> ServiceLoader<S> services(Class<S> c, int side) {
        if (MODE == Mode.ENFORCE && ready) return ServiceLoader.load(c, viewFor(side));
        return ServiceLoader.load(c);
    }

    /** replaces ServiceLoader.load(Class, ClassLoader) */
    public static <S> ServiceLoader<S> services(Class<S> c, ClassLoader l, int side) {
        if (MODE == Mode.ENFORCE && ready && isKnot(l)) l = viewFor(side);
        return ServiceLoader.load(c, l);
    }

    // Forge mods only: ClassLoader.getResource*("/assets/...") with a leading slash. Forge's module class loader resolves the
    // name as a path in the mod jar, so the slash does no harm there; Knot (a URLClassLoader) finds nothing. Embeddium loads its
    // chunk shaders this way and crashed with "Shader not found: /assets/sodium/shaders/blocks/block_layer_opaque.vsh".

    static String noSlash(ClassLoader l, String name) {
        return name != null && name.startsWith("/") && isKnot(l) ? name.substring(1) : name;
    }

    /** replaces ClassLoader.getResource(String) in Forge mods */
    public static URL resource(ClassLoader l, String name) { return l.getResource(noSlash(l, name)); }

    /** replaces ClassLoader.getResourceAsStream(String) in Forge mods */
    public static InputStream resourceStream(ClassLoader l, String name) { return l.getResourceAsStream(noSlash(l, name)); }

    /** replaces ClassLoader.getResources(String) in Forge mods */
    public static Enumeration<URL> resources(ClassLoader l, String name) throws IOException { return l.getResources(noSlash(l, name)); }

    /** replaces MethodHandles.Lookup.findClass(String) */
    public static Class<?> findClass(MethodHandles.Lookup lookup, String name, int side) throws ClassNotFoundException, IllegalAccessException {
        if (MODE == Mode.ENFORCE && ready && !viewAllows(side == Side.FABRIC.ordinal() ? Side.FABRIC : Side.FORGE, name))
            throw new ClassNotFoundException(name + " is hidden by Alloy isolation");
        return lookup.findClass(name);
    }

    // ---------------------------------------------------------------------------------------------------- transform

    private static final Set<String> NAME_KEYS = Set.of("forName", "loadClass", "findClass", "java/util/ServiceLoader");
    private static final Set<String> RESOURCE_KEYS = Set.of("getResource", "getResources", "getResourceAsStream");
    private static final Set<String> warned = ConcurrentHashMap.newKeySet();

    /**
     * Called from ForgeLaunchPlugins.transform on pre-Mixin bytes (dotted class name). enforce: renames references to
     * the other side and reroutes by-name lookups; warn: only logs; off: returns the bytes untouched.
     */
    public static byte[] remapIfNeeded(String name, byte[] bytes) {
        if (MODE == Mode.OFF || !ready || bytes == null) return bytes;
        try {
            String internal = name.replace('.', '/');
            Side mine = sideOf(internal);
            if (mine == Side.BRIDGE && !internal.startsWith("alloy/")) return bridgeResources(bytes);
            if (mine != Side.FABRIC && mine != Side.FORGE) return bytes;
            int hits = scanPool(bytes, mine);
            boolean aware = mine == Side.FORGE && awarePkgs.contains(pkgOf(internal));   // Embeddium, Oculus: see Fabric API
            boolean freeLookup = aware || lookupPkgs.contains(pkgOf(internal));
            if (freeLookup) hits &= ~2;
            boolean res = mine == Side.FORGE && (hits & 4) != 0;   // Forge mod calls ClassLoader.getResource*: leading slash fix
            if ((hits & 1) == 0 && (MODE == Mode.WARN || ((hits & 2) == 0 && !res))) return bytes;
            if (MODE == Mode.WARN) {
                new ClassReader(bytes).accept(new ClassRemapper(new ClassVisitor(Opcodes.ASM9) {}, new Recorder(mine, internal)), ClassReader.SKIP_FRAMES);
                return bytes;
            }
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            ClassRemapper cr = new ClassRemapper(cw, new Hider(mine, aware));
            new ClassReader(bytes).accept(freeLookup && !res ? cr : new LookupRewriter(cr, mine, !freeLookup, res), 0);
            return cw.toByteArray();
        } catch (Throwable t) {
            Bridge.LOG.error("Alloy isolation: could not process {}", name, t);
            return bytes;
        }
    }

    /**
     * Mods allowlisted for both sides (Embeddium, Oculus) see everything, but their ClassLoader.getResource* calls still
     * need the leading slash fix: Embeddium loads its chunk shaders as "/assets/sodium/shaders/..." and Knot finds
     * nothing ("Shader not found", Forgeulously Optimized once Embeddium became a both-sides mod).
     */
    private static byte[] bridgeResources(byte[] bytes) {
        if ((scanPool(bytes, Side.BRIDGE) & 4) == 0) return bytes;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(bytes).accept(new LookupRewriter(cw, Side.BRIDGE, false, true), 0);
        return cw.toByteArray();
    }

    /**
     * Constant-pool fast path. Returns bit 1 if some UTF8 entry mentions a class (or package) the other side owns,
     * bit 2 if the pool mentions a by-name lookup (forName/loadClass/findClass/ServiceLoader), bit 4 if it mentions
     * getResource/getResources/getResourceAsStream. Never decodes code.
     */
    private static int scanPool(byte[] b, Side mine) {
        int n = ((b[8] & 0xff) << 8) | (b[9] & 0xff);
        int p = 10, res = 0;
        for (int i = 1; i < n; i++) {
            int tag = b[p] & 0xff;
            switch (tag) {
                case 1 -> {
                    int len = ((b[p + 1] & 0xff) << 8) | (b[p + 2] & 0xff);
                    boolean slash = false;
                    for (int k = p + 3; k < p + 3 + len; k++) if (b[k] == '/') { slash = true; break; }
                    if (slash || len == 7 || len == 9 || len == 11 || len == 12 || len == 19 || len == 22) {
                        String s = new String(b, p + 3, len, StandardCharsets.ISO_8859_1);
                        if (NAME_KEYS.contains(s)) res |= 2;
                        if (RESOURCE_KEYS.contains(s)) res |= 4;
                        if (slash && (res & 1) == 0 && mentionsOther(s, mine)) res |= 1;
                    }
                    p += 3 + len;
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> p += 5;
                case 5, 6 -> { p += 9; i++; }
                case 7, 8, 16, 19, 20 -> p += 3;
                case 15 -> p += 4;
                default -> { return 7; }   // unknown tag: be conservative, let ASM decide
            }
        }
        return res;
    }

    private static boolean mentionsOther(String s, Side mine) {
        int start = 0, n = s.length();
        for (int i = 0; i <= n; i++) {
            if (i < n && "();<>[:,\" ".indexOf(s.charAt(i)) < 0) continue;
            if (i > start) {
                int slash = s.lastIndexOf('/', i - 1);
                if (slash > start - 1 && slash >= start) {
                    if (isOther(s.substring(start, i), mine)) return true;
                    if (s.charAt(start) == 'L' && isOther(s.substring(start + 1, i), mine)) return true;
                }
            }
            start = i + 1;
        }
        return false;
    }

    private static boolean isOther(String cls, Side mine) {
        int dot = cls.indexOf('.');           // "Lpkg/Outer<TT;>.Inner" style tails
        if (dot >= 0) cls = cls.substring(0, dot);
        return !refAllowed(mine, sideOf(cls));
    }

    private static final class Hider extends Remapper {
        final Side mine;
        final String prefix;
        final boolean aware;   // a FABRIC_AWARE Forge mod: Fabric API and Fabric Loader stay linked
        Hider(Side mine) { this(mine, false); }
        Hider(Side mine, boolean aware) {
            super(Opcodes.ASM9);
            this.mine = mine;
            this.aware = aware;
            this.prefix = HIDDEN + mine.name().toLowerCase(java.util.Locale.ROOT) + "/";
        }
        @Override
        public String map(String internal) {
            Side to = sideOf(internal);
            return refAllowed(mine, to) || aware && to == Side.FABRIC_PLATFORM ? internal : prefix + internal;
        }
    }

    private static final class Recorder extends Remapper {
        final Side mine;
        final String from;
        Recorder(Side mine, String from) { super(Opcodes.ASM9); this.mine = mine; this.from = from; }
        @Override
        public String map(String internal) {
            Side to = sideOf(internal);
            if (!refAllowed(mine, to) && warned.add(from + ">" + internal))
                report("XREF " + mine + "->" + to + " " + from.replace('/', '.') + " -> " + internal.replace('/', '.'));
            return internal;
        }
    }

    /** Rewrites by-name lookups to the wrappers, appending the caller's side (Side.ordinal()) as last argument. */
    private static final class LookupRewriter extends ClassVisitor {
        final int sideOrd;
        final boolean names, resources;
        LookupRewriter(ClassVisitor cv, Side mine) { this(cv, mine, true, false); }
        LookupRewriter(ClassVisitor cv, Side mine, boolean names, boolean resources) {
            super(Opcodes.ASM9, cv);
            sideOrd = mine.ordinal();
            this.names = names;
            this.resources = resources;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
            MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
            return mv == null ? null : new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                    String iso = "alloy/Isolation";
                    if (resources && op == Opcodes.INVOKEVIRTUAL && owner.equals("java/lang/ClassLoader")) {
                        String to = switch (n + d) {
                            case "getResource(Ljava/lang/String;)Ljava/net/URL;" -> "resource";
                            case "getResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;" -> "resourceStream";
                            case "getResources(Ljava/lang/String;)Ljava/util/Enumeration;" -> "resources";
                            default -> null;
                        };
                        if (to != null) { super.visitMethodInsn(Opcodes.INVOKESTATIC, iso, to, "(Ljava/lang/ClassLoader;" + d.substring(1), false); return; }
                    }
                    if (!names) { super.visitMethodInsn(op, owner, n, d, itf); return; }
                    if (op == Opcodes.INVOKESTATIC && owner.equals("java/lang/Class") && n.equals("forName")) {
                        if (d.equals("(Ljava/lang/String;)Ljava/lang/Class;")) { push(); super.visitMethodInsn(op, iso, n, "(Ljava/lang/String;I)Ljava/lang/Class;", false); return; }
                        if (d.equals("(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;")) { push(); super.visitMethodInsn(op, iso, n, "(Ljava/lang/String;ZLjava/lang/ClassLoader;I)Ljava/lang/Class;", false); return; }
                    } else if (op == Opcodes.INVOKEVIRTUAL && owner.equals("java/lang/ClassLoader") && n.equals("loadClass")
                            && d.equals("(Ljava/lang/String;)Ljava/lang/Class;")) {
                        push(); super.visitMethodInsn(Opcodes.INVOKESTATIC, iso, n, "(Ljava/lang/ClassLoader;Ljava/lang/String;I)Ljava/lang/Class;", false); return;
                    } else if (op == Opcodes.INVOKESTATIC && owner.equals("java/util/ServiceLoader") && n.equals("load")) {
                        if (d.equals("(Ljava/lang/Class;)Ljava/util/ServiceLoader;")) { push(); super.visitMethodInsn(op, iso, "services", "(Ljava/lang/Class;I)Ljava/util/ServiceLoader;", false); return; }
                        if (d.equals("(Ljava/lang/Class;Ljava/lang/ClassLoader;)Ljava/util/ServiceLoader;")) { push(); super.visitMethodInsn(op, iso, "services", "(Ljava/lang/Class;Ljava/lang/ClassLoader;I)Ljava/util/ServiceLoader;", false); return; }
                    } else if (op == Opcodes.INVOKEVIRTUAL && owner.equals("java/lang/invoke/MethodHandles$Lookup") && n.equals("findClass")
                            && d.equals("(Ljava/lang/String;)Ljava/lang/Class;")) {
                        push(); super.visitMethodInsn(Opcodes.INVOKESTATIC, iso, n, "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;I)Ljava/lang/Class;", false); return;
                    }
                    super.visitMethodInsn(op, owner, n, d, itf);
                }
                private void push() { super.visitLdcInsn(sideOrd); }
            };
        }
    }

    // ---------------------------------------------------------------------------------------------------- report

    static synchronized void report(String line) {
        if (report == null) return;
        try {
            report.write(line);
            report.newLine();
            report.flush();
        } catch (IOException ignored) {
        }
    }

    /** Touch everything the transform hook uses so none of it is first loaded (and re-entered) mid-transform. */
    private static void warmUp() {
        try {
            new ClassWriter(ClassWriter.COMPUTE_MAXS);
            new Hider(Side.FABRIC).map("a/B");
            new Recorder(Side.FABRIC, "a/B");
            new LookupRewriter(new ClassVisitor(Opcodes.ASM9) {}, Side.FABRIC);
            mentionsOther("La/B;", Side.FABRIC);
            new ClassRemapper(new ClassVisitor(Opcodes.ASM9) {}, new Hider(Side.FORGE));
            warned.add("warm");
            urlSide(new URL("file:/warm"));
        } catch (Throwable ignored) {
        }
    }
}
