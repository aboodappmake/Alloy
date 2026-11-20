package alloy.installer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.*;

import static alloy.installer.Util.*;

/** The Alloy installer (port of install.py): downloads, Forge install, PrepFml, instance layout, run scripts. */
final class Installer {
    // The Minecraft version this run installs. Defaults to 26.1.2; select() switches to another bundled version
    // (resources /versions/<id>.properties, written by build_installer.py from versions/<id>.json).
    static String MC = "26.1.2";
    static String FV = "26.1.2-64.1.6";
    static String LOADER = "0.20.1";
    static String MIXIN = "0.17.5+mixin.0.8.7";
    static String ASM = "9.10.1";
    static String MODLAUNCHER = "10.2.6", SECUREMODULES = "2.2.24", UNSAFE = "0.9.2";
    static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    static final String FORGE_MAVEN = "https://maven.minecraftforge.net";
    static final String FABRIC_MAVEN = "https://maven.fabricmc.net";
    static final String CENTRAL = "https://repo1.maven.org/maven2";
    static final Pattern FML_JARS = Pattern.compile("net/minecraftforge/(fmlcore|fmlloader|javafmllanguage|lowcodelanguage|mclanguage|modlauncher)/"
            + "|net/minecraftforge/forge/[^/]+/forge-[^/]+-universal\\.jar$");
    // Forge's own Mixin/MixinExtras are left out: Fabric's serve Forge mods too
    static String[] SKIP_SERVER, SKIP_CLIENT;
    static { skipLists(); }

    static void skipLists() {
        SKIP_SERVER = new String[]{"org/spongepowered/mixin/", "io/github/llamalad7/mixinextras-forge/", "forge-" + FV + "-server.jar",
                "net/minecraftforge/bootstrap/", "net/minecraftforge/bootstrap-api/"};
        SKIP_CLIENT = new String[]{"org/spongepowered/mixin/", "io/github/llamalad7/mixinextras-forge/", "forge-" + FV + "-client.jar",
                "forge-" + FV + "-server.jar", "net/minecraftforge/bootstrap/", "net/minecraftforge/bootstrap-api/"};
    }

