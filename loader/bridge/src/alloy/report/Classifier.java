package alloy.report;

import alloy.report.Entry.Severity;
import alloy.report.ModIndex.Ref;
import net.minecraftforge.fml.ModLoadingException;
import net.minecraftforge.fml.ModLoadingStage;
import net.minecraftforge.fml.ModLoadingWarning;
import net.minecraftforge.fml.loading.EarlyLoadingException;
import net.minecraftforge.forgespi.language.IModInfo;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw failures (FML ModLoadingExceptions, Mixin apply errors, FML start failures, any Throwable) into plain-language
 * {@link Entry}s that name the side (Forge/Fabric) and the mod jar. Pure logic: no I/O, no Minecraft classes.
 */
public final class Classifier {
    private Classifier() { }

    /** Known libraries, by package prefix: display name and Modrinth slug (one table, shared with the text rules). */
    private static final String[][] KNOWN_LIBS = LogDiagnosis.KNOWN_LIBS;

    private static final Map<String, String> PRETTY_IDS = Map.of(
            "glitchcore", "GlitchCore", "terrablender", "TerraBlender", "fabric-api", "Fabric API",
            "forge", "Forge", "minecraft", "Minecraft", "architectury", "Architectury API", "geckolib", "GeckoLib",
            "cloth_config", "Cloth Config API", "balm", "Balm", "curios", "Curios API");

    // ------------------------------------------------------------------ FML

    public static Entry fromModLoadingException(ModLoadingException e) {
        String key = e.getI18NMessage() == null ? "" : e.getI18NMessage();
        Object[] c = e.getContext();
        IModInfo info = e.getModInfo();
        Ref mod = refOf(info);
        Throwable cause = e.getCause();
        String stage = "";
        try {
            // ModLoadingException does not expose its stage; the i18n key and cause are enough, stage is read from the message
            String raw = clean(e.formatToString());
            Matcher m = Pattern.compile("during the (\\S+) event phase").matcher(raw);
            if (m.find()) stage = m.group(1).replace('_', ' ');
        } catch (Throwable ignored) { }

        switch (key) {
            case "fml.modloading.missingdependency", "fml.modloading.missingdependency.optional" -> {
                return missingDependency(key, c, info, mod);
            }
            case "fml.modloading.errorduringevent", "fml.modloading.failedtoloadmod", "fml.modloading.failedtoloadmodclass",
                 "fml.modloading.failedtoprocesswork", "fml.modloading.uncaughterror" -> {
                String name = mod != null ? mod.name : info != null ? info.getDisplayName() : "A Forge mod";
                String when = key.endsWith("errorduringevent") ? " during its " + (stage.isEmpty() ? "setup" : stage + " step")
                        : key.endsWith("failedtoloadmodclass") ? " while loading its classes" : key.endsWith("failedtoprocesswork") ? " while processing deferred work" : "";
                Entry inner = cause == null ? null : fromThrowable(cause, "Forge");
                String root = cause == null ? "" : rootSummary(cause);
                Ref culprit = cause == null ? null : ModIndex.get().firstModFrame(cause);
                String detail = name + " crashed" + when + ": " + (inner != null ? inner.detail : root);
                if (culprit != null && mod != null && !culprit.id.equals(mod.id) || culprit != null && mod == null)
                    detail += " (the failing code is in " + culprit.label() + ", " + culprit.side + " jar " + culprit.jar + ")";
                String fix = inner != null && !inner.fix.isEmpty() ? inner.fix : "Update " + name + " to a build made for Minecraft " + mc() + " + Forge " + forge() + ", or remove its jar from forgemods.";
                return new Entry(Severity.ERROR, "Forge", mod == null ? name : mod.label(), jarOf(mod, info),
                        "Forge mod " + name + " failed" + when, detail, fix, techOf(cause));
            }
            case "fml.modloading.cycle" -> {
                return new Entry(Severity.ERROR, "Forge", "", "", "Forge mods depend on each other in a circle", "Mods in the cycle: " + str(c, 0),
                        "Remove one of the mods in the cycle.", key);
            }
            case "fml.modloading.feature.missing" -> {
                String name = info != null ? info.getDisplayName() : "A Forge mod";
                return new Entry(Severity.ERROR, "Forge", mod == null ? name : mod.label(), jarOf(mod, info),
                        "Forge mod " + name + " needs a feature this setup does not provide", clean(safeFormat(e)),
                        "Use a version of the mod that does not need it, or remove it.", key + " " + Arrays.toString(c));
            }
            default -> {
                if (key.startsWith("fml.modloading.brokenfile") || key.equals("fml.modloading.missinglicense") || key.startsWith("fml.language")
                        || key.startsWith("fml.modloading.missing")) {
                    String text = clean(safeFormat(e));
                    String fileName = text.replaceAll(".*[\\\\/]", "");
                    String fix = key.endsWith("fabric") ? "This is a Fabric mod in the Forge folder; move it to the mods folder."
                            : key.endsWith("oldforge") ? "Get a build for Minecraft " + mc() + " / Forge " + forge() + "."
                            : key.startsWith("fml.language") ? "Install the language provider or a mod version that matches this Forge."
                            : "Replace or remove that jar.";
                    return new Entry(Severity.ERROR, "Forge", "", fileName, "Forge cannot use a mod file: " + text.replace('\n', ' '), text, fix, key + " " + Arrays.toString(c));
                }
                String text = clean(safeFormat(e));
                String name = info != null ? info.getDisplayName() : "";
                return new Entry(Severity.ERROR, "Forge", mod == null ? name : mod.label(), jarOf(mod, info),
                        "Forge reported a problem" + (name.isEmpty() ? "" : " with " + name), text.replace('\n', ' '),
                        "", key + " " + Arrays.toString(c) + "\n" + techOf(cause));
            }
        }
    }

