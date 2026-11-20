package alloy;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Alloy conflict checker. With -Dalloy.conflicts=true:
 *  - every mixin apply error (from any mod: Fabric mods vs Forge-patched Minecraft) is recorded and downgraded to a
 *    warning instead of crashing, and
 *  - after Forge's mod discovery the bridge calls {@link #auditAndExit()}, which force-applies every mixin config to
 *    every target, writes alloy-conflicts.txt and halts before the game runs.
 * Without the property this class only registers nothing and changes nothing.
 *
 * It is the mixin config plugin of alloy.mixins.json so that its onLoad runs at Mixin startup, before any class
 * is transformed (error handlers registered later would miss early failures).
 */
public final class ConflictReport implements IMixinConfigPlugin, IMixinErrorHandler {
    public static final boolean ON = Boolean.getBoolean("alloy.conflicts");
    /** Lenient mode (default on): Fabric-vs-Forge mixin apply errors become logged warnings instead of crashes. */
    public static final boolean LENIENT = !Boolean.getBoolean("alloy.strict");
    static final List<String> ERRORS = Collections.synchronizedList(new ArrayList<>());

    // ---- IMixinErrorHandler ----
    @Override
    public ErrorAction onPrepareError(org.spongepowered.asm.mixin.extensibility.IMixinConfig config, Throwable th, IMixinInfo mixin, ErrorAction action) {
        record("PREPARE", config.getName(), mixin == null ? "?" : mixin.getClassName(), "?", th);
        return ErrorAction.WARN;
    }

    @Override
    public ErrorAction onApplyError(String targetClassName, Throwable th, IMixinInfo mixin, ErrorAction action) {
        record("APPLY", mixin.getConfig().getName(), mixin.getClassName(), targetClassName, th);
        LenientRetry.record(targetClassName, mixin);   // the wrapper applies the other mixins of that class again without this one
        return ErrorAction.WARN;
    }

    static void record(String phase, String config, String mixin, String target, Throwable th) {
        if (!ON) Bridge.LOG.warn("Alloy lenient: skipped {} ({}) on {}: {}", mixin, config, target, th.getMessage());
        String msg = String.valueOf(th.getMessage()).replaceAll("\\s+", " ");
        if (msg.length() > 3000) msg = msg.substring(0, 3000) + "...";
        ERRORS.add(phase + " | " + config + " | " + mixin + " -> " + target + " | " + th.getClass().getSimpleName() + ": " + msg);
        //#if report   (builds without the report module, e.g. launch120.py, drop this call)
        alloy.report.AlloyReport.onMixinError(phase, config, mixin, target, th, LENIENT);
        //#endif
    }

