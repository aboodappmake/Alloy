package alloy.installer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.Manifest;
import java.util.regex.*;
import java.util.zip.*;

/**
 * alloy-prep: brings a game dir in line with the Forge mods in its forgemods/ folder (plus the Forge mods a launcher put
 * in mods/, which {@link ForgeInMods} first mirrors into forgemods/). Runs from the installer jar
 * (--prep GAMEDIR), before every launch (run script / Prism PreLaunchCommand), and from the installer itself.
 *
 * Needs GAMEDIR/.alloy/prep.properties (written by the installer):
 *   side, vanilla, forge, universal, transformers (';' list), gameJar, resources (optional)  - paths relative to GAMEDIR
 *
 * When the hash of forgemods/ (names, sizes, mtimes) changed it
 *   1. re-runs PrepGame (access transformers of the mods applied to the Forge-patched game jar),
 *   2. re-extracts the jar-in-jar libraries to forgemods/.nested,
 *   3. rewrites mods/alloy-modmixins.jar (mixin configs of the Forge mods + the classpath language adapter),
 *   4. rewrites alloy-inject.txt (from mods/*.inject.txt),
 * and records the hash in .alloy/prep.stamp.
 */
final class Prep {
    private Prep() { }

    /** thrown when the Mod Doctor found problems that stop the game from starting (exit code 2). */
    static final class Blocked extends Exception {
        Blocked(String m) { super(m); }
    }

    /**
     * Fabric instance mode: keeps .alloy/alloy-agent.jar equal to this installer's copy. When the agent
     * started this prep it holds its jar open (Windows refuses the replace); then the copy is updated on a later start.
     */
    static void refreshAgent(Path game) {
        Path marker = game.resolve(".alloy").resolve("fabric-instance.properties");
        if (!Files.isRegularFile(marker) && !Files.isRegularFile(game.resolve(LauncherExport.AGENT_MARKER))) return;   // also the official launcher profile
        Path agent = game.resolve(".alloy").resolve("alloy-agent.jar");
        try {
            byte[] want = Util.resource("/alloy-agent.jar");
            if (Files.isRegularFile(agent) && sameClasses(agent, want)) return;   // also the full agent (it carries the installer)
            Path tmp = agent.resolveSibling("alloy-agent.jar.tmp");
            Files.write(tmp, want);
            try {
                Files.move(tmp, agent, StandardCopyOption.REPLACE_EXISTING);
                Util.log("alloy-prep: Fabric instance mode, .alloy/alloy-agent.jar written");
            } catch (IOException inUse) {
                Files.deleteIfExists(tmp);
                Util.log("alloy-prep: .alloy/alloy-agent.jar is in use; the new copy is written on a later start");
            }
        } catch (IOException e) {
            Util.log("alloy-prep: no agent jar in this installer (" + e.getMessage() + ")");
        }
    }

    /** true when the jar file has the same .class entries (names and bytes) as the jar bytes {@code want}. */
    static boolean sameClasses(Path jar, byte[] want) throws IOException {
        return classes(Files.readAllBytes(jar)).equals(classes(want));
    }