    /** fml.modloading.missingdependency[.optional] [depId, modId, range, found] as a plain-language entry. */
    static Entry missingDependency(String key, Object[] c, IModInfo info, Ref mod) {
        String depId = str(c, 0), modId = str(c, 1);
        Object range = c.length > 2 ? c[2] : null;
        Object found = c.length > 3 ? c[3] : null;
        Ref owner = ModIndex.get().forgeMod(modId);
        String modName = owner != null ? owner.name : (info != null ? info.getDisplayName() : modId);
        String dep = pretty(depId);
        String need = prettyRange(range);
        boolean optional = key.endsWith("optional");
        String state = found == null || "null".equals(String.valueOf(found)) ? "not installed" : "installed version is " + found;
        String title = "Forge mod " + modName + (optional ? " only works with " : " needs ") + dep + " " + need + " (" + state + ")";
        String fix = found == null || "null".equals(String.valueOf(found))
                ? "Get " + dep + " for Minecraft " + mc() + " (Forge) from " + link(depId) + " and put it in the forgemods folder."
                : "Replace " + dep + " in the forgemods folder with a version that is " + need + ": " + link(depId);
        return new Entry(optional ? Severity.WARN : Severity.ERROR, "Forge", mod == null ? modName : mod.label(), jarOf(mod, info),
                title, modName + " cannot start because a mod it depends on is missing or has the wrong version.", fix,
                key + " " + Arrays.toString(c));
    }

    public static Entry fromModLoadingWarning(ModLoadingWarning w) {
        IModInfo info = warningPart(w, "modInfo") instanceof IModInfo i ? i : null;
        String i18n = String.valueOf(warningPart(w, "i18nMessage"));
        Ref mod = refOf(info);
        String text;
        try { text = clean(ModLoadingExceptionFormat.format(w)); } catch (Throwable t) { text = i18n; }
        String name = info != null ? info.getDisplayName() : "";
        return new Entry(Severity.WARN, "Forge", mod == null ? name : mod.label(), jarOf(mod, info),
                "Forge warning" + (name.isEmpty() ? "" : " for " + name) + ": " + oneLine(text), text.replace('\n', ' '), "", i18n + " " + warningPart(w, "context"));
    }

