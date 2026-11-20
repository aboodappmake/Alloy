package alloy.installer;

import alloy.ideas.ModMeta;
import alloy.ideas.Versions;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import static alloy.installer.Util.*;

/**
 * Modpack import: one or more Modrinth .mrpack files or CurseForge pack zips (manifest.json) become one Alloy instance.
 *
 *   java -jar alloy-installer.jar --modpack PACK [--modpack PACK2 ...] --dir TARGET [--side client|server]
 *        [--pack-mods DIR] [--check] (plus the normal install options: --cache, --java, --no-assets, --eula, --mc-dir, --lean)
 *
 * Several packs are merged: a Forge pack and a Fabric pack ("halves") or packs that mix both. Each mod jar is sorted with
 * the same rules as Forge jars found in mods/ ({@link ForgeInMods#classify}): Forge jars go to forgemods/, Fabric jars to
 * mods/. A multi-loader jar follows its pack's loader. The pack's overrides (config, options.txt, resource packs, ...)
 * are copied into TARGET. Then the normal install runs ({@link Installer#install}).
 *
 * Checks, all before anything is written: Minecraft version (this installer builds MC_VERSION only), loaders (Forge and
 * Fabric only: NeoForge packs are refused, Quilt packs import as Fabric packs unless a jar is Quilt-only), Forge and Fabric Loader versions (other versions of the same line
 * give a note), file hashes (sha1 and sha512 from the index), safe paths (no "..", no absolute paths), download hosts.
 * Files that cannot be downloaded (CurseForge without an API key, hosts Modrinth does not allow, dead links) are listed
 * in TARGET/.alloy/modpack-missing.txt; the user fetches them by hand into a folder and passes --pack-mods DIR. Jars in
 * that folder that match an index hash fill that entry; the others are added as extra mods.
 */
final class Modpack {
    private Modpack() { }

    /** a problem that stops the import, with a plain message for the user (exit code 2) */
    static final class Refused extends Exception {
        Refused(String m) { super(m); }
    }

    /** one file a pack wants in the game dir */
    static final class Want {
        final Pack pack;
        final String path;                // relative to the game dir, forward slashes (mods/x.jar, resourcepacks/y.zip)
        final List<String> urls;
        final String sha1, sha512;
        final Long size;
        final boolean optional;
        final String label;               // for messages
        String envClient = "required", envServer = "required";   // Modrinth env (required, optional, unsupported)
        Path file;                        // local copy once downloaded or found
        String why;                       // why it could not be fetched

        Want(Pack pack, String path, List<String> urls, String sha1, String sha512, Long size, boolean optional, String label) {
            this.pack = pack; this.path = path; this.urls = urls; this.sha1 = sha1; this.sha512 = sha512; this.size = size;
            this.optional = optional; this.label = label;
        }

        String name() { return path.substring(path.lastIndexOf('/') + 1); }
    }

    /** a pack file that was read */
    static final class Pack {
        final Path source;
        String format, name = "?", version = "", mc;
        final Map<String, String> loaders = new LinkedHashMap<>();   // forge, fabric, neoforge, quilt -> version ("" if none given)
        final List<Want> files = new ArrayList<>();
        final List<String> overrideRoots = new ArrayList<>();         // zip folders copied into the game dir, in order
        final List<String> modlist = new ArrayList<>();               // CurseForge modlist.html entries (name + link)

        boolean quilt;                                                // a quilt-loader pack, imported as a Fabric pack

        Pack(Path source) { this.source = source; }

        boolean forgeOnly() { return loaders.containsKey("forge") && !loaders.containsKey("fabric"); }
        boolean fabricOnly() { return loaders.containsKey("fabric") && !loaders.containsKey("forge"); }
        String title() { return name + (version.isEmpty() ? "" : " " + version) + " (" + source.getFileName() + ")"; }
    }

    /** a mod jar and where it goes */
    static final class Placed {
        final Path jar;
        final String name;
        final Pack pack;
        ForgeInMods.Side side;
        String why;
        String skipWhy;   // set when the jar is left out for a reason other than being a second copy
        final ModMeta.JarInfo meta;
        Set<String> forgeIds = Set.of();
        String project;                    // Modrinth project id when the jar came from the index   // top-level mods.toml ids (inline mods = [...] form too), from ForgeInMods

        Placed(Path jar, String name, Pack pack, ForgeInMods.Side side, String why) {
            this.jar = jar; this.name = name; this.pack = pack; this.side = side; this.why = why;
            this.meta = ModMeta.read(jar);
        }

        Set<String> ids() {
            Set<String> s = new TreeSet<>();
            ModMeta.Loader l = side == ForgeInMods.Side.FORGE ? ModMeta.Loader.FORGE : ModMeta.Loader.FABRIC;
            for (ModMeta.Mod m : meta.mods) if (m.loader == l) s.add(m.id);
            return s;
        }

        String version(String id) {
            for (ModMeta.Mod m : meta.mods) if (m.id.equals(id)) return m.version;
            return null;
        }
    }

    static final Set<String> MODRINTH_HOSTS = Set.of("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com");
    static final String FABRIC_API_ID = "fabric-api";

    // ------------------------------------------------------------------ reading

    static Pack read(Path p) throws Refused {
        if (!Files.isRegularFile(p)) throw new Refused("modpack file not found: " + p);
        Pack pk = new Pack(p.toAbsolutePath().normalize());
        try (ZipFile z = new ZipFile(p.toFile())) {
            if (z.getEntry("modrinth.index.json") != null) readModrinth(pk, z);
            else if (z.getEntry("manifest.json") != null) readCurseForge(pk, z);
            else throw new Refused(p.getFileName() + " is not a modpack: it has no modrinth.index.json (Modrinth) or manifest.json (CurseForge)");
        } catch (ZipException e) {
            throw new Refused(p.getFileName() + " is not a zip file, so not a .mrpack or CurseForge pack (" + e.getMessage() + ")");
        } catch (IOException e) {
            throw new Refused("cannot read " + p + ": " + e.getMessage());
        } catch (RuntimeException e) {
            throw new Refused(p.getFileName() + ": the pack index is broken (" + e + ")");
        }
        return pk;
    }

    private static String text(ZipFile z, String name) throws IOException {
        ZipEntry e = z.getEntry(name);
        if (e == null) return null;
        try (InputStream in = z.getInputStream(e)) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private static String str(Object o) { return o instanceof String s ? s : o == null ? null : String.valueOf(o); }

    static void readModrinth(Pack pk, ZipFile z) throws IOException, Refused {
        pk.format = "Modrinth";
        var m = Json.obj(Json.parse(text(z, "modrinth.index.json")));
        if (!"minecraft".equals(m.get("game"))) throw new Refused(pk.source.getFileName() + " is a pack for the game '" + m.get("game") + "', not Minecraft");
        pk.name = Objects.requireNonNullElse(str(m.get("name")), "?");
        pk.version = Objects.requireNonNullElse(str(m.get("versionId")), "");
        var deps = Json.obj(m.get("dependencies"));
        if (deps == null) deps = Map.of();
        pk.mc = str(deps.get("minecraft"));
        for (var e : deps.entrySet()) {
            switch (e.getKey()) {
                case "forge" -> pk.loaders.put("forge", str(e.getValue()));
                case "fabric-loader" -> pk.loaders.put("fabric", str(e.getValue()));
                case "neoforge" -> pk.loaders.put("neoforge", str(e.getValue()));
                case "quilt-loader" -> pk.loaders.put("quilt", str(e.getValue()));
                default -> { }
            }
        }
        Object files = m.get("files");
        if (files != null) for (Object o : Json.arr(files)) {
            var f = Json.obj(o);
            String path = str(f.get("path"));
            var h = f.get("hashes") == null ? Map.<String, Object>of() : Json.obj(f.get("hashes"));
            List<String> urls = new ArrayList<>();
            if (f.get("downloads") != null) for (Object u : Json.arr(f.get("downloads"))) urls.add(str(u));
            String envC = "required", envS = "required";
            if (f.get("env") != null) {
                var env = Json.obj(f.get("env"));
                envC = Objects.requireNonNullElse(str(env.get("client")), "required");
                envS = Objects.requireNonNullElse(str(env.get("server")), "required");
            }
            Want w = new Want(pk, path, urls, lower(str(h.get("sha1"))), lower(str(h.get("sha512"))),
                    f.get("fileSize") instanceof Long l ? l : null, false, path);
            w.envClient = envC;
            w.envServer = envS;
            pk.files.add(w);
        }
        pk.overrideRoots.add("overrides/");
    }

    static void readCurseForge(Pack pk, ZipFile z) throws IOException, Refused {
        pk.format = "CurseForge";
        var m = Json.obj(Json.parse(text(z, "manifest.json")));
        if (m.get("manifestType") != null && !"minecraftModpack".equals(m.get("manifestType")))
            throw new Refused(pk.source.getFileName() + ": manifest.json is a '" + m.get("manifestType") + "', not a Minecraft modpack");
        pk.name = Objects.requireNonNullElse(str(m.get("name")), "?");
        pk.version = Objects.requireNonNullElse(str(m.get("version")), "");
        var mcs = m.get("minecraft") == null ? Map.<String, Object>of() : Json.obj(m.get("minecraft"));
        pk.mc = str(mcs.get("version"));
        if (mcs.get("modLoaders") != null) for (Object o : Json.arr(mcs.get("modLoaders"))) {
            String id = str(Json.obj(o).get("id"));
            if (id == null) continue;
            int dash = id.indexOf('-');
            String kind = dash < 0 ? id : id.substring(0, dash), ver = dash < 0 ? "" : id.substring(dash + 1);
            pk.loaders.put(kind.equals("fabric-loader") ? "fabric" : kind, ver);
        }
        List<String> names = new ArrayList<>();
        String html = text(z, "modlist.html");
        if (html != null) {
            var mt = java.util.regex.Pattern.compile("<a\\s+href=\"([^\"]+)\"[^>]*>([^<]+)</a>").matcher(html);
            while (mt.find()) names.add(mt.group(2).strip() + "  " + mt.group(1));
        }
        pk.modlist.addAll(names);
        if (m.get("files") != null) for (Object o : Json.arr(m.get("files"))) {
            var f = Json.obj(o);
            String proj = str(f.get("projectID")), file = str(f.get("fileID"));
            boolean required = !Boolean.FALSE.equals(f.get("required"));
            String label = "CurseForge project " + proj + ", file " + file + "  (https://www.curseforge.com/projects/" + proj + ")";
            Want w = new Want(pk, null, List.of(), null, null, null, !required, label);
            w.why = "CurseForge downloads need an API key; this installer has none";
            pk.files.add(w);
        }
        String ov = str(m.get("overrides"));
        pk.overrideRoots.add((ov == null || ov.isBlank() ? "overrides" : ov.replace('\\', '/').replaceAll("/+$", "")) + "/");
    }

    private static String lower(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT); }

    /** a path from a pack must stay inside the game dir */
    static String safePath(String p) {
        if (p == null || p.isBlank()) return null;
        String s = p.replace('\\', '/');
        if (s.startsWith("/") || s.matches("^[A-Za-z]:.*") || s.contains("\0")) return null;
        for (String part : s.split("/")) if (part.equals("..")) return null;
        while (s.startsWith("./")) s = s.substring(2);
        return s.isEmpty() ? null : s;
    }

    // ------------------------------------------------------------------ checks