    private static Map<String, String> classes(byte[] jar) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; )
                if (e.getName().endsWith(".class")) out.put(e.getName(), Util.sha1(z.readAllBytes()));
        }
        return out;
    }

    /** Classes of the generated alloy-modmixins.jar: the forgemods language adapter and Fabric instance mode (FabricInstance, ArgFile). */
    static final String[] SHIM_CLASSES = {"alloy/shim/ForgeModsAdapter.class", "alloy/shim/FabricInstance.class", "alloy/fabinst/ArgFile.class"};

    /** true when the jar holds this installer's copy of every SHIM_CLASSES entry (false also for a missing or unreadable jar). */
    static boolean shimCurrent(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (String c : SHIM_CLASSES) {
                ZipEntry e = z.getEntry(c);
                if (e == null) return false;
                try (InputStream in = z.getInputStream(e)) { if (!Arrays.equals(in.readAllBytes(), Util.resource("/" + c))) return false; }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static void run(Path game, boolean force) throws Exception {
        game = game.toAbsolutePath().normalize();
        Installer.selectFor(game);   // the Minecraft version this game dir was installed for (prep.properties)
        Installer.portableArgfiles(game.resolve(".alloy"));   // a folder moved between Windows and Linux
        refreshAgent(game);   // Fabric instance mode only (.alloy/fabric-instance.properties)
        C2meGuard.apply(game);   // every launch, before the fast path: C2ME's async chunk saving off (config/c2me.toml)
        LithiumGuard.apply(game, Installer.MC);   // every launch on 1.20.x: Lithium options that skip Forge hooks off (config/lithium.properties)
        // Fast path: every launch re-ran the Mod Doctor (reads every jar incl. jar-in-jar), the stamp check, Fit, Collide and the
        // last-good lock (SHA-1/SHA-512 of every jar), ~0.6-1.0 s. If nothing the run looks at changed since the last complete,
        // all-unchanged run (jar names/sizes/mtimes, installer jar, config), replay that run's output and skip the work.
        Path fastFile = game.resolve(".alloy/prep.fast");
        String k0 = force ? null : fastKey(game);
        if (k0 != null && Files.exists(fastFile) && Files.exists(game.resolve(".alloy/last-good.lock.json"))) {
            try {
                byte[] b = Files.readAllBytes(fastFile);
                int nl = -1;
                for (int i = 0; i < b.length && i < 200; i++) if (b[i] == '\n') { nl = i; break; }
                if (nl > 0 && new String(b, 0, nl, StandardCharsets.US_ASCII).equals(k0)) {
                    System.out.write(b, nl + 1, b.length - nl - 1);
                    System.out.flush();
                    return;
                }
            } catch (IOException e) { /* slow path */ }
        }
        java.io.PrintStream realOut = System.out;
        java.nio.charset.Charset cs = outCharset();
        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(new OutputStream() {
            @Override public void write(int b) throws IOException { realOut.write(b); cap.write(b); }
            @Override public void write(byte[] b, int o, int l) throws IOException { realOut.write(b, o, l); cap.write(b, o, l); }
            @Override public void flush() { realOut.flush(); }
        }, true, cs));
        StatCache.open(game);
        try {
            runFull(game, force);
        } finally {
            StatCache.close();
            System.out.flush();
            System.setOut(realOut);
        }
        if (force) return;
        try {   // remember the run only when it found nothing to do
            String out = cap.toString(cs);
            String k1 = fastKey(game);
            if (k1 != null && out.contains("alloy-prep: ") && out.contains(" Forge mod(s), unchanged") && out.contains("alloy-fit: nothing to fit")
                    && out.contains("alloy-collide: unchanged") && Files.exists(game.resolve(".alloy/last-good.lock.json"))) {
                ByteArrayOutputStream f = new ByteArrayOutputStream();
                f.write((k1 + "\n").getBytes(StandardCharsets.US_ASCII));
                cap.writeTo(f);
                Files.write(fastFile, f.toByteArray());
            } else Files.deleteIfExists(fastFile);
        } catch (Exception e) { /* fast path is optional */ }
    }

    /** the console's charset (PrintStream.charset() needs Java 18; the installer runs on 17 for Minecraft 1.20.1) */
    static java.nio.charset.Charset outCharset() {
        try { return java.nio.charset.Charset.forName(System.getProperty("stdout.encoding", System.getProperty("sun.stdout.encoding", ""))); }
        catch (Exception e) { return java.nio.charset.Charset.defaultCharset(); }
    }

    /** Fingerprint of everything a no-change run reads: null when something is missing (then the full run decides). */
    private static String fastKey(Path game) {
        try {
            Path work = game.resolve(".alloy"), cfgFile = work.resolve("prep.properties");
            if (!Files.exists(cfgFile)) return null;
            Properties cfg = new Properties();
            try (Reader r = Files.newBufferedReader(cfgFile)) { cfg.load(r); }
            StringBuilder sb = new StringBuilder("f1|").append(System.getProperty("java.version")).append('|').append(Installer.FV).append('\n');
            sb.append("self|").append(stat(Util.selfJar())).append('\n').append("cfg|").append(stat(cfgFile)).append('\n');
            Path sides = game.resolve(ForgeInMods.CONFIG);
            if (Files.exists(sides)) sb.append("sides|").append(stat(sides)).append('\n');
            for (String k : List.of("vanilla", "forge", "universal", "gameJar", "resources")) {
                String v = cfg.getProperty(k);
                if (v == null || v.isBlank()) { if (k.equals("resources")) continue; return null; }
                Path p = game.resolve(v).normalize();
                if (!Files.exists(p)) return null;
                sb.append(k).append('|').append(stat(p)).append('\n');
            }
            for (String f : List.of("fit-state.properties", "collide.properties", "last-good.lock.json")) {
                Path p = work.resolve(f);
                sb.append(f).append('|').append(Files.exists(p) ? stat(p) : "-").append('\n');
            }
            Path mods = game.resolve("mods"), fm = game.resolve("forgemods");
            if (!Files.exists(mods.resolve("alloy-modmixins.jar"))) return null;
            listing(sb, "M", mods, ".jar");
            listing(sb, "I", mods, ".inject.txt");
            listing(sb, "J", work.resolve("fit"), ".inject.txt");
            listing(sb, "F", fm, ".jar");
            listing(sb, "N", fm.resolve(".nested"), ".jar");
            listing(sb, "O", work.resolve("originals/forgemods"), ".jar");
            // the Mod Doctor report names the last failed start: a new crash report (or a failed logs/latest.log) must not
            // be hidden behind an older replayed report
            sb.append("crash|").append(alloy.ideas.ModDoctor.lastCrash(game, mods, fm)).append('\n');
            return Util.sha1(sb.toString());
        } catch (Exception e) { return null; }
    }

    private static String stat(Path p) throws IOException {
        return Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis();
    }

    private static void listing(StringBuilder sb, String tag, Path dir, String suffix) throws IOException {
        if (!Files.isDirectory(dir)) { sb.append(tag).append("|-\n"); return; }
        List<String> l = new ArrayList<>();
        try (var s = Files.newDirectoryStream(dir)) {
            for (Path p : s) {
                String n = p.getFileName().toString();
                if (n.endsWith(suffix) && Files.isRegularFile(p)) l.add(n + "|" + stat(p));
            }
        }
        Collections.sort(l);
        for (String x : l) sb.append(tag).append('|').append(x).append('\n');
    }

    private static void runFull(Path game, boolean force) throws Exception {
        Timer tm = new Timer();
        try { ForgeInMods.route(game); }   // Forge mods a launcher put in mods/ -> forgemods/frommods_*.jar
        catch (Exception e) { Util.log("alloy-mods: Forge mods in mods/ not checked (" + e + ")"); }
        Fit.reconcile(game);   // fitted copies are derived from their originals: no second copy after a reinstall / update
        Collide.reconcile(game);
        tm.lap("route+reconcile");
        doctor(game);
        tm.lap("doctor");
        String key = prep(game, force);
        tm.lap("prepgame+nested+shim");
        Fit.run(game, key);   // Fabric mods dropped into mods/: adapt them to the Forge-patched game
        tm.lap("fit");
        Collide.run(game, key);   // Forge-vs-Fabric @Redirect collisions -> @WrapOperation in the forgemods/ copies
        tm.lap("collide");
        try {   // last good lock: what was installed when prep last succeeded (for 'what changed since it worked')
            Path lock = game.resolve(".alloy/last-good.lock.json"), lockKey = game.resolve(".alloy/last-good.lock.key");
            // the lock only depends on the jars in mods/ and forgemods/: when none of them changed since it was written, it
            // would come out the same, byte for byte, so it is not read, hashed and written again
            String lk = lockKey(game);
            if (lk == null || !Files.exists(lock) || !Files.exists(lockKey)
                    || !(lk + "\n" + StatCache.stamp(lock)).equals(Files.readString(lockKey).trim())) {
                Files.deleteIfExists(lockKey);
                java.io.PrintStream old = System.out;
                System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
                try { alloy.ideas.ModLock.cli(new String[]{"lock", game.toString(), "-o", lock.toString()}); }
                finally { System.setOut(old); }
                String after = lockKey(game);
                if (lk != null && lk.equals(after)) Files.writeString(lockKey, lk + "\n" + StatCache.stamp(lock));   // the lock file itself too
            }
        } catch (Exception e) { Util.log("could not write the last-good lock: " + e); }
        tm.lap("lock");
        tm.print();
    }

    /** what the last-good lock is made from: the jars in mods/ and forgemods/ (stamps), the installer and the version. null: no cache */
    private static String lockKey(Path game) {
        if (StatCache.off()) return null;
        try {
            return Util.sha1("lock1|" + StatCache.self() + "|" + alloy.ideas.ModLock.minecraft + "\n" + StatCache.listing("M", game.resolve("mods"), ".jar")
                    + StatCache.listing("F", game.resolve("forgemods"), ".jar"));
        } catch (IOException e) { return null; }
    }

    /** -Dalloy.timing=true: one line with the time of each prep step (to stderr, so the fast-path replay never shows old numbers). */
    static final class Timer {
        final boolean on = Boolean.getBoolean("alloy.timing");
        final StringBuilder sb = new StringBuilder();
        long t0 = System.nanoTime(), last = t0;

        void lap(String what) {
            if (!on) return;
            long now = System.nanoTime();
            sb.append(sb.length() == 0 ? "" : ", ").append(what).append(' ').append(String.format(java.util.Locale.ROOT, "%.2f", (now - last) / 1e9)).append(" s");
            last = now;
        }

        void print() {
            if (on) System.err.println("[alloy] timing: " + sb + String.format(java.util.Locale.ROOT, "; total %.2f s", (System.nanoTime() - t0) / 1e9));
        }
    }

    /** Mod Doctor before anything slow: blockers stop the launch with plain words (exit code 2); warnings only print. */
    static void doctor(Path game) throws Exception {
        Properties cfg = new Properties();
        Path pf = game.resolve(".alloy/prep.properties");
        if (Files.exists(pf)) try (Reader r = Files.newBufferedReader(pf)) { cfg.load(r); }
        int rc;
        String side = cfg.getProperty("side", "client");
        try {
            rc = StatCache.off() ? alloy.ideas.ModDoctor.cli(new String[]{game.toString(), "--side", side})
                    : alloy.ideas.ModDoctor.cli(new String[]{game.toString(), "--side", side, "--cache", game.resolve(".alloy/doctor.cache").toString()});
        }
        catch (Exception e) { Util.log("Mod Doctor skipped: " + e); return; }
        if (rc == 2) throw new Blocked("The mods in this folder will very likely not start the game. Fix the BLOCKER lines above, then start again.");
    }

    private static String prep(Path game, boolean force) throws Exception {
        game = game.toAbsolutePath().normalize();
        Path work = game.resolve(".alloy");
        Path cfgFile = work.resolve("prep.properties");
        if (!Files.exists(cfgFile)) throw new IOException("not an Alloy game dir (missing " + cfgFile + ")");
        Properties cfg = new Properties();
        try (Reader r = Files.newBufferedReader(cfgFile)) { cfg.load(r); }
        Path vanilla = game.resolve(cfg.getProperty("vanilla")).normalize();
        Path forge = game.resolve(cfg.getProperty("forge")).normalize();
        Path universal = game.resolve(cfg.getProperty("universal")).normalize();
        Path gameJar = game.resolve(cfg.getProperty("gameJar")).normalize();
        String res = cfg.getProperty("resources");
        Path resources = res == null || res.isBlank() ? null : game.resolve(res).normalize();
        Path mods = game.resolve("mods"), fmods = game.resolve("forgemods"), nested = fmods.resolve(".nested");
        Files.createDirectories(mods);
        Files.createDirectories(nested);
        Installer in120 = null;
        Path names120 = null;
        if (V120.on()) {   // Minecraft 1.20.1: downloads, Forge install, PrepFml and the name translator (each skipped when done)
            in120 = new Installer(cfg.getProperty("side", "client"), game, game.resolve(cfg.getProperty("cache", ".alloy/cache")));
            if ("1".equals(cfg.getProperty("bootstrap")) && (V120.names(in120) == null || !(Files.exists(vanilla) && Files.exists(forge) && Files.exists(universal)))) {
                Installer.bootstrap(game, cfg);
                force = true;
            }
            in120 = new Installer(cfg.getProperty("side", "client"), game, game.resolve(cfg.getProperty("cache", ".alloy/cache")));
            Installer.fillLibraries(game, cfg);   // Prism instance: Forge libraries, patched loader jars, Mojang-named jars, made here
            names120 = V120.syncNames(in120, game, cfg);
            V120.remapForgeMods(in120, game, names120);   // before the stamp: forgemods/ then holds the Mojang-named copies
            V120.sodium(game);
        } else {
            if ("1".equals(cfg.getProperty("bootstrap")) && !(Files.exists(vanilla) && Files.exists(forge) && Files.exists(universal))) {
                Installer.bootstrap(game, cfg);
                force = true;
            }
            Installer.fillLibraries(game, cfg);   // Prism instance: Forge libraries and patched loader jars, made here, not shipped
        }

        List<Path> forgeJars = Util.jars(fmods);
        StringBuilder sb = new StringBuilder();
        sb.append("v1|").append(Installer.FV).append('|').append(Util.toolsId()).append('\n');
        // shim rules: 2 = Forgified Fabric API copies skipped, 3 = Mixin 0.10 behaviour for Forge configs, 4 = aware list from
        // every nested jar, 5 = the shim depends on alloyloader (the old id "alloy" no longer resolves), 6 = no nested copy of a
        // forgemods/ mod (jarJarPick), 7 = nested copies of one mod in several versions kept again. Older instances redo it once.
        sb.append("N|7\n");
        for (Path p : List.of(vanilla, forge, universal)) sb.append(p.getFileName()).append(Files.size(p)).append('\n');
        for (Path j : forgeJars) sb.append("F|").append(j.getFileName()).append('|').append(V120.on() ? V120.identity(game, j) : Collide.identity(game, j)).append('\n');
        List<Path> sidecars = new ArrayList<>();
        // fit notes: next to the jars in mods/ (older layout) or in .alloy/fit/ (adapt_fabric.py and the installer now put them
        // there, so mods that scan mods/ do not trip over them)
        for (Path dir : List.of(mods, work.resolve("fit")))
            if (Files.isDirectory(dir)) try (var s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".inject.txt")).sorted().forEach(sidecars::add);
            }
        for (Path j : sidecars) sb.append("I|").append(j.getFileName()).append('|').append(Files.size(j)).append('\n');
        sb.append(ForgeInMods.stampLines(game));   // empty without Forge mods in mods/: older stamps stay valid
        List<String[]> stubs = forgeStubs(forgeJars, mods);
        for (String[] st : stubs) sb.append("S|").append(st[0]).append('|').append(st[1]).append('\n');   // only when there are stubs
        Map<String, String> fabricCfgs = fabricMixinConfigs(mods);
        Set<String> twins = new TreeSet<>();
        for (Path j : forgeJars) try (ZipFile z = new ZipFile(j.toFile())) { for (String c : fabricCfgs.keySet()) if (z.getEntry(c) != null) twins.add(c); }
        for (String c : twins) sb.append("T|").append(c).append('\n');   // none without a library on both loaders: older stamps stay valid
        String key = Util.sha1(sb.toString());
        Path stamp = work.resolve("prep.stamp");
        boolean upToDate = !force && Files.exists(stamp) && Files.readString(stamp).trim().equals(key)
                && Files.exists(gameJar) && Files.exists(mods.resolve("alloy-modmixins.jar"));
        if (resources != null && !Files.exists(resources)) upToDate = false;
        if (upToDate && !shimCurrent(mods.resolve("alloy-modmixins.jar"))) {   // a new installer with other adapter classes: only the jar
            Util.log("alloy-prep: " + forgeJars.size() + " Forge mod(s), unchanged; the language adapter changed, alloy-modmixins.jar rewritten");
            writeMods(game, mods, nested, forgeJars, stubs, sidecars, stamp, key);
            return key;
        }
        if (upToDate) { Util.log("alloy-prep: " + forgeJars.size() + " Forge mod(s), unchanged"); return key; }
        Util.log("alloy-prep: " + forgeJars.size() + " Forge mod(s) changed, preparing");

        // 1. PrepGame (not on 1.20.1: Forge 47 applies the mods' access transformers at class load, the game jar is the names one)
        if (!V120.on()) prepGame(game, cfg, work, vanilla, forge, universal, gameJar, resources, forgeJars);
        else Util.log("alloy-prep: Minecraft " + Installer.MC + " game jar from the name translator (" + names120.getFileName() + ")");
        writeMods(game, mods, nested, forgeJars, stubs, sidecars, stamp, key);
        return key;
    }

    private static void prepGame(Path game, Properties cfg, Path work, Path vanilla, Path forge, Path universal, Path gameJar, Path resources,
                                 List<Path> forgeJars) throws Exception {
        List<String> tr = new ArrayList<>();
        for (String t : cfg.getProperty("transformers", "").split(";")) if (!t.isBlank()) tr.add(game.resolve(t).toAbsolutePath().toString());
        List<String> cmd = new ArrayList<>(List.of(Util.ownJava().toString(), "-Xmx1500m", "-XX:ActiveProcessorCount=2"));
        cmd.addAll(Util.JVM_PROPS);
        cmd.add("-Dalloy.transformers=" + String.join(";", tr));
        // built game jars by input (PrepGame.Store): going back to an earlier Forge mod set, or adding a mod without access
        // transformers, reuses a jar instead of rebuilding it. -Dalloy.gamejar.nostore=true turns it off.
        if (!Boolean.getBoolean("alloy.gamejar.nostore")) cmd.add("-Dalloy.gamejar.store=" + work.resolve("gamejars"));
        cmd.addAll(List.of("-cp", Util.selfJar().toString(), "PrepGame", vanilla.toString(), forge.toString(),
                gameJar.toString(), universal.toString(), universal.toString()));
        for (Path j : forgeJars) cmd.add(j.toString());
        Files.createDirectories(gameJar.getParent());
        Object[] r = Util.runCapture(cmd, work);   // not the game dir: log4j would create logs/ there
        String out = (String) r[1];
        String summary = out.lines().filter(l -> l.startsWith("classes ")).findFirst().orElse(null);
        if ((int) r[0] != 0 || summary == null) {
            System.err.println(out.length() > 3000 ? out.substring(out.length() - 3000) : out);
            throw new IOException("PrepGame failed (exit " + r[0] + ")");
        }
        Util.log("PrepGame: " + summary);
        if (resources != null) gameResourcesJar(gameJar, resources);
    }

    private static void writeMods(Path game, Path mods, Path nested, List<Path> forgeJars, List<String[]> stubs, List<Path> sidecars,
                                  Path stamp, String key) throws Exception {
        // 2. jar-in-jar libraries
        try (var s = Files.list(nested)) {
            for (Path old : s.filter(p -> p.toString().endsWith(".jar")).toList()) Files.delete(old);
        }
        List<String> cfgs = new ArrayList<>();
        Map<String, byte[]> found = new LinkedHashMap<>();
        // Forge mods that nest Forgified Fabric API: their nested copies are skipped, and Isolation lets these mods link to
        // the real Fabric API instead (.alloy/fabric-aware.txt)
        List<String> aware = new ArrayList<>();
        Set<String> skipped = new TreeSet<>();
        for (Path j : forgeJars) {
            Set<String> ff = new TreeSet<>();
            try (InputStream in = new BufferedInputStream(Files.newInputStream(j))) {
                scan(in, cfgs, found, ff);
            }
            if (!ff.isEmpty()) { aware.add(j.getFileName().toString()); skipped.addAll(ff); }
        }
        List<String> dropped = jarJarPick(forgeJars, found);
        if (!dropped.isEmpty()) Util.log("alloy-prep: nested copies of mods that are in forgemods/ already are left out (as Forge's JarJar does): " + dropped);
        for (var e : found.entrySet()) Files.write(nested.resolve(e.getKey()), e.getValue());
        Path awareFile = game.resolve(".alloy").resolve("fabric-aware.txt");
        if (!aware.isEmpty()) {
            Files.createDirectories(awareFile.getParent());
            Files.writeString(awareFile, String.join(System.lineSeparator(), aware) + System.lineSeparator());
            Util.log("alloy-prep: " + aware.size() + " Forge mod(s) nest Forgified Fabric API, the real Fabric API is used instead: "
                    + aware + " (skipped " + skipped + ")");
        } else Files.deleteIfExists(awareFile);

        // 3. shim mod. A library installed for both loaders (GeckoLib Forge + GeckoLib Fabric) ships one mixin config name
        // twice; both jars are on Knot's class path, so the name resolves to one file anyway, and declaring it again
        // stops Fabric Loader ("Non-unique Mixin config name"). The Fabric mod's declaration is kept.
        Map<String, String> fabricCfgs = fabricMixinConfigs(mods);
        Set<String> sorted = new TreeSet<>(cfgs);
        for (Iterator<String> it = sorted.iterator(); it.hasNext(); ) {
            String c = it.next();
            if (fabricCfgs.containsKey(c)) { Util.log("alloy-prep: mixin config " + c + " is declared by Fabric " + fabricCfgs.get(c) + "; the Forge copy is not declared again"); it.remove(); }
        }
        List<Object> mixins = new ArrayList<>();
        mixins.add("alloy_early.mixins.json");
        mixins.addAll(sorted);
        Map<String, Object> early = new LinkedHashMap<>();
        early.put("required", true);
        early.put("minVersion", "0.8");
        early.put("package", "alloy.early");
        early.put("plugin", "alloy.EarlyInit");
        early.put("compatibilityLevel", V120.on() ? "JAVA_17" : "JAVA_21");   // 1.20.1 may run on Java 17
        early.put("mixins", new ArrayList<>());
        Map<String, Object> fmj = new LinkedHashMap<>();
        fmj.put("schemaVersion", 1);
        fmj.put("id", "alloy_modmixins");
        fmj.put("version", "0.0.1");
        fmj.put("name", "Alloy: Forge mod mixin configs");
        fmj.put("environment", "*");
        fmj.put("description", "Generated. Puts forgemods/ on Knot's classpath and declares the mixin configs of the installed Forge mods.");
        fmj.put("languageAdapters", Map.of("alloy_forgemods", "alloy.shim.ForgeModsAdapter"));
        fmj.put("mixins", mixins);
        // Fabric Loader picks each config's Mixin behaviour from the mod's fabricloader dependency; without one it is
        // Mixin 0.9.2's, whose local capture differs from the Mixin 0.8.5+ the Forge mods were built for (Survive's
        // thirst bar: "LVT in Gui::renderPlayerHealth has incompatible changes", MMC4). 0.12.0 maps to the 0.10 behaviour.
        fmj.put("depends", Map.of("alloyloader", "*", "fabricloader", ">=0.12.0"));
        if (!stubs.isEmpty()) {
            List<Object> jars = new ArrayList<>();
            for (String[] st : stubs) jars.add(Map.of("file", "META-INF/jars/alloy-stub-" + st[0] + ".jar"));
            fmj.put("jars", jars);
        }
        Path shim = mods.resolve("alloy-modmixins.jar");
        Path tmp = mods.resolve("alloy-modmixins.jar.tmp");
        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            put(z, "fabric.mod.json", Json.write(fmj).getBytes(StandardCharsets.UTF_8));
            put(z, "alloy_early.mixins.json", Json.write(early).getBytes(StandardCharsets.UTF_8));
            for (String c : SHIM_CLASSES) put(z, c, Util.resource("/" + c));
            for (String[] st : stubs) put(z, "META-INF/jars/alloy-stub-" + st[0] + ".jar", stubJar(st));
        }
        if (!stubs.isEmpty()) Util.log("forge stubs for Fabric dependencies: " + stubs.stream().map(st -> st[0] + " " + st[1]).toList());
        List<String> twins = stubs.stream().filter(st -> st.length > 3 && st[3].equals("twin")).map(st -> st[0]).toList();
        Path twinsFile = game.resolve(".alloy").resolve("twins-both.txt");
        if (!twins.isEmpty()) {
            Files.createDirectories(twinsFile.getParent());
            Files.writeString(twinsFile, String.join(System.lineSeparator(), twins) + System.lineSeparator());
            Util.log("alloy-prep: Fabric mods nest older copies of " + twins + "; the newer Forge copies are used for both sides");
        } else Files.deleteIfExists(twinsFile);
        Files.move(tmp, shim, StandardCopyOption.REPLACE_EXISTING);

        // 4. inject list
        Set<String> inject = new TreeSet<>();
        for (Path j : sidecars) for (String l : Files.readAllLines(j)) if (!l.isBlank()) inject.add(l);
        Files.writeString(game.resolve("alloy-inject.txt"), inject.isEmpty() ? "" : String.join("\n", inject) + "\n");

        Util.log("mixin configs declared: " + sorted);
        Util.log("forge mods: " + forgeJars.stream().map(p -> p.getFileName().toString()).toList() + ", nested: " + found.keySet());
        Files.writeString(stamp, key);
    }

    // ---- Fabric stubs for Forge-only mod ids ---------------------------------------------------------------------------
    // Fabric mods depend on ids like "terrablender" or "jei". When that mod is installed only as a Forge mod, Fabric Loader
    // would refuse to start. alloy-modmixins.jar then carries a class-less jar-in-jar Fabric mod with the Forge mod's id and
    // version ("provides" is not enough: a provided id takes the host's version and version ranges fail).
    static final List<String> FORGE_STUB_IDS = alloy.ideas.ModDoctor.FORGE_STUB_IDS;
    private static final Pattern TOML_KV = Pattern.compile("^(modId|version)\\s*=\\s*[\"']([^\"']*)[\"']");

    /**
     * Library twins nested in a Fabric mod: Counter's Terrain Slabs nests MidnightLib 1.4.1 (Fabric) and VORTEX also has
     * MidnightLib 1.9.2 in forgemods/. Both copies define eu.midnightdust classes on one class path, the old Fabric one won
     * and the Forge MidnightLib failed (NoSuchFieldError configInstances). Like Forge's JarJar under Connector, the newer
     * Forge copy is used: a stub with its id and version outranks the nested Fabric jar in Fabric Loader's pick, and the
     * id goes to .alloy/twins-both.txt so Isolation lets the Fabric mods link to the Forge copy.
     */
    static Map<String, String> nestedFabricMods(Path mods, Set<String> topLevel) throws IOException {
        Map<String, String> out = new HashMap<>();
        for (Path j : Util.jars(mods)) {
            if (j.getFileName().toString().startsWith("alloy-")) continue;
            try (ZipFile z = new ZipFile(j.toFile())) {
                for (var en = z.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    if (!e.getName().startsWith("META-INF/jars/") || !e.getName().endsWith(".jar")) continue;
                    try (ZipInputStream n = new ZipInputStream(z.getInputStream(e))) {
                        for (ZipEntry ne; (ne = n.getNextEntry()) != null; ) {
                            if (!ne.getName().equals("fabric.mod.json")) continue;
                            Map<String, Object> fm = Json.obj(Json.parse(new String(n.readAllBytes(), StandardCharsets.UTF_8)));
                            if (fm != null && fm.get("id") instanceof String id && fm.get("version") instanceof String v && !topLevel.contains(id))
                                out.merge(id, v, (x, y) -> compareVersions(x, y) >= 0 ? x : y);
                            break;
                        }
                    } catch (RuntimeException ignored) { }
                }
            } catch (IOException ignored) { }
        }
        return out;
    }

    /** {id, version, Forge file name, "twin" or ""} for each FORGE_STUB_IDS mod, or Forge copy of a library a Fabric mod
     *  nests in an older version (nestedFabricMods), that exists in forgemods/ but not as a Fabric mod in mods/. */
    static List<String[]> forgeStubs(List<Path> forgeJars, Path mods) throws IOException {
        Set<String> fabric = new HashSet<>();
        for (Path j : Util.jars(mods)) {
            if (j.getFileName().toString().startsWith("alloy-")) continue;
            fabric.addAll(ForgeInMods.classify(j, Map.of()).fabricIds());
        }
        Map<String, String> nestedFabric = nestedFabricMods(mods, fabric);
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Path j : forgeJars) {
            try (ZipFile z = new ZipFile(j.toFile())) {
                ZipEntry e = z.getEntry("META-INF/mods.toml");
                if (e == null) continue;
                String toml;
                try (InputStream in = z.getInputStream(e)) { toml = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
                String jarVer = "0.0.0";
                ZipEntry m = z.getEntry("META-INF/MANIFEST.MF");
                if (m != null) try (InputStream in = z.getInputStream(m)) {
                    String v = new Manifest(in).getMainAttributes().getValue("Implementation-Version");
                    if (v != null) jarVer = v;
                }
                boolean table = false;
                String id = null;
                Map<String, String> vers = new LinkedHashMap<>();
                for (String raw : toml.split("\\R")) {
                    String l = raw.contains("#") ? raw.substring(0, raw.indexOf('#')).strip() : raw.strip();
                    if (l.startsWith("[")) { table = l.replace(" ", "").startsWith("[[mods]]"); id = null; continue; }
                    Matcher kv = TOML_KV.matcher(l);
                    if (!table || !kv.find()) continue;
                    if (kv.group(1).equals("modId")) { id = kv.group(2); vers.putIfAbsent(id, jarVer); }
                    else if (id != null) vers.put(id, kv.group(2).contains("${") ? jarVer : kv.group(2));
                }
                for (var v : vers.entrySet()) {
                    if (fabric.contains(v.getKey())) continue;
                    String nv = nestedFabric.get(v.getKey());
                    boolean twin = nv != null && compareVersions(v.getValue(), nv) >= 0;
                    if ((FORGE_STUB_IDS.contains(v.getKey()) || twin) && seen.add(v.getKey()))
                        out.add(new String[]{v.getKey(), v.getValue(), j.getFileName().toString(), twin ? "twin" : ""});
                }
            } catch (IOException ignored) { }
        }
        String[] sod = embeddiumSodiumStub(forgeJars, mods, fabric);
        if (sod != null && seen.add("sodium")) out.add(sod);
        for (var e : nestedFabric.entrySet())
            if (PLATFORM_LIBS.contains(e.getKey()) && seen.add(e.getKey()))
                out.add(new String[]{e.getKey(), PLATFORM_STUB_VERSION, "Forge's own " + e.getKey() + " library", ""});
        return out;
    }

    /**
     * Forge's loader libraries nested in a Fabric mod: CreativeCore (Fabric, 1.20.1) nests eventbus 6.2.33, which Loom wraps
     * as the Fabric mod net_minecraftforge_eventbus. Knot then loaded that copy, which PrepFml never rewrote: its
     * ModLauncherService (an ILaunchPluginService) found no event bus engine and threw for every class, so Forge's other
     * launch plugins (the access transformer) were skipped too and Bootstrap stopped with IllegalAccessError
     * (ExtraCodecs$EitherCodec from HolderSetCodec, reg05 dry boot). Forge's own copy is always on the class path, so a stub
     * with a higher version takes the nested copy's place, as Forge's JarJar would.
     */
    static final Set<String> PLATFORM_LIBS = Set.of("net_minecraftforge_eventbus", "net_minecraftforge_coremods",
            "net_minecraftforge_accesstransformers", "net_minecraftforge_forgespi", "net_minecraftforge_unsafe",
            "cpw_mods_modlauncher", "cpw_mods_securejarhandler", "cpw_mods_bootstraplauncher");
    static final String PLATFORM_STUB_VERSION = "999.0.0";

    /**
     * Fabric Sodium add-ons declare {@code depends: sodium}; Embeddium never declares that id, so Fabric Loader refused
     * them. When Embeddium is in forgemods/, Fabric Sodium is not installed, and every Fabric mod that needs sodium is one
     * Alloy tested on Embeddium (ModDoctor.EMBEDDIUM_SODIUM_ADDONS), a sodium stub with Embeddium's Sodium base version
     * stands in. Any other Sodium add-on keeps the stub away (its mixins target Sodium code Embeddium changed) and Mod
     * Doctor names it.
     */
    static String[] embeddiumSodiumStub(List<Path> forgeJars, Path mods, Set<String> fabricIds) throws IOException {
        if (fabricIds.contains("sodium")) return null;
        String jar = null, ver = null;
        for (Path j : forgeJars) {
            String v = modVersions(Files.readAllBytes(j)).get("embeddium");
            if (v != null) { jar = j.getFileName().toString(); ver = v; break; }
        }
        if (jar == null) return null;
        Set<String> needs = new TreeSet<>();
        for (Path j : Util.jars(mods)) {
            if (j.getFileName().toString().startsWith("alloy-")) continue;
            for (alloy.ideas.ModMeta.Mod m : alloy.ideas.ModMeta.read(j).mods)
                if (m.loader == alloy.ideas.ModMeta.Loader.FABRIC && m.deps.stream().anyMatch(d -> d.id().equals("sodium") && d.mandatory() && !d.incompatible()))
                    needs.add(m.id);
        }
        if (!alloy.ideas.ModDoctor.embeddiumStandsInForSodium(needs)) {
            if (!needs.isEmpty()) Util.log("no sodium stub for Embeddium: " + needs + " not all tested on Embeddium " + alloy.ideas.ModDoctor.EMBEDDIUM_SODIUM_ADDONS);
            return null;
        }
        return new String[]{"sodium", alloy.ideas.ModDoctor.embeddiumSodiumVersion(ver), jar, ""};
    }

    /**
     * Forge's JarJar never loads a nested copy of a mod that is installed in forgemods/ itself. All nested jars used to go
     * on the class path, so an old nested copy could shadow the real one (Aileron nests MidnightLib 1.4.2, VORTEX also has
     * MidnightLib 1.9.2: NoSuchFieldError configInstances). Removes those copies from {@code found} and returns
     * "file (reason)" for the log. Nested copies of one mod in several versions stay: FML picks among them by the
     * version ranges the mods ask for (not always the highest: VORTEX gets Flywheel 1.0.5, not 1.0.6-beta), and a pick
     * made here could disagree with it. Jars without a mods.toml are kept.
     */
    static List<String> jarJarPick(List<Path> forgeJars, Map<String, byte[]> found) throws IOException {
        Set<String> topIds = new HashSet<>();
        for (Path j : forgeJars) topIds.addAll(modVersions(Files.readAllBytes(j)).keySet());
        List<String> out = new ArrayList<>();
        for (var e : new ArrayList<>(found.entrySet())) {
            Map<String, String> mv = modVersions(e.getValue());
            if (mv.isEmpty()) continue;
            String id = mv.keySet().iterator().next();
            if (topIds.contains(id)) { found.remove(e.getKey()); out.add(e.getKey() + " (" + id + " is in forgemods/)"); }
        }
        return out;
    }

    /** mod id -> version of each [[mods]] entry in a Forge jar's mods.toml (${file.jarVersion} read from the manifest). */
    static Map<String, String> modVersions(byte[] jar) {
        String toml = null, jarVer = "0.0.0";
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.getName().equals("META-INF/mods.toml")) toml = new String(z.readAllBytes(), StandardCharsets.UTF_8);
                else if (e.getName().equals("META-INF/MANIFEST.MF")) {
                    String v = new Manifest(new ByteArrayInputStream(z.readAllBytes())).getMainAttributes().getValue("Implementation-Version");
                    if (v != null) jarVer = v;
                }
            }
        } catch (IOException ex) { return Map.of(); }
        Map<String, String> vers = new LinkedHashMap<>();
        if (toml == null) return vers;
        boolean table = false;
        String id = null;
        for (String raw : toml.split("\\R")) {
            String l = raw.contains("#") ? raw.substring(0, raw.indexOf('#')).strip() : raw.strip();
            if (l.startsWith("[")) { table = l.replace(" ", "").startsWith("[[mods]]"); id = null; continue; }
            Matcher kv = TOML_KV.matcher(l);
            if (!table || !kv.find()) continue;
            if (kv.group(1).equals("modId")) { id = kv.group(2); vers.putIfAbsent(id, jarVer); }
            else if (id != null) vers.put(id, kv.group(2).contains("${") ? jarVer : kv.group(2));
        }
        return vers;
    }

    /** Numeric parts compare as numbers, the rest as text ("1.9.2+1.20.1" > "1.4.2"). */
    static int compareVersions(String a, String b) {
        String[] x = a.split("[.+\\-_]"), y = b.split("[.+\\-_]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0", q = i < y.length ? y[i] : "0";
            int c;
            if (p.matches("\\d{1,9}") && q.matches("\\d{1,9}")) c = Integer.compare(Integer.parseInt(p), Integer.parseInt(q));
            else c = p.compareTo(q);
            if (c != 0) return c;
        }
        return 0;
    }

    static byte[] stubJar(String[] st) throws IOException {
        Map<String, Object> fmj = new LinkedHashMap<>();
        fmj.put("schemaVersion", 1);
        fmj.put("id", st[0]);
        fmj.put("version", st[1]);
        fmj.put("name", st[0] + " (Forge mod, Alloy stub)");
        fmj.put("environment", "*");
        fmj.put("description", "Generated by Alloy. Stands in for the Forge mod " + st[2] + " so Fabric mods that depend on '" + st[0] + "' resolve.");
        fmj.put("custom", Map.of("alloy:forge-stub", st[2], "modmenu", Map.of("badges", List.of("library"))));   // Mod Menu hides libraries by default
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            put(z, "fabric.mod.json", Json.write(fmj).getBytes(StandardCharsets.UTF_8));
        }
        return b.toByteArray();
    }

    /** mixin config names declared in fabric.mod.json by the Fabric mods in mods/ (nested jars too) -> jar file name */
    static Map<String, String> fabricMixinConfigs(Path mods) throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isDirectory(mods)) return out;
        for (Path j : Util.jars(mods)) {
            String n = j.getFileName().toString();
            if (n.equals("alloy-modmixins.jar")) continue;
            try (InputStream in = new BufferedInputStream(Files.newInputStream(j))) { fabricConfigs(in, n, out, 0); }
            catch (IOException e) { /* not a readable jar: Fabric Loader reports it */ }
        }
        return out;
    }

    private static void fabricConfigs(InputStream jar, String owner, Map<String, String> out, int depth) throws IOException {
        List<byte[]> nestedJars = new ArrayList<>();
        try (ZipInputStream z = new ZipInputStream(jar)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                if (n.equals("fabric.mod.json")) {
                    try {
                        Object m = Json.obj(Json.parse(new String(z.readAllBytes(), StandardCharsets.UTF_8))).get("mixins");
                        if (m instanceof List<?> l) for (Object x : l) {
                            Object c = x instanceof Map<?, ?> mm ? mm.get("config") : x;
                            if (c != null) out.putIfAbsent(c.toString(), owner);
                        }
                    } catch (RuntimeException ex) { /* bad fabric.mod.json: Fabric Loader reports it */ }
                } else if (depth < 3 && n.startsWith("META-INF/jars/") && n.endsWith(".jar")) nestedJars.add(z.readAllBytes());
            }
        }
        for (byte[] b : nestedJars) fabricConfigs(new ByteArrayInputStream(b), owner, out, depth + 1);
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(0);
        z.putNextEntry(e);
        z.write(data);
        z.closeEntry();
    }

    /** Resource-only jar with the game jar's version.json for the system class loader (launch.game_resources_jar). */
    static void gameResourcesJar(Path gameJar, Path out) throws IOException {
        byte[] data = null;
        try (ZipFile z = new ZipFile(gameJar.toFile())) {
            ZipEntry e = z.getEntry("version.json");
            if (e != null) try (InputStream in = z.getInputStream(e)) { data = in.readAllBytes(); }
        }
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        try (ZipOutputStream o = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            if (data != null) put(o, "version.json", data);
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
    }

    private static final Pattern PATH = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * Reads one jar: collects its manifest MixinConfigs into cfgs and, recursively, its jar-in-jar libraries
     * (every jarjar metadata path, plus META-INF/jarjar/*.jar) into found (MixinExtras skipped: Fabric Loader has it).
     */
    static void scan(InputStream jar, List<String> cfgs, Map<String, byte[]> found) throws IOException {
        scan(jar, cfgs, found, null);
    }

    /**
     * Jar-in-jar libraries of one Forge mod. Forgified Fabric API modules and Sinytra's Forge build of Fabric Loader
     * (accessories, Snow! Real Magic!, TxniLib, Sodium Options API nest them) are skipped: Alloy runs the real Fabric Loader
     * and Fabric API, and a second copy of net.fabricmc classes on the class path stops the game (loader constraint
     * violation on net.fabricmc.api.EnvType). Their file names go to {@code ffapi} when it is not null.
     */
    static void scan(InputStream jar, List<String> cfgs, Map<String, byte[]> found, Set<String> ffapi) throws IOException {
        Set<String> names = new HashSet<>();
        Map<String, byte[]> jarEntries = new HashMap<>();
        byte[] manifest = null, meta = null;
        try (ZipInputStream z = new ZipInputStream(jar)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                names.add(n);
                if (n.equals("META-INF/MANIFEST.MF")) manifest = z.readAllBytes();
                else if (n.equals("META-INF/jarjar/metadata.json")) meta = z.readAllBytes();
                else if (n.equals(NestedFfapi.REMOVED)) {   // pack import removed them (Modpack.nestedFfapi)
                    byte[] b = z.readAllBytes();
                    if (ffapi != null) for (String l : new String(b, StandardCharsets.UTF_8).split("\\R")) if (!l.isBlank()) ffapi.add(l.trim());
                }
                else if (n.startsWith("META-INF/") && n.endsWith(".jar")) jarEntries.put(n, z.readAllBytes());
            }
        }
        if (manifest != null) cfgs.addAll(manifestMixins(manifest));
        List<String> paths = new ArrayList<>();
        if (meta != null) {
            Matcher m = PATH.matcher(new String(meta, StandardCharsets.UTF_8));
            while (m.find()) paths.add(m.group(1));
        }
        names.stream().filter(n -> n.startsWith("META-INF/jarjar/") && n.endsWith(".jar")).sorted().forEach(paths::add);
        for (String n : new LinkedHashSet<>(paths)) {
            byte[] b = jarEntries.get(n);
            if (b == null) continue;
            String fn = n.substring(n.lastIndexOf('/') + 1);
            if (fn.toLowerCase().startsWith("mixinextras")) continue;
            if (forgifiedFabric(b)) {
                if (ffapi != null) ffapi.add(fn);
                continue;
            }
            if (!found.containsKey(fn)) found.put(fn, b);
            scan(new ByteArrayInputStream(b), cfgs, found, ffapi);
        }
        // on 1.20.1 the forgemods/ copies no longer list their Forgified Fabric API in the jar-in-jar metadata
        // (V120.dropNestedFfapi), but the files are still inside: the mod still needs the real Fabric API (Accessories)
        if (ffapi != null)
            for (var e : jarEntries.entrySet())
                if (!paths.contains(e.getKey()) && forgifiedFabric(e.getValue())) ffapi.add(e.getKey().substring(e.getKey().lastIndexOf('/') + 1));
    }

    /**
     * A jar with net.fabricmc classes and no fabric.mod.json: a Forgified Fabric API module (mods.toml) or Sinytra's Forge
     * build of Fabric Loader (a language provider, no mods.toml). Alloy's real Fabric Loader and Fabric API own those classes.
     */
    static boolean forgifiedFabric(byte[] jar) {
        boolean fmj = false, fabricCls = false;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                if (n.equals("fabric.mod.json")) fmj = true;
                else if (!fabricCls && n.startsWith("net/fabricmc/") && n.endsWith(".class")) fabricCls = true;
            }
        } catch (IOException e) {
            return false;
        }
        return !fmj && fabricCls;
    }

    /**
     * Forge mods that declare a jar-in-jar MixinExtras dependency without shipping the nested jar: real Forge provides
     * MixinExtras itself, Alloy gets it from Fabric, so FML's JarSelector would fail. Writes a patched copy of
     * {@code in} to {@code out} without those dangling MixinExtras entries (other missing entries stay: real Forge
     * fails on them too). Returns false, and writes nothing, when there is nothing to drop.
     */
    @SuppressWarnings("unchecked")
    static boolean stripDanglingMixinExtras(Path in, Path out) throws IOException {
        try (ZipFile z = new ZipFile(in.toFile())) {
            ZipEntry me = z.getEntry("META-INF/jarjar/metadata.json");
            if (me == null) return false;
            Map<String, Object> d;
            try (InputStream is = z.getInputStream(me)) { d = Json.obj(Json.parse(new String(is.readAllBytes(), StandardCharsets.UTF_8))); }
            if (d == null || !(d.get("jars") instanceof List)) return false;
            List<Object> keep = new ArrayList<>();
            boolean dropped = false;
            for (Object o : Json.arr(d.get("jars"))) {
                Map<String, Object> j = Json.obj(o);
                Object id = j.get("identifier"), path = j.get("path");
                String art = id instanceof Map ? String.valueOf(((Map<String, Object>) id).get("artifact")) : "";
                if (art.toLowerCase().contains("mixinextras") && (path == null || z.getEntry(path.toString()) == null)) dropped = true;
                else keep.add(o);
            }
            if (!dropped) return false;
            d.put("jars", keep);
            Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
            try (ZipOutputStream o = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                for (var en = z.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    ZipEntry ne = new ZipEntry(e.getName());
                    ne.setTime(e.getTime());
                    o.putNextEntry(ne);
                    if (e.getName().equals("META-INF/jarjar/metadata.json")) o.write(Json.write(d).getBytes(StandardCharsets.UTF_8));
                    else if (!e.isDirectory()) try (InputStream is = z.getInputStream(e)) { is.transferTo(o); }
                    o.closeEntry();
                }
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            return true;
        }
    }

    static List<String> manifestMixins(byte[] mf) throws IOException {
        String v = new Manifest(new ByteArrayInputStream(mf)).getMainAttributes().getValue("MixinConfigs");
        List<String> out = new ArrayList<>();
        if (v != null) for (String c : v.split(",")) if (!c.isBlank()) out.add(c.trim());
        return out;
    }
}
