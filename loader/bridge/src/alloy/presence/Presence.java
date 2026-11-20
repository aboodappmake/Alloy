package alloy.presence;

import alloy.Bridge;
import alloy.Fml;
import alloy.Isolation;
import alloy.presence.PresenceTable.Answer;
import alloy.presence.PresenceTable.Caller;
import alloy.presence.PresenceTable.Entry;
import alloy.presence.PresenceTable.Side;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Cross-side answers for "is mod X loaded?". Hooks:
 * <ul>
 * <li>Forge {@code ModList.isLoaded}: a mixin at RETURN (compat120/presence on Forge 47 and 49, where it is an instance
 *     method; compat/viewers/ModListViewers on Forge 64, where it is static) calls {@link #forgeIsLoaded} when FML said no.</li>
 * <li>Fabric {@code FabricLoader.isModLoaded}: Fabric Loader's classes cannot be transformed, so {@link PresenceRewrite}
 *     points the call sites in Fabric side classes at {@link #fabricIsModLoaded}.</li>
 * </ul>
 * Only the miss path does work: when the own loader says no and the id (or an alias) exists elsewhere, the caller is taken
 * from the stack. Data checks (Fabric resource conditions, Forge's forge:mod_loaded, Lithostitched's mod_loaded predicate)
 * answer yes across sides; code checks follow {@link PresenceTable#code}. Mod lists are never changed.
 *
 * <p>Switches: {@code -Dalloy.presence=false} (all off), {@code -Dalloy.presence.data=false} (data stays one sided),
 * {@code -Dalloy.presence.code=linkable|off|all} (default linkable), {@code -Dalloy.viewers.forgeIsLoaded=false} (Forge
 * mods stop seeing Fabric recipe viewers). Every answer that crossed sides, and every "would have been yes", is written
 * once to alloy-presence.txt in the game folder.
 */
public final class Presence {
    private Presence() {}

    public static final boolean ON = !"false".equals(System.getProperty("alloy.presence"));
    static final boolean DATA_ON = ON && !"false".equals(System.getProperty("alloy.presence.data"));
    static final String CODE_MODE = System.getProperty("alloy.presence.code", "linkable").trim().toLowerCase(java.util.Locale.ROOT);
    static final boolean VIEWERS = !"false".equals(System.getProperty("alloy.viewers.forgeIsLoaded"));
    /** Recipe viewers a Forge mod may see on the Fabric side (ViewerBridge feeds Forge plugins into them on 26.x). */
    static final Set<String> VIEWER_IDS = Set.of("jei", "roughlyenoughitems", "emi");
    /** Fabric "mods" that are the platform or Alloy itself */
    static final Set<String> NOT_MODS = Set.of("java", "minecraft", "fabricloader", "mixinextras", "alloyloader", "alloy",
            "alloy_modmixins", "alloy_api", "alloy-bridge");

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static volatile PresenceTable table;
    private static final Map<String, Boolean> owned = new ConcurrentHashMap<>();       // side:id|class -> caller is in that mod
    private static final Map<String, Path> roots = new ConcurrentHashMap<>();        // side:id -> jar (class check without isolation)
    private static final Map<String, Boolean> classes = new ConcurrentHashMap<>();
    private static final ClassValue<Map<String, Boolean>> CACHE = new ClassValue<>() {
        @Override protected Map<String, Boolean> computeValue(Class<?> c) { return new ConcurrentHashMap<>(); }
    };
    private static final Set<String> written = ConcurrentHashMap.newKeySet();
    private static final List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static BufferedWriter report;

    // ------------------------------------------------------------------------------------------------ hooks

    /** Rewritten call sites of {@code FabricLoader.isModLoaded(id)} in Fabric side classes (PresenceRewrite). */
    public static boolean fabricIsModLoaded(FabricLoader loader, String id) {
        if (loader.isModLoaded(id)) return true;
        return ON && miss(id, Side.FABRIC, "Fabric isModLoaded");
    }

    /** Forge {@code ModList.isLoaded(id)} returned false (mixin at RETURN). */
    public static boolean forgeIsLoaded(String id) {
        return ON && miss(id, Side.FORGE, "Forge ModList.isLoaded");
    }

    /** One mod loaded condition, for callers that know they are data (KubeJS style script platforms). */
    public static boolean dataLoaded(String id) {
        PresenceTable t = table();
        if (!DATA_ON || t == null || id == null) return false;
        Answer a = t.data(id);
        if (a.yes()) note("DATA yes", "mod_loaded \"" + id + "\"", null, a);
        return a.yes();
    }

    /** true when a Forge mod may see this Fabric recipe viewer (ViewerBridge's old switch and id list). */
    public static boolean viewerBridged(String id) {
        if (!VIEWERS || id == null || !VIEWER_IDS.contains(id)) return false;
        try {
            return FabricLoader.getInstance().isModLoaded(id);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------ rules

    static boolean miss(String id, Side asker, String api) {
        if (id == null) return false;
        PresenceTable t = table();
        if (t == null || !t.elsewhere(id, asker)) return false;
        try {
            List<Class<?>> frames = WALKER.walk(s -> s.limit(24).map(StackWalker.StackFrame::getDeclaringClass).toList());
            List<String> names = new ArrayList<>(frames.size());
            for (Class<?> c : frames) names.add(c.getName());
            Caller caller = t.classify(names);
            Class<?> cc = null;
            if (caller.className() != null) for (Class<?> c : frames) if (c.getName().equals(caller.className())) { cc = c; break; }
            String key = (caller.data() ? "d:" : "c:") + asker.name() + ":" + id;
            Map<String, Boolean> cache = cc != null ? CACHE.get(cc) : null;
            Boolean hit = cache != null ? cache.get(key) : null;
            if (hit != null) return hit;
            boolean yes = decide(t, id, asker, api, caller, cc);
            if (cache != null) cache.put(key, yes);
            return yes;
        } catch (Throwable e) {
            if (written.add("error:" + e)) Bridge.LOG.warn("Alloy presence: check of \"{}\" failed, answering no: {}", id, e.toString());
            return false;
        }
    }

    private static boolean decide(PresenceTable t, String id, Side asker, String api, Caller caller, Class<?> cc) {
        String what = api + "(\"" + id + "\")";
        String from = caller.className();
        if (caller.data()) {
            if (!DATA_ON) return false;
            Answer a = t.data(id);
            if (a.yes()) note("DATA yes", what, from, a);
            return a.yes();
        }
        if (CODE_MODE.equals("off")) {
            Answer a = t.code(id, asker, never(cc));
            if (a.yes() && ownAlias(id, a, cc)) { note("CODE no ", what, from, new Answer(false, a.via(), "the caller is " + a.via().owner() + " itself asking for an alias of its own id", false)); return false; }
            if (a.yes() && a.via() != null && a.via().side() == asker) { note("CODE yes", what, from, a); return true; }   // same side alias
            if (a.via() != null) note("CODE no ", what, from, new Answer(false, a.via(), "cross side code answers are off (-Dalloy.presence.code=off)", true));
            return false;
        }
        Answer a = t.code(id, asker, linker(asker, cc));
        if (a.yes() && ownAlias(id, a, cc)) {
            note("CODE no ", what, from, new Answer(false, a.via(), "the caller is " + a.via().owner() + " itself asking for an alias of its own id", false));
            return false;
        }
        if (a.yes()) note("CODE yes", what, from, a);
        else if (a.via() != null && !a.wouldBeYes()) note("CODE no ", what, from, a);   // an alias of the caller's own mod
        else if (a.wouldBeYes()) note("CODE no ", what, from, new Answer(false, a.via(), a.reason() + "; -Dalloy.presence.code=all answers yes", true));
        return a.yes();
    }

    /**
     * A yes through an alias that points back at the caller's own mod: Fabric Sodium 0.6 asks isModLoaded("embeddium") to
     * find a conflicting fork, and the sodium/embeddium alias would answer yes because Sodium itself is loaded (it then turned
     * all of its mixins off, sodium121 2026-11-16). The caller is that mod when its class comes from the mod's own jar.
     */
    static boolean ownAlias(String id, Answer a, Class<?> cc) {
        Entry via = a.via();
        if (via == null || cc == null || id.equals(via.id())) return false;
        Path root = roots.get(via.side() + ":" + via.owner());
        if (root == null) return false;
        try {
            var cs = cc.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return false;
            Path from = Path.of(cs.getLocation().toURI());
            return from.toAbsolutePath().normalize().equals(root.toAbsolutePath().normalize());
        } catch (Throwable e) {
            return false;
        }
    }

    private static PresenceTable.Linker never(Class<?> caller) {
        return new PresenceTable.Linker() {
            @Override public boolean bridged(Side asker, String id) { return false; }
            @Override public boolean hasClasses(Entry target) { return true; }
            @Override public boolean linkable(Entry target) { return false; }
            @Override public boolean isCaller(Entry target) { return owns(target, caller); }
        };
    }

    private static PresenceTable.Linker linker(Side asker, Class<?> caller) {
        boolean all = CODE_MODE.equals("all");
        String internal = caller == null ? null : caller.getName().replace('.', '/');
        return new PresenceTable.Linker() {
            @Override public boolean bridged(Side a, String id) { return a == Side.FORGE && viewerBridged(id); }
            @Override public boolean hasClasses(Entry target) { return Presence.hasClasses(target); }
            @Override public boolean linkable(Entry target) {
                if (all) return true;
                if (!Isolation.indexed()) return Isolation.bothDefault(target.owner());   // without isolation: Embeddium, Oculus only
                Isolation.Side to = Isolation.modSide(target.owner(), target.side() == Side.FORGE);
                return to != null && internal != null && Isolation.canLink(internal, asker == Side.FORGE, to);
            }
            @Override public boolean isCaller(Entry target) { return owns(target, caller); }
        };
    }

    /** The caller's class file is in the target mod's jar (or folder): the mod is asking about an alias of itself. */
    static boolean owns(Entry e, Class<?> caller) {
        if (e == null || caller == null) return false;
        String key = e.side() + ":" + e.owner();
        Path p = roots.get(key);
        if (p == null) return false;
        String entry = caller.getName().replace('.', '/') + ".class";
        return owned.computeIfAbsent(key + "|" + entry, k -> {
            try {
                if (Files.isDirectory(p)) return Files.isRegularFile(p.resolve(entry));
                try (ZipFile z = new ZipFile(p.toFile())) {
                    return z.getEntry(entry) != null;
                }
            } catch (Throwable t) {
                return false;
            }
        });
    }

    static boolean hasClasses(Entry e) {
        String key = e.side() + ":" + e.owner();
        Boolean b = classes.get(key);
        if (b != null) return b;
        Boolean iso = Isolation.modHasClasses(e.owner(), e.side() == Side.FORGE);
        boolean yes = iso != null ? iso : scanClasses(roots.get(key));
        classes.put(key, yes);
        return yes;
    }

    /** Any .class file outside META-INF in a jar or folder; unknown counts as "has classes" (the safe answer). */
    static boolean scanClasses(Path p) {
        if (p == null) return true;
        try {
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.walk(p)) {
                    return s.anyMatch(f -> f.toString().endsWith(".class") && !p.relativize(f).toString().replace('\\', '/').startsWith("META-INF/"));
                }
            }
            try (ZipFile z = new ZipFile(p.toFile())) {
                var en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (ze.getName().endsWith(".class") && !ze.getName().startsWith("META-INF/")) return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    // ------------------------------------------------------------------------------------------------ index

    /** The table, built once FML's mod file list exists (null before that: callers then keep their own answer). */
    static PresenceTable table() {
        PresenceTable t = table;
        if (t != null) return t;
        synchronized (Presence.class) {
            if (table != null) return table;
            List<ModFileInfo> files;
            try {
                files = Fml.modFiles();
            } catch (Throwable e) {
                return null;
            }
            if (files == null || files.isEmpty()) return null;
            table = t = build(files);
            return t;
        }
    }

    private static PresenceTable build(List<ModFileInfo> files) {
        long t0 = System.nanoTime();
        PresenceTable t = new PresenceTable();
        int fabric = 0, forge = 0;
        try {
            for (ModContainer mc : FabricLoader.getInstance().getAllMods()) {
                ModMetadata md = mc.getMetadata();
                String id = md.getId();
                if (NOT_MODS.contains(id) || md.containsCustomValue("alloy:forge-stub")) continue;   // stubs stand in for a Forge mod
                String v = md.getVersion().getFriendlyString();
                t.add(new Entry(id, Side.FABRIC, v, id, false));
                fabric++;
                for (String p : md.getProvides()) t.add(new Entry(p, Side.FABRIC, v, id, true));
                try {
                    var o = mc.getOrigin();
                    if (o.getKind() == net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH && o.getPaths().size() == 1) roots.put("FABRIC:" + id, o.getPaths().get(0));
                    else if (mc.getRootPaths().size() == 1) roots.put("FABRIC:" + id, mc.getRootPaths().get(0));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            Bridge.LOG.warn("Alloy presence: cannot read the Fabric mod list: {}", e.toString());
        }
        try {
            for (ModFileInfo mfi : files) {
                Path jar = null;
                try { jar = mfi.getFile().getFilePath(); } catch (Throwable ignored) {}
                for (var mi : mfi.getMods()) {
                    String id = mi.getModId();
                    if (id.equals("minecraft") || id.equals("forge")) continue;
                    t.add(new Entry(id, Side.FORGE, String.valueOf(mi.getVersion()), id, false));
                    forge++;
                    if (jar != null) roots.put("FORGE:" + id, jar);
                }
            }
        } catch (Throwable e) {
            Bridge.LOG.warn("Alloy presence: cannot read the Forge mod list: {}", e.toString());
        }
        Path game = gameDir();
        if (game != null) {
            Path cfg = game.resolve("config").resolve("alloy-presence.txt");
            try {
                if (Files.isRegularFile(cfg)) for (String bad : t.readConfig(Files.readAllLines(cfg, StandardCharsets.UTF_8)))
                    Bridge.LOG.warn("Alloy presence: {}: line not understood: {}", cfg, bad);
            } catch (IOException e) {
                Bridge.LOG.warn("Alloy presence: cannot read {}: {}", cfg, e.toString());
            }
            try {
                report = Files.newBufferedWriter(game.resolve("alloy-presence.txt"), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                write("# Alloy presence: cross-side answers for mod loaded checks. data=" + DATA_ON + " code=" + CODE_MODE
                        + " viewers=" + VIEWERS + "; " + fabric + " Fabric and " + forge + " Forge mod ids indexed");
            } catch (IOException e) {
                Bridge.LOG.warn("Alloy presence: cannot write alloy-presence.txt: {}", e.toString());
            }
        }
        Bridge.LOG.info("Alloy presence: indexed {} Fabric and {} Forge mod ids in {} ms (data {}, code {}); {} Fabric isModLoaded call(s) in {} class(es) hooked so far",
                fabric, forge, (System.nanoTime() - t0) / 1_000_000, DATA_ON ? "on" : "off", CODE_MODE, PresenceRewrite.SITES.get(), PresenceRewrite.CLASSES.get());
        return t;
    }

    private static Path gameDir() {
        try {
            return FabricLoader.getInstance().getGameDir();
        } catch (Throwable e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------ report

    private static void note(String kind, String what, String from, Answer a) {
        String line = kind + " " + what + (from != null ? " from " + from : "") + ": " + a.reason();
        if (!written.add(line)) return;
        lines.add(line);
        Bridge.LOG.info("Alloy presence: {}", line);
        write(line);
    }

    private static synchronized void write(String line) {
        if (report == null) return;
        try {
            report.write(line);
            report.newLine();
            report.flush();
        } catch (IOException ignored) {
        }
    }

    /** Every line written to alloy-presence.txt so far (tests, the selftest). */
    public static List<String> summary() { return List.copyOf(lines); }
}