    /** notes for the user (not blocking); refusals throw */
    static List<String> check(List<Pack> packs) throws Refused {
        List<String> notes = new ArrayList<>();
        // A pack for another Minecraft version this installer supports switches the install to that version.
        // Only the first pack decides; every other pack must then match it.
        if (!packs.isEmpty() && packs.get(0).mc != null && !packs.get(0).mc.equals(Installer.MC) && Installer.supports(packs.get(0).mc)) {
            try {
                Installer.select(packs.get(0).mc);
                notes.add(packs.get(0).title() + " is for Minecraft " + Installer.MC + ", so this install uses Minecraft " + Installer.MC);
            } catch (java.io.IOException e) {
                throw new Refused("could not switch to Minecraft " + packs.get(0).mc + ": " + e.getMessage());
            }
        }
        String forgeLine = Installer.FV.substring(Installer.FV.indexOf('-') + 1);   // 64.1.6
        for (Pack pk : packs) {
            if (pk.mc == null) throw new Refused(pk.title() + " does not say which Minecraft version it is for");
            if (!pk.mc.equals(Installer.MC))
                throw new Refused(pk.title() + " is for Minecraft " + pk.mc + ". This install is for Minecraft " + Installer.MC
                        + " (this installer supports " + String.join(", ", Installer.versions()) + "). Use a matching version of the pack if it has one.");
            if (pk.loaders.containsKey("neoforge"))
                throw new Refused(pk.title() + " is a NeoForge pack. Alloy runs Forge and Fabric mods; NeoForge is not supported yet.");
            if (pk.loaders.containsKey("quilt")) {
                // most Quilt packs are Fabric mods plus QSL/QFAPI in place of Fabric API: import them as a Fabric pack
                // (QSL/QFAPI/QKL are left out, real Fabric API is added); a Quilt-only jar stops the import later (plan)
                pk.loaders.remove("quilt");
                pk.loaders.putIfAbsent("fabric", "");
                pk.quilt = true;
                notes.add(pk.title() + " is a Quilt pack. Alloy runs Fabric Loader, not Quilt Loader: it is imported as a Fabric pack, QSL, QFAPI and"
                        + " Quilt Kotlin Libraries are left out and the real Fabric API is used");
            }
            for (String k : pk.loaders.keySet())
                if (!k.equals("forge") && !k.equals("fabric"))
                    throw new Refused(pk.title() + " needs the mod loader '" + k + "', which Alloy does not have (it has Forge and Fabric Loader)");
            if (pk.loaders.isEmpty()) notes.add(pk.title() + " names no mod loader (a vanilla pack?); its jars are sorted by their own metadata");
            String fv = pk.loaders.get("forge");
            if (fv != null && !fv.isEmpty() && !fv.equals(forgeLine)) {
                String major = fv.split("\\.")[0], ours = forgeLine.split("\\.")[0];
                if (!major.equals(ours))
                    notes.add(pk.title() + " asks for Forge " + fv + "; Alloy runs Forge " + forgeLine + ". That is another Forge line: some mods may refuse to load");
                else notes.add(pk.title() + " asks for Forge " + fv + "; Alloy runs Forge " + forgeLine + " (same line, mods for " + fv + " normally work)");
            }
            String lv = pk.loaders.get("fabric");
            if (lv != null && !lv.isEmpty() && !lv.equals(Installer.LOADER)) {
                int c;
                try { c = Versions.cmp(lv, Installer.LOADER); } catch (IllegalArgumentException e) { c = 0; }
                notes.add(pk.title() + " asks for Fabric Loader " + lv + "; Alloy runs " + Installer.LOADER
                        + (c > 0 ? ". It is older than what the pack wants: a mod that needs the newer loader will say so at start" : " (newer, fine)"));
            }
        }
        return notes;
    }

    // ------------------------------------------------------------------ downloads

    static boolean allowedHost(String url) {
        try {
            var u = java.net.URI.create(url);
            return "https".equals(u.getScheme()) && u.getHost() != null && MODRINTH_HOSTS.contains(u.getHost().toLowerCase(Locale.ROOT));
        } catch (Exception e) { return false; }
    }