    /**
     * A part of a ModLoadingWarning: modInfo, i18nMessage or context. Forge 64 has a record with accessors of these names;
     * Forge 47 has a class with private fields of the same names and no getters.
     */
    static Object warningPart(ModLoadingWarning w, String name) {
        try { return ModLoadingWarning.class.getMethod(name).invoke(w); } catch (Throwable ignored) { }
        try {
            java.lang.reflect.Field f = ModLoadingWarning.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(w);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ versions in the advice texts

    /** "26.1.2" / "1.20.1": the running Minecraft (Fabric's "minecraft" mod); 26.1.2 when there is no loader (self test). */
    static String mc() {
        String v = mcVersion;
        if (v == null) {
            try { v = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString(); }
            catch (Throwable t) { v = "26.1.2"; }
            mcVersion = v;
        }
        return v;
    }

    /** "64.1.x" / "47.4.x": the running Forge major.minor (ForgeVersion); 64.1.x when Forge is not loaded (self test). */
    static String forge() {
        String v = forgeVersion;
        if (v == null) {
            try {
                String full = String.valueOf(Class.forName("net.minecraftforge.versions.forge.ForgeVersion").getMethod("getVersion").invoke(null));
                String[] p = full.split("\\.");
                v = p.length >= 2 ? p[0] + "." + p[1] + ".x" : full;
            } catch (Throwable t) { v = "64.1.x"; }
            forgeVersion = v;
        }
        return v;
    }

    /** "64" / "47" */
    static String forgeMajor() { String f = forge(); int i = f.indexOf('.'); return i < 0 ? f : f.substring(0, i); }

    private static volatile String mcVersion, forgeVersion;

    /** FML's early (discovery/validation) errors: one EarlyLoadingException carries several ExceptionData. */
    public static List<Entry> fromEarly(EarlyLoadingException e) {
        List<Entry> out = new ArrayList<>();
        for (var d : e.getAllData()) {
            IModInfo info = d.getModInfo();
            Ref mod = refOf(info);
            String key = d.getI18message();
            Object[] a = d.getArgs();
            String name = info != null ? info.getDisplayName() : "";
            String text = key + (a == null || a.length == 0 ? "" : " " + Arrays.toString(a));
            if (key != null && key.startsWith("fml.modloading.missingdependency") && a != null && a.length >= 2) {
                out.add(missingDependency(key, a, info, mod)); continue;
            }
            out.add(new Entry(Severity.ERROR, "Forge", mod == null ? name : mod.label(), jarOf(mod, info),
                    "Forge could not load mods" + (name.isEmpty() ? "" : " (" + name + ")") + ": " + oneLine(text),
                    "Forge stopped during mod discovery: " + text, "Check the forgemods folder for broken, duplicate or wrong-version jars.", text));
        }
        if (out.isEmpty()) out.add(fromThrowable(e, "Forge"));
        return out;
    }

    // ------------------------------------------------------------------ Mixin

    private static final Pattern MIXIN_FOR_MOD = Pattern.compile("Mixin apply for mod (\\S+) failed (\\S+?):(\\S+)(?: from mod (\\S+))? -> (\\S+?):");
    private static final Pattern MIXIN_TRANSFORM = Pattern.compile("Mixin transformation of (\\S+) failed");

    /** A Mixin prepare/apply error as seen by ConflictReport (lenient mode: the mixin was skipped). */
    public static Entry fromMixin(String phase, String config, String mixin, String target, Throwable th, boolean lenient) {
        ModIndex idx = ModIndex.get();
        String simple = simple(mixin);
        Ref owner = idx.ownerOf(mixin);
        if (owner == null && config != null) {
            // config names are usually <modid>.mixins.json / <modid>.forge.mixins.json
            String id = config.split("[.-]")[0];
            owner = idx.fabricMod(id) != null ? idx.fabricMod(id) : idx.forgeMod(id);
        }
        Ref tgt = target == null || target.equals("?") ? null : idx.ownerOf(target);
        String tgtPlatform = ModIndex.platformName(target);
        String tgtDesc = tgt != null ? tgt.side + " mod " + tgt.name : tgtPlatform != null ? (tgtPlatform.equals("Minecraft") ? "Minecraft" : tgtPlatform) : "the game";
        String side = owner != null ? owner.side : "Unknown";
        String who = owner != null ? owner.side + " mod " + owner.name : "A mod (" + config + ")";
        String feature = featureOf(simple);
        String why = firstLine(th);
        String forgeNote = tgtPlatform != null && (tgtPlatform.equals("Forge") || tgtPlatform.equals("Minecraft")) && "Fabric".equals(side)
                ? " Forge changes this class, so the Fabric mod's patch no longer fits." : "";
        String title = who + " conflicts with " + tgtDesc + (target == null || target.equals("?") ? "" : " (" + simpleClass(target) + ")")
                + (lenient ? " - feature \"" + feature + "\" disabled" : "");
        String detail = who + " has a mixin (" + simple + ") that could not be applied to " + target + "." + forgeNote
                + (lenient ? " Alloy skipped it so the game can still start; \"" + feature + "\" will not work." : " The game will not start.")
                + " Reason: " + why;
        String fix = owner == null ? "Find the mod that owns " + config + " and update or remove it."
                : "Update " + owner.name + (owner.jar.isEmpty() ? "" : " (" + owner.jar + ")") + " or remove it if the feature matters less than the conflict; "
                + "report it to the author if it is the latest version.";
        return new Entry(lenient ? Severity.WARN : Severity.ERROR, side, owner == null ? config : owner.label(), owner == null ? "" : owner.jar,
                title, detail, fix, phase + " | " + config + " | " + mixin + " -> " + target + " | " + oneLine(String.valueOf(th)));
    }

    // ------------------------------------------------------------------ FML start / generic throwables

    /** Alloy failed to start Forge's FML (Bridge.failure). */
    public static Entry fromStartFailure(Throwable t) {
        Throwable root = root(t);
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof EarlyLoadingException ele) {
                List<Entry> es = fromEarly(ele);
                Entry f = es.get(0);
                return new Entry(Severity.ERROR, f.side, f.mod, f.jar, "Forge failed to start: " + f.title, f.detail, f.fix, techOf(t));
            }
            if (c instanceof ModLoadingException mle) return fromModLoadingException(mle);
        }
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (!c.getClass().getSimpleName().equals("InvalidVersionSpecificationException")) continue;
            String msg = String.valueOf(c.getMessage());
            String spec = msg.contains(": ") ? msg.substring(msg.lastIndexOf(": ") + 2).trim() : msg;
            String jar = forgeModDeclaring(spec);
            String who = jar == null ? "A Forge mod" : "Forge mod " + jar;
            return new Entry(Severity.ERROR, "Forge", jar == null ? "" : jar, jar == null ? "" : jar,
                    who + " has an invalid version range in its mods.toml",
                    (jar == null ? "A mod" : jar) + " declares the version range \"" + spec + "\", which Forge cannot read (" + msg + ")."
                            + " Forge stops before loading any mod. The same jar also fails on plain Forge.",
                    jar == null ? "Find the mod whose META-INF/mods.toml contains \"" + spec + "\" and update or remove it."
                            : "Remove " + jar + " from forgemods/, or get a fixed version; report it to its author.", techOf(t));
        }
        if (root instanceof ClassNotFoundException || root instanceof NoClassDefFoundError) {
            Entry m = missingClass(String.valueOf(root.getMessage()), t);
            String jar = serviceJarUsing(String.valueOf(root.getMessage()));
            if (jar != null) return new Entry(Severity.ERROR, "Forge", jar, jar,
                    "Forge mod " + jar + " was made for a different Forge version",
                    jar + " adds a Forge loader service that needs class " + root.getMessage() + ", which this Forge version does not have."
                            + " The same jar also fails on plain Forge.",
                    "Remove " + jar + " from forgemods/, or get its build for Minecraft " + mc() + " / Forge " + forgeMajor() + ".", techOf(t));
            if (m.detail.contains("Forge") && !m.title.startsWith("Alloy isolation")) return new Entry(Severity.ERROR, "Alloy", "", "alloy-bridge.jar",
                    "Alloy could not start Forge: a Forge file is missing", "Class " + root.getMessage() + " was not found on the class path.",
                    "Re-run the Alloy installer; the Forge libraries it copies are incomplete.", techOf(t));
            return new Entry(Severity.ERROR, "Alloy", m.mod, m.jar, "Alloy could not start Forge: " + m.title, m.detail, m.fix, techOf(t));
        }
        Entry g = fromThrowable(t, "Alloy");
        return new Entry(Severity.ERROR, "Alloy", g.mod, g.jar, "Alloy could not start Forge's mod loader: " + g.title, g.detail,
                g.fix.isEmpty() ? "Re-run the Alloy installer. If it keeps happening, send alloy-report.txt to the Alloy developer." : g.fix, techOf(t));
    }

    /** the forgemods/ jar whose META-INF/mods.toml contains the given text, or null */
    static String forgeModDeclaring(String text) {
        try {
            java.nio.file.Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("forgemods");
            try (var st = java.nio.file.Files.list(dir)) {
                for (java.nio.file.Path p : st.filter(x -> x.toString().endsWith(".jar")).sorted().toList()) {
                    try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(p.toFile())) {
                        var e = z.getEntry("META-INF/mods.toml");
                        if (e == null) continue;
                        String t;
                        try (var in = z.getInputStream(e)) { t = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
                        if (t.contains(text)) return p.getFileName().toString();
                    } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** a service-provider mod jar (Bridge.SERVICE_JARS) whose classes reference the missing class, or null */
    static String serviceJarUsing(String missing) {
        byte[] needle = missing.replace('.', '/').getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (java.nio.file.Path p : alloy.Bridge.SERVICE_JARS) {
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(p.toFile())) {
                for (var en = z.entries(); en.hasMoreElements(); ) {
                    var e = en.nextElement();
                    if (!e.getName().endsWith(".class")) continue;
                    byte[] b;
                    try (var in = z.getInputStream(e)) { b = in.readAllBytes(); }
                    if (indexOf(b, needle) >= 0) return p.getFileName().toString();
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static int indexOf(byte[] h, byte[] n) {
        outer:
        for (int i = 0; i + n.length <= h.length; i++) {
            for (int j = 0; j < n.length; j++) if (h[i + j] != n[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** Anything else: a crash, a mod's exception. */
    public static Entry fromThrowable(Throwable t, String sideHint) {
        if (t == null) return new Entry(Severity.ERROR, sideHint, "", "", "Unknown error", "No exception was recorded.", "", "");
        Throwable root = root(t);
        String all = chainText(t);
        ModIndex idx = ModIndex.get();
        Ref culprit = idx.firstModFrame(t);
        String side = culprit != null ? culprit.side : sideHint == null ? "Unknown" : sideHint;
        String where = culprit == null ? "" : " in " + culprit.side + " mod " + culprit.name + " (" + culprit.jar + ")";
        String msg = String.valueOf(root.getMessage());

        // 1. Mixin failures
        Matcher fm = MIXIN_FOR_MOD.matcher(all);
        if (fm.find()) {
            String target = fm.group(5);
            Entry e = fromMixin("APPLY", fm.group(2), fm.group(3), target, root, false);
            return new Entry(Severity.ERROR, e.side, e.mod, e.jar, e.title, e.detail, e.fix, techOf(t));
        }
        Matcher tm = MIXIN_TRANSFORM.matcher(all);
        if (tm.find() || all.contains("org.spongepowered.asm.mixin") && (root.getClass().getName().contains("Mixin") || root.getClass().getName().contains("Injection"))) {
            String target = tm.reset().find() ? tm.group(1) : "?";
            Entry e = fromMixin("APPLY", "?", "?", target, root, false);
            return new Entry(Severity.ERROR, culprit != null ? culprit.side : "Unknown", culprit != null ? culprit.label() : "", culprit != null ? culprit.jar : "",
                    "A mixin failed to apply to " + simpleClass(target), "A mod's mixin could not be applied to " + target + ". Reason: " + firstLine(root),
                    "Look for a recently added mod that patches the same class; remove it or update it.", techOf(t));
        }
        // 2. Missing class / wrong version
        if (root instanceof NoClassDefFoundError || root instanceof ClassNotFoundException) {
            Entry e = missingClass(msg, t);
            return new Entry(Severity.ERROR, e.side.equals("Unknown") ? side : e.side, culprit != null ? culprit.label() : e.mod, culprit != null ? culprit.jar : e.jar,
                    e.title + where, e.detail, e.fix, techOf(t));
        }
        if (root instanceof NoSuchMethodError || root instanceof NoSuchFieldError || root instanceof AbstractMethodError || root instanceof IncompatibleClassChangeError) {
            String shown = ModIndex.platformName(msg.replace('/', '.')) != null ? ModIndex.platformName(msg.replace('/', '.')) : "another mod";
            return new Entry(Severity.ERROR, side, culprit != null ? culprit.label() : "", culprit != null ? culprit.jar : "",
                    "A mod was built for a different version of " + shown + where, root.getClass().getSimpleName() + ": " + msg
                    + ". The mod calls something that does not exist in the installed version.",
                    "Update the mod and what it depends on so their versions match Minecraft " + mc() + ".", techOf(t));
        }
        if (root instanceof UnsupportedClassVersionError) {
            return new Entry(Severity.ERROR, side, culprit != null ? culprit.label() : "", culprit != null ? culprit.jar : "", "A mod needs a newer Java" + where,
                    msg, "Run the game with Java 25 (the version Alloy is built for).", techOf(t));
        }
        if (root instanceof OutOfMemoryError) {
            return new Entry(Severity.ERROR, "Minecraft", "", "", "Minecraft ran out of memory", msg, "Give the game more RAM in the launcher's Java arguments (for example -Xmx6G).", techOf(t));
        }
        // 3. known mod bugs
        if (msg.contains("Only a single listener found")) {
            return new Entry(Severity.ERROR, side, culprit != null ? culprit.label() : "", culprit != null ? culprit.jar : "",
                    "A mod registers its events in a way Forge " + mc() + " rejects" + where,
                    "Forge's new EventBus refuses a class that has only one listener when it is registered as a whole (\"" + firstLine(root) + "\"). This is a bug in that mod's " + mc() + " build.",
                    "Update the mod (a newer build may fix it), or remove its jar. Report it to the author otherwise.", techOf(t));
        }
        // 4. the text rules (frozen registry, Fabric API cast after a lost patch, known real-loader bugs, ...)
        Entry text = fromDiagnosis(LogDiagnosis.diagnose(traceText(t)), techOf(t));
        if (text != null) return text;
        // 5. generic
        String title = culprit != null ? side + " mod " + culprit.name + " crashed" : "An error occurred";
        String detail = root.getClass().getSimpleName() + ": " + oneLine(msg) + (culprit == null ? "" : ". The failing code belongs to " + culprit.label() + ", jar " + culprit.jar + ".");
        String fix = culprit != null ? "Update or remove " + culprit.name + " (" + culprit.jar + "). If it is up to date, report the bug to its author with alloy-report.txt."
                : "Send alloy-report.txt (and the crash report) to whoever helps you with this setup.";
        return new Entry(Severity.ERROR, side, culprit != null ? culprit.label() : "", culprit != null ? culprit.jar : "", title, detail, fix, techOf(t));
    }

    /** A specific {@link LogDiagnosis} result as an entry (null for the generic kinds). The side comes from the named mod. */
    public static Entry fromDiagnosis(LogDiagnosis.Result r, String technical) {
        if (r == null || !r.specific()) return null;
        String side = "Unknown", mod = "", jar = "";
        try {
            ModIndex idx = ModIndex.get();
            for (String m0 : r.mods()) {
                // "Cherished Worlds (cherishedworlds)" as Forge's crash text names a mod: use the id in brackets
                java.util.regex.Matcher br = Pattern.compile("\\(([\\w.-]+)\\)$").matcher(m0.trim());
                String m = br.find() ? br.group(1) : m0;
                Ref x = idx.byJarName(m);
                if (x == null) x = idx.forgeMod(m);
                if (x == null) x = idx.fabricMod(m);
                if (x != null) { side = x.side; mod = x.label(); jar = x.jar; break; }
            }
        } catch (Throwable ignored) { }
        if (mod.isEmpty() && !r.mods().isEmpty()) mod = r.mods().get(0);
        if (r.kind() == LogDiagnosis.Kind.WRONG_JAVA || r.kind() == LogDiagnosis.Kind.OUT_OF_MEMORY) side = "Minecraft";
        String title = r.line().endsWith(".") ? r.line().substring(0, r.line().length() - 1) : r.line();
        StringBuilder detail = new StringBuilder(r.line());
        for (LogDiagnosis.Hit h : r.also()) detail.append(" Also: ").append(h.text());   // other crash signatures that matched
        return new Entry(Severity.ERROR, side, mod, jar, title, detail.toString(), r.fix(), technical == null ? "" : technical);
    }

    /** The whole stack trace as text (what a crash report shows), for the text rules. */
    static String traceText(Throwable t) {
        try {
            java.io.StringWriter w = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(w));
            String s = w.toString();
            return s.length() > 200_000 ? s.substring(0, 200_000) : s;
        } catch (Throwable x) { return String.valueOf(t); }
    }

    /** Crash report text when no exception object is available: pick out the first mod jar in the trace. */
    public static Entry fromCrashText(String title, String text) {
        Entry d = fromDiagnosis(LogDiagnosis.diagnose(text), "");
        if (d != null) return d;
        Ref r = ModIndex.get().firstJarInText(text);
        String desc = title == null ? "Minecraft crashed" : title;
        return new Entry(Severity.ERROR, r != null ? r.side : "Unknown", r != null ? r.label() : "", r != null ? r.jar : "",
                "Minecraft crashed: " + desc, desc + (r != null ? ". The first mod in the stack trace is " + r.label() + " (" + r.jar + ")." : ""),
                r != null ? "Update or remove " + r.name + ", then try again." : "Open the crash report and send it to whoever helps you with this setup.", "");
    }

    private static Entry missingClass(String msg, Throwable t) {
        String cls = msg.replace('/', '.').trim();
        ModIndex idx = ModIndex.get();
        if (cls.startsWith("alloy.hidden.")) {
            // Isolation renamed a reference to a class the other side owns (alloy.hidden.<side>.<real name>)
            String rest = cls.substring("alloy.hidden.".length());
            int dot = rest.indexOf('.');
            String who = dot > 0 ? rest.substring(0, dot) : "";
            String real = dot > 0 ? rest.substring(dot + 1) : rest;
            Ref o = idx.ownerOf(real);
            String side = who.equals("forge") ? "A Forge mod" : who.equals("fabric") ? "A Fabric mod" : "A mod";
            return new Entry(Severity.ERROR, "Alloy", "", "alloy-bridge.jar", "Alloy isolation hid a class this mod needs",
                    side + " uses class " + real + (o != null ? ", which Alloy counts as part of " + o.label() + " (" + o.jar + ")" : "")
                            + ". Alloy keeps the two loaders' mods apart and hid that class. Nothing is missing: this is an Alloy bug.",
                    "Send alloy-report.txt to the Alloy developer. To play now, start the game with -Dalloy.isolation=warn.", "");
        }
        Ref owner = idx.ownerOf(cls);
        for (String[] lib : KNOWN_LIBS) {
            if (cls.startsWith(lib[0])) {
                String where = lib[2].isEmpty() ? "" : " Get it from https://modrinth.com/mod/" + lib[2] + ".";
                boolean fabric = cls.startsWith("net.fabricmc");
                return new Entry(Severity.ERROR, fabric ? "Fabric" : "Unknown", "", "", lib[1] + " is missing",
                        "A mod needs " + lib[1] + " (class " + cls + " was not found)." + (owner != null ? " It is provided by " + owner.label() + "." : ""),
                        owner != null ? "" : "Install " + lib[1] + " for Minecraft " + mc() + " in the " + (fabric ? "mods" : "right (mods / forgemods)") + " folder." + where, "");
            }
        }
        if (cls.startsWith("net.minecraftforge.") || cls.startsWith("net.minecraft.")) {
            return new Entry(Severity.ERROR, "Unknown", "", "", "A mod uses a game class that does not exist in this version",
                    "Class " + cls + " was not found. The mod was most likely built for a different Minecraft or Forge version.",
                    "Get the build of that mod made for Minecraft " + mc() + ".", "");
        }
        String detail = "Class " + cls + " was not found." + (owner != null ? " It belongs to " + owner.label() + " (" + owner.jar + "), which failed to load before." : "");
        return new Entry(Severity.ERROR, owner != null ? owner.side : "Unknown", owner != null ? owner.label() : "", owner != null ? owner.jar : "",
                "A required library or mod is missing", detail,
                owner != null ? "Check " + owner.name + " for earlier errors in the report." : "A mod's dependency is not installed. Look at the mod's page for its required mods (package " + packageOf(cls) + ").", "");
    }

    // ------------------------------------------------------------------ helpers

    static Ref refOf(IModInfo info) {
        if (info == null) return null;
        try { return ModIndex.get().forgeMod(info.getModId()); } catch (Throwable t) { return null; }
    }

    static String jarOf(Ref r, IModInfo info) {
        if (r != null) return r.jar;
        try { return info.getOwningFile().getFile().getFileName(); } catch (Throwable t) { return ""; }
    }

    static String safeFormat(ModLoadingException e) {
        try { return e.formatToString(); } catch (Throwable t) { return e.getI18NMessage() + " " + Arrays.toString(e.getContext()); }
    }

    /** Formats a warning through FML's own message parser, like ModLoadingException.formatToString does. */
    static final class ModLoadingExceptionFormat {
        static String format(ModLoadingWarning w) { return w.formatToString(); }
    }

    static String clean(String s) { return s == null ? "" : s.replaceAll("§.", "").trim(); }
    static String str(Object[] c, int i) { return c != null && c.length > i && c[i] != null ? String.valueOf(c[i]) : ""; }
    static String oneLine(String s) { return s == null ? "" : s.replaceAll("\\s+", " ").trim(); }
    static String simple(String cls) { int i = Math.max(cls == null ? -1 : cls.lastIndexOf('.'), cls == null ? -1 : cls.lastIndexOf('$')); return cls == null ? "?" : cls.substring(i + 1); }
    static String simpleClass(String cls) { return cls == null ? "?" : cls.substring(cls.lastIndexOf('.') + 1); }
    static String packageOf(String cls) { int i = cls.lastIndexOf('.'); return i < 0 ? cls : cls.substring(0, i); }

    static String pretty(String modId) { return PRETTY_IDS.getOrDefault(modId, modId); }

    static String link(String modId) {
        for (String[] lib : KNOWN_LIBS) if (lib[2].equals(modId)) return "https://modrinth.com/mod/" + modId;
        return "https://modrinth.com/mods?q=" + modId;
    }

    /** "SomeFeatureMixin" / "MixinSomeFeature" -> "some feature". */
    static String featureOf(String simple) {
        String s = simple.replace("Mixin", "").replace("mixin", "");
        s = s.replaceAll("([a-z0-9])([A-Z])", "$1 $2").trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? simple : s;
    }

    static String prettyRange(Object range) {
        String s = range == null ? "" : String.valueOf(range).trim();
        if (s.isEmpty() || s.equals("null") || s.equals("(,)") || s.equals("[,)") || s.equals("any")) return "(any version)";
        Matcher m = Pattern.compile("^([\\[(])\\s*([^,]*?)\\s*,\\s*([^,\\])]*?)\\s*([\\])])$").matcher(s);
        if (m.matches()) {
            String lo = m.group(2), hi = m.group(3);
            boolean loIn = m.group(1).equals("["), hiIn = m.group(4).equals("]");
            if (!lo.isEmpty() && hi.isEmpty()) return lo + (loIn ? "+" : " or newer (not " + lo + " itself)");
            if (lo.isEmpty() && !hi.isEmpty()) return "up to " + hi + (hiIn ? "" : " (not including " + hi + ")");
            if (!lo.isEmpty()) return "between " + lo + " and " + hi + (hiIn ? "" : " (not including " + hi + ")");
        }
        Matcher exact = Pattern.compile("^\\[([^,\\]]+)\\]$").matcher(s);
        if (exact.matches()) return "exactly " + exact.group(1);
        return s;
    }

    static Throwable root(Throwable t) {
        Throwable r = t;
        for (int i = 0; i < 30 && r.getCause() != null && r.getCause() != r; i++) r = r.getCause();
        return r;
    }

    static String firstLine(Throwable t) {
        String m = t == null ? "" : String.valueOf(t.getMessage());
        String l = m.lines().findFirst().orElse(m).trim();
        return (t == null ? "" : t.getClass().getSimpleName() + ": ") + (l.length() > 400 ? l.substring(0, 400) + "..." : l);
    }

    static String rootSummary(Throwable t) { return firstLine(root(t)); }

    static String chainText(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Throwable c = t; c != null && n++ < 15; c = c.getCause() == c ? null : c.getCause()) sb.append(c).append('\n');
        return sb.toString();
    }

    /** Root cause line plus the top frames, for the technical part of the report. */
    static String techOf(Throwable t) {
        if (t == null) return "";
        Throwable r = root(t);
        StringBuilder sb = new StringBuilder("Root cause: ").append(r.getClass().getName()).append(": ").append(oneLine(String.valueOf(r.getMessage())));
        StackTraceElement[] st = r.getStackTrace();
        for (int i = 0; i < Math.min(8, st.length); i++) {
            sb.append("\n  at ").append(st[i]);
            Ref o = ModIndex.get().ownerOf(st[i].getClassName());
            String p = ModIndex.platformName(st[i].getClassName());
            if (o != null) sb.append("   [").append(o.side).append(" mod ").append(o.id).append(", ").append(o.jar).append(']');
            else if (p != null) sb.append("   [").append(p).append(']');
        }
        return sb.toString();
    }
}