    /** Minecraft versions this installer jar can install, default first (resource /versions/index.txt). */
    static List<String> versions() {
        try (InputStream in = Installer.class.getResourceAsStream("/versions/index.txt")) {
            if (in == null) return List.of(MC);
            List<String> out = new ArrayList<>();
            for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) if (!l.isBlank() && !l.startsWith("#")) out.add(l.trim());
            return out.isEmpty() ? List.of(MC) : out;
        } catch (IOException e) { return List.of(MC); }
    }

    static boolean supports(String mc) { return mc != null && versions().contains(mc); }

    /** Switch every version value (and the names derived from it) to Minecraft version mc. */
    static synchronized void select(String mc) throws IOException {
        if (mc == null || mc.isBlank()) mc = versions().get(0);
        if (!supports(mc)) throw new IOException("this installer has no Minecraft " + mc + " support (it has: " + String.join(", ", versions()) + ")");
        try (InputStream in = Installer.class.getResourceAsStream("/versions/" + mc + ".properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                MC = p.getProperty("minecraft", mc);
                FV = p.getProperty("forge");
                LOADER = p.getProperty("loader", LOADER);
                MIXIN = p.getProperty("mixin", MIXIN);
                ASM = p.getProperty("asm", ASM);
                MODLAUNCHER = p.getProperty("modlauncher", MODLAUNCHER);
                SECUREMODULES = p.getProperty("securemodules", SECUREMODULES);
                UNSAFE = p.getProperty("unsafe", UNSAFE);
                V120.NATIVE = "mojang".equals(p.getProperty("forgeNames"));   // Forge 50+: Mojang-named game, no MCPConfig
                V120.MCP = p.getProperty("mcp", V120.NATIVE ? "" : V120.MCP);
                V120.FAMILY = p.getProperty("fmlFamily", "fml47");
                V120.EXPAND_FRAMES = "true".equals(p.getProperty("mixinExpandFrames"));   // Forge 47 to 49 (Mixin 0.8.5)
            }
        }
        skipLists();
        PrismExport.INSTANCE_NAME = "Alloy " + MC;
        LauncherExport.ID = "Alloy-" + MC;
        LauncherExport.PROFILE_KEY = "alloy-" + MC;
        alloy.ideas.ModDoctor.defaultMc = MC;
        alloy.ideas.ModDoctor.defaultForge = FV.substring(FV.indexOf('-') + 1);
        alloy.ideas.ModDoctor.defaultFabricLoader = LOADER;
        alloy.ideas.ModLock.minecraft = MC;
    }

    /** The bridge mod built for the selected version (/bridges/<mc>/alloy-bridge.jar), else the default one. */
    static byte[] bridgeJar() throws IOException {
        try (InputStream in = Installer.class.getResourceAsStream("/bridges/" + MC + "/alloy-bridge.jar")) {
            if (in != null) return in.readAllBytes();
        }
        return resource("/alloy-bridge.jar");
    }

    final String side;
    final boolean server;
    final Path target, cache, work, mojang, fabricLibs, clientLibs, assets, forgeDir, installerJar, forge, forgeLibs, fmlRoot;
    Path fml;   // the PrepFml output folder in use: fmlRoot/<key> (see stepPrepFml), or fmlRoot itself for older caches
    final Path gameJar, versionJson, serverBundle, serverInner, clientJar, forgePatched, forgeUniversal, shim;
    Path java;       // the install's own child JVMs (Forge installer, PrepFml, the name translator)
    Path gameJava;   // the game's Java for the run scripts; null = none found (the scripts then use java from PATH)
    boolean devScripts;   // client: also write run-client.cmd/.sh (offline dev test start, no account check); off for players
    boolean noAssets;
    boolean fabricInstance;   // client: Alloy inside another launcher's plain Fabric instance (fabric-instance.args, marker, agent; no Prism zip)
    boolean lean;
    boolean eula;
    Path mcDir;   // client: also write an official-launcher profile here
    String instanceName;   // Prism instance name (default PrismExport.INSTANCE_NAME); a modpack import uses the pack name

    Installer(String side, Path dir, Path cache) {
        this.side = side;
        this.server = side.equals("server");
        target = dir.toAbsolutePath().normalize();
        this.cache = (cache != null ? cache : target.resolve(".alloy-cache")).toAbsolutePath().normalize();
        work = target.resolve(".alloy");
        mojang = this.cache.resolve("mojang");
        fabricLibs = this.cache.resolve(perVersion("fabric-libs"));
        clientLibs = this.cache.resolve("client-libs");
        assets = this.cache.resolve("assets");
        forgeDir = this.cache.resolve("forge");
        installerJar = forgeDir.resolve("forge-" + FV + "-installer.jar");
        forge = forgeDir.resolve(perVersion(side));
        forgeLibs = forge.resolve("libraries");
        fmlRoot = this.cache.resolve(perVersion("fml-" + side));
        fml = currentFml(fmlRoot);
        gameJar = work.resolve(server ? "hybrid_game.jar" : "hybrid_client_game.jar");
        versionJson = mojang.resolve(MC + ".json");
        serverBundle = mojang.resolve("server-" + MC + ".jar");
        serverInner = mojang.resolve("server-" + MC + "-unpacked.jar");
        clientJar = mojang.resolve("client-" + MC + ".jar");
        forgePatched = forgeLibs.resolve("net/minecraftforge/forge/" + FV + "/forge-" + FV + "-" + side + ".jar");
        forgeUniversal = forgeLibs.resolve("net/minecraftforge/forge/" + FV + "/forge-" + FV + "-universal.jar");
        shim = server ? forge.resolve("forge-" + FV + "-shim.jar") : forgeLibs.resolve("net/minecraftforge/forge/" + FV + "/forge-" + FV + "-shim.jar");
    }

    /** Cache folders whose contents depend on the Minecraft version (the server's library folder is read as a whole, the Forge
     *  install and patched FML jars are per version) get a "-<version>" suffix for every version but the default, so one
     *  --cache can serve several versions. The default version keeps the old names. Downloads and assets stay shared. */
    static String perVersion(String name) {
        return MC.equals(versions().get(0)) ? name : name + "-" + MC;
    }

    // ------------------------------------------------------------------ main flow
    void install(List<Path> forgeMods, List<Path> fabricMods) throws Exception {
        long t0 = System.currentTimeMillis();
        if (!server && mcDir != null) {   // fail before downloads
            LauncherProfiles.check(mcDir.toAbsolutePath().normalize());
            for (Path d : List.of(target, cache)) {   // Flatpak launcher: the game folder and the cache must be inside its sandbox
                String fp = LauncherProfiles.flatpakProblem(mcDir, d);
                if (fp != null) throw new IllegalStateException(fp);
            }
        }
        if (java == null) {
            JavaChoice jc = chooseJava(MC, ownJava(), Runtime.version().feature(), bundledRuntime(), Util::findGameJava, Util::gameJavaMissing);
            java = jc.child();
            gameJava = jc.game();
            if (jc.note() != null) log(jc.note());
        } else gameJava = java;
        if (V120.on()) {
            if (server && !V120.serverReady())
                throw new IOException("this installer makes Minecraft " + MC + " clients only (a " + MC + " dedicated server is not done yet; servers: "
                        + String.join(", ", new TreeSet<>(V120.SERVERS)) + " and 26.x)");
            if (gameJava != null) V120.checkJava(gameJava);
        } else if (javaMajorOf(java) < 25) throw new IOException("Java 25 or newer is required (running " + java + "); pass --java <JAVA_HOME>");
        mkdirs(target);
        mkdirs(work);
        log(side + " install into " + target + " (Minecraft " + MC + ", cache " + cache + ", java " + java
                + (gameJava == null ? ", game java: none found" : gameJava.equals(java) ? "" : ", game java " + gameJava) + ")");

        if (V120.on()) V120.base(this, true);
        else {
            Map<String, Object> vj = stepMojang(true, true);
            stepServerLibs();
            stepForge();
            if (!server) stepClientAssets(vj);
            stepPrepFml();
        }

        // jars of the installer itself: the prep tool runs from a copy in the game dir
        Path selfCopy = work.resolve("alloy-installer.jar");
        Path self = selfJar();
        if (Files.isRegularFile(self) && !self.toAbsolutePath().normalize().equals(selfCopy.toAbsolutePath().normalize()))
            Files.copy(self, selfCopy, StandardCopyOption.REPLACE_EXISTING);

        // bridge + mods
        Path mods = target.resolve("mods"), fmods = target.resolve("forgemods");
        mkdirs(mods);
        mkdirs(fmods.resolve(".nested"));
        Files.write(mods.resolve("alloy-bridge.jar"), bridgeJar());
        for (Path f : fabricMods) {
            Files.copy(f, mods.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            Path it = f.resolveSibling(f.getFileName() + ".inject.txt");
            if (!Files.exists(it) && f.getParent() != null && f.getParent().getParent() != null)   // adapt_fabric.py: <game>/.alloy/fit/
                it = f.getParent().getParent().resolve(".alloy").resolve("fit").resolve(f.getFileName() + ".inject.txt");
            if (Files.exists(it)) {   // into .alloy/fit/, not mods/: mods that scan mods/ (BCLib) would read it as a mod file
                Path fit = target.resolve(".alloy").resolve("fit");
                mkdirs(fit);
                Files.copy(it, fit.resolve(f.getFileName() + ".inject.txt"), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        for (Path m : forgeMods) Files.copy(m, fmods.resolve(m.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        writeQuietDefaults(target, !server);

        writePrepConfig();
        if (V120.on() && lean) { log("--lean is ignored for Minecraft " + MC + ": the game is prepared now"); Prep.run(target, false); }
        else if (lean && leanBlockedByLauncher()) Prep.run(target, false);
        else if (lean) makeLean();
        else Prep.run(target, false);

        String name = writeScripts();
        if (fabricInstance && !server) writeFabricInstance();
        else if (!server) new PrismExport(this).build();
        if (!server && mcDir != null) new LauncherExport(this).build(mcDir);
        if (server) {
            Path e = target.resolve("eula.txt");
            if (eula) Files.writeString(e, "eula=true\n");
            else if (!Files.exists(e)) {
                Files.writeString(e, "eula=false\n");
                log("eula.txt written with eula=false: re-run with --eula (or edit it) after reading https://aka.ms/MinecraftEULA");
            }
        }
        log("done in " + (System.currentTimeMillis() - t0) / 1000 + "s. " + startHint(name));
    }

    /**
     * A new game folder makes no update pings by default: Forge's update check ({@code versionCheck} in config/fml.toml, the
     * same key in Forge 47 to 66) and Mod Menu's ({@code update_checker} in config/modmenu.json, client only) start off.
     * Only files that do not exist yet are written, so a pack's own config or a player's choice is never changed. Forge and
     * Mod Menu fill in their other keys with defaults on the first start.
     */
    static void writeQuietDefaults(Path gameDir, boolean client) throws IOException {
        Path cfg = gameDir.resolve("config");
        mkdirs(cfg);
        List<String> made = new ArrayList<>();
        Path fml = cfg.resolve("fml.toml");
        if (!Files.exists(fml)) { Files.writeString(fml, "# written by the Alloy installer: no update check at start\nversionCheck = false\n"); made.add("config/fml.toml"); }
        Path mm = cfg.resolve("modmenu.json");
        if (client && !Files.exists(mm)) { Files.writeString(mm, "{\n  \"update_checker\": false\n}\n"); made.add("config/modmenu.json"); }
        if (!made.isEmpty()) log("update checks off by default (" + String.join(", ", made) + ")");
    }

    /** --lean with --mc-dir: the official launcher has no pre-launch step to build the game jar, so prepare it now. */
    boolean leanBlockedByLauncher() {
        if (server || mcDir == null) return false;
        log("--lean is ignored with --mc-dir: the Minecraft Launcher cannot run Alloy's prep before a start, so the game is prepared now");
        return true;
    }

    /** What the player starts. Clients go through a launcher (it signs the player in); run-client is a dev-only script. */
    String startHint(String script) {
        if (server) return "Start with: " + target.resolve(script) + " (hosts and panels: server jar " + SERVER_JAR + ", plain java -jar " + SERVER_JAR + ")";
        if (devScripts) return "Start with: " + target.resolve(script);
        if (fabricInstance) return "Start this Fabric instance from its launcher, with the Java argument -javaagent:.alloy/alloy-agent.jar";
        StringBuilder b = new StringBuilder("Start Alloy from your launcher: ");
        if (mcDir != null) b.append("in the Minecraft Launcher pick the profile 'Alloy ").append(MC).append("', or ");
        b.append("import ").append(target.resolve("prism").resolve((instanceName != null ? instanceName : PrismExport.INSTANCE_NAME) + ".zip"))
                .append(" in Prism Launcher or MultiMC (Add Instance, Import)");
        return b.toString();
    }

    /** Pick the Minecraft version a game dir was installed for (.alloy/prep.properties "minecraft"; older installs: default). */
    static void selectFor(Path game) throws IOException {
        Path pf = game.resolve(".alloy/prep.properties");
        String mc = null;
        if (Files.isRegularFile(pf)) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(pf)) { p.load(r); }
            mc = p.getProperty("minecraft");
        }
        select(mc);
    }

    // ------------------------------------------------------------------ downloads
    Map<String, Object> stepMojang(boolean wantServer, boolean wantClient) throws Exception {
        if (!Files.exists(versionJson)) {
            var m = Json.obj(Json.parse(new String(get(MANIFEST, 60), StandardCharsets.UTF_8)));
            for (Object v : Json.arr(m.get("versions"))) {
                var e = Json.obj(v);
                if (MC.equals(e.get("id"))) {
                    fetch(List.of((String) e.get("url")), versionJson, (String) e.get("sha1"), null, MC + ".json");
                    break;
                }
            }
        }
        var vj = Json.obj(Json.parse(Files.readString(versionJson)));
        var d = Json.obj(vj.get("downloads"));
        if (wantServer) {
            var s = Json.obj(d.get("server"));
            fetch(List.of((String) s.get("url")), serverBundle, (String) s.get("sha1"), (Long) s.get("size"), "vanilla server bundle");
        }
        if (wantClient) {
            var c = Json.obj(d.get("client"));
            fetch(List.of((String) c.get("url")), clientJar, (String) c.get("sha1"), (Long) c.get("size"), "vanilla client jar");
        }
        return vj;
    }

    void stepServerLibs() throws Exception {
        try (ZipFile z = new ZipFile(serverBundle.toFile())) {
            for (String line : new String(z.getInputStream(z.getEntry("META-INF/libraries.list")).readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank()) continue;
                String[] p = line.split("\t");
                Path dest = fabricLibs.resolve(p[2]);
                if (Files.exists(dest) && digest(dest, "SHA-256").equals(p[0])) continue;
                mkdirs(dest.getParent());
                try (InputStream in = z.getInputStream(z.getEntry("META-INF/libraries/" + p[2]))) { Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING); }
                if (!digest(dest, "SHA-256").equals(p[0])) throw new IOException("corrupt library in server bundle: " + p[2]);
            }
            String[] v = new String(z.getInputStream(z.getEntry("META-INF/versions.list")).readAllBytes(), StandardCharsets.UTF_8).trim().split("\\s+");
            if (!(Files.exists(serverInner) && digest(serverInner, "SHA-256").equals(v[0]))) {
                try (InputStream in = z.getInputStream(z.getEntry("META-INF/versions/" + v[2]))) { Files.copy(in, serverInner, StandardCopyOption.REPLACE_EXISTING); }
                if (!digest(serverInner, "SHA-256").equals(v[0])) throw new IOException("corrupt inner server jar");
            }
        }
        stepFabricLibs();
    }

    /** Fabric Loader, Fabric's Mixin and ASM from the Fabric maven / Maven Central into fabric-libs */
    void stepFabricLibs() throws Exception {
        for (String rel : fabricLibRels()) fetchPinned(List.of(FABRIC_MAVEN, CENTRAL), rel, fabricLibs.resolve(rel), null);
    }

    /** maven paths of Fabric Loader, Fabric's Mixin and ASM for the selected version (all SHA-256 pinned, see Util.Pins) */
    static List<String> fabricLibRels() {
        List<String> arts = new ArrayList<>(List.of("net/fabricmc/fabric-loader/" + LOADER + "/fabric-loader-" + LOADER + ".jar",
                "net/fabricmc/sponge-mixin/" + MIXIN + "/sponge-mixin-" + MIXIN + ".jar"));
        for (String a : List.of("asm", "asm-tree", "asm-commons", "asm-util", "asm-analysis"))
            arts.add("org/ow2/asm/" + a + "/" + ASM + "/" + a + "-" + ASM + ".jar");
        return arts;
    }

    static String forgeInstallerRel() { return "net/minecraftforge/forge/" + FV + "/forge-" + FV + "-installer.jar"; }

    /** the official Forge installer, checked against its pin every time (it is run as a JVM right after) */
    void fetchForgeInstaller() throws IOException {
        fetchPinned(List.of(FORGE_MAVEN), forgeInstallerRel(), installerJar, "Forge installer " + FV);
    }

    void stepForge() throws Exception {
        fetchForgeInstaller();
        if (Files.exists(forgePatched) && Files.exists(forgeUniversal) && (V120.on() || Files.exists(shim))) return;   // Forge 47 has no shim
        mkdirs(forge);
        String flag = server ? "--installServer" : "--installClient";
        if (!server) {
            Path prof = forge.resolve("launcher_profiles.json");
            if (!Files.exists(prof)) Files.writeString(prof, "{\"profiles\":{},\"version\":3}");
        }
        log("running Forge installer " + flag + " (downloads Minecraft libraries, applies Forge patches; a few minutes)");
        Path lg = forgeDir.resolve("install-" + side + ".log");
        List<String> cmd = new ArrayList<>(List.of(java.toString()));
        cmd.addAll(JVM_PROPS);
        cmd.addAll(List.of("-jar", installerJar.toString(), flag, forge.toString()));
        int rc = run(cmd, lg, forgeDir);
        if (rc != 0 || !Files.exists(forgePatched)) throw new IOException("Forge installer failed (exit " + rc + "); see " + lg);
    }

    /** Mojang client libraries not provided by Forge's client install, plus asset index and objects. */
    void stepClientAssets(Map<String, Object> vj) throws Exception {
        for (Object o : Json.arr(vj.get("libraries"))) {
            var lib = Json.obj(o);
            var a = libArtifact(lib);
            if (a == null) continue;
            String path = (String) a.get("path");
            if (Files.exists(forgeLibs.resolve(path)) || Files.exists(fabricLibs.resolve(path)) || Files.exists(clientLibs.resolve(path))) continue;
            fetch(List.of((String) a.get("url")), clientLibs.resolve(path), (String) a.get("sha1"), (Long) a.get("size"), null);
        }
        var ai = Json.obj(vj.get("assetIndex"));
        Path idx = fetch(List.of((String) ai.get("url")), assets.resolve("indexes").resolve(ai.get("id") + ".json"),
                (String) ai.get("sha1"), (Long) ai.get("size"), "asset index");
        if (!noAssets) fetchAssets(idx);
    }

    /** artifact map of a version.json library if it applies on this system (rules, natives for this CPU), else null. */
    static Map<String, Object> libArtifact(Map<String, Object> lib) { return libArtifact(lib, OS_NAME, ARCH); }

    /**
     * artifact map of a version.json library for system os (windows, osx, linux) and CPU arch (x64, arm64, x86, arm32),
     * else null. Vanilla's rules check only the OS name and list natives-macos plus natives-macos-arm64 (and windows,
     * windows-arm64, windows-x86): the plain natives jar is the x64 one and is always kept; an -arm64 jar is kept on arm64
     * and on every Mac (an arm64 or a Rosetta x64 Java may start the game there); -x86 / -arm32 only on that CPU. Both
     * on the class path is what the launcher does: LWJGL picks its os/arch folder inside the jars by os.arch at launch.
     */
    static Map<String, Object> libArtifact(Map<String, Object> lib, String os, String arch) {
        boolean ok = true;
        if (lib.get("rules") != null) {
            ok = false;
            for (Object ro : Json.arr(lib.get("rules"))) {
                var r = Json.obj(ro);
                Object ros = r.get("os");
                String osName = ros == null ? null : (String) Json.obj(ros).get("name");
                if (osName == null || osName.equals(os)) ok = "allow".equals(r.get("action"));
            }
        }
        var dl = Json.obj(lib.get("downloads"));
        if (!ok || dl == null || dl.get("artifact") == null) return null;
        var a = Json.obj(dl.get("artifact"));
        String p = (String) a.get("path");
        if (p.endsWith("-arm64.jar") && !(arch.equals("arm64") || os.equals("osx"))) return null;
        if (p.endsWith("-x86.jar") && !arch.equals("x86")) return null;
        if (p.endsWith("-arm32.jar") && !arch.equals("arm32")) return null;
        return a;
    }

    void fetchAssets(Path idx) throws Exception {
        var objs = Json.obj(Json.obj(Json.parse(Files.readString(idx))).get("objects"));
        List<String[]> todo = new ArrayList<>();
        long bytes = 0;
        for (var e : objs.entrySet()) {
            var o = Json.obj(e.getValue());
            String h = (String) o.get("hash");
            long size = (Long) o.get("size");
            Path p = assets.resolve("objects").resolve(h.substring(0, 2)).resolve(h);
            if (!(Files.exists(p) && Files.size(p) == size)) { todo.add(new String[]{h}); bytes += size; }
        }
        log("assets: " + todo.size() + " of " + objs.size() + " objects to fetch, " + bytes / 1_000_000 + " MB (resumable)");
        if (todo.isEmpty()) return;
        ExecutorService ex = Executors.newFixedThreadPool(2);
        AtomicInteger done = new AtomicInteger(), bad = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (String[] t : todo) fs.add(ex.submit(() -> {
            String h = t[0];
            Path p = assets.resolve("objects").resolve(h.substring(0, 2)).resolve(h);
            boolean ok = false;
            for (int k = 0; k < 4 && !ok; k++) {
                try {
                    byte[] b = get("https://resources.download.minecraft.net/" + h.substring(0, 2) + "/" + h, 60);
                    if (sha1(b).equals(h)) {
                        mkdirs(p.getParent());
                        Path tmp = p.resolveSibling(h + ".tmp");
                        Files.write(tmp, b);
                        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
                        ok = true;
                    }
                } catch (Exception ignored) { }
            }
            if (!ok) bad.incrementAndGet();
            int d = done.incrementAndGet();
            if (d % 500 == 0) log("  assets " + d + "/" + todo.size());
        }));
        for (Future<?> f : fs) f.get();
        ex.shutdown();
        log("assets finished: " + (done.get() - bad.get()) + " ok, " + bad.get() + " failed");
        if (bad.get() > 0) throw new IOException(bad.get() + " asset download(s) failed; re-run to retry");
    }

    // ------------------------------------------------------------------ PrepFml
    /** bootstrap-shim.list of the Forge shim as it is (lines of sha256, coordinate, path; see V120.shimEntries) */
    String shimText() throws IOException {
        try (ZipFile z = new ZipFile(shim.toFile())) {
            ZipEntry e = z.getEntry("bootstrap-shim.list");
            if (e == null) throw new IOException(shim + " has no bootstrap-shim.list");
            return new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    List<String> shimList() throws IOException {
        try (ZipFile z = new ZipFile(shim.toFile())) {
            return Arrays.asList(new String(z.getInputStream(z.getEntry("bootstrap-shim.list")).readAllBytes(), StandardCharsets.UTF_8).trim().split("\\s+"));
        }
    }

    /** The PrepFml folder the last stepPrepFml picked (fmlRoot/current), else fmlRoot itself (caches from older installers). */
    static Path currentFml(Path root) {
        try {
            Path cur = root.resolve("current");
            if (Files.isRegularFile(cur)) {
                String n = Files.readString(cur).trim();
                if (!n.isEmpty() && !n.contains("/") && !n.contains("\\") && Files.isDirectory(root.resolve(n))) return root.resolve(n);
            }
        } catch (IOException ignored) { }
        return root;
    }

    static boolean fmlReady(Path dir, String key, List<String> rels) throws IOException {
        Path stamp = dir.resolve(".stamp-java");
        if (!(Files.isRegularFile(stamp) && Files.readString(stamp).equals(key))) return false;
        for (String r : rels) if (!Files.isRegularFile(dir.resolve(r))) return false;
        return true;
    }

    /**
     * Patched copies of Forge's loader jars (tools/PrepFml). The output folder is named after the key (installer tools +
     * SHA-1 of Forge's jars), so a finished folder is never written again: two installs that share one --cache, or a
     * second install while a game from the first one runs, never rewrite jars a running game has open. A new key
     * (new installer, other Forge jars) gets a new folder; "current" names the one in use.
     */
    List<String> stepPrepFml() throws Exception {
        List<String> rels = new ArrayList<>();
        if (V120.on()) rels.addAll(V120.fmlJars(this));   // Forge 47: no bootstrap shim list
        else for (String x : shimList()) if (x.endsWith(".jar") && FML_JARS.matcher(x).find()) rels.add(x);
        StringBuilder sb = new StringBuilder(toolsId());
        for (String r : rels) sb.append(r).append(digest(forgeLibs.resolve(r), "SHA-1"));
        String key = sha1(sb.toString());
        if (fmlReady(fmlRoot, key, rels)) { fml = fmlRoot; return rels; }   // older cache layout, already right
        Path cur0 = currentFml(fmlRoot);
        if (!cur0.equals(fmlRoot) && fmlReady(cur0, key, rels)) { fml = cur0; return rels; }
        Path dir = fmlRoot.resolve(key.substring(0, 16));
        if (!fmlReady(dir, key, rels)) {
            log("PrepFml: patching " + rels.size() + " FML jars");
            mkdirs(fmlRoot);
            Path tmp = Files.createTempDirectory(fmlRoot, key.substring(0, 16) + ".tmp");
            List<String> cmd = new ArrayList<>(List.of(java.toString()));
            cmd.addAll(JVM_PROPS);
            if (V120.on()) cmd.add("-Dalloy.fml=" + V120.FAMILY);
            cmd.addAll(List.of("-Xmx512m", "-cp", selfJar().toString(), "PrepFml", tmp.toString(), forgeLibs.toString()));
            cmd.addAll(rels);
            int rc = run(cmd, null, null);
            if (rc != 0) throw new IOException("PrepFml failed");
            Files.writeString(tmp.resolve(".stamp-java"), key);
            try {
                Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                if (fmlReady(dir, key, rels)) {   // another install finished the same folder first
                    try (var s = Files.walk(tmp)) { for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x); }
                } else {   // a broken folder of that name (stopped run, files in use): never write into it, use a new name
                    dir = fmlRoot.resolve(key.substring(0, 16) + "-" + Long.toString(System.nanoTime(), 36));
                    Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
                }
            }
        }
        fml = dir;
        Path cur = fmlRoot.resolve("current");
        if (!(Files.isRegularFile(cur) && Files.readString(cur).trim().equals(dir.getFileName().toString())))
            Files.writeString(cur, dir.getFileName().toString());
        return rels;
    }

    /**
     * Prism instance (PrismExport): the zip holds placeholders for every Forge library (and the patched loader jars).
     * prep.properties "libraries" lists them as  DEST|fml|REL  or  DEST|forge|REL  (';' separated, DEST relative to the
     * game dir). This runs PrepFml on this PC, then copies the patched jars (fml) and Forge's own files from the Forge
     * install in the instance's cache (forge) over the placeholders. Files that are already right are left alone.
     */
    static void fillLibraries(Path game, Properties cfg) throws Exception {
        String spec = cfg.getProperty("libraries", "");
        if (spec.isBlank()) return;
        Installer in = new Installer(cfg.getProperty("side", "client"), game, game.resolve(cfg.getProperty("cache", ".alloy/cache")));
        in.java = ownJava();
        List<String[]> rows = new ArrayList<>();
        for (String e : spec.split(";")) if (!e.isBlank()) rows.add(e.split("\\|", 3));
        if (rows.stream().anyMatch(r -> r[1].equals("fml"))) in.stepPrepFml();
        Path names = rows.stream().anyMatch(r -> r[1].equals("names")) ? V120.names(in) : null;
        int n = 0;
        for (String[] r : rows) {
            Path dst = game.resolve(r[0]).normalize();
            if (r[1].equals("names") && names == null) throw new IOException("the Minecraft " + MC + " names folder is missing; start again");
            Path src = (r[1].equals("fml") ? in.fml : r[1].equals("names") ? names : in.forgeLibs).resolve(r[2]);
            if (!Files.isRegularFile(src)) throw new IOException("Forge library missing after the Forge install: " + src);
            if (Files.isRegularFile(dst) && Files.mismatch(src, dst) == -1) continue;
            mkdirs(dst.getParent());
            Path tmp = dst.resolveSibling(dst.getFileName() + ".tmp");
            Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
            n++;
        }
        if (n > 0) log("alloy-prep: " + n + " Forge librar" + (n == 1 ? "y" : "ies") + " put into the instance (Forge's files from the Forge install, "
                + "loader jars patched on this PC)");
    }

    // ------------------------------------------------------------------ class paths
    Path findIn(String rel, Path... dirs) {
        for (Path d : dirs) if (Files.exists(d.resolve(rel))) return d.resolve(rel);
        return null;
    }

    boolean skipped(String j, String[] skip) {
        for (String s : skip) if (j.contains(s)) return true;
        return false;
    }

    /** Server: Fabric libs + Forge runtime jars (patched FML jars preferred). */
    List<Path> serverClasspath() throws IOException {
        List<Path> out = new ArrayList<>(rglobJars(fabricLibs));
        Set<String> have = new HashSet<>();
        for (Path p : out) have.add(p.getFileName().toString());
        for (String j : shimList()) {
            if (!j.endsWith(".jar") || skipped(j, SKIP_SERVER) || have.contains(j.substring(j.lastIndexOf('/') + 1))) continue;
            Path p = Files.exists(fml.resolve(j)) ? fml.resolve(j) : forgeLibs.resolve(j);
            if (Files.exists(p)) out.add(p);
        }
        return out;
    }

    /** {library artifact paths for the client in version.json order, asset index id} */
    List<Path> vanillaClientLibs(Map<String, Object> vj) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Object o : Json.arr(vj.get("libraries"))) {
            var a = libArtifact(Json.obj(o));
            if (a == null) continue;
            Path p = findIn((String) a.get("path"), fml, fabricLibs, forgeLibs, clientLibs);
            if (p == null) throw new IOException("missing client library " + a.get("path"));
            out.add(p);
        }
        return out;
    }

    List<Path> clientForgeRuntime() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String j : shimList()) {
            if (!j.endsWith(".jar") || skipped(j, SKIP_CLIENT)) continue;
            Path p = findIn(j, fml, fabricLibs, forgeLibs, clientLibs);
            if (p != null) out.add(p);
        }
        return out;
    }

    List<Path> clientClasspath(Map<String, Object> vj) throws IOException {
        List<Path> cands = new ArrayList<>();
        for (Path p : rglobJars(fabricLibs)) {
            String s = p.toString().replace('\\', '/');
            if (s.contains("net/fabricmc/") || s.contains("org/ow2/asm/")) cands.add(p);
        }
        cands.addAll(vanillaClientLibs(vj));
        cands.addAll(clientForgeRuntime());
        Set<String> seen = new HashSet<>();
        List<Path> cp = new ArrayList<>();
        for (Path p : cands) if (seen.add(p.getFileName().toString())) cp.add(p);   // one copy per artifact file name
        return cp;
    }

    // ------------------------------------------------------------------ prep config + run scripts
    void writePrepConfig() throws IOException {
        if (V120.on()) { writePrepConfig120(); return; }
        Path vanilla = server ? serverInner : clientJar;
        List<String> tr = new ArrayList<>();
        for (String r : List.of("forge-transformers/" + FV + "/forge-transformers-" + FV + ".jar", "modlauncher/" + MODLAUNCHER + "/modlauncher-" + MODLAUNCHER + ".jar",
                "securemodules/" + SECUREMODULES + "/securemodules-" + SECUREMODULES + ".jar", "unsafe/" + UNSAFE + "/unsafe-" + UNSAFE + ".jar"))
            tr.add(rel(target, forgeLibs.resolve("net/minecraftforge/" + r)));
        for (String pat : List.of("org/apache/logging/log4j/log4j-api", "org/apache/logging/log4j/log4j-core", "com/google/code/gson/gson")) {
            List<Path> js = rglobJars(fabricLibs.resolve(pat));
            if (!js.isEmpty()) tr.add(rel(target, js.get(0)));
        }
        Properties p = new Properties();
        p.setProperty("side", side);
        p.setProperty("minecraft", MC);   // read by --prep (Prep.run) to pick the same version again
        p.setProperty("vanilla", rel(target, vanilla));
        p.setProperty("forge", rel(target, forgePatched));
        p.setProperty("universal", rel(target, forgeUniversal));
        p.setProperty("transformers", String.join(";", tr));
        p.setProperty("gameJar", rel(target, gameJar));
        p.setProperty("resources", rel(target, resourcesJar()));
        p.setProperty("cache", rel(target, cache));
        p.setProperty("bootstrap", "1");   // missing vanilla / Forge-patched jars are downloaded and rebuilt by --prep
        try (Writer w = Files.newBufferedWriter(work.resolve("prep.properties"))) { p.store(w, "alloy-prep configuration (generated)"); }
    }

    /** 1.20.1: no PrepGame (Forge 47 applies access transformers at class load); the launch files are copied from the names folder. */
    void writePrepConfig120() throws IOException {
        Properties p = new Properties();
        p.setProperty("side", side);
        p.setProperty("minecraft", MC);
        p.setProperty("vanilla", rel(target, server ? V120.serverObf(this) : clientJar));
        p.setProperty("forge", rel(target, forgePatched));
        p.setProperty("universal", rel(target, forgeUniversal));
        p.setProperty("gameJar", rel(target, gameJar));
        p.setProperty("universalMojang", rel(target, work.resolve("lib120").resolve(V120.universalName())));
        if (!V120.NATIVE) p.setProperty("srgNames", rel(target, work.resolve("srg2mojang.tsv")));   // Forge 50+: none (srg2off csv)
        p.setProperty("cache", rel(target, cache));
        p.setProperty("bootstrap", "1");
        try (Writer w = Files.newBufferedWriter(work.resolve("prep.properties"))) { p.store(w, "alloy-prep configuration (generated)"); }
    }

    /** Mojang's and the Forge-patched jars are not redistributable: remove everything Minecraft from this install. --prep rebuilds it. */
    void makeLean() throws IOException {
        for (Path p : List.of(gameJar, resourcesJar(), work.resolve("prep.stamp"))) Files.deleteIfExists(p);
        Path store = work.resolve("gamejars");   // stored game jars (PrepGame.Store) hold Minecraft code too
        if (Files.exists(store)) try (var s = Files.walk(store)) { for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(x); }
        if (!inside(target, cache)) { log("lean install: the cache " + cache + " is outside the install and left alone"); return; }
        for (Path p : List.of(forgePatched, forgeUniversal, clientJar, serverBundle, serverInner)) Files.deleteIfExists(p);
        for (Path d : List.of(forge.resolve("libraries/net/minecraft"), forge.resolve("versions"), mojang.resolve("libraries")))
            if (Files.exists(d)) try (var s = Files.walk(d)) { for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(x); }
        log("lean install: no Minecraft code left in " + target + "; the first --prep downloads it from Mojang/Forge");
    }

    /** First --prep of a lean install / Prism export: Mojang jar (sha1-checked), Forge installer (headless), nothing is shipped. */
    static void bootstrap(Path game, Properties cfg) throws Exception {
        String side = cfg.getProperty("side", "client");
        Installer in = new Installer(side, game, game.resolve(cfg.getProperty("cache", ".alloy/cache")));
        in.java = ownJava();
        if (V120.on()) {
            V120.checkJava(in.java);
            mkdirs(in.work);
            if (V120.names(in) == null || !Files.exists(in.forgePatched))
                log("first start: downloading Minecraft " + MC + " from Mojang, running the Forge installer and translating names (a few minutes, once)");
            V120.base(in, false);   // the launcher brings the assets
            return;
        }
        if (javaMajorOf(in.java) < 25) throw new IOException("Java 25 or newer is required");
        log("first start: downloading Minecraft " + MC + " from Mojang and running the Forge installer (a few minutes, once)");
        mkdirs(in.work);
        if (in.server) { in.stepMojang(true, false); in.stepServerLibs(); }
        else in.stepMojang(false, true);
        in.stepForge();
    }

    Path resourcesJar() {
        String n = gameJar.getFileName().toString();
        return gameJar.resolveSibling(n.substring(0, n.length() - 4) + "-resources.jar");
    }

    static void argfile(Path path, List<String> lines) throws IOException {
        Files.writeString(path, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    /**
     * A folder installed on Windows and moved to Linux (or back): the -cp line of .alloy/*.args uses the other system's
     * separator. Swap it for this system's, but only when every entry is relative (an absolute C:/ path cannot move anyway).
     * Runs at the start of every --prep, so run-server and --server-start always see the right one.
     */
    static void portableArgfiles(Path work) throws IOException {
        if (!Files.isDirectory(work)) return;
        if (!WINDOWS && work.getParent() != null)   // a folder unzipped or uploaded over SFTP loses the exec bit: give it back
            for (String n : List.of("run-server.sh", "run-client.sh")) {
                Path f = work.getParent().resolve(n);
                if (Files.isRegularFile(f) && !Files.isExecutable(f)) f.toFile().setExecutable(true, false);
            }
        char mine = File.pathSeparatorChar, other = mine == ';' ? ':' : ';';
        try (var s = Files.newDirectoryStream(work, "*.args")) {
            for (Path f : s) {
                List<String> lines = new ArrayList<>(Files.readAllLines(f, StandardCharsets.UTF_8));
                boolean changed = false;
                for (int i = 0; i + 1 < lines.size(); i++) {
                    if (!lines.get(i).equals("-cp")) continue;
                    String cp = lines.get(i + 1), bare = cp.replace("\"", "");
                    if (bare.indexOf(other) < 0 || bare.matches("(?s)(.*[;:])?[A-Za-z]:[/\\\\].*") || bare.startsWith("/") || bare.contains(other + "/")) continue;
                    lines.set(i + 1, cp.replace(other, mine));
                    changed = true;
                }
                if (changed) {
                    argfile(f, lines);
                    log("alloy-prep: " + f.getFileName() + " class path separator set to '" + mine + "' for this system");
                }
            }
        }
    }

    static final String SERVER_JAR = "server.jar";

    /**
     * server.jar at the server folder root: a jar with only a manifest (Main-Class alloy.Launch, Class-Path
     * .alloy/alloy-installer.jar). Hosts start it as {@code java -jar server.jar} with no Alloy arguments, and the
     * launcher stub turns that into --server-start (Launch.hostStart). A server.jar that is not Alloy's is left alone.
     */
    static void writeServerJar(Path target) throws IOException {
        Path f = target.resolve(SERVER_JAR);
        if (Files.exists(f) && !isAlloyServerJar(f)) {
            log("note: " + f + " is not Alloy's, left as it is. Start this server with run-server or "
                    + "java -jar .alloy/alloy-installer.jar --server-start nogui");
            return;
        }
        Manifest mf = new Manifest();
        var at = mf.getMainAttributes();   // string keys: the field "java" hides the java.* package names here
        at.putValue("Manifest-Version", "1.0");
        at.putValue("Main-Class", "alloy.Launch");
        at.putValue("Class-Path", ".alloy/alloy-installer.jar");
        at.putValue("Alloy-Server-Jar", "1");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            ZipEntry dir = new ZipEntry("META-INF/");
            dir.setTime(315532800000L);   // fixed time: the same bytes on every install
            z.putNextEntry(dir);
            z.closeEntry();
            ZipEntry e = new ZipEntry("META-INF/MANIFEST.MF");
            e.setTime(315532800000L);
            z.putNextEntry(e);
            mf.write(z);
            z.closeEntry();
        }
        Files.write(f, bo.toByteArray());
    }

    static final String UNIX_ARGS = "unix_args.txt", UNIX_ARGS_LINE = "-jar " + SERVER_JAR;

    /**
     * unix_args.txt at the server folder root, one line "-jar server.jar": hosts that start {@code java @unix_args.txt}
     * (the Pterodactyl Forge and NeoForge eggs, scripts copied from Forge's run.sh) then start Alloy's server.jar. A link
     * there (the Forge egg links Forge's own argfile to the root) is removed first, never written through. A file of the
     * user's own with other text is left alone.
     */
    static void writeUnixArgs(Path target) throws IOException {
        Path f = target.resolve(UNIX_ARGS);
        if (Files.isSymbolicLink(f)) Files.delete(f);
        else if (Files.exists(f)) {
            String t = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1).trim();
            if (t.equals(UNIX_ARGS_LINE)) return;
            log("note: " + f + " is not Alloy's, left as it is (hosts that start java @" + UNIX_ARGS + " then do not start Alloy)");
            return;
        }
        Files.writeString(f, UNIX_ARGS_LINE + "\n", StandardCharsets.US_ASCII);
    }

    static boolean isAlloyServerJar(Path f) {
        try (JarFile j = new JarFile(f.toFile())) {
            var m = j.getManifest();
            return m != null && "1".equals(m.getMainAttributes().getValue("Alloy-Server-Jar"));
        } catch (IOException e) { return false; }
    }

    static final String FABRIC_INSTANCE_ARGS = "fabric-instance.args";

    /**
     * --fabric-instance: the marker (.alloy/fabric-instance.properties, kept when it exists) and the Java agent. The launcher's
     * instance keeps its own start; the player adds -javaagent:.alloy/alloy-agent.jar.
     */
    void writeFabricInstance() throws IOException {
        Path marker = work.resolve("fabric-instance.properties");
        if (!Files.exists(marker))
            Files.writeString(marker, "# Alloy inside a plain Fabric Loader instance\nformat=1\nminecraft=" + MC + "\n");
        Prep.refreshAgent(target);
        log("Fabric instance mode: add -javaagent:.alloy/alloy-agent.jar to this instance's Java arguments in your launcher");
    }

    String writeScripts() throws Exception {
        if (!server && !devScripts && !fabricInstance) {
            // Players start the client through a launcher, which signs them in. run-client starts the game offline
            // (AlloyDev, access token 0), so it and its argfile are written only with --dev-scripts.
            for (Path f : List.of(target.resolve("run-client.cmd"), target.resolve("run-client.sh"), work.resolve("client.args")))
                Files.deleteIfExists(f);
            return "run-client.cmd";
        }
        List<String> common = new ArrayList<>(List.of("-Dfabric.log.disableAnsi=true",
                "-Dlog4j2.contextSelector=org.apache.logging.log4j.core.selector.ClassLoaderContextSelector",
                "-Duser.language=en", "-Duser.region=US", "-Dfabric.gameJarPath=" + rel(target, gameJar)));
        List<Path> cp;
        String main, name, xmx;
        List<String> tail;
        var vj = Json.obj(Json.parse(Files.readString(versionJson)));
        if (V120.on()) {   // launch120.py step_run: Forge 47 needs its library folder, the srg names and a few --add-opens
            List<Path> cp120 = V120.classpath(this, vj, work.resolve("lib120").resolve(V120.universalName()));
            Path sjh = V120.secureJarHandler(cp120);
            common.removeIf(a -> a.startsWith("-Dfabric.gameJarPath="));
            for (String a : V120.jvmArgs(rel(target, gameJar), sjh == null ? "" : rel(target, sjh), rel(target, forgeLibs), rel(target, work.resolve("srg2mojang.tsv"))))
                common.add(V120.argfileQuote(a));
        }
        if (server) {
            cp = V120.on() ? V120.classpath(this, vj, work.resolve("lib120").resolve(V120.universalName())) : serverClasspath();
            main = "net.fabricmc.loader.impl.launch.knot.KnotServer";
            tail = List.of("nogui");
            name = "run-server";
            xmx = "2G";
        } else {
            cp = V120.on() ? V120.classpath(this, vj, work.resolve("lib120").resolve(V120.universalName())) : clientClasspath(vj);
            main = "net.fabricmc.loader.impl.launch.knot.KnotClient";
            name = "run-client";
            xmx = Util.clientHeapMb() + "M";   // 4 GB on PCs with >= 12 GB RAM, else 3 GB
            tail = List.of("--username", "AlloyDev", "--version", MC, "--gameDir", ".", "--assetsDir", rel(target, assets),
                    "--assetIndex", (String) Json.obj(vj.get("assetIndex")).get("id"), "--accessToken", "0", "--userType", "legacy",
                    "--versionType", "release", "--width", "1280", "--height", "720");
        }
        List<String> cps = new ArrayList<>();
        for (Path c : cp) cps.add(rel(target, c));
        if (!V120.on()) cps.add(rel(target, resourcesJar()));   // version.json for the system class loader (some Forge mods read it there)
        Path af = work.resolve(side + ".args");
        List<String> lines = new ArrayList<>(common);
        // AppCDS: the first normal exit writes .alloy/<side>.jsa (JDK 19+, dynamic archive of the library/loader classes); later starts
        // map it instead of parsing/verifying those classes again (~1.5-2 s faster to the title screen). A stale or missing file is ignored and rebuilt.
        if (!server && !V120.on()) lines.addAll(List.of("-XX:+AutoCreateSharedArchive", "-XX:SharedArchiveFile=.alloy/" + side + ".jsa"));   // JDK 19+
        if (!server && !V120.on()) lines.addAll(vanillaJvmFlags(vj));   // 26.3: -XX:StackShadowPages=32, see vanillaJvmFlags
        if (!server) {   // -XstartOnFirstThread on macOS, natives folder flags everywhere
            mkdirs(work.resolve("natives"));
            lines.addAll(clientOsArgs(OS_NAME, rel(target, work.resolve("natives"))));
        }
        lines.addAll(List.of("-cp", "\"" + String.join(File.pathSeparator, cps) + "\"", main));
        if (fabricInstance && !server) argfile(work.resolve(FABRIC_INSTANCE_ARGS), lines);   // JVM part only: no user name, no token
        if (!server && !devScripts) return "run-client.cmd";   // fabric instance without --dev-scripts: no offline start script
        lines.addAll(tail);
        argfile(af, lines);
        String prep = ".alloy/alloy-installer.jar";
        if (server) {   // portable: no install-time Java path, every path relative to the server folder (see ServerStart)
            if (!inside(target, cache)) log("note: the cache " + cache + " is outside the server folder, so this server folder cannot be moved to another PC or host");
            writeServerJar(target);
            writeUnixArgs(target);
            String jneed = V120.on() ? String.valueOf(V120.javaMin()) : "25";
            String cmd = "@echo off\r\n"
                    + "rem Alloy server start (generated). Extra JVM args can be passed: " + name + ".cmd -Xmx4G -XX:ActiveProcessorCount=2\r\n"
                    + "rem Uses java from PATH (an older Java finds a Java " + jneed + " by itself). Override: set ALLOY_JAVA=C:\\path\\to\\java.exe (or JAVA)\r\n"
                    + "rem Hosts and panels: use " + SERVER_JAR + " as the server jar, no arguments needed:  java -Xmx4G -jar " + SERVER_JAR + " nogui\r\n"
                    + "cd /d \"%~dp0\"\r\n"
                    + "set \"ALLOY_J=java\"\r\n"
                    + "if defined JAVA set \"ALLOY_J=%JAVA%\"\r\n"
                    + "if defined ALLOY_JAVA set \"ALLOY_J=%ALLOY_JAVA%\"\r\n"
                    + "\"%ALLOY_J%\" -Xmx" + xmx + " %* -jar " + SERVER_JAR + " nogui\r\n";
            Files.writeString(target.resolve(name + ".cmd"), cmd, StandardCharsets.US_ASCII);
            Files.writeString(target.resolve(name + ".sh"), serverSh(name, jneed, xmx), StandardCharsets.US_ASCII);
            makeExecutable(target.resolve(name + ".sh"));
            return name + ".cmd";
        }
        String jv = gameJava == null ? "java" : gameJava.toAbsolutePath().toString().replace('\\', '/');   // dev-only client scripts: the game's Java
        String cmd = "@echo off\r\n"
                + "rem Alloy " + side + " launcher (generated). Extra JVM args can be passed: " + name + ".cmd -Xmx4G -XX:ActiveProcessorCount=2\r\n"
                + "rem Override the JVM with: set ALLOY_JAVA=C:\\path\\to\\java.exe   (Minecraft " + MC + " needs Java " + LauncherProfile.javaMajor(MC) + " or newer)\r\n"
                + "cd /d \"%~dp0\"\r\n"
                + "set \"JAVA=" + jv + "\"\r\n"
                + "if defined ALLOY_JAVA set \"JAVA=%ALLOY_JAVA%\"\r\n"
                + "rem drop-in mods: re-prepares when forgemods\\ changed\r\n"
                + "\"%JAVA%\" -Duser.language=en -Duser.region=US -jar \"" + prep + "\" --prep . || exit /b 1\r\n"
                + "\"%JAVA%\" -Xmx" + xmx + " %* @\".alloy/" + side + ".args\"\r\n";
        Files.writeString(target.resolve(name + ".cmd"), cmd, StandardCharsets.US_ASCII);
        String sh = "#!/bin/sh\n# Alloy " + side + " launcher (generated). Minecraft " + MC + " needs Java " + LauncherProfile.javaMajor(MC) + " or newer: set ALLOY_JAVA to override.\n"
                + "cd \"$(dirname \"$0\")\" || exit 1\n"
                + "JAVA=\"${ALLOY_JAVA:-" + jv + "}\"\n"
                + "\"$JAVA\" -Duser.language=en -Duser.region=US -jar \"" + prep + "\" --prep . || exit 1\n"
                + "exec \"$JAVA\" -Xmx" + xmx + " \"$@\" @\".alloy/" + side + ".args\"\n";
        Files.writeString(target.resolve(name + ".sh"), sh, StandardCharsets.US_ASCII);
        makeExecutable(target.resolve(name + ".sh"));
        return name + ".cmd";
    }

    /**
     * Client JVM options that depend on the system (vanilla's version.json has the same): macOS needs -XstartOnFirstThread
     * (GLFW must run on the first thread, else "GLFW windows may only be created on the main thread"); every system gets
     * the natives folder, so LWJGL, JNA and netty unpack their native libraries there instead of java.io.tmpdir (Linux
     * systems that mount /tmp noexec cannot load them from there).
     */
    /**
     * The plain JVM options of vanilla's client version.json (arguments.jvm): -XX flags, --enable-native-access,
     * --sun-misc-unsafe-memory-access and --add-exports/--add-opens pairs. Entries with ${...} placeholders, rules, -cp
     * and -D are left out (Alloy sets its own class path and natives folders). The official launcher passes these; the
     * dev client.args and the Prism patch did not. 26.3 asks for -XX:StackShadowPages=32: without it the 26.3 client
     * (SDL3) died now and then about 16 s in, inside jvm.dll (0xc0000409 / 0xc0000005, no hs_err file).
     */
    static List<String> vanillaJvmFlags(Map<String, Object> vj) {
        List<String> out = new ArrayList<>();
        if (!(vj.get("arguments") instanceof Map<?, ?> a) || !(a.get("jvm") instanceof List<?> l)) return out;
        for (int i = 0; i < l.size(); i++) {
            if (!(l.get(i) instanceof String s) || s.contains("${")) continue;
            if (s.startsWith("-XX:") || s.startsWith("--enable-native-access=") || s.startsWith("--sun-misc-unsafe-memory-access=")) {
                out.add(s);
            } else if ((s.equals("--add-exports") || s.equals("--add-opens")) && i + 1 < l.size()
                    && l.get(i + 1) instanceof String v && !v.contains("${")) {
                out.add(s);
                out.add(v);
                i++;
            }
        }
        return out;
    }

    static List<String> clientOsArgs(String os, String natives) {
        List<String> out = new ArrayList<>();
        if (os.equals("osx")) out.add("-XstartOnFirstThread");
        for (String k : List.of("-Djava.library.path=", "-Djna.tmpdir=", "-Dorg.lwjgl.system.SharedLibraryExtractPath=", "-Dio.netty.native.workdir="))
            out.add(V120.argfileQuote(k + natives));
        return out;
    }

    // vanilla server arguments: run-server.sh sends these (and everything after them) to the server, not to the JVM
    static final String SERVER_ARGS_SH = "nogui|--nogui|-nogui|--port|--world|--universe|--serverId|--pidFile|--forceUpgrade|--eraseCache"
            + "|--safeMode|--demo|--bonusChest|--initSettings|--recreateRegionFiles|--jfrProfile";

    /**
     * run-server.sh: LF only, POSIX sh. The leading arguments that start with '-' (-Xmx4G, -D...) are JVM options and go
     * before -jar; from the first other one (nogui, --port 25570, ...) on they go after it to the server, so
     * ./run-server.sh nogui works like a vanilla or Paper script. nogui is added once unless it was given.
     */
    static String serverSh(String name, String jneed, String xmx) {
        return "#!/bin/sh\n# Alloy server start (generated). Extra JVM args can be passed: ./" + name + ".sh -Xmx4G [nogui] [--port 25570]\n"
                + "# Uses java from PATH (an older Java finds a Java " + jneed + " by itself). Override: ALLOY_JAVA=/path/to/java (or JAVA)\n"
                + "# Hosts and panels: use " + SERVER_JAR + " as the server jar, no arguments needed:  java -Xmx4G -jar " + SERVER_JAR + " nogui\n"
                + "# \"Permission denied\": start it as  sh " + name + ".sh  or run  chmod +x " + name + ".sh  once\n"
                + "cd \"$(dirname \"$0\")\" || exit 1\n"
                + "n=0\n"
                + "for a in \"$@\"; do\n"
                + "  case \"$a\" in " + SERVER_ARGS_SH + ") break ;; -*) n=$((n + 1)) ;; *) break ;; esac\n"
                + "done\n"
                + "gui=nogui\n"
                + "for a in \"$@\"; do case \"$a\" in nogui|--nogui|-nogui) gui= ;; esac; done\n"
                + "total=$#\n"
                + "i=0\n"
                + "for a in \"$@\"; do\n"
                + "  [ \"$i\" -eq \"$n\" ] && set -- \"$@\" -jar " + SERVER_JAR + "\n"
                + "  set -- \"$@\" \"$a\"\n"
                + "  i=$((i + 1))\n"
                + "done\n"
                + "[ \"$n\" -eq \"$total\" ] && set -- \"$@\" -jar " + SERVER_JAR + "\n"
                + "shift \"$total\"\n"
                + "exec \"${ALLOY_JAVA:-${JAVA:-java}}\" -Xmx" + xmx + " \"$@\" $gui\n";
    }

    /**
     * Sets the exec bit of a generated script and says what to do when the file system does not keep it (exFAT, SMB,
     * a folder zipped on Windows). Windows has no exec bit: nothing to do there. Returns whether the file is executable.
     */
    static boolean makeExecutable(Path f) {
        if (WINDOWS) return true;
        boolean ok = f.toFile().setExecutable(true, false) && Files.isExecutable(f);
        if (!ok) log("note: could not mark " + f.getFileName() + " executable on this file system; start it with  sh " + f.getFileName()
                + "  (or chmod +x " + f.getFileName() + ")");
        return ok;
    }
}