    /** downloads one Modrinth index file into store/ (named by sha1); sets w.file or w.why */
    static void download(Want w, Path store) {
        if (w.urls.isEmpty()) { w.why = "the pack gives no download link"; return; }
        List<String> ok = new ArrayList<>();
        for (String u : w.urls) if (u != null && allowedHost(u)) ok.add(u);
        if (ok.isEmpty()) { w.why = "download host not allowed by the Modrinth pack format (" + w.urls.get(0) + ")"; return; }
        if (w.sha1 == null && w.sha512 == null) { w.why = "the pack gives no hash for it, so it cannot be checked"; return; }
        String key = w.sha1 != null ? w.sha1 : w.sha512.substring(0, 40);
        Path dest = store.resolve(key).resolve(w.name());
        try {
            if (Files.exists(dest) && matches(w, dest)) { w.file = dest; return; }
            Files.createDirectories(dest.getParent());
            Exception last = null;
            for (String url : ok) {
                for (int attempt = 0; attempt < 3 && w.file == null; attempt++) {
                    try {
                        Path part = dest.resolveSibling(w.name() + ".part");
                        Files.write(part, get(url, 600));
                        if (!matches(w, part)) { Files.delete(part); throw new IOException("hash does not match the pack index"); }
                        Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
                        w.file = dest;
                    } catch (Exception e) {
                        last = e;
                        try { Thread.sleep(800L * (attempt + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                }
                if (w.file != null) return;
            }
            w.why = "download failed: " + (last == null ? "?" : last.getMessage());
        } catch (IOException e) {
            w.why = "cannot write the download: " + e.getMessage();
        }
    }

    static boolean matches(Want w, Path f) throws IOException {
        if (w.size != null && Files.size(f) != w.size) return false;
        if (w.sha1 != null && !digest(f, "SHA-1").equals(w.sha1)) return false;
        return w.sha512 == null || digest(f, "SHA-512").equals(w.sha512);
    }

    // ------------------------------------------------------------------ main flow

    /** a pack file Alloy leaves out on purpose (Sinytra Connector and its parts), with the reason */
    record Dropped(String file, String why) { }

    record Plan(List<Placed> forge, List<Placed> fabric, List<Placed> skipped, List<Want> missing, List<String> notes,
                Map<String, byte[]> files, Map<String, String> fileFrom, List<Dropped> dropped) { }

    static void cli(Map<String, String> o) throws Exception {
        String dir = o.get("dir");
        if (dir == null) throw new Refused("--modpack needs --dir TARGET (the instance folder to create)");
        String side = o.getOrDefault("side", "client");
        if (!side.equals("client") && !side.equals("server")) throw new Refused("--side must be client or server");
        List<Path> packFiles = new ArrayList<>();
        for (String s : o.get("modpack").split("\n")) if (!s.isBlank()) packFiles.add(Path.of(s.strip()));
        Path target = Path.of(dir).toAbsolutePath().normalize();
        Path cache = o.containsKey("cache") ? Path.of(o.get("cache")).toAbsolutePath().normalize() : target.resolve(".alloy-cache");
        Path handDir = o.containsKey("pack-mods") ? Path.of(o.get("pack-mods")) : null;
        boolean checkOnly = o.containsKey("check");

        Plan plan = plan(packFiles, side, cache, handDir);
        report(plan, side);
        Path missingFile = target.resolve(".alloy").resolve("modpack-missing.txt");
        List<Want> mustHave = plan.missing.stream().filter(w -> !w.optional).toList();
        if (!plan.missing.isEmpty() && !checkOnly) writeMissing(missingFile, plan.missing, packFiles, handDir);
        if (!mustHave.isEmpty()) {
            long cf = mustHave.stream().filter(w -> "CurseForge".equals(w.pack.format)).count();
            int handJars = handDir == null ? 0 : Util.jars(handDir).size();
            if (cf == mustHave.size() && handDir != null && handJars >= cf) {
                log("modpack: " + cf + " CurseForge file(s) cannot be checked one by one; using the " + handJars + " jar(s) in " + handDir);
            } else {
                throw new Refused(mustHave.size() + " file(s) of the pack could not be downloaded (list above"
                        + (checkOnly ? "" : ", also in " + missingFile) + "). Download them by hand into one folder and run again with --pack-mods FOLDER.");
            }
        }
        if (checkOnly) { log("modpack: --check only, nothing installed"); return; }

        // overrides and other pack files into the game dir, then the normal install with the sorted mod lists
        mkdirs(target);
        for (var e : plan.files.entrySet()) {
            Path d = target.resolve(e.getKey()).normalize();
            if (!d.startsWith(target)) continue;   // safePath already refused these
            Files.createDirectories(d.getParent());
            Files.write(d, e.getValue());
        }
        log("modpack: " + plan.files.size() + " config and other file(s) copied into " + target);
        Path stage = target.resolve(".alloy").resolve("modpack");
        rmTree(stage);
        List<Path> forge = stageJars(plan.forge, stage.resolve("forgemods")), fabric = stageJars(plan.fabric, stage.resolve("mods"));
        writeRecord(target, packFiles, plan, side);

        Installer in = new Installer(side, target, o.containsKey("cache") ? Path.of(o.get("cache")) : null);
        in.eula = o.containsKey("eula");
        in.noAssets = o.containsKey("no-assets");
        in.lean = o.containsKey("lean");
        in.devScripts = Main.devScripts || o.containsKey("dev-scripts");   // same flag as a normal install
        if (o.containsKey("mc-dir")) in.mcDir = Path.of(o.get("mc-dir"));
        if (o.containsKey("java")) {
            in.java = Util.javaExe(Path.of(o.get("java")), Util.WINDOWS);   // a JAVA_HOME, java itself, or a macOS .jdk bundle
        }
        in.instanceName = instanceName(packs(plan));
        in.install(forge, fabric);
        saveAlternates(target, plan);
        rmTree(stage);
        log("modpack: imported " + packs(plan) + " into " + target);
    }

    static String packs(Plan plan) {
        Set<String> t = new LinkedHashSet<>();
        for (Placed p : plan.forge) t.add(p.pack == null ? "" : p.pack.name);
        for (Placed p : plan.fabric) t.add(p.pack == null ? "" : p.pack.name);
        t.remove("");
        return t.isEmpty() ? "the pack" : String.join(" + ", t);
    }

    /** Prism instance name from the pack name(s): file-name safe, short */
    static String instanceName(String packs) {
        String n = packs.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "").replaceAll("\\s+", " ").strip();
        if (n.length() > 60) n = n.substring(0, 60).strip();
        return n.isEmpty() || n.equals("the pack") ? null : n + " (Alloy)";
    }

    static List<Path> stageJars(List<Placed> ps, Path dir) throws IOException {
        Files.createDirectories(dir);
        List<Path> out = new ArrayList<>();
        for (Placed p : ps) {
            Path d = dir.resolve(p.name);
            Files.copy(p.jar, d, StandardCopyOption.REPLACE_EXISTING);
            out.add(d);
        }
        return out;
    }

    static void rmTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) { for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(x); }
    }

    /** reads, checks and downloads; decides where every jar goes. Writes only into the download cache. */
    static Plan plan(List<Path> packFiles, String side, Path cache, Path handDir) throws Exception {
        if (packFiles.isEmpty()) throw new Refused("no modpack given");
        List<Pack> packs = new ArrayList<>();
        for (Path p : packFiles) {
            Pack pk = read(p);
            log("modpack: " + pk.format + " pack " + pk.title() + ": Minecraft " + pk.mc + ", loaders " + loaders(pk) + ", "
                    + pk.files.size() + " file(s) in the index");
            packs.add(pk);
        }
        List<String> notes = check(packs);
        boolean client = side.equals("client");

        // 1. the index files for this side
        List<Want> wants = new ArrayList<>();
        List<Dropped> dropped = new ArrayList<>(), quiltDropped = new ArrayList<>();
        Map<String, String> aliases = new LinkedHashMap<>(CONNECTOR_ALIASES);   // config/connector.json globalModAliases (Connector's defaults when it has none)
        int sideSkipped = 0;
        for (Pack pk : packs) for (Want w : pk.files) {
            if (pk.format.equals("Modrinth")) {
                String sp = safePath(w.path);
                if (sp == null) throw new Refused(pk.title() + " wants to write outside the game folder: '" + w.path + "'. The pack is unsafe, nothing was installed.");
                String env = client ? w.envClient : w.envServer;
                if ("unsupported".equals(env)) { sideSkipped++; continue; }
                // Connector parts are known from the index alone (Modrinth project or file name): not even downloaded
                String why = isModJar(sp) ? connectorPart(w) : connectorFile(sp);
                if (why != null) { dropped.add(new Dropped(sp, why)); continue; }
                String qw = isModJar(sp) ? quiltLib(projectOf(w), w.name()) : null;
                if (qw != null) { quiltDropped.add(new Dropped(sp, qw)); continue; }
            }
            wants.add(w);
        }
        if (sideSkipped > 0) log("modpack: " + sideSkipped + " file(s) are marked as not for the " + side + " and are left out");

        // 2. hand-fetched jars: by hash first
        Map<String, Path> handBySha1 = new HashMap<>();
        List<Path> handJars = handDir == null ? List.of() : Util.jars(handDir);
        if (handDir != null && !Files.isDirectory(handDir)) throw new Refused("--pack-mods folder not found: " + handDir);
        for (Path j : handJars) handBySha1.put(digest(j, "SHA-1"), j);
        Set<Path> handUsed = new HashSet<>();
        Path store = cache.resolve("modpack-files");
        List<Want> todo = new ArrayList<>();
        for (Want w : wants) {
            if (w.sha1 != null && handBySha1.containsKey(w.sha1) && matches(w, handBySha1.get(w.sha1))) {
                w.file = handBySha1.get(w.sha1);
                handUsed.add(w.file);
            } else if (w.path != null) todo.add(w);
        }
        log("modpack: downloading " + todo.size() + " file(s) (hash-checked, cached in " + store + ")");
        var ex = java.util.concurrent.Executors.newFixedThreadPool(4);
        List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
        for (Want w : todo) fs.add(ex.submit(() -> download(w, store)));
        for (var f : fs) f.get();
        ex.shutdown();
        List<Want> missing = new ArrayList<>();
        for (Want w : wants) if (w.file == null) missing.add(w);

        // 3. sort jars; other files are copied to their path
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, String> fileFrom = new HashMap<>();
        List<Placed> jars = new ArrayList<>();
        for (Want w : wants) {
            if (w.file == null) continue;
            String sp = safePath(w.path);
            if (isModJar(sp)) {
                Placed p = place(w.file, w.name(), w.pack);
                p.project = projectOf(w);
                jars.add(p);
            }
            else putFile(files, fileFrom, sp, Files.readAllBytes(w.file), w.pack, notes);
        }
        // overrides: overrides/, then client-overrides/ or server-overrides/ (Modrinth); later packs win on the same path
        List<String> unindexed = new ArrayList<>();   // mod jars from overrides or --pack-mods that no index entry names
        for (Pack pk : packs) {
            List<String> roots = new ArrayList<>(pk.overrideRoots);
            if (pk.format.equals("Modrinth")) roots.add(client ? "client-overrides/" : "server-overrides/");
            try (ZipFile z = new ZipFile(pk.source.toFile())) {
                for (String root : roots) {
                    int cacheFiles = 0;
                    for (var en = z.entries(); en.hasMoreElements(); ) {
                        ZipEntry e = en.nextElement();
                        if (e.isDirectory() || !e.getName().startsWith(root)) continue;
                        String rel = safePath(e.getName().substring(root.length()));
                        if (rel == null) throw new Refused(pk.title() + " has an override outside the game folder: '" + e.getName() + "'. Nothing was installed.");
                        if (inConnectorCache(rel)) { cacheFiles++; continue; }
                        String cw = connectorFile(rel);
                        if (cw != null) {
                            if (rel.equalsIgnoreCase("config/connector.json"))
                                try (InputStream in = z.getInputStream(e)) {
                                    Map<String, String> own = readConnectorJson(in.readAllBytes(), notes);
                                    if (own != null) { aliases.clear(); aliases.putAll(own); }
                                }
                            dropped.add(new Dropped(rel, cw));
                            continue;
                        }
                        byte[] b;
                        try (InputStream in = z.getInputStream(e)) { b = in.readAllBytes(); }
                        if (!isModJar(rel) && rel.startsWith("mods/") && rel.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                            // a jar in a folder under mods/ (no loader reads it there); a renamed Connector there is still dropped
                            Path tmp = cache.resolve("modpack-files").resolve("overrides").resolve(sha1(b)).resolve(rel.substring(rel.lastIndexOf('/') + 1));
                            Files.createDirectories(tmp.getParent());
                            if (!Files.exists(tmp)) Files.write(tmp, b);
                            String nw = connectorPart(place(tmp, tmp.getFileName().toString(), pk));
                            if (nw != null) { dropped.add(new Dropped(rel, nw)); continue; }
                        }
                        if (isModJar(rel)) {
                            Path tmp = cache.resolve("modpack-files").resolve("overrides").resolve(sha1(b)).resolve(rel.substring(rel.lastIndexOf('/') + 1));
                            Files.createDirectories(tmp.getParent());
                            if (!Files.exists(tmp)) Files.write(tmp, b);
                            jars.add(place(tmp, tmp.getFileName().toString(), pk));
                            unindexed.add(rel);
                        } else putFile(files, fileFrom, rel, b, pk, notes);
                    }
                    if (cacheFiles > 0) dropped.add(new Dropped(root + CONNECTOR_CACHE + " (" + cacheFiles + " file(s))",
                            "Sinytra Connector's cache of Fabric jars it remapped for Forge. Alloy loads the Fabric jars themselves and does not use it"));
                }
            }
        }
        // hand-fetched jars that match no index file are extra mods; ones that match a file left out for this side stay out
        Set<String> indexSha1 = new HashSet<>();
        for (Pack pk : packs) for (Want x : pk.files) if (x.sha1 != null) indexSha1.add(x.sha1);
        for (Path j : handJars) if (!handUsed.contains(j) && !indexSha1.contains(digest(j, "SHA-1"))) {
            jars.add(place(j, j.getFileName().toString(), null));
            unindexed.add(j.getFileName().toString() + " (--pack-mods)");
        }
        // like Prism's "untrusted mods" list: these jars came from no index entry, so no download host or hash vouches for them
        if (!unindexed.isEmpty())
            notes.add(unindexed.size() + " mod jar(s) are not from any pack index, so nothing checked where they came from (they come from the pack's"
                    + " overrides folder or were added by hand). Only keep them if you trust the pack author: " + String.join(", ", unindexed));

        // 4. not for Alloy: NeoForge-only jars, broken jars
        List<Placed> forge = new ArrayList<>(), fabric = new ArrayList<>(), skipped = new ArrayList<>();
        for (Placed p : jars) {
            String cw = connectorPart(p);
            if (cw != null) { dropped.add(new Dropped(p.name, cw)); continue; }
            String qw = quiltLib(p.project, p.name);
            if (qw != null) { quiltDropped.add(new Dropped(p.name, qw)); continue; }
            if (p.side == ForgeInMods.Side.FORGE) forge.add(p);
            else if (p.side == ForgeInMods.Side.FABRIC) fabric.add(p);
            else if (p.meta.error == null && p.meta.loaders.isEmpty()) {
                // no mod metadata (a plain library, or a jar some mod reads itself): kept in mods/ where the pack put it; no loader reads it
                putFile(files, fileFrom, "mods/" + p.name, Files.readAllBytes(p.jar), p.pack, notes);
                notes.add(p.name + " has no Forge or Fabric metadata: copied to mods/ as the pack has it, no loader loads it");
            } else skipped.add(p);
        }
        boolean nestedApi = nestedFfapi(forge, cache, notes);
        distantHorizons(forge, fabric, notes);
        if (!client) clientOnly(forge, fabric, skipped, notes);
        if (!dropped.isEmpty()) connectorBuilds(forge, fabric, dropped, notes, cache);
        dedupe(forge, skipped, notes, "Forge");
        dedupe(fabric, skipped, notes, "Fabric");
        foreignWrongMc(fabric, skipped, notes);
        crossDupes(forge, fabric, skipped, notes);
        frapiExtras(forge, fabric, skipped, notes);
        forgeFirst(forge, fabric, skipped, notes, (p, id) -> modrinthForge(p.project != null ? p.project : slugFor(id), id, p.version(id), cache,
                "Forge build of '" + id + "' from Modrinth (in place of the pack's Fabric build " + p.name + ")"));
        modernFixVehicleOff(forge, files, fileFrom, notes);
        boolean ffapiDropped = dropped.stream().anyMatch(d -> d.why().equals(FFAPI_WHY));
        if (!dropped.isEmpty()) notes.add(dropped.size() + " Sinytra Connector file(s) left out (list above): Alloy runs the pack's Fabric mods itself"
                + (ffapiDropped ? ", with its own fitted Fabric API in place of Forgified Fabric API" : ""));
        if (!dropped.isEmpty()) forgeNeedsFfapi(forge, fabric, notes, ffapiDropped, skipped);
        quiltOnly(packs, skipped);
        if (!quiltDropped.isEmpty() && !fabric.isEmpty())
            notes.add(quiltDropped.size() + " Quilt library file(s) left out (QSL, QFAPI or Quilt Kotlin Libraries): they only run on Quilt Loader and"
                    + " clash with the real Fabric API, which Alloy uses");
        fabricApi(fabric, cache, notes, ffapiDropped || !quiltDropped.isEmpty(), nestedApi);
        if (!dropped.isEmpty()) connectorAliases(forge, fabric, notes, cache, aliases, dropped.stream().anyMatch(d -> d.why().equals(EXTRAS_WHY)));
        if (client) modMenuForConfigs(fabric, notes, () -> modrinthFabric("modmenu", "modmenu", null, List.of(), cache,
                "Mod Menu from Modrinth (the pack's Fabric mods have config screens only Mod Menu opens)"));
        dropped.addAll(quiltDropped);
        return new Plan(forge, fabric, skipped, missing, notes, files, fileFrom, dropped);
    }

    /**
     * Forge jars that nest Forgified Fabric API modules (Accessories, ClickSigns): a copy without them is used, in the
     * download cache, so Forge's JarJar does not load a second Fabric API on the Forge side (NestedFfapi). True when a jar was
     * changed: that mod now gets its Fabric API classes from the real Fabric API, so the pack needs one in mods/.
     */
    static boolean nestedFfapi(List<Placed> forge, Path cache, List<String> notes) throws IOException {
        boolean any = false;
        for (int i = 0; i < forge.size(); i++) {
            Placed p = forge.get(i);
            if (NestedFfapi.find(p.jar).isEmpty()) continue;
            Path out = cache.resolve("modpack-files").resolve("ffapi-stripped").resolve(digest(p.jar, "SHA-1")).resolve(p.name);
            List<String> gone = NestedFfapi.strip(p.jar, out);
            Placed q = new Placed(out, p.name, p.pack, p.side, p.why + "; nested Forgified Fabric API removed");
            q.project = p.project;
            q.forgeIds = p.forgeIds;
            forge.set(i, q);
            any = true;
            for (String g : gone) log("modpack: dropped " + p.name + "!" + g.substring(g.lastIndexOf('/') + 1) + " (Forgified Fabric API module inside a Forge mod)");
            notes.add(p.name + " carries " + gone.size() + " Forgified Fabric API module(s) inside it; they are removed from Alloy's copy, because Forge would"
                    + " load them next to the real Fabric API. If this mod then misses a Fabric API class at start, use its Fabric build");
        }
        return any;
    }

    // ------------------------------------------------------------------ Quilt
    // QSL with QFAPI (Modrinth qvIfYCYJ) and Quilt Kotlin Libraries (lwVhp9o5) run only on Quilt Loader, and QFAPI replaces
    // Fabric API (Quilt says never to install both). Some big multi-loader mods (Dynamic Lights, Spawn Animations) list QSL
    // as required on Fabric files next to Fabric API, so a resolver that adds every required dependency would add it.
    static final String QSL_WHY = "QSL / Quilted Fabric API: Quilt's copy of Fabric API, runs only on Quilt Loader and clashes with the real Fabric API Alloy uses";
    static final String QKL_WHY = "Quilt Kotlin Libraries: runs only on Quilt Loader (Fabric Language Kotlin does this job on Fabric)";
    static final Map<String, String> QUILT_PROJECTS = Map.of("qvIfYCYJ", QSL_WHY, "lwVhp9o5", QKL_WHY);
    /** qfapi-7.7.0_qsl-6.3.0_fapi-0.92.2_mc-1.20.1.jar, qsl-6.1.2+1.20.1.jar, quilted-fabric-api-*.jar, qkl-*.jar */
    static final java.util.regex.Pattern QSL_NAME = java.util.regex.Pattern.compile("(?i)(qfapi|qsl|quilted[-_]fabric[-_]api)[-_+][^/]*\\.jar");
    static final java.util.regex.Pattern QKL_NAME = java.util.regex.Pattern.compile("(?i)(qkl|quilt[-_]kotlin[-_]libraries)[-_+][^/]*\\.jar");
    /** mod ids and Modrinth slugs of those libraries: never fetched as a dependency */
    static final Set<String> QUILT_LIB_IDS = Set.of("qsl", "quilted_fabric_api", "quilted-fabric-api", "qkl", "quilt_kotlin_libraries", "quilt-kotlin-libraries",
            "qvIfYCYJ", "lwVhp9o5");

    /** why a jar (Modrinth project id or null, file name) is a Quilt library Alloy leaves out, or null */
    static String quiltLib(String project, String name) {
        if (project != null && QUILT_PROJECTS.containsKey(project)) return QUILT_PROJECTS.get(project);
        if (name != null && QSL_NAME.matcher(name).matches()) return QSL_WHY;
        if (name != null && QKL_NAME.matcher(name).matches()) return QKL_WHY;
        return null;
    }

    /** a Quilt pack imports as a Fabric pack only when none of its jars is Quilt-only (quilt.mod.json and no fabric.mod.json) */
    static void quiltOnly(List<Pack> packs, List<Placed> skipped) throws Refused {
        for (Pack pk : packs) {
            if (!pk.quilt) continue;
            List<String> q = new ArrayList<>();
            for (Placed p : skipped)
                if (p.pack == pk && p.meta.loaders.contains(ModMeta.Loader.QUILT) && !p.meta.loaders.contains(ModMeta.Loader.FABRIC)) q.add(p.name);
            if (!q.isEmpty())
                throw new Refused(pk.title() + " is a Quilt pack with " + q.size() + " Quilt-only mod(s): " + String.join(", ", q)
                        + ". Alloy runs Fabric Loader, which cannot load them. If these mods have Fabric builds, or the pack has a Fabric version, use those.");
        }
    }

    /** true for a jar with both a Forge and a Fabric half */
    static boolean multiLoader(Placed p) {
        return p.meta.mods.stream().anyMatch(m -> m.loader == ModMeta.Loader.FORGE) && p.meta.mods.stream().anyMatch(m -> m.loader == ModMeta.Loader.FABRIC);
    }

    /**
     * Distant Horizons (one 1.20.1 jar with both halves) goes to the side of the pack's renderer and shader mod
     * (ForgeInMods.dhRoute): with Embeddium + Oculus on Forge it must be a Forge mod, or Oculus never sees it.
     */
    static void distantHorizons(List<Placed> forge, List<Placed> fabric, List<String> notes) {
        Set<String> forgeIds = new TreeSet<>(), fabricIds = new TreeSet<>();
        List<Placed> dh = new ArrayList<>();
        for (Placed p : forge) { if (p.meta.mods.stream().anyMatch(m -> m.id.equals(ForgeInMods.DH_ID)) && multiLoader(p)) dh.add(p); else forgeIds.addAll(p.ids()); }
        for (Placed p : fabric) { if (p.meta.mods.stream().anyMatch(m -> m.id.equals(ForgeInMods.DH_ID)) && multiLoader(p)) dh.add(p); else fabricIds.addAll(p.ids()); }
        if (dh.isEmpty()) return;
        ForgeInMods.DhRoute r = ForgeInMods.dhRoute(forgeIds, fabricIds);
        if (r == null) return;
        for (Placed p : dh) {
            List<Placed> from = p.side == ForgeInMods.Side.FORGE ? forge : fabric, to = r.side() == ForgeInMods.Side.FORGE ? forge : fabric;
            if (from == to) { p.why = p.why + "; Distant Horizons stays here: " + r.why(); continue; }
            from.remove(p);
            to.add(p);
            p.side = r.side();
            p.why = "Distant Horizons (one jar for both loaders) on the " + (r.side() == ForgeInMods.Side.FORGE ? "Forge" : "Fabric") + " side: " + r.why();
            notes.add(p.name + " moved to the " + (r.side() == ForgeInMods.Side.FORGE ? "Forge side (forgemods/)" : "Fabric side (mods/)")
                    + " because " + r.why() + "; its other half would not see the shader mod");
        }
    }

    /**
     * Server import: jars whose mods all say they are client-only in their own metadata (fabric.mod.json "environment":
     * "client", mods.toml clientSideOnly) are left out. A Modrinth index marks most of them already; CurseForge packs and
     * overrides have no such marks, so the jar's metadata decides. A Fabric server would skip them at start anyway; Forge
     * would load them and they often crash on a server (client classes).
     */
    static void clientOnly(List<Placed> forge, List<Placed> fabric, List<Placed> skipped, List<String> notes) {
        int n = 0;
        for (List<Placed> list : List.of(forge, fabric)) for (Iterator<Placed> it = list.iterator(); it.hasNext(); ) {
            Placed p = it.next();
            ModMeta.Loader want = list == forge ? ModMeta.Loader.FORGE : ModMeta.Loader.FABRIC;
            List<ModMeta.Mod> mods = p.meta.mods.stream().filter(m -> m.loader == want).toList();
            if (mods.isEmpty() || !mods.stream().allMatch(m -> "client".equals(m.env))) continue;
            it.remove();
            p.skipWhy = "client-only mod (its " + (want == ModMeta.Loader.FORGE ? "mods.toml" : "fabric.mod.json") + " says so): a server does not use it";
            skipped.add(p);
            n++;
        }
        if (n > 0) notes.add(n + " client-only jar(s) left out of the server (their own metadata says client only)");
    }

    static boolean isModJar(String rel) {
        return rel != null && rel.startsWith("mods/") && rel.indexOf('/', 5) < 0 && rel.toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    static void putFile(Map<String, byte[]> files, Map<String, String> from, String rel, byte[] b, Pack pk, List<String> notes) {
        String who = pk == null ? "--pack-mods" : pk.title();
        byte[] old = files.put(rel, b);
        String was = from.put(rel, who);
        if (old != null && was != null && !was.equals(who) && !Arrays.equals(old, b))
            notes.add(rel + " is in two packs; the copy from " + who + " is used");
    }

    /** where one jar goes: same rules as Forge jars in mods/, but a multi-loader jar follows its pack's loader */
    static Placed place(Path jar, String name, Pack pk) {
        ForgeInMods.Verdict v = ForgeInMods.classify(jar, Map.of());
        ForgeInMods.Side s = v.side();
        String why = v.why();
        if (v.multi() && pk != null && !ForgeInMods.wrongLoaderStub(v.fabricIds())) {
            if (pk.forgeOnly()) { s = ForgeInMods.Side.FORGE; why = "multi-loader jar in a Forge pack: Forge"; }
            else if (pk.fabricOnly()) { s = ForgeInMods.Side.FABRIC; why = "multi-loader jar in a Fabric pack: Fabric"; }
        }
        Placed p = new Placed(jar, name, pk, s, why);
        p.forgeIds = v.forgeIds();
        // A Fabric mod packed for Connector (Continuity's "forge" build): its mods.toml is only a placeholder that needs
        // Connector. The jar is a real Fabric mod, so it goes to mods/ (connectorBuilds may swap it for the upstream build).
        if (v.multi() && !v.overridden() && p.side == ForgeInMods.Side.FORGE && needsConnector(p)) {
            p.side = ForgeInMods.Side.FABRIC;
            p.why = "Fabric mod packed for Sinytra Connector (its mods.toml needs Connector): Fabric";
        }
        return p;
    }

    /** two jars of one mod id on one side: keep the newer one */
    static void dedupe(List<Placed> list, List<Placed> skipped, List<String> notes, String sideName) {
        Map<String, Placed> byId = new LinkedHashMap<>();
        for (Placed p : new ArrayList<>(list)) {
            if (!list.contains(p)) continue;
            for (String id : p.ids()) {
                Placed old = byId.get(id);
                if (old == null || old == p) { byId.put(id, p); continue; }
                Placed keep = newer(old, p, id), drop = keep == old ? p : old;
                list.remove(drop);
                skipped.add(drop);
                byId.put(id, keep);
                notes.add(sideName + " mod '" + id + "' is in the packs twice (" + old.name + ", " + p.name + "): kept " + keep.name);
                break;
            }
        }
    }

    /** which copy to keep: one built for this Minecraft version beats one built for another, then the newer one wins */
    static Placed newer(Placed a, Placed b, String id) {
        Boolean fa = mcFits(a), fb = mcFits(b);
        if (Boolean.FALSE.equals(fa) && !Boolean.FALSE.equals(fb)) return b;
        if (Boolean.FALSE.equals(fb) && !Boolean.FALSE.equals(fa)) return a;
        // a Fabric jar from a Fabric pack beats one from a Forge-only pack (and the other way round): the pack of the
        // jar's own loader was tested as a set, so its copy matches its other mods (Iris and the Sodium it needs)
        boolean ha = home(a), hb = home(b);
        if (ha != hb) return ha ? a : b;
        String va = a.version(id), vb = b.version(id);
        try { return Versions.cmp(vb, va) > 0 ? b : a; } catch (Exception e) { return a; }
    }

    static boolean home(Placed p) {
        if (p.pack == null) return false;
        return p.side == ForgeInMods.Side.FORGE ? p.pack.loaders.containsKey("forge") : p.pack.loaders.containsKey("fabric");
    }

    /** does the jar's own minecraft dependency allow this installer's version? FALSE = built for another version, null = no range */
    static Boolean mcFits(Placed p) {
        ModMeta.Loader l = p.side == ForgeInMods.Side.FORGE ? ModMeta.Loader.FORGE : ModMeta.Loader.FABRIC;
        Boolean fit = null;
        for (ModMeta.Mod m : p.meta.mods) {
            if (m.loader != l) continue;
            for (ModMeta.Dep d : m.deps) {
                if (!d.id().equals("minecraft") || d.incompatible() || !d.mandatory() || d.range() == null) continue;
                Boolean ok;
                try { ok = Versions.matches(d.range(), Installer.MC); } catch (Exception e) { ok = null; }
                if (Boolean.FALSE.equals(ok)) return false;
                if (Boolean.TRUE.equals(ok)) fit = true;
            }
            // no minecraft range: a Fabric API range like ">=0.155.0+26.2" still says which Minecraft the jar is for
            if (fit == null) for (ModMeta.Dep d : m.deps)
                if (d.id().equals(FABRIC_API_ID) && d.range() != null && !d.incompatible()) {
                    var mm = java.util.regex.Pattern.compile("\\+(\\d+\\.\\d+(?:\\.\\d+)?)\\b").matcher(d.range());
                    if (mm.find()) {
                        if (!sameMc(mm.group(1))) return false;
                        fit = true;
                    }
                }
        }
        return fit;
    }

    /** "26.1" and "26.1.2" both name the 26.1.2 install; "26.2" does not */
    static boolean sameMc(String v) {
        return Installer.MC.equals(v) || Installer.MC.startsWith(v + ".");
    }

    static final java.util.regex.Pattern MC_IN_NAME = java.util.regex.Pattern.compile("(?i)(?:mc|[-+_])((?:1\\.(?:1[4-9]|2\\d)|2[6-9]\\.\\d+)(?:\\.\\d+)?)(?=[-+_]|\\.jar$)");

    /** a Minecraft version in the file name (appleskin-fabric-mc26.2-3.0.10.jar): FALSE if every one names another version */
    static Boolean mcFromName(String name) {
        var m = MC_IN_NAME.matcher(name);
        boolean any = false;
        while (m.find()) {
            any = true;
            if (sameMc(m.group(1))) return true;
        }
        return any ? false : null;
    }

    /** the minecraft range a jar asks for (for messages) */
    static String mcRange(Placed p) {
        for (ModMeta.Mod m : p.meta.mods) for (ModMeta.Dep d : m.deps) if (d.id().equals("minecraft") && d.range() != null) return d.range();
        for (ModMeta.Mod m : p.meta.mods) for (ModMeta.Dep d : m.deps)
            if (d.id().equals(FABRIC_API_ID) && d.range() != null && d.range().contains("+")) return d.range().substring(d.range().indexOf('+') + 1) + " (from its Fabric API range)";
        var nm = MC_IN_NAME.matcher(p.name);
        if (nm.find()) return nm.group(1) + " (from its file name)";
        return "?";
    }

    /**
     * A Forge-only pack can carry Fabric jars. Real Forge never loads them, Alloy would. Those built for another Minecraft
     * version cannot work here, so they are left out (with any Fabric jar of the same pack that then misses a dependency).
     * Fabric jars of a Forge pack that fit this version still load: that is what Alloy adds.
     */
    static void foreignWrongMc(List<Placed> fabric, List<Placed> skipped, List<String> notes) {
        List<Placed> out = new ArrayList<>();
        for (Placed p : fabric)
            if (p.pack != null && p.pack.forgeOnly() && (Boolean.FALSE.equals(mcFits(p))
                    || (mcFits(p) == null && Boolean.FALSE.equals(mcFromName(p.name))))) out.add(p);
        if (out.isEmpty()) return;
        // dependents: a Fabric jar from a Forge-only pack that needs a mod only the left-out jars provide
        boolean grew = true;
        while (grew) {
            grew = false;
            Set<String> have = new HashSet<>(List.of("minecraft", "java", "fabricloader", "fabric-loader", "mixinextras"));
            for (Placed p : fabric) if (!out.contains(p)) for (ModMeta.Mod m : p.meta.allMods()) { have.add(m.id); have.addAll(m.provides); }
            for (Placed p : fabric) {
                if (out.contains(p) || p.pack == null || !p.pack.forgeOnly()) continue;
                for (ModMeta.Mod m : p.meta.mods) for (ModMeta.Dep d : m.deps)
                    if (d.mandatory() && !d.incompatible() && !have.contains(d.id()) && !out.contains(p)) { out.add(p); grew = true; }
            }
        }
        for (Placed p : out) {
            fabric.remove(p);
            skipped.add(p);
            p.skipWhy = Boolean.FALSE.equals(mcFits(p)) || (mcFits(p) == null && Boolean.FALSE.equals(mcFromName(p.name)))
                    ? "Fabric jar in a Forge pack, built for Minecraft " + mcRange(p) + ": real Forge would not load it either"
                    : "Fabric jar in a Forge pack that needs a left-out jar";
        }
        notes.add(out.size() + " Fabric jar(s) of a Forge-only pack are built for another Minecraft version (or need one that is) and are left out."
                + " Real Forge ignores Fabric jars, so the pack loses nothing it had on Forge");
    }

    /**
     * One mod id as a Forge build and as a Fabric build (two pack halves often both have it): both would register the same
     * blocks and items. The Forge build is kept (it needs no fitting), unless a Fabric mod of the pack needs the Fabric build
     * (Fabric mods cannot use Forge mods), then both stay.
     */
    static void crossDupes(List<Placed> forge, List<Placed> fabric, List<Placed> skipped, List<String> notes) {
        Map<String, Placed> forgeIds = new HashMap<>();
        for (Placed p : forge) for (String id : p.ids()) forgeIds.putIfAbsent(id, p);
        Set<String> fabricNeeds = new HashSet<>();
        for (Placed p : fabric) for (ModMeta.Mod m : p.meta.mods) for (ModMeta.Dep d : m.deps) if (d.mandatory() && !d.incompatible()) fabricNeeds.add(d.id());
        for (Placed p : new ArrayList<>(fabric)) {
            Set<String> ids = p.ids();
            if (ids.isEmpty() || ids.contains(FABRIC_API_ID)) continue;
            String both = ids.stream().filter(forgeIds::containsKey).findFirst().orElse(null);
            if (both == null) continue;
            if (ids.stream().anyMatch(fabricNeeds::contains)) {
                Placed fp = forgeIds.get(both);
                if (forgeNeeds(forge, fp, both)) {
                    notes.add("mod '" + both + "' comes as a Forge build (" + fp.name + ") and a Fabric build (" + p.name
                            + "); both are kept because Fabric mods need the Fabric build and Forge mods need the Forge build"
                            + (alloy.ideas.ModDoctor.SAFE_TWINS.contains(both) ? "" : ". Two builds of one mod share one class space on Alloy and"
                            + " may clash; if the game fails, use the other loader's build of one group of mods"));
                    continue;
                }
                // only Fabric mods need it: keep the Fabric build. Two builds of one library on Knot's class path share class
                // and mixin config names (Forge Config API Port: 'Non-unique Mixin config name' at start)
                forge.remove(fp);
                skipped.add(fp);
                notes.add("mod '" + both + "' comes as a Forge build (" + fp.name + ") and a Fabric build (" + p.name
                        + "): kept the Fabric build, a Fabric mod needs it and no other Forge mod needs the Forge build");
                forgeIds.remove(both);
                continue;
            }
            fabric.remove(p);
            skipped.add(p);
            notes.add("mod '" + both + "' comes as a Forge build (" + forgeIds.get(both).name + ") and a Fabric build (" + p.name + "): kept the Forge build");
        }
    }

    /** mods whose Forge and Fabric builds are the same code: on 1.20.x the Forge build is picked */
    static final Set<String> FORGE_FIRST = Set.of("ferritecore", "modernfix", "immediatelyfast", "entityculling", "clumps", "dynamic_fps",
            "badoptimizations", "noisium");

    /**
     * Forge-first swap on Minecraft 1.20.x: a Fabric build of a FORGE_FIRST mod that no Fabric mod requires is replaced with
     * the same Modrinth project's Forge build (fetch: hash checked, Modrinth hosts only). The Forge build runs without fitting
     * and sees Forge's events. Off with -Dalloy.modpack.forgefirst=false. Nothing changes when no Forge build is found.
     */
    static final String MF_CONFIG = "config/modernfix-mixins.properties", MF_VEHICLE = "mixin.bugfix.forge_vehicle_packets";

    /**
     * Forge ModernFix before 5.18.0 crashes at launch on Forge 47 (its vehicle packet mixin; Mod Doctor blocks it). On a
     * 1.20.1 import (the pack's own Forge build or the Forge-first swap) that one mixin is turned off with ModernFix's own
     * switch in config/modernfix-mixins.properties, the fix Mod Doctor names, so the pack starts. Other lines are kept.
     */
    static void modernFixVehicleOff(List<Placed> forge, Map<String, byte[]> files, Map<String, String> fileFrom, List<String> notes) {
        if (!"1.20.1".equals(Installer.MC)) return;
        for (Placed p : forge) {
            String v = p.ids().contains("modernfix") ? p.version("modernfix") : null;
            if (v == null || alloy.ideas.ModDoctor.cmpBase(v, alloy.ideas.ModDoctor.MODERNFIX_FIXED) >= 0) continue;
            byte[] old = files.get(MF_CONFIG);
            StringBuilder sb = new StringBuilder();
            if (old != null) for (String l : new String(old, StandardCharsets.UTF_8).split("\\R")) {
                String k = l.contains("=") ? l.substring(0, l.indexOf('=')).strip() : "";
                if (k.equals(MF_VEHICLE)) continue;
                sb.append(l).append('\n');
            }
            sb.append(MF_VEHICLE).append("=false\n");
            files.put(MF_CONFIG, sb.toString().getBytes(StandardCharsets.UTF_8));
            fileFrom.putIfAbsent(MF_CONFIG, "Alloy");
            notes.add("ModernFix " + v + " (Forge build) crashes at launch on Forge 47 with its vehicle packet fix on: turned that one fix off in "
                    + MF_CONFIG + " (" + MF_VEHICLE + "=false). ModernFix " + alloy.ideas.ModDoctor.MODERNFIX_FIXED + " and newer do not need this");
            return;
        }
    }

    static void forgeFirst(List<Placed> forge, List<Placed> fabric, List<Placed> skipped, List<String> notes,
                           java.util.function.BiFunction<Placed, String, Placed> fetch) {
        if (!"1.20".equals(Installer.MC) && !Installer.MC.startsWith("1.20.")) return;
        if ("false".equalsIgnoreCase(System.getProperty("alloy.modpack.forgefirst", "true"))) return;
        Set<String> fabricNeeds = new HashSet<>(), forgeHas = new HashSet<>();
        for (Placed p : fabric) for (ModMeta.Mod m : p.meta.mods) for (ModMeta.Dep d : m.deps) if (d.mandatory() && !d.incompatible()) fabricNeeds.add(d.id());
        for (Placed p : forge) forgeHas.addAll(p.ids());
        for (Placed p : new ArrayList<>(fabric)) {
            Set<String> ids = p.ids();
            String id = ids.stream().filter(FORGE_FIRST::contains).findFirst().orElse(null);
            if (id == null || ids.size() > 1 || fabricNeeds.contains(id) || forgeHas.contains(id)) continue;
            Placed got = fetch.apply(p, id);
            if (got == null || !got.ids().contains(id)) continue;
            fabric.remove(p);
            p.skipWhy = "Fabric build of '" + id + "': replaced with its Forge build " + got.name + " (same mod, no fitting needed, sees Forge's events)";
            skipped.add(p);
            forge.add(got);
            forgeHas.add(id);
            notes.add("mod '" + id + "': used the Forge build " + got.name + " in place of the pack's Fabric build " + p.name
                    + " (Forge first on Minecraft " + Installer.MC + "; turn off with -Dalloy.modpack.forgefirst=false)");
        }
    }

    /**
     * Embeddium 0.3.20+ has Fabric Rendering API support built in. Lazurite (the old Forge port of Indium, 17 packs) is then
     * left out, and so is Indium (Sodium's FRAPI renderer) when the pack has no Sodium: next to Embeddium it takes over the
     * renderer that Embeddium's own support expects.
     */
    static void frapiExtras(List<Placed> forge, List<Placed> fabric, List<Placed> skipped, List<String> notes) {
        String emb = null;
        for (Placed p : forge) {
            String v = p.ids().contains("embeddium") ? p.version("embeddium") : null;
            if (v != null && alloy.ideas.ModDoctor.cmpBase(v, alloy.ideas.ModDoctor.EMBEDDIUM_FRAPI) >= 0) emb = v;
        }
        if (emb == null) return;
        boolean sodium = fabric.stream().anyMatch(p -> p.ids().contains("sodium"));
        for (List<Placed> list : List.of(forge, fabric)) for (Placed p : new ArrayList<>(list)) {
            boolean laz = list == forge && p.ids().contains("lazurite"), ind = list == fabric && !sodium && p.ids().contains("indium");
            if (!laz && !ind) continue;
            String id = laz ? "lazurite" : "indium";
            if (list.stream().anyMatch(q -> q != p && q.meta.mods.stream().anyMatch(m -> m.deps.stream().anyMatch(d -> d.id().equals(id) && d.mandatory() && !d.incompatible())))) {
                notes.add(p.name + " is not needed with Embeddium " + emb + " (Fabric Rendering API built in), but a mod of the pack requires it, so it stays");
                continue;
            }
            list.remove(p);
            skipped.add(p);
            p.skipWhy = (laz ? "Lazurite" : "Indium") + " is not needed with Embeddium " + emb + ", which has Fabric Rendering API support built in"
                    + (laz ? "" : " (Indium would take over its renderer)");
            notes.add(p.name + " left out: " + p.skipWhy);
        }
    }

    /** does a Forge mod other than the jar itself have a mandatory dependency on the id? */
    static boolean forgeNeeds(List<Placed> forge, Placed self, String id) {
        for (Placed q : forge) {
            if (q == self) continue;
            for (ModMeta.Mod m : q.meta.mods) for (ModMeta.Dep d : m.deps)
                if (d.id().equals(id) && d.mandatory() && !d.incompatible()) return true;
        }
        return false;
    }

    static String fabricApiRel(String v) { return "net/fabricmc/fabric-api/fabric-api/" + v + "/fabric-api-" + v + ".jar"; }

    /** the Fabric API version the installer has a hand-fitted copy of for the selected Minecraft version (prefit/index.txt) */
    static String shippedFabricApi() {
        String idx = Fit.readResourceText("/prefit/index.txt");
        if (idx == null) return null;
        String first = null;
        for (String l : idx.split("\\R")) {
            String[] p = l.split("\\|");
            if (p.length != 3 || !p[0].equals(FABRIC_API_ID)) continue;
            if (p[1].endsWith("+" + Installer.MC)) return p[1];   // 0.161.0+26.2 for a 26.2 install
            if (first == null) first = p[1];
        }
        return first;
    }

    /**
     * Fabric mods need Fabric API. Alloy ships a hand-fitted copy of one version; an older Fabric API in the pack is swapped for
     * it (mods that work with the older one work with the newer one), a missing one is added. A newer one is kept and fitted.
     */
    static void fabricApi(List<Placed> fabric, Path cache, List<String> notes, boolean ffapiDropped, boolean nestedApi) throws Exception {
        boolean needs = ffapiDropped && !fabric.isEmpty();   // Connector packs' Fabric mods counted on Forgified Fabric API
        needs |= nestedApi;   // a Forge mod whose nested Forgified Fabric API was removed (Accessories) uses the real one
        Placed api = null;
        for (Placed p : fabric) {
            if (p.ids().contains(FABRIC_API_ID)) api = p;
            else for (ModMeta.Mod m : p.meta.mods) for (ModMeta.Dep d : m.deps)
                if (d.mandatory() && !d.incompatible() && (d.id().equals(FABRIC_API_ID) || d.id().equals("fabric")
                        || (d.id().startsWith("fabric-") && !d.id().startsWith("fabric-language-")))) needs = true;
        }
        String ship = shippedFabricApi();
        if (ship == null) return;
        if (api != null) {
            String have = api.version(FABRIC_API_ID);
            int c;
            try { c = Versions.cmp(have, ship); } catch (Exception e) { c = 1; }
            if (have != null && have.equals(ship)) return;
            if (c > 0) { notes.add("the pack has Fabric API " + have + ", newer than Alloy's fitted " + ship + ": kept, it is fitted on the first start"); return; }
            fabric.remove(api);
            notes.add("the pack's Fabric API " + have + " is replaced with " + ship + ", the version Alloy ships a fitted copy of");
        } else if (!needs) return;
        else if (nestedApi && !ffapiDropped) notes.add("added Fabric API " + ship + " (Alloy's fitted copy): it gives the Forge mods whose nested Forgified Fabric API was removed their Fabric API classes");
        else if (ffapiDropped) notes.add("added Fabric API " + ship + " (Alloy's fitted copy) in place of the pack's Forgified Fabric API");
        else notes.add("the pack has Fabric mods that need Fabric API but no Fabric API jar: added Fabric API " + ship);
        Path dest = cache.resolve("modpack-files").resolve("fabric-api").resolve("fabric-api-" + ship + ".jar");
        fetchPinned(List.of(Installer.FABRIC_MAVEN), fabricApiRel(ship), dest, "Fabric API " + ship);
        fabric.add(new Placed(dest, dest.getFileName().toString(), null, ForgeInMods.Side.FABRIC, "Fabric API (Alloy's version)"));
    }

    // ------------------------------------------------------------------ Sinytra Connector packs

    // Many 1.20.1 Forge packs run their Fabric mods through Sinytra Connector. Alloy does that job itself, and Connector
    // next to Alloy patches the same things twice. So Connector, Connector Extras and Forgified Fabric API (Connector's
    // Forge copy of Fabric API) are left out, with Connector's jar cache and config; the real fitted Fabric API is used.
    static final String CONNECTOR_WHY = "Sinytra Connector: it runs Fabric mods on Forge, which Alloy does itself. The two together clash";
    static final String EXTRAS_WHY = "Connector Extras: bridges for Sinytra Connector, not needed on Alloy (Alloy has its own bridges)";
    static final String FFAPI_WHY = "Forgified Fabric API: Connector's Forge copy of Fabric API. Alloy uses the real Fabric API";
    static final String CONFIG_WHY = "config of Sinytra Connector or Connector Extras, which are left out";
    static final String CONNECTOR_CACHE = "mods/.connector/";

    /** Modrinth project ids (cdn.modrinth.com/data/ID/...) of connector, connector-extras, forgified-fabric-api */
    static final Map<String, String> CONNECTOR_PROJECTS = Map.of("u58R1TMW", CONNECTOR_WHY, "FYpiwiBR", EXTRAS_WHY, "Aqlf1Shp", FFAPI_WHY);
    /** Connector-1.0.0-beta.49+1.20.1.jar, Connector-1.0.0-beta+1.20.1-full.jar (Connector has no mod id of its own) */
    static final java.util.regex.Pattern CONNECTOR_NAME = java.util.regex.Pattern.compile("(?i)(sinytra-)?connector-\\d[^/]*\\.jar");
    /** ConnectorExtras-1.11.2+1.20.1.jar */
    static final java.util.regex.Pattern EXTRAS_NAME = java.util.regex.Pattern.compile("(?i)connector[-_ ]?extras[^/]*\\.jar");
    /** fabric-api-0.92.6+1.11.15+1.20.1.jar (two '+' parts; real Fabric API is fabric-api-0.92.6+1.20.1.jar) */
    static final java.util.regex.Pattern FFAPI_NAME = java.util.regex.Pattern.compile(
            "(?i)(forgified-fabric-api[^/]*|fabric-api-\\d[\\d.]*\\+\\d[\\d.]*\\+\\d[\\d.]*)\\.jar");
    /** Forge mod ids: Forgified Fabric API (fabric_api, provides fabric-api), Connector Extras, Connector's inner mod */
    static final Map<String, String> CONNECTOR_IDS = Map.of("fabric_api", FFAPI_WHY, "forgified_fabric_api", FFAPI_WHY,
            "connectorextras", EXTRAS_WHY, "connectormod", CONNECTOR_WHY);

    static String connectorByName(String name) {
        if (CONNECTOR_NAME.matcher(name).matches()) return CONNECTOR_WHY;
        if (EXTRAS_NAME.matcher(name).matches()) return EXTRAS_WHY;
        if (FFAPI_NAME.matcher(name).matches()) return FFAPI_WHY;
        return null;
    }

    /** an index jar, before download: by Modrinth project, then by file name */
    static String connectorPart(Want w) {
        for (String u : w.urls) {
            if (u == null) continue;
            var m = java.util.regex.Pattern.compile("^https://cdn\\.modrinth\\.com/data/([A-Za-z0-9]+)/").matcher(u);
            if (m.find() && CONNECTOR_PROJECTS.containsKey(m.group(1))) return CONNECTOR_PROJECTS.get(m.group(1));
        }
        return connectorByName(w.name());
    }

    /** a jar from overrides, --pack-mods or the index: by file name, then by its mod ids, then by Connector's classes */
    static String connectorPart(Placed p) {
        String why = connectorByName(p.name);
        if (why != null) return why;
        for (String id : p.forgeIds) if (CONNECTOR_IDS.containsKey(id)) return CONNECTOR_IDS.get(id);
        for (ModMeta.Mod m : p.meta.allMods())
            if (m.loader == ModMeta.Loader.FORGE && CONNECTOR_IDS.containsKey(m.id) && (!m.nested || m.id.equals("connectormod")))
                return CONNECTOR_IDS.get(m.id);
        // a renamed Connector jar (Creat More Attachments ships it under overrides/mods/mods/): its own classes
        if (p.meta.hasModLauncherService || p.meta.mods.isEmpty()) {
            try (ZipFile z = new ZipFile(p.jar.toFile())) {
                if (z.stream().anyMatch(e -> e.getName().startsWith("org/sinytra/connector/"))) return CONNECTOR_WHY;
            } catch (IOException ignored) { }
        }
        return null;
    }

    /** Fabric or Forge metadata that needs Connector, Connector Extras or Forgified Fabric API */
    static boolean needsConnector(Placed p) {
        for (ModMeta.Mod m : p.meta.mods) for (ModMeta.Dep d : m.deps)
            if (d.mandatory() && !d.incompatible() && (d.id().equals("connectormod") || d.id().equals("connector") || d.id().equals("connectorextras")
                    || (m.loader == ModMeta.Loader.FORGE && ffapiId(d.id())))) return true;
        // Connector's own placeholder mods.toml next to a Fabric mod's fabric.mod.json (Countered's Terrain Slabs in VORTEX)
        return p.meta.mods.stream().anyMatch(m -> m.loader == ModMeta.Loader.FABRIC) && hasPlaceholder(p.jar);
    }

    static boolean hasPlaceholder(Path jar) {
        try (var z = new java.util.zip.ZipFile(jar.toFile())) {
            var e = z.getEntry("META-INF/mods.toml");
            if (e == null) return false;
            try (InputStream in = z.getInputStream(e)) { return new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("connector:placeholder"); }
        } catch (IOException e) { return false; }
    }

    /** Forge ids of Forgified Fabric API and its modules (fabric_api, fabric_resource_loader_v0, fabric_api_base, ...) */
    static boolean ffapiId(String id) {
        return id.equals("fabric_api") || id.equals("forgified_fabric_api") || id.startsWith("fabric_");
    }

    /** dark-waters-connector-0.0.22.jar, wall-jump-connector-*.jar, fowlplay-...-sinytra.jar */
    static final java.util.regex.Pattern CONNECTOR_BUILD_NAME = java.util.regex.Pattern.compile("(?i).*[-_+.](connector(ed)?|sinytra)([-_+.].*)?\\.jar");

    /** Connector's jar cache (mods/.connector/, 40 of 145 Connector packs ship it in overrides) */
    static boolean inConnectorCache(String rel) {
        return rel.startsWith(CONNECTOR_CACHE);
    }

    /** a non-jar pack file that only Connector reads: config/connector.json, config/connectorextras_*.toml */
    static String connectorFile(String rel) {
        if (inConnectorCache(rel)) return "Sinytra Connector's cache of Fabric jars it remapped for Forge. Alloy does not use it";
        String l = rel.toLowerCase(Locale.ROOT);
        if (l.equals("config/connector.json") || (l.startsWith("config/connectorextras") && l.indexOf('/', 7) < 0)) return CONFIG_WHY;
        return null;
    }

    /** Modrinth project id from a cdn.modrinth.com download link, or null */
    static String projectOf(Want w) {
        for (String u : w.urls) {
            if (u == null) continue;
            var m = java.util.regex.Pattern.compile("^https://cdn\\.modrinth\\.com/data/([A-Za-z0-9]+)/").matcher(u);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /**
     * config/connector.json: globalModAliases (Forge id to the Fabric id Connector treated as the same mod) help find the
     * Fabric twins Alloy needs; hiddenMods has no Alloy option, so it is reported. Null when the file has no
     * globalModAliases: Connector then uses its defaults (CONNECTOR_ALIASES). A value may be one id or a list of ids.
     */
    static Map<String, String> readConnectorJson(byte[] b, List<String> notes) {
        Map<String, String> aliases = null;
        try {
            var m = Json.obj(Json.parse(new String(b, StandardCharsets.UTF_8)));
            if (m.get("globalModAliases") instanceof Map<?, ?> a) {
                aliases = new LinkedHashMap<>();
                for (var e : a.entrySet()) {
                    if (e.getValue() instanceof String v) aliases.put(String.valueOf(e.getKey()), v);
                    else if (e.getValue() instanceof List<?> l) for (Object v : l) if (v instanceof String sv) aliases.put(String.valueOf(e.getKey()) + "#" + sv, sv);
                }
            }
            if (m.get("hiddenMods") instanceof List<?> h && !h.isEmpty())
                notes.add("config/connector.json hides these mods from Connector: " + h + ". Alloy has no such option; they load as normal mods");
        } catch (RuntimeException e) {
            notes.add("config/connector.json could not be read (" + e.getMessage() + "); its aliases are not used");
        }
        return aliases;
    }

    /**
     * Forge-tagged Connector builds (they need Connector, Connector Extras or Forgified Fabric API, or are named
     * *-connector-*; Continuity 3.0.0+1.20.1.forge is in 128 packs) are swapped for the same Modrinth project's Fabric build.
     * Without one: a jar that has a fabric.mod.json loads from mods/ as it is; a Forge-only one stays, with a note.
     */
    static void connectorBuilds(List<Placed> forge, List<Placed> fabric, List<Dropped> dropped, List<String> notes, Path cache) {
        List<Placed> all = new ArrayList<>(forge);
        all.addAll(fabric);
        for (Placed p : all) {
            if (!needsConnector(p) && !CONNECTOR_BUILD_NAME.matcher(p.name).matches()) continue;
            boolean hasFmj = p.meta.loaders.contains(ModMeta.Loader.FABRIC);
            ModMeta.Loader l = hasFmj ? ModMeta.Loader.FABRIC : ModMeta.Loader.FORGE;
            ModMeta.Mod own = p.meta.mods.stream().filter(m -> m.loader == l).findFirst().orElse(null);
            String id = own == null ? null : own.id, ver = own == null ? null : own.version;
            String why = "Fabric build of " + p.name + " (that one was made for Sinytra Connector)";
            Placed got = p.project == null ? null : modrinthFabric(p.project, null, ver, List.of(), cache, why);
            if (got == null && id != null) got = modrinthFabric(slugFor(id), id, ver, List.of(), cache, why);
            if (got != null) {
                forge.remove(p);
                fabric.remove(p);
                fabric.add(got);
                dropped.add(new Dropped(p.name, "a build of '" + id + "' made for Sinytra Connector: replaced with its Fabric build " + got.name + " from Modrinth"));
            } else if (hasFmj) {
                if (forge.remove(p)) fabric.add(p);
                p.side = ForgeInMods.Side.FABRIC;
                p.why = "Fabric mod packed for Sinytra Connector, no Fabric build found on Modrinth: loads from mods/ as it is";
            } else if (!needsConnector(p)) notes.add("Forge mod '" + id + "' (" + p.name + ") is a build for Sinytra Connector (by its file name)"
                    + " and no Fabric build was found. It may not load on Alloy; use its Fabric build if it has one");
        }
    }

    /**
     * Forge mods built against Forgified Fabric API need the Forge id fabric_api (Lazyyyyy, Ship in a Bottle, ClickSigns,
     * XK's Decoration, vlib). Alloy has no Forge mod of that id yet, so Forge would refuse them: say so. Same for a Forge mod
     * that needs a Fabric mod by the underscore form of its id (cloth_config), which Connector made visible to Forge.
     */
    static void forgeNeedsFfapi(List<Placed> forge, List<Placed> fabric, List<String> notes, boolean ffapiDropped, List<Placed> skipped) {
        Set<String> forgeHave = new HashSet<>(List.of("forge", "minecraft", "java"));
        for (Placed p : forge) for (ModMeta.Mod m : p.meta.allMods()) { forgeHave.add(m.id); forgeHave.addAll(m.provides); }
        Map<String, String> fabricUnderscore = new HashMap<>();
        for (Placed p : fabric) for (ModMeta.Mod m : p.meta.allMods()) fabricUnderscore.putIfAbsent(m.id.replace('-', '_'), m.id);
        Map<String, List<String>> missing = new LinkedHashMap<>();   // "mod (jar)|what" -> ids
        for (Placed p : forge) for (ModMeta.Mod m : p.meta.mods) for (ModMeta.Dep d : m.deps) {
            if (m.loader != ModMeta.Loader.FORGE || !d.mandatory() || d.incompatible() || forgeHave.contains(d.id())) continue;
            boolean ff = ffapiId(d.id());
            String who = "Forge mod '" + m.id + "' (" + p.name + ")";
            if (ff || d.id().equals("connectormod") || d.id().equals("connector") || d.id().equals("connectorextras")) {
                if (ff && !ffapiDropped) continue;
                String what = d.id().equals("connectormod") || d.id().equals("connector") ? "Sinytra Connector" : d.id().equals("connectorextras") ? "Connector Extras" : "Forgified Fabric API";
                missing.computeIfAbsent(who + "|" + what, k -> new ArrayList<>()).add(d.id());
            } else if (fabricUnderscore.containsKey(d.id()))
                notes.add(who + " needs '" + d.id() + "', which the pack has only as the Fabric mod '"
                        + fabricUnderscore.get(d.id()) + "'. Connector let Forge see Fabric mods; Alloy does not, so add the Forge build of it to forgemods/");
        }
        // Forge refuses to start with a mandatory dependency missing, so such a mod is left out (the pack starts without it),
        // and so is every Forge mod that needs it in turn
        Set<String> gone = new HashSet<>();
        Map<String, Placed> byName = new HashMap<>();
        for (Placed p : forge) byName.put(p.name, p);
        missing.forEach((k, ids) -> {
            String who = k.substring(0, k.indexOf('|')), what = k.substring(k.indexOf('|') + 1);
            String jar = who.substring(who.indexOf("(") + 1, who.lastIndexOf(")"));
            Placed p = byName.get(jar);
            String why = "needs " + what + " ('" + String.join("', '", ids) + "'), which Alloy replaces; Forge would refuse to start with it";
            if (p != null && forge.remove(p)) {
                gone.addAll(p.ids());
                p.skipWhy = why;
                skipped.add(p);
                notes.add(who + " " + why + ": left out, the pack starts without it. If it has a Fabric build, use that one in mods/");
            } else notes.add(who + " " + why + "; if it has a Fabric build, use that one in mods/");
        });
        for (boolean more = !gone.isEmpty(); more; ) {
            more = false;
            for (Placed p : new ArrayList<>(forge))
                for (ModMeta.Mod m : p.meta.mods) for (ModMeta.Dep d : m.deps)
                    if (m.loader == ModMeta.Loader.FORGE && d.mandatory() && !d.incompatible() && gone.contains(d.id()) && forge.remove(p)) {
                        gone.addAll(p.ids());
                        p.skipWhy = "needs '" + d.id() + "', which was left out";
                        skipped.add(p);
                        notes.add("Forge mod '" + m.id + "' (" + p.name + ") needs '" + d.id() + "', which was left out: left out too");
                        more = true;
                    }
        }
    }

    /** Mod Menu is only added when its jar is at most this big (7.2.2 for 1.20.1 is about 0.5 MB) */
    static final long MODMENU_MAX_BYTES = 3_000_000;

    /**
     * Client import: Fabric mods that declare a "modmenu" entrypoint have config screens that only Mod Menu opens (Alloy's
     * config list asks Mod Menu too). When the pack has such mods, has Fabric API, and no Mod Menu, the real Fabric Mod Menu
     * is added from Modrinth (hash-checked, as for Connector packs), if its jar is small (MODMENU_MAX_BYTES). Alloy never
     * stands in for the modmenu id itself (Connector Extras issue 82). -Dalloy.modpack.modmenu=false turns this off.
     * Returns true when Mod Menu was added.
     */
    static boolean modMenuForConfigs(List<Placed> fabric, List<String> notes, java.util.function.Supplier<Placed> fetch) {
        if ("false".equals(System.getProperty("alloy.modpack.modmenu"))) return false;
        List<String> users = new ArrayList<>();
        boolean api = false;
        for (Placed p : fabric) for (ModMeta.Mod m : p.meta.allMods()) {
            if (m.loader != ModMeta.Loader.FABRIC) continue;
            if (m.id.equals("modmenu") || m.provides.contains("modmenu")) return false;
            if (m.id.equals(FABRIC_API_ID)) api = true;
            if (m.modmenuEntrypoint && !m.nested && users.size() < 50) users.add("'" + m.id + "'");
        }
        if (users.isEmpty()) return false;
        String who = users.size() <= 5 ? String.join(", ", users) : String.join(", ", users.subList(0, 5)) + " and " + (users.size() - 5) + " more";
        if (!api) {
            notes.add("Fabric mod(s) " + who + " have config screens that open through Mod Menu, but the pack has no Mod Menu and no Fabric API: nothing added");
            return false;
        }
        Placed got = fetch.get();
        long size = -1;
        try { if (got != null) size = Files.size(got.jar); } catch (IOException ignored) { }
        if (got == null || size < 0 || size > MODMENU_MAX_BYTES) {
            notes.add("Fabric mod(s) " + who + " have config screens that open through Mod Menu, which the pack lacks"
                    + (got == null ? "; add the Fabric Mod Menu to mods/ to reach them" : "; Mod Menu's jar is too big to add (" + size + " bytes), add it to mods/ yourself"));
            return false;
        }
        fabric.add(got);
        notes.add("Fabric mod(s) " + who + " have config screens that open through Mod Menu: added " + got.name + " from Modrinth");
        return true;
    }

    /** Connector's own default globalModAliases (ConnectorConfig), used when a pack's connector.json does not set any */
    static final Map<String, String> CONNECTOR_ALIASES = Map.of("cloth_config", "cloth-config2", "playeranimator", "player-animator");

    /** Modrinth slugs for mod ids that differ from them */
    static final Map<String, String> SLUGS = Map.ofEntries(Map.entry("cloth-config2", "cloth-config"), Map.entry("cloth_config", "cloth-config"),
            Map.entry("player-animator", "playeranimator"), Map.entry("player_animator", "playeranimator"),
            Map.entry("architectury", "architectury-api"), Map.entry("amecsapi", "amecs"),
            Map.entry("forgeconfigapiport", "forge-config-api-port"),
            Map.entry("cardinal-components", "cardinal-components-api"), Map.entry("cardinal-components-base", "cardinal-components-api"),
            Map.entry("cardinal-components-entity", "cardinal-components-api"));
    static String slugFor(String id) { return SLUGS.getOrDefault(id, id); }

    static List<String> concat(String a, Collection<String> b) { List<String> l = new ArrayList<>(b); l.add(0, a); return l; }

    /** Fabric mods Connector Extras carried a Forge port or a stand-in for */
    static final Set<String> EXTRAS_GIVES = Set.of("reach-entity-attributes", "amecsapi", "amecs", "forgeconfigapiport", "modmenu");
    /** Alloy serves the Fabric plugins of these from the Forge copy (viewer and TerraBlender bridges): no twin */
    static final Set<String> ALLOY_BRIDGED = Set.of("jei", "roughlyenoughitems", "rei", "emi", "terrablender");
    /** Connector packs use Embeddium and Oculus; Alloy keeps them and does not add Sodium or Iris (Forge mods hard-require them) */
    static final Set<String> RENDERERS = Set.of("sodium", "iris", "indium");
    /**
     * Libraries whose Fabric build is not added next to the Forge one: GeckoLib Forge plus Fabric crashed on 26.1.2, and the
     * others share entrypoint classes, have differing mixin lists, pick one platform service, or register their own content. Architectury stays here until a twin boot passes. Only a note.
     */
    static final Set<String> NO_TWIN = Set.of("geckolib", "geckolib3", "azurelib", "moonlight", "balm", "puzzleslib", "bookshelf", "resourcefullib",
            "fzzy_config", "architectury");

    /** Forge Config API Port's Fabric build ships classes under Forge's own names: never added on 1.x (1.20.1 to 1.21.11), where real Forge has them */
    static boolean fcapBlocked(String id) {
        return id.equals("forgeconfigapiport") && Installer.MC.startsWith("1.");
    }

    /**
     * Fabric mods of a Connector pack whose dependency Connector met another way: a Forge mod of the same id or an alias
     * from config/connector.json (Fabric cloth-config by the Forge cloth_config), or a Connector Extras module (Mod Menu,
     * Reach Entity Attributes, Amecs, Forge Config API Port). On Alloy a Fabric mod needs the Fabric build: it is fetched
     * from Modrinth (hash-checked, the jar must declare that Fabric id and fit the wanted ranges), next to the Forge one.
     * Otherwise a note says what to add. Renderers (Sodium, Iris) are never added: the pack keeps Embeddium and Oculus.
     * -Dalloy.modpack.api=none turns the lookups off.
     */
    static void connectorAliases(List<Placed> forge, List<Placed> fabric, List<String> notes, Path cache, Map<String, String> aliases, boolean extras) {
        Map<String, String> forgeIds = new HashMap<>(), forgeVer = new HashMap<>();
        for (Placed p : forge) for (String id : p.ids()) forgeIds.putIfAbsent(id, p.name);
        for (Placed p : forge) for (ModMeta.Mod m : p.meta.mods) if (m.version != null) forgeVer.putIfAbsent(m.id, m.version);
        Map<String, String> near = new HashMap<>();   // Fabric id -> version of the Forge copy Connector linked it to
        Map<String, String> fabricToForge = new HashMap<>();
        aliases.forEach((f, fa) -> fabricToForge.put(fa, f.contains("#") ? f.substring(0, f.indexOf('#')) : f));   // list values are keyed forgeId#fabricId
        Set<String> have = new HashSet<>(List.of("minecraft", "java", "fabricloader", "fabric-loader", "mixinextras", "fabric", FABRIC_API_ID));
        Map<String, List<String>> haveVer = new HashMap<>();   // versions of mods the Fabric side has (nested copies too)
        for (Placed p : fabric) for (ModMeta.Mod m : p.meta.allMods()) {
            have.add(m.id);
            have.addAll(m.provides);
            if (m.version != null) haveVer.computeIfAbsent(m.id, k -> new ArrayList<>()).add(m.version);
        }
        Map<String, List<String>> users = new LinkedHashMap<>(), ranges = new HashMap<>();
        Map<String, String> how = new HashMap<>();
        for (Placed p : fabric) for (ModMeta.Mod m : p.meta.mods) for (ModMeta.Dep d : m.deps) {
            String id = d.id();
            // present, but only in a version the range refuses (VORTEX: Cloth Config 11.0.99 nested in Client Side Note
            // Blocks, More Music wants >=11.1; under Connector the pack's Forge Cloth Config 11.1 counted): fetch a fitting one
            boolean tooOld = have.contains(id) && d.range() != null && haveVer.containsKey(id)
                    && haveVer.get(id).stream().allMatch(v -> Boolean.FALSE.equals(Versions.matches(d.range(), v)));
            if (m.loader != ModMeta.Loader.FABRIC || !d.mandatory() || d.incompatible() || (have.contains(id) && !tooOld) || id.startsWith("fabric-") || QUILT_LIB_IDS.contains(id)) continue;
            String fid = forgeIds.containsKey(id) ? id : forgeIds.containsKey(id.replace('-', '_')) ? id.replace('-', '_')
                    : fabricToForge.containsKey(id) && forgeIds.containsKey(fabricToForge.get(id)) ? fabricToForge.get(id) : null;
            if (RENDERERS.contains(id)) {
                if (fid == null && !forgeIds.containsKey("embeddium") && !forgeIds.containsKey("oculus") && !forgeIds.containsKey("rubidium")) continue;
                how.put(id, "Connector counted the pack's Forge renderer (Embeddium or Oculus) for it");
            } else if (fid != null) {
                how.put(id, "the pack has only its Forge build (" + forgeIds.get(fid) + "), which counted under Connector");
                // under Connector the Fabric mod linked to that Forge copy's classes, so it was built for that API line
                // (Eldritch End: AzureLib 2.x's animatable.GeoEntity, gone in AzureLib 3): same major.minor first
                if (forgeVer.containsKey(fid)) near.putIfAbsent(id, forgeVer.get(fid));
            }
            else if (extras && EXTRAS_GIVES.contains(id)) how.put(id, "Connector Extras, which is left out, stood in for it");
            else continue;
            String who = "'" + m.id + "' (" + p.name + ")";
            if (!users.computeIfAbsent(id, k -> new ArrayList<>()).contains(who)) users.get(id).add(who);
            if (d.range() != null) ranges.computeIfAbsent(id, k -> new ArrayList<>()).add(d.range());
        }
        for (var e : users.entrySet()) {
            String id = e.getKey();
            boolean fits = have.contains(id) && (!haveVer.containsKey(id) || ranges.getOrDefault(id, List.of()).stream().allMatch(r ->
                    haveVer.get(id).stream().anyMatch(v -> !Boolean.FALSE.equals(Versions.matches(r, v)))));
            if (fits) {   // an earlier added jar provides it (cloth-config also provides cloth-config2)
                notes.add("Fabric mod(s) " + String.join(", ", e.getValue()) + " need the Fabric mod '" + id + "': the Fabric build added above provides it");
                continue;
            }
            String head = "Fabric mod(s) " + String.join(", ", e.getValue()) + " need the Fabric mod '" + id + "'; " + how.get(id);
            if (RENDERERS.contains(id)) { notes.add(head + ". Alloy keeps the pack's Embeddium/Oculus and does not add " + id + ", so these may not load"); continue; }
            if (ALLOY_BRIDGED.contains(id)) { notes.add(head + ". Alloy's bridge serves Fabric plugins from the Forge copy, nothing added"); continue; }
            if (NO_TWIN.contains(id)) {
                notes.add(head + ". Its Fabric build next to the Forge one is known to clash or is not tested on Alloy (shared classes, services or"
                        + " registry content), so it is not added. Use the Forge builds of these Fabric mods if they have one");
                continue;
            }
            if (fcapBlocked(id)) {
                notes.add(head + ". On Minecraft " + Installer.MC + " the Fabric Forge Config API Port is not added: it ships classes under Forge's own"
                        + " names, next to real Forge (not tested). Use the Forge builds of these mods if they have one");
                continue;
            }
            Placed got = modrinthFabric(slugFor(id), id, near.get(id), ranges.getOrDefault(id, List.of()), cache, "Fabric build of '" + id + "' from Modrinth (" + how.get(id) + ")");
            if (got != null) {
                fabric.add(got);
                for (ModMeta.Mod m : got.meta.allMods()) {
                    have.add(m.id);
                    have.addAll(m.provides);
                    if (m.version != null) for (String pid : concat(m.id, m.provides)) haveVer.computeIfAbsent(pid, k -> new ArrayList<>()).add(m.version);
                }
                notes.add(head + ": added the Fabric build " + got.name + " from Modrinth");
            } else notes.add(head + ". On Alloy, add the Fabric build of '" + id + "' to mods/ (or with --pack-mods)");
        }
    }

    /**
     * A Fabric build from a Modrinth project (id or slug) for this Minecraft version: hash-checked, a Fabric jar, declaring
     * mod id {@code wantId} (any id when null, for a project id we know) within the {@code wanted} ranges. Versions whose
     * number starts like {@code nearVersion} are tried first. Null when none fits or lookups are off (-Dalloy.modpack.api=none).
     */
    static Placed modrinthFabric(String project, String wantId, String nearVersion, List<String> wanted, Path cache, String why) {
        return modrinthBuild(ModMeta.Loader.FABRIC, project, wantId, nearVersion, wanted, cache, why);
    }

    /** the same as {@link #modrinthFabric}, for the Forge build of a Modrinth project (Forge-first swap on 1.20.x) */
    static Placed modrinthForge(String project, String wantId, String nearVersion, Path cache, String why) {
        return modrinthBuild(ModMeta.Loader.FORGE, project, wantId, nearVersion, List.of(), cache, why);
    }

    static Placed modrinthBuild(ModMeta.Loader loader, String project, String wantId, String nearVersion, List<String> wanted, Path cache, String why) {
        boolean fab = loader == ModMeta.Loader.FABRIC;
        ForgeInMods.Side side = fab ? ForgeInMods.Side.FABRIC : ForgeInMods.Side.FORGE;
        String api = System.getProperty("alloy.modpack.api", "https://api.modrinth.com/v2");
        if (api.equals("none") || project == null || !project.matches("[A-Za-z0-9_-]{2,64}")) return null;
        if (!api.startsWith("https://")) { log("alloy.modpack.api must be an https:// address, not " + api + ": Modrinth lookups are off"); return null; }
        if (QUILT_LIB_IDS.contains(project) || QUILT_LIB_IDS.contains(wantId)) return null;   // never QSL, QFAPI or QKL
        try {
            String q = "loaders=" + java.net.URLEncoder.encode(fab ? "[\"fabric\"]" : "[\"forge\"]", StandardCharsets.UTF_8)
                    + "&game_versions=" + java.net.URLEncoder.encode("[\"" + Installer.MC + "\"]", StandardCharsets.UTF_8);
            List<Object> vs = new ArrayList<>(Json.arr(Json.parse(new String(get(api + "/project/" + project + "/version?" + q, 60), StandardCharsets.UTF_8))));
            if (nearVersion != null) {
                String base = nearVersion.replaceAll("[+-].*", "");
                java.util.regex.Matcher mm = java.util.regex.Pattern.compile("^(\\d+\\.\\d+)").matcher(base);
                String line = mm.find() ? mm.group(1) + "." : base;   // 2.0.39 -> "2.0.": the same API line, newest first
                vs.sort(Comparator.comparingInt(o -> {
                    String v = String.valueOf(Json.obj(o).get("version_number"));
                    return v.startsWith(base) ? 0 : v.startsWith(line) || v.contains("-" + line) ? 1 : 2;
                }));
            }
            int tried = 0;
            for (Object o : vs) {
                if (++tried > 4) break;
                Map<String, Object> f = null;
                for (Object fo : Json.arr(Json.obj(o).get("files"))) {
                    var ff = Json.obj(fo);
                    if (f == null || Boolean.TRUE.equals(ff.get("primary"))) f = ff;
                }
                if (f == null) continue;
                String url = str(f.get("url")), name = str(f.get("filename"));
                var h = Json.obj(f.get("hashes"));
                if (url == null || name == null || !allowedHost(url) || name.contains("/") || name.contains("\\") || safePath("mods/" + name) == null) continue;
                Want w = new Want(null, (fab ? "mods/" : "forgemods/") + name, List.of(url), lower(str(h.get("sha1"))), lower(str(h.get("sha512"))),
                        f.get("size") instanceof Long l ? l : null, false, name);
                download(w, cache.resolve("modpack-files").resolve(fab ? "connector-fabric" : "forge-first"));
                if (w.file == null || ForgeInMods.classify(w.file, Map.of()).side() != side) continue;
                Placed p = new Placed(w.file, name, null, side, why);
                ModMeta.Mod m = p.meta.mods.stream().filter(x -> x.loader == loader
                        && (wantId == null || x.id.equals(wantId) || x.provides.contains(wantId))).findFirst().orElse(null);
                if (m == null) continue;
                boolean fits = true;
                for (String r : wanted) {
                    try { if (Boolean.FALSE.equals(Versions.matches(r, m.version))) fits = false; } catch (Exception ignored) { }
                }
                if (fits) return p;
            }
        } catch (Exception e) {
            log("modpack: no " + (fab ? "Fabric" : "Forge") + " build of '" + project + "' found on Modrinth (" + e.getMessage() + ")");
        }
        return null;
    }

    static String loaders(Pack pk) {
        if (pk.loaders.isEmpty()) return "none";
        List<String> l = new ArrayList<>();
        pk.loaders.forEach((k, v) -> l.add(k + (v == null || v.isEmpty() ? "" : " " + v)));
        return String.join(", ", l);
    }

    // ------------------------------------------------------------------ output

    static void report(Plan plan, String side) {
        long quilt = plan.dropped.stream().filter(d -> d.why().equals(QSL_WHY) || d.why().equals(QKL_WHY)).count();
        log("modpack: " + plan.forge.size() + " Forge mod(s) -> forgemods/, " + plan.fabric.size() + " Fabric mod(s) -> mods/, "
                + plan.files.size() + " other file(s), " + plan.skipped.size() + " jar(s) left out, " + plan.missing.size() + " file(s) missing"
                + (plan.dropped.size() == quilt ? "" : ", " + (plan.dropped.size() - quilt) + " Connector file(s) dropped")
                + (quilt == 0 ? "" : ", " + quilt + " Quilt library file(s) dropped"));
        for (Placed p : plan.forge) log("  forgemods/ " + p.name + "   (" + p.why + ")");
        for (Placed p : plan.fabric) log("  mods/      " + p.name + "   (" + p.why + ")");
        for (Placed p : plan.skipped) log("  left out   " + p.name + "   (" + skipWhy(p) + ")");
        for (Dropped d : plan.dropped) log("  dropped    " + d.file() + "   (" + d.why() + ")");
        for (String n : plan.notes) log("  note: " + n);
        if (!plan.missing.isEmpty()) {
            log("modpack: these files could not be fetched:");
            for (Want w : plan.missing) log("  " + (w.optional ? "(optional) " : "") + w.label + "  - " + w.why
                    + (w.urls.isEmpty() ? "" : "  " + w.urls.get(0)));
            Set<Pack> cfs = new LinkedHashSet<>();
            for (Want w : plan.missing) if ("CurseForge".equals(w.pack.format)) cfs.add(w.pack);
            for (Pack pk : cfs) if (!pk.modlist.isEmpty()) {
                log("  mod names from " + pk.title() + " modlist.html:");
                for (String s : pk.modlist) log("    " + s);
            }
        }
    }

    static String skipWhy(Placed p) {
        if (p.skipWhy != null) return p.skipWhy;
        if (p.side == ForgeInMods.Side.FORGE || p.side == ForgeInMods.Side.FABRIC) return "a second copy of a mod, see the notes";
        if (p.meta.loaders.contains(ModMeta.Loader.NEOFORGE)) return "NeoForge-only jar: Alloy cannot run it";
        if (p.meta.loaders.contains(ModMeta.Loader.QUILT)) return "Quilt-only jar: Alloy cannot run it";
        return p.why;
    }

    static void writeMissing(Path f, List<Want> missing, List<Path> packs, Path handDir) throws IOException {
        Files.createDirectories(f.getParent());
        StringBuilder b = new StringBuilder("Files of the modpack that the installer could not download.\n"
                + "Download each one by hand into one folder, then run the import again with --pack-mods FOLDER.\n\n");
        for (Want w : missing) b.append(w.optional ? "(optional) " : "").append(w.label).append("\n    ").append(w.why)
                .append(w.urls.isEmpty() ? "" : "\n    " + w.urls.get(0)).append(w.sha1 == null ? "" : "\n    sha1 " + w.sha1).append("\n");
        Set<Pack> cfs = new LinkedHashSet<>();
        for (Want w : missing) if ("CurseForge".equals(w.pack.format)) cfs.add(w.pack);
        for (Pack pk : cfs) if (!pk.modlist.isEmpty()) {
            b.append("\nMods named in ").append(pk.title()).append(" modlist.html:\n");
            for (String s : pk.modlist) b.append("    ").append(s).append("\n");
        }
        Files.writeString(f, b.toString(), StandardCharsets.UTF_8);
    }

    /**
     * The other loader's build of a mod the import left out because it kept one (Forge-first swap, a mod in both pack halves):
     * kept in .alloy/alternates/FOLDER/FILE and listed in .alloy/alternates.tsv (id, loader, folder, file, the build in use).
     * Crash recovery offers "Try the Forge/Fabric build of MOD" with it when the build in use crashed the start.
     */
    static void saveAlternates(Path target, Plan plan) {
        try {
            Map<String, String> forgeKept = new HashMap<>(), fabricKept = new HashMap<>();
            for (Placed p : plan.forge) for (String id : p.ids()) forgeKept.putIfAbsent(id, p.name);
            for (Placed p : plan.fabric) for (String id : p.ids()) fabricKept.putIfAbsent(id, p.name);
            StringBuilder b = new StringBuilder();
            Path root = target.resolve(".alloy").resolve("alternates");
            for (Placed p : plan.skipped) {
                if ((p.side != ForgeInMods.Side.FORGE && p.side != ForgeInMods.Side.FABRIC) || p.meta.error != null || !Files.isRegularFile(p.jar)) continue;
                boolean forgeBuild = p.side == ForgeInMods.Side.FORGE;
                Map<String, String> other = forgeBuild ? fabricKept : forgeKept;
                String id = p.ids().stream().filter(other::containsKey).findFirst().orElse(null);
                if (id == null || p.name.contains("/") || p.name.contains("\\")) continue;
                String folder = forgeBuild ? "forgemods" : "mods";
                Path dst = root.resolve(folder).resolve(p.name);
                Files.createDirectories(dst.getParent());
                Files.copy(p.jar, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                b.append(id).append('\t').append(forgeBuild ? "forge" : "fabric").append('\t').append(folder).append('\t').append(p.name)
                        .append('\t').append(other.get(id)).append('\n');
            }
            Path list = target.resolve(".alloy").resolve("alternates.tsv");
            if (b.length() == 0) { Files.deleteIfExists(list); return; }
            Files.writeString(list, "# other-loader builds the pack import left out (crash recovery offers them): id, loader, folder, file, build in use\n" + b,
                    StandardCharsets.UTF_8);
            log("modpack: kept " + b.toString().split("\n").length + " other-loader build(s) in .alloy/alternates/ for crash recovery");
        } catch (IOException e) {
            log("modpack: other-loader builds not kept (" + e + ")");
        }
    }

    /** .alloy/modpack.json: what was imported and where each jar went */
    static void writeRecord(Path target, List<Path> packFiles, Plan plan, String side) throws IOException {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("side", side);
        r.put("minecraft", Installer.MC);
        List<Object> ps = new ArrayList<>();
        for (Path p : packFiles) ps.add(p.toAbsolutePath().normalize().toString());
        r.put("packs", ps);
        r.put("forgemods", plan.forge.stream().map(p -> (Object) p.name).toList());
        r.put("mods", plan.fabric.stream().map(p -> (Object) p.name).toList());
        r.put("leftOut", plan.skipped.stream().map(p -> (Object) (p.name + ": " + skipWhy(p))).toList());
        r.put("missing", plan.missing.stream().map(w -> (Object) w.label).toList());
        List<Object> dr = new ArrayList<>();
        for (Dropped d : plan.dropped) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", d.file());
            m.put("reason", d.why());
            dr.add(m);
        }
        r.put("dropped", dr);
        r.put("notes", new ArrayList<Object>(plan.notes));
        Files.createDirectories(target.resolve(".alloy"));
        Files.writeString(target.resolve(".alloy").resolve("modpack.json"), Json.write(r), StandardCharsets.UTF_8);
    }
}