    public static void auditAndExit(Path gameDir) {
        Bridge.LOG.info("Alloy conflict check: force-applying every mixin to every target ...");
        // Load every mixin target class by itself (loading transforms it): a "critical" injection failure throws past
        // the error handler and would abort MixinEnvironment.audit() at the first bad class.
        Set<String> targets = new TreeSet<>();
        // Mixins.getConfigs() only holds not-yet-visited configs; ask Fabric Loader for every mod's declared configs
        for (var mod : net.fabricmc.loader.impl.FabricLoaderImpl.INSTANCE.getModsInternal())
            for (String cfg : mod.getMetadata().getMixinConfigs(net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()))
                targets.addAll(targetsOf(cfg));
        int ok = 0;
        for (String t : targets) {
            try {
                Class.forName(t.replace('/', '.'), false, ConflictReport.class.getClassLoader());
                ok++;
            } catch (Throwable th) {
                Throwable root = th;
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                String msg = String.valueOf(root.getMessage());
                String cfgName = "?";
                var m = java.util.regex.Pattern.compile("in ([\\w.\\-]+\\.json):").matcher(msg);
                if (m.find()) cfgName = m.group(1);
                record("LOAD", cfgName, "-", t, root);
            }
        }
        Bridge.LOG.info("Alloy conflict check: {} target class(es) loaded cleanly of {}", ok, targets.size());
        List<String> lines = new ArrayList<>(new LinkedHashSet<>(ERRORS));
        Map<String, Integer> byConfig = new TreeMap<>();
        for (String l : lines) byConfig.merge(l.split(" \\| ")[1], 1, Integer::sum);
        StringBuilder sb = new StringBuilder("Alloy conflict report: " + lines.size() + " mixin failure(s)\n\nby config:\n");
        byConfig.forEach((k, v) -> sb.append("  ").append(v).append("  ").append(k).append('\n'));
        sb.append("\ndetails (phase | config | mixin -> target | error):\n");
        for (String l : lines) sb.append(l).append('\n');
        try {
            Files.writeString(gameDir.resolve("alloy-conflicts.txt"), sb.toString());
        } catch (Exception e) {
            Bridge.LOG.error("could not write report", e);
        }
        Bridge.LOG.info("Alloy conflict check: {} failure(s), report in alloy-conflicts.txt", lines.size());
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    /** Targets of a mixin config, read from its JSON + each mixin's @Mixin annotation (server side: mixins + server). */
    static Set<String> targetsOf(String configName) {
        Set<String> out = new TreeSet<>();
        ClassLoader cl = ConflictReport.class.getClassLoader();
        try (var in = cl.getResourceAsStream(configName)) {
            if (in == null) return out;
            var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in)).getAsJsonObject();
            String pkg = json.has("package") ? json.get("package").getAsString() : "";
            boolean client = net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT;
            for (String key : client ? new String[]{"mixins", "client"} : new String[]{"mixins", "server"}) {
                if (!json.has(key) || !json.get(key).isJsonArray()) continue;
                for (var e : json.getAsJsonArray(key)) {
                    String cls = (pkg.isEmpty() ? "" : pkg + ".") + e.getAsString();
                    try (var cin = cl.getResourceAsStream(cls.replace('.', '/') + ".class")) {
                        if (cin == null) continue;
                        ClassNode cn = new ClassNode();
                        new org.objectweb.asm.ClassReader(cin.readAllBytes()).accept(cn, org.objectweb.asm.ClassReader.SKIP_CODE);
                        List<org.objectweb.asm.tree.AnnotationNode> anns = new ArrayList<>();
                        if (cn.invisibleAnnotations != null) anns.addAll(cn.invisibleAnnotations);
                        if (cn.visibleAnnotations != null) anns.addAll(cn.visibleAnnotations);
                        for (var a : anns) {
                            if (!a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;") || a.values == null) continue;
                            for (int i = 0; i + 1 < a.values.size(); i += 2) {
                                Object v = a.values.get(i + 1);
                                if (!(v instanceof List<?> l)) continue;
                                for (Object o : l) {
                                    if (o instanceof org.objectweb.asm.Type t) out.add(t.getInternalName());
                                    else if (o instanceof String s && a.values.get(i).equals("targets")) out.add(s.replace('.', '/'));
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            record("CONFIG", configName, "-", "-", ex);
        }
        return out;
    }

    // ---- IMixinConfigPlugin (only onLoad matters) ----
    @Override
    public void onLoad(String mixinPackage) {
        this.mixinPackage = mixinPackage;
        if (ON || LENIENT) Mixins.registerErrorHandlerClass(ConflictReport.class.getName());
    }

    private String mixinPackage = "";

    /** Compat mixins whose target belongs to an optional Fabric mod: added only when that mod is installed (a listed
     *  mixin with a missing target logs an ERROR and would show in alloy-report.txt). key = mod id, value = client mixins. */
    private static final java.util.Map<String, List<String>> OPTIONAL_CLIENT = java.util.Map.of(
            "sodium", List.of("sodiumforge.SodiumForgeModelCompat"));
    @Override public String getRefMapperConfig() { return null; }
    /** compat.* mixins bridge Forge code to Fabric API events: apply them only when Fabric API is installed. */
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // late Fabric key registration: only when the Fabric entrypoints run after Forge's (-Dalloy.fabricAfterForge)
        if (mixinClassName.endsWith(".LateFabricKeys120"))
            return ForgeLaunchPlugins.FABRIC_AFTER_FORGE && classExists(targetClassName);
        if (mixinClassName.startsWith("alloy.mixin.compat.") || mixinClassName.startsWith("alloy.compat.")
                || mixinClassName.startsWith("alloy.compat120.")) {
            if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("fabric-api-base")) return false;
            // only some Fabric API modules may be installed (nested in a Fabric mod): skip a compat mixin whose module is missing
            String missing = FabricApiRefs.firstMissing(mixinClassName, ConflictReport::node, ConflictReport::classExists);
            if (missing != null) Bridge.LOG.info("Alloy: compat mixin {} skipped, its Fabric API module is not installed ({})", mixinClassName, missing);
            return missing == null;
        }
        if (SCREEN_SETTER_MIXINS.contains(mixinClassName))   // listed for Minecraft (26.1) and Gui (26.2): apply where setScreen is
            return hasSetScreen(targetClassName);
        // A pack without Fabric API: a mixin on a Fabric API class fails to prepare, and Mixin then drops the WHOLE
        // config, which took the crash-report, report-screen and screen-log mixins with it. Skip just that mixin.
        if (targetClassName.startsWith("net.fabricmc.fabric.")) return classExists(targetClassName);
        return true;
    }

    static ClassNode node(String internalName) {
        try { return org.spongepowered.asm.service.MixinService.getService().getBytecodeProvider().getClassNode(internalName, false); }
        catch (Throwable t) { return null; }
    }

    private static final Map<String, Boolean> EXISTS = new java.util.concurrent.ConcurrentHashMap<>();
    static boolean classExists(String cls) {
        return EXISTS.computeIfAbsent(cls, c -> {
            try { return org.spongepowered.asm.service.MixinService.getService().getBytecodeProvider().getClassNode(c.replace('.', '/'), false) != null; }
            catch (Throwable t) { return false; }
        });
    }

    /** Mixins on setScreen(Screen): Minecraft owns it up to 26.1, Gui from 26.2. They target both; only one has the method. */
    static final Set<String> SCREEN_SETTER_MIXINS = Set.of("alloy.mixin.ScreenLogMixin", "alloy.mixin.ReportScreenMixin");
    private static final Map<String, Boolean> SET_SCREEN = new java.util.concurrent.ConcurrentHashMap<>();
    static boolean hasSetScreen(String cls) {
        return SET_SCREEN.computeIfAbsent(cls, c -> {
            try {   // read the class bytes only (never load a Minecraft class during Mixin startup)
                ClassNode cn = org.spongepowered.asm.service.MixinService.getService().getBytecodeProvider().getClassNode(c.replace('.', '/'), false);
                for (var m : cn.methods) if (m.name.equals("setScreen") && m.desc.equals("(Lnet/minecraft/client/gui/screens/Screen;)V")) return true;
            } catch (Throwable ignored) { }
            return false;
        });
    }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() {
        if (!"alloy.compat".equals(mixinPackage)) return null;
        var fl = net.fabricmc.loader.api.FabricLoader.getInstance();
        if (fl.getEnvironmentType() != net.fabricmc.api.EnvType.CLIENT || !fl.isModLoaded("fabric-api-base")) return null;
        List<String> out = new java.util.ArrayList<>();
        OPTIONAL_CLIENT.forEach((mod, mixins) -> { if (fl.isModLoaded(mod)) out.addAll(mixins); });
        return out.isEmpty() ? null : out;
    }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
