package alloy.installer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import static alloy.installer.Util.*;

/**
 * Minecraft 1.20.1 (Forge 47) and 1.20.4 (Forge 49) in the installer: the same steps as the dev script launch120.py, done on
 * the player's PC. The two differ only in data (versions/<id>.properties): the PrepFml rule set (fml47 / fml49), Forge 49
 * has no client-extra jar, uses securemodules instead of securejarhandler, and lists its loader jars in its version json.
 *
 * Nothing from Mojang or Forge is shipped. The installer downloads the vanilla client jar and Mojang's client mappings
 * from Mojang, runs the official Forge installer (Forge maven), gets Intermediary from the Fabric maven and MCPConfig from
 * the Forge maven, and then, on this PC:
 *   - PrepFml (-Dalloy.fml=fml47) patches Forge's loader jars,
 *   - the name translator (alloy.remap, bundled) turns Forge's SRG game jar (Forge's patched classes + client-srg +
 *     client-extra) into a Mojang-named game jar, the vanilla client into a Mojang-named copy (the fit compares the two),
 *     and Forge universal into a Mojang-named copy, and writes srg2mojang.tsv (FML's name function),
 *   - two lines of Forge's own access transformer that clash with Fabric API are changed (FORGE_AT_EDITS),
 *   - Forge mods (forgemods/) are remapped SRG -> Mojang in place (the player's files go to .alloy/originals/forgemods),
 *   - Fabric mods (mods/) are remapped Intermediary -> Mojang and fitted (Fit, with the 1.20.1 lists).
 * Derived files live in the cache under names-1.20.1-client/KEY/ (KEY = tools + inputs), never rewritten once finished.
 *
 * Dedicated server (--side server): the official Forge installer runs with --installServer and brings the vanilla server
 * and its libraries. No client jar is downloaded; Mojang's client mappings are (client and server share obfuscated names).
 * The names folder (names-1.20.1-server/KEY/) holds the Mojang-named Forge server game, server-mojang.jar (vanilla, for
 * the fit) and the same srg2mojang.tsv. The class path comes from Forge's server start list (win_args.txt). Every path in
 * .alloy/server.args is relative to the server folder, so the folder can be moved (see ServerStart).
 * Forge 48 (1.20.2) has the same server layout. Forge 49 (1.20.4) and up start their server through a shim jar: its
 * bootstrap-shim.list is the class path (checked against its SHA-256 column), server-MC-unpacked.jar (no MCP version)
 * is the obfuscated vanilla server and the patched forge-FV-server.jar is part of the game jar. Forge 49 keeps SRG names
 * (forge-FV-server.jar over the MCPConfig server-MC-MCP-srg.jar, translated like 1.20.1); on Forge 50 and up
 * forge-FV-server.jar over server-MC-official.jar is the Mojang-named game.
 * Which versions may install a server: SERVERS.
 */
final class V120 {
    private V120() { }

    static final String GROUP = "net.minecraftforge";
    static String MCP = "20230612.114412";   // MCPConfig version of the selected 1.20.x Forge (versions/<id>.properties "mcp")
    static String FAMILY = "fml47";          // PrepFml rule set (-Dalloy.fml): fml47 for Forge 47, fml49 for Forge 49 ("fmlFamily")
    /**
     * Forge 50 and up (Minecraft 1.20.6 and 1.21.x, "forgeNames=mojang"): Forge's installer renames the game to Mojang names
     * itself. Then there is no MCPConfig and no SRG game: the game jar is Forge's patched classes over the installer's
     * client-MC-official.jar, Forge universal and Forge mods are used as published, and FML's srg names (Forge still writes
     * its access transformers in SRG) come from Forge's own srg2off fields.csv/methods.csv on the class path. Only Fabric
     * mods (Intermediary) and the vanilla client (for the fit) go through the name translator.
     */
    static boolean NATIVE = false;

    /**
     * Forge 49 and up (1.20.4, 1.21.x): the dedicated server starts through forge-FV-shim.jar, whose bootstrap-shim.list
     * is the server class path, and Forge's server install unpacks the vanilla server without an MCP version in its path.
     * Forge 47/48 list their server class path in win_args.txt / unix_args.txt instead.
     */
    static boolean shimServer() { return NATIVE || "fml49".equals(FAMILY); }

    /**
     * Forge 47 to 49 (Forge's own Mixin is 0.8.5, versions/<id>.properties "mixinExpandFrames=true"): Mixin's ClassInfo
     * frames are read with ClassReader.EXPAND_FRAMES, as Forge's ModLauncher service does. Knot reads them compressed by
     * default, and ClassInfo then counts a CHOP frame as initialFrameSize, so @Local finds no candidate after a chop
     * (MMC4: Sawmill RecipeManagerMixin). Forge 52 and up (Mixin 0.8.7) read compressed frames themselves: off there.
     */
    static boolean EXPAND_FRAMES = false;
    static final String EXPAND_FRAMES_ARG = "-Dmixin.tunable.classReaderExpandFrames=true";

    /** true when the selected Minecraft version runs through these steps (every 1.x: 1.20.1 to 1.21.11; 26.x does not). */
    static boolean on() { return Installer.MC.startsWith("1."); }

    static String forge() { return Installer.FV.substring(Installer.FV.indexOf('-') + 1); }

    /** lowest Java the game and these tools run on (Minecraft 1.20.x needs 17, 1.20.5 and up 21) */
    static int javaMin() { return NATIVE ? 21 : 17; }

    /**
     * Forge's own access transformer lines that clash with Fabric API 0.92.12 (see launch120.py FORGE_AT_EDITS). Forge's AT
     * runs after Fabric's access wideners, so the +f is dropped in Forge universal's AT file, in SRG and in Mojang names.
     */
    static final Map<String, String> FORGE_AT_EDITS = Map.of(
            "public+f net.minecraft.world.item.crafting.Ingredient toNetwork(", "public net.minecraft.world.item.crafting.Ingredient toNetwork(",
            "public+f net.minecraft.world.item.crafting.Ingredient m_43923_(", "public net.minecraft.world.item.crafting.Ingredient m_43923_(");

    // ------------------------------------------------------------------ paths
    static Path mappingsTxt(Installer in) { return in.mojang.resolve("client-" + Installer.MC + "-mappings.txt"); }
    static Path intermediary(Installer in) { return in.cache.resolve("mappings").resolve("intermediary-" + Installer.MC + "-v2.jar"); }
    static String intermediaryRel() { return "net/fabricmc/intermediary/" + Installer.MC + "/intermediary-" + Installer.MC + "-v2.jar"; }
    static String mcpRel() { return "de/oceanlabs/mcp/mcp_config/" + Installer.MC + "-" + MCP + "/mcp_config-" + Installer.MC + "-" + MCP + ".zip"; }
    static Path mcpZip(Installer in) { return in.forgeLibs.resolve(mcpRel()); }
    /** Forge's MCPConfig output for this side: libraries/net/minecraft/{client|server}/MC-MCP/{side}-MC-MCP-KIND.jar */
    static Path mcpSideJar(Installer in, String kind) {
        String s = in.server ? "server" : "client";
        return in.forgeLibs.resolve("net/minecraft/" + s + "/" + Installer.MC + "-" + MCP + "/" + s + "-" + Installer.MC + "-" + MCP + "-" + kind + ".jar");
    }
    static Path srgJar(Installer in) { return NATIVE ? officialJar(in) : mcpSideJar(in, "srg"); }
    /** Forge 50+: the installer's Mojang-named vanilla client (libraries/net/minecraft/client/MC/client-MC-official.jar) */
    static Path officialJar(Installer in) {
        String s = in.server ? "server" : "client";
        return in.forgeLibs.resolve("net/minecraft/" + s + "/" + Installer.MC + "/" + s + "-" + Installer.MC + "-official.jar");
    }
    /** the -extra jar (resources) of Forge 47; Forge 49 has none (its srg jar holds the resources) */
    static Path extraJar(Installer in) { return mcpSideJar(in, "extra"); }
    static boolean hasExtra() { return !NATIVE && !"fml49".equals(FAMILY); }
    /**
     * the vanilla server jar (obfuscated, out of Mojang's bundler) that Forge's server install leaves in its libraries:
     * Forge 47/48 libraries/net/minecraft/server/MC-MCP/server-MC-MCP-unpacked.jar, Forge 49+ (no MCP version there)
     * libraries/net/minecraft/server/MC/server-MC-unpacked.jar
     */
    static Path serverObf(Installer in) {
        if (shimServer()) return in.forgeLibs.resolve("net/minecraft/server/" + Installer.MC + "/server-" + Installer.MC + "-unpacked.jar");
        return mcpSideJar(in, "unpacked");
    }

    /**
     * Minecraft versions whose dedicated server went through the server boot checks (install, --server-start, server.jar,
     * Mod Doctor, a Forge and a Fabric mod loaded up to the EULA prompt). Other 1.x versions stay client only.
     */
    static final Set<String> SERVERS = Set.of("1.20.1", "1.20.2", "1.20.4", "1.21.1", "1.21.4", "1.21.8", "1.21.11");

    static boolean serverReady() { return SERVERS.contains(Installer.MC); }
    /**
     * The obfuscated vanilla jar of this side: the class hierarchy for the name translator. Client and server share their
     * obfuscated names (Fabric's Intermediary and Forge's MCPConfig have one "official" namespace for both), so Mojang's
     * client mappings translate the server too; a server install downloads those mappings but not the client jar.
     */
    static Path gameObf(Installer in) { return in.server ? serverObf(in) : in.clientJar; }
    /** the Mojang-named vanilla jar of this side in the names folder (the fit compares it with the Forge game) */
    static String vanillaMojangName(boolean server) { return server ? "server-mojang.jar" : "client-mojang.jar"; }
    static String universalName() { return "forge-" + Installer.FV + "-universal.jar"; }
    static String universalRel() { return "net/minecraftforge/forge/" + Installer.FV + "/" + universalName(); }
    static Path forgeVersionJson(Installer in) {
        String id = Installer.MC + "-forge-" + forge();
        return in.forge.resolve("versions").resolve(id).resolve(id + ".json");
    }
    static Path namesRoot(Installer in) { return in.cache.resolve("names-" + Installer.MC + "-" + in.side); }

    /** the finished names folder in use (namesRoot/current), or null */
    static Path names(Installer in) {
        Path cur = Installer.currentFml(namesRoot(in));
        return cur.equals(namesRoot(in)) || !Files.isRegularFile(cur.resolve(".stamp")) ? null : cur;
    }

    // ------------------------------------------------------------------ Java
    /** Warns or stops for the game's Java (1.20.1 and 1.20.4 run on Java 17 and newer; Alloy tests them on 21). */
    static void checkJava(Path java) throws IOException {
        int v = javaMajorOf(java), min = javaMin();
        if (v < min) throw new IOException("Minecraft " + Installer.MC + " needs Java " + min + " or newer (found Java " + v + " at " + java + "); pass --java <JAVA_HOME>");
        if (v != min && v != 21)
            log("note: this install uses Java " + v + ". Minecraft " + Installer.MC + " is made for Java " + min + "; Alloy tests it on Java 21. "
                    + "If the game does not start, use Java 21 (in Prism: Settings, Java).");
    }

    // ------------------------------------------------------------------ base: downloads, Forge install, PrepFml, names
    /**
     * Everything that does not depend on the mods: Mojang client jar + mappings, vanilla libraries (+ assets unless
     * noAssets), Fabric Loader / Mixin / ASM, the official Forge installer, Intermediary + MCPConfig, PrepFml, the names
     * folder. Each part is skipped when its output is already there, so this is cheap after the first run.
     */
    static Map<String, Object> base(Installer in, boolean assets) throws Exception {
        // server: only the version json from Mojang here; the official Forge installer (--installServer) gets the vanilla
        // server and its libraries itself
        Map<String, Object> vj = in.stepMojang(false, !in.server);
        var dl = Json.obj(vj.get("downloads"));
        var cm = Json.obj(dl.get("client_mappings"));
        fetch(List.of((String) cm.get("url")), mappingsTxt(in), (String) cm.get("sha1"), (Long) cm.get("size"), "Mojang client mappings");
        in.stepFabricLibs();
        in.stepForge();
        if (shimServer() && in.server) checkShimHashes(in);
        if (!in.server) {
            boolean na = in.noAssets;
            if (!assets) in.noAssets = true;
            try { in.stepClientAssets(vj); } finally { in.noAssets = na; }
        }
        fetchPinned(List.of(Installer.FABRIC_MAVEN), intermediaryRel(), intermediary(in), "Intermediary mappings");
        // Forge 47/49: the Forge installer normally put MCPConfig there already (its own SHA-1 check); the pin checks it again.
        // Forge 50+ (NATIVE) has no MCPConfig.
        if (!NATIVE) fetchPinned(List.of(Installer.FORGE_MAVEN), mcpRel(), mcpZip(in), "MCPConfig");
        // Forge's AT is edited in the installed universal first: FML 47 reads the "forge" mod's AT from there (MinecraftLocator)
        int n = editForgeAt(in.forgeUniversal);
        if (n > 0) log("Forge access transformer: " + n + " line(s) changed in the installed " + universalName() + " (clash with Fabric API)");
        // the shim's SHA-256 list names the unedited jar: remember the edited jar's hash, so the next install into this cache
        // (checkShimHashes) knows the change is Alloy's own (Forge 49: its universal has the two Ingredient lines)
        if (n > 0 && in.server && shimServer()) Files.writeString(editedHashFile(in.forgeUniversal), digest(in.forgeUniversal, "SHA-256") + "\n");
        in.stepPrepFml();
        buildNames(in, vj);
        return vj;
    }

    /** relative paths (to the Forge libraries folder) of the Forge 47/49 jars PrepFml rewrites (launch120.py FML_JARS, profile tools.fmlJars) */
    static List<String> fmlJars(Installer in) throws IOException {
        List<String> out = new ArrayList<>();
        for (String a : List.of("fmlcore", "fmlloader", "javafmllanguage", "lowcodelanguage", "mclanguage"))
            out.add("net/minecraftforge/" + a + "/" + Installer.FV + "/" + a + "-" + Installer.FV + ".jar");
        out.add(universalRel());
        Set<String> want = Set.of("modlauncher", "eventbus", "coremods", "accesstransformers");
        for (String rel : forgeLibRels(in)) if (want.contains(artifactOf(rel))) out.add(rel);
        return out;
    }

    /** artifact id of a maven path g/r/o/u/p/ARTIFACT/VERSION/file.jar */
    static String artifactOf(String rel) {
        String[] p = rel.split("/");
        return p.length >= 3 ? p[p.length - 3] : "";
    }

    /** library names of the installed Forge version json */
    static List<String> forgeLibNames(Installer in) throws IOException {
        var fj = Json.obj(Json.parse(Files.readString(forgeVersionJson(in))));
        List<String> out = new ArrayList<>();
        for (Object o : Json.arr(fj.get("libraries"))) out.add((String) Json.obj(o).get("name"));
        return out;
    }

    /**
     * Forge's runtime libraries as paths relative to the Forge libraries folder. Client: the libraries of the installed
     * Forge version json. Server: a server install has no version json; its start files (win_args.txt / unix_args.txt next
     * to the Forge jars) list the module path (-p) and -DlegacyClassPath, which hold the vanilla server libraries too.
     */
    static List<String> forgeLibRels(Installer in) throws IOException {
        List<String> out = new ArrayList<>();
        if (!in.server) {
            for (String n : forgeLibNames(in)) out.add(coordPath(n));
            return out;
        }
        if (shimServer()) {   // Forge 49+: the argfile is only "-jar forge-FV-shim.jar"; the class path is the shim's bootstrap-shim.list
            for (String[] e : shimEntries(in.shimText())) if (e[2].endsWith(".jar") && !out.contains(e[2])) out.add(e[2]);
            if (out.isEmpty()) throw new IOException("no libraries in " + in.shim + " (bootstrap-shim.list)");
            return out;
        }
        Path dir = in.forgeLibs.resolve("net/minecraftforge/forge/" + Installer.FV);
        Path f = dir.resolve("win_args.txt");
        String sep = ";";
        if (!Files.isRegularFile(f)) { f = dir.resolve("unix_args.txt"); sep = ":"; }
        if (!Files.isRegularFile(f)) throw new IOException("missing " + f + " (the Forge server install is incomplete; re-run the installer)");
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            line = line.trim();
            String list = line.startsWith("-p ") ? line.substring(3) : line.startsWith("-DlegacyClassPath=") ? line.substring("-DlegacyClassPath=".length()) : null;
            if (list == null) continue;
            for (String e : list.split(java.util.regex.Pattern.quote(sep))) {
                e = e.trim().replace('\\', '/');
                if (e.startsWith("libraries/")) e = e.substring("libraries/".length());
                if (e.endsWith(".jar") && !out.contains(e)) out.add(e);
            }
        }
        if (out.isEmpty()) throw new IOException("no libraries in " + f);
        return out;
    }

    /**
     * the lines of a Forge shim's bootstrap-shim.list (Forge 49 and up): {sha256, maven coordinate, path under libraries/},
     * tab separated. Blank or short lines are skipped.
     */
    static List<String[]> shimEntries(String text) {
        List<String[]> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String[] p = line.trim().split("\t");
            if (p.length < 3) p = line.trim().split("\\s+");
            if (p.length < 3) continue;
            out.add(new String[]{p[0].trim(), p[1].trim(), p[2].trim().replace('\\', '/')});
        }
        return out;
    }

    /**
     * Forge 49+ server: the shim does not check the sha256 column of its list, Alloy does. Every jar the server starts
     * with (vanilla libraries, Forge's own) must match the hash in the shim, which the pinned Forge installer brought.
     */
    static void checkShimHashes(Installer in) throws IOException {
        int n = 0;
        for (String[] e : shimEntries(in.shimText())) {
            Path p = in.forgeLibs.resolve(e[2]);
            if (!Files.isRegularFile(p)) throw new IOException("missing " + p + " (listed in " + in.shim.getFileName() + "; the Forge server install is incomplete, re-run the installer)");
            String sha = digest(p, "SHA-256");
            if (!sha.equalsIgnoreCase(e[0]) && !editedByAlloy(p, sha))
                throw new IOException("corrupt Forge server library " + p + " (SHA-256 does not match " + in.shim.getFileName()
                        + "; delete " + in.forge + " and install again)");
            n++;
        }
        log("Forge server libraries: " + n + " jars match the SHA-256 list of " + in.shim.getFileName());
    }

    /** next to a Forge jar whose access transformer Alloy edited (editForgeAt): the SHA-256 of the edited jar */
    static Path editedHashFile(Path jar) { return jar.resolveSibling(jar.getFileName() + ".alloy-edited.sha256"); }

    /**
     * true when jar is the copy Alloy itself edited after it matched the shim list: its hash is the one Alloy wrote then.
     * Any other change (a damaged or swapped file) still fails the check.
     */
    static boolean editedByAlloy(Path jar, String sha) throws IOException {
        Path f = editedHashFile(jar);
        return Files.isRegularFile(f) && Files.readString(f).trim().equalsIgnoreCase(sha);
    }

    /** maven coordinate g:a:v[:classifier][@ext] -> relative path */
    static String coordPath(String name) {
        String ext = "jar";
        if (name.contains("@")) { ext = name.substring(name.indexOf('@') + 1); name = name.substring(0, name.indexOf('@')); }
        String[] p = name.split(":");
        String cls = p.length > 3 ? "-" + p[3] : "";
        return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + p[1] + "-" + p[2] + cls + "." + ext;
    }

    /**
     * the class path of the name translator's hierarchy: the vanilla client libraries (version json order), or on a server
     * every library of Forge's server start (vanilla server libraries and Forge's own)
     */
    static List<Path> vanillaLibs(Installer in, Map<String, Object> vj) throws IOException {
        if (!in.server) return in.vanillaClientLibs(vj);
        List<Path> out = new ArrayList<>();
        for (String rel : forgeLibRels(in)) {
            Path p = in.forgeLibs.resolve(rel);
            if (Files.isRegularFile(p)) out.add(p);
        }
        return out;
    }

    static List<Path> vanillaLibs(Installer in) throws IOException {
        return vanillaLibs(in, in.server ? null : Json.obj(Json.parse(Files.readString(in.versionJson))));
    }

    /**
     * names folder: game-srg.jar (Forge's runtime game as one jar), game-mojang.jar, client-mojang.jar (vanilla, for the
     * fit), the Mojang-named Forge universal and srg2mojang.tsv. Keyed by the tools and the inputs; built in a temp folder
     * and moved in, so a finished folder is never written again (a running game may have its jars open).
     */
    static Path buildNames(Installer in, Map<String, Object> vj) throws Exception {
        Path patches = in.forgePatched, srg = srgJar(in), extra = extraJar(in), uni = in.fml.resolve(universalRel()), obf = gameObf(in);
        String vanillaMojang = vanillaMojangName(in.server);
        List<Path> inputs = new ArrayList<>(List.of(patches, srg, extra, uni, obf, mappingsTxt(in), intermediary(in), mcpZip(in)));
        if (!hasExtra()) inputs.remove(extra);
        if (NATIVE) inputs.remove(mcpZip(in));
        for (Path p : inputs)
            if (!Files.isRegularFile(p)) throw new IOException("missing " + p + " (the Forge install or a download is incomplete; re-run the installer)");
        StringBuilder sb = new StringBuilder("names-1|").append(toolsId()).append('|').append(sha1(resource("/alloy/remap/JarRemapper.class")));
        for (Path p : inputs)
            sb.append('|').append(p.getFileName()).append(Files.size(p));
        sb.append('|').append(digest(uni, "SHA-1"));
        if (NATIVE && in.server) sb.append("|srvmain1");   // serverMainFirst
        String key = sha1(sb.toString());
        Path root = namesRoot(in);
        Path dir = root.resolve(key.substring(0, 16));
        if (!(Files.isRegularFile(dir.resolve(".stamp")) && Files.readString(dir.resolve(".stamp")).equals(key))) {
            log("name translator: Forge's SRG game and Forge universal -> Mojang names (once, about a minute)");
            mkdirs(root);
            Path tmp = Files.createTempDirectory(root, key.substring(0, 16) + ".tmp");
            Path gsrg = tmp.resolve("game-srg.jar");
            List<Path> libs = vanillaLibs(in, vj);
            Path list = tmp.resolve("remap-list.txt");
            if (NATIVE) {   // Forge 50+: the game is Mojang-named already; only the vanilla client is translated (for the fit)
                union(tmp.resolve("game-mojang.jar"), patches, srg);
                if (in.server && serverMainFirst(tmp.resolve("game-mojang.jar"))) log("Forge's server Main: the dedicated server lambda moved first (Fabric Loader looks for it there)");
                Files.copy(uni, tmp.resolve(universalName()));
                Files.writeString(list, "obf\tnamed\t" + obf + "\t" + tmp.resolve(vanillaMojang) + "\n");
                remap(in, List.of("batch", "--list", list.toString(), "--classpath", joinPaths(libs)), tmp.resolve("remap.log"));
            } else {
                if (hasExtra()) union(gsrg, patches, srg, extra);
                else union(gsrg, patches, srg);
                Files.writeString(list, String.join("\n",
                        "srg\tnamed\t" + gsrg + "\t" + tmp.resolve("game-mojang.jar"),
                        "obf\tnamed\t" + obf + "\t" + tmp.resolve(vanillaMojang),
                        "srg\tnamed\t" + uni + "\t" + tmp.resolve(universalName()) + "\t" + gsrg) + "\n");
                remap(in, List.of("batch", "--list", list.toString(), "--classpath", joinPaths(libs)), tmp.resolve("remap.log"));
                remap(in, List.of("export", "--out", tmp.toString()), tmp.resolve("export.log"));
            }
            editForgeAt(tmp.resolve(universalName()));
            for (String f : NATIVE ? List.of("game-mojang.jar", vanillaMojang, universalName()) : List.of("game-mojang.jar", vanillaMojang, universalName(), "srg2mojang.tsv"))
                if (!Files.isRegularFile(tmp.resolve(f))) throw new IOException("name translator did not write " + f + "; see " + tmp.resolve("remap.log"));
            Files.writeString(tmp.resolve(".stamp"), key);
            try { Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException e) {
                if (Files.isRegularFile(dir.resolve(".stamp")) && Files.readString(dir.resolve(".stamp")).equals(key)) deleteTree(tmp);
                else { dir = root.resolve(key.substring(0, 16) + "-" + Long.toString(System.nanoTime(), 36)); Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE); }
            }
            log("name translator: done (" + dir + ")");
        }
        Path cur = root.resolve("current");
        if (!(Files.isRegularFile(cur) && Files.readString(cur).trim().equals(dir.getFileName().toString())))
            Files.writeString(cur, dir.getFileName().toString());
        return dir;
    }

    static final String SERVER_MAIN = "net/minecraft/server/Main.class";

    /**
     * Fabric Loader finds the dedicated server in net.minecraft.server.Main by taking the first synthetic method that
     * returns an object (the lambda given to MinecraftServer.spin) and looking there for a constructor whose first
     * argument is a Thread. Forge 61's patched Main (1.21.11) has another lambda first (lambda$createNewWorldData$4,
     * returns DataResult), so Fabric stops with "Could not find dedicated server constructor". This moves the server
     * lambda (synthetic, returns an object, last argument Thread) in front of the other synthetic methods. Method bodies
     * are not changed. Returns true when the jar was rewritten.
     */
    static boolean serverMainFirst(Path jar) throws IOException {
        byte[] cls;
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry(SERVER_MAIN);
            if (e == null) return false;
            try (InputStream is = z.getInputStream(e)) { cls = is.readAllBytes(); }
        }
        byte[] out = serverMainFirst(cls);
        if (out == null) return false;
        Path tmp = jar.resolveSibling(jar.getFileName() + ".tmp");
        try (ZipFile z = new ZipFile(jar.toFile()); ZipOutputStream zo = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20))) {
            for (var en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                ZipEntry ne = new ZipEntry(e.getName());
                ne.setTime(e.getTime());
                zo.putNextEntry(ne);
                if (e.getName().equals(SERVER_MAIN)) zo.write(out);
                else try (InputStream is = z.getInputStream(e)) { is.transferTo(zo); }
                zo.closeEntry();
            }
        }
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    /** the class bytes with the server lambda first among the synthetic object-returning methods, or null when it is already */
    static byte[] serverMainFirst(byte[] cls) {
        var node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(cls).accept(node, 0);
        int first = -1, server = -1;
        for (int i = 0; i < node.methods.size(); i++) {
            var m = node.methods.get(i);
            if ((m.access & org.objectweb.asm.Opcodes.ACC_SYNTHETIC) == 0 || m.name.equals("main")) continue;
            if (org.objectweb.asm.Type.getReturnType(m.desc).getSort() != org.objectweb.asm.Type.OBJECT) continue;
            if (first < 0) first = i;
            if (server < 0 && m.desc.contains("Ljava/lang/Thread;)")) server = i;
        }
        if (server < 0 || server == first) return null;
        node.methods.add(first, node.methods.remove(server));
        var w = new org.objectweb.asm.ClassWriter(0);
        node.accept(w);
        return w.toByteArray();
    }

    static String joinPaths(List<Path> ps) {
        StringBuilder b = new StringBuilder();
        for (Path p : ps) { if (b.length() > 0) b.append(';'); b.append(p.toAbsolutePath()); }
        return b.toString();
    }

    /** runs the bundled name translator (alloy.remap.Main) in a child JVM with the mapping arguments */
    static void remap(Installer in, List<String> args, Path logFile) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(ownJava().toString(), "-Xmx1500m", "-XX:ActiveProcessorCount=2"));
        cmd.addAll(JVM_PROPS);
        cmd.addAll(List.of("-cp", selfJar().toString(), "alloy.remap.Main"));
        cmd.addAll(args);
        cmd.addAll(List.of("--mojang", mappingsTxt(in).toString(), "--intermediary", intermediary(in).toString()));
        if (!NATIVE) cmd.addAll(List.of("--mcp", mcpZip(in).toString()));   // Forge 50+ has no MCPConfig
        cmd.addAll(List.of("--game-obf", gameObf(in).toString()));
        mkdirs(logFile.getParent());
        int rc = run(cmd, logFile, null);
        if (rc != 0) {
            String t = Files.exists(logFile) ? Files.readString(logFile) : "";
            System.err.println(t.length() > 3000 ? t.substring(t.length() - 3000) : t);
            throw new IOException("name translator failed (exit " + rc + "); see " + logFile);
        }
    }

    /** Forge's runtime game: the union of Forge's patched classes, client-srg and client-extra (patched classes win). */
    static void union(Path out, Path... jars) throws IOException {
        Set<String> seen = new HashSet<>();
        try (ZipOutputStream zo = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(out), 1 << 20))) {
            for (Path j : jars) try (ZipFile z = new ZipFile(j.toFile())) {
                for (var en = z.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (e.isDirectory() || seen.contains(n)) continue;
                    if (n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith("MANIFEST.MF"))) continue;
                    seen.add(n);
                    ZipEntry ne = new ZipEntry(n);
                    ne.setTime(e.getTime());
                    zo.putNextEntry(ne);
                    try (InputStream is = z.getInputStream(e)) { is.transferTo(zo); }
                    zo.closeEntry();
                }
            }
        }
    }

    /**
     * FORGE_AT_EDITS in META-INF/accesstransformer.cfg of a Forge universal jar; returns the number of changed lines (0: nothing
     * to do). Forge 49's universal is signed: the signature files are dropped with the edit (a changed file breaks the
     * signature, and PrepFml's JarFile then refuses the jar with "SHA-256 digest error"). An edited jar that still has them
     * (an earlier installer) is rewritten without them.
     */
    static int editForgeAt(Path jar) throws IOException {
        String name = "META-INF/accesstransformer.cfg";
        List<Object[]> entries = new ArrayList<>();
        String at;
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry ae = z.getEntry(name);
            if (ae == null) return 0;
            try (InputStream is = z.getInputStream(ae)) { at = new String(is.readAllBytes(), StandardCharsets.UTF_8); }
            boolean any = false, edited = false, signed = false;
            for (String k : FORGE_AT_EDITS.keySet()) any |= at.contains(k);
            for (String v : FORGE_AT_EDITS.values()) edited |= at.contains(v);
            for (var en = z.entries(); en.hasMoreElements(); ) signed |= signatureFile(en.nextElement().getName());
            if (!any && !(edited && signed)) return 0;
            for (var en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || signatureFile(e.getName())) continue;
                try (InputStream is = z.getInputStream(e)) { entries.add(new Object[]{e, is.readAllBytes()}); }
            }
        }
        int n = 0;
        for (var e : FORGE_AT_EDITS.entrySet()) {
            int i = 0;
            while ((i = at.indexOf(e.getKey(), i)) >= 0) { n++; i += e.getKey().length(); }
            at = at.replace(e.getKey(), e.getValue());
        }
        Path tmp = jar.resolveSibling(jar.getFileName() + ".tmp");
        try (ZipOutputStream zo = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            for (Object[] o : entries) {
                ZipEntry e = (ZipEntry) o[0];
                ZipEntry ne = new ZipEntry(e.getName());
                ne.setTime(e.getTime());
                zo.putNextEntry(ne);
                zo.write(e.getName().equals(name) ? at.getBytes(StandardCharsets.UTF_8) : (byte[]) o[1]);
                zo.closeEntry();
            }
        }
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        return n;
    }

    static boolean signatureFile(String name) {
        String u = name.toUpperCase(Locale.ROOT);
        return u.startsWith("META-INF/") && u.indexOf('/', 9) < 0 && (u.endsWith(".SF") || u.endsWith(".RSA") || u.endsWith(".DSA") || u.endsWith(".EC"));
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) { for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x); }
    }

    // ------------------------------------------------------------------ class path and JVM arguments
    /**
     * Forge 47/49 runtime jars that are not on Knot's class path, as path prefixes relative to the libraries folder: Fabric's
     * Mixin + ASM serve Forge too, Alloy replaces the JPMS boot (bootstraplauncher on 47, bootstrap + bootstrap-api on 49),
     * and Forge 49's patched client jar is part of the game jar.
     */
    static List<String> forgeSkip() {
        return List.of("org/spongepowered/mixin/", "org/ow2/asm/", "cpw/mods/bootstraplauncher/", "net/minecraftforge/bootstrap/",
                "net/minecraftforge/bootstrap-api/", coordPath(GROUP + ":forge:" + Installer.FV + ":client"));
    }
    /** server only: the -extra jar of Forge's server start (Minecraft's data files) is inside the game jar already */
    static final String SERVER_SKIP = "net/minecraft/";

    /**
     * The class path (launch120.py classpath): Fabric Loader + Mixin + ASM, the vanilla libraries (client: version json;
     * server: in Forge's server start list), Forge 47's runtime libraries (PrepFml copies where there are some; Forge
     * universal in Mojang names from the names folder). One copy per file name. The game jar itself is not on it
     * (-Dfabric.gameJarPath).
     */
    static List<Path> classpath(Installer in, Map<String, Object> vj, Path universalMojang) throws IOException {
        List<Path> cands = new ArrayList<>();
        for (Path p : rglobJars(in.fabricLibs)) {
            String s = p.toString().replace('\\', '/');
            if (s.contains("net/fabricmc/") || s.contains("org/ow2/asm/")) cands.add(p);
        }
        // vanilla's own ASM (1.21.4 and up list ASM 9.6) stays off: Fabric Loader refuses two ASM copies on the class path
        if (!in.server) for (Path p : in.vanillaClientLibs(vj)) if (!p.toString().replace('\\', '/').contains("/org/ow2/asm/")) cands.add(p);
        cands.addAll(forgeRuntime(in, universalMojang));
        Set<String> seen = new HashSet<>();
        List<Path> cp = new ArrayList<>();
        for (Path p : cands) if (seen.add(p.getFileName().toString())) cp.add(p);
        return cp;
    }

    static List<Path> forgeRuntime(Installer in, Path universalMojang) throws IOException {
        // Forge 47's json does not list fmlcore and the language providers (FML finds them through LibraryFinder); Forge 49's
        // lists them and universal, so each is added only when it is not there yet
        List<String> rels = new ArrayList<>(forgeLibRels(in));
        for (String a : List.of("fmlcore", "javafmllanguage", "lowcodelanguage", "mclanguage")) {
            String r = coordPath(GROUP + ":" + a + ":" + Installer.FV);
            if (!rels.contains(r)) rels.add(r);
        }
        if (!rels.contains(universalRel())) rels.add(universalRel());
        List<Path> out = new ArrayList<>();
        List<String> skip = forgeSkip();
        for (String rel : rels) {
            if (skip.stream().anyMatch(rel::startsWith) || in.server && rel.startsWith(SERVER_SKIP)) continue;
            if (shimServer() && in.server && rel.equals(coordPath(GROUP + ":forge:" + Installer.FV + ":server"))) continue;   // Forge 49+: the patched server is in the game jar
            Path f = rel.equals(universalRel()) ? universalMojang : Files.exists(in.fml.resolve(rel)) ? in.fml.resolve(rel) : in.forgeLibs.resolve(rel);
            if (!Files.exists(f) && !rel.equals(universalRel())) throw new IOException("missing Forge library " + f);
            out.add(f);
        }
        return out;
    }

    /**
     * Forge's union file system jar from the class path (securejarhandler on Forge 47, securemodules on Forge 49): Knot must
     * use the system class loader's copy (-Dfabric.systemLibraries)
     */
    static Path secureJarHandler(List<Path> cp) {
        for (Path p : cp) {
            String n = p.getFileName().toString();
            if (n.startsWith("securejarhandler-") || n.startsWith("securemodules-")) return p;
        }
        return null;
    }

    /**
     * JVM arguments of a 1.20.1 start (launch120.py step_run), paths as given (relative to the game dir or absolute).
     * Values with spaces are fine in a launcher's list; argfiles quote them (see {@link #argfileQuote}).
     */
    static List<String> jvmArgs(String gameJar, String systemLibs, String libraryDir, String srgNames) {
        if (NATIVE) srgNames = "";   // Forge 50+: the bridge reads Forge's srg2off csv files from the class path instead
        List<String> out = new ArrayList<>(List.of("-Dfabric.gameJarPath=" + gameJar, "-Dfabric.systemLibraries=" + systemLibs,
                "-DlibraryDirectory=" + libraryDir, "-Dalloy.forgeVersion=" + forge(), "-Dalloy.mcVersion=" + Installer.MC,
                "-Dalloy.mcpVersion=" + MCP, "-Dalloy.forgeGroup=" + GROUP, "-Dalloy.naming=mcp", "-Dalloy.srgNames=" + srgNames,
                "--add-opens", "java.base/java.util.jar=ALL-UNNAMED", "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
                "--add-exports", "java.base/sun.security.util=ALL-UNNAMED"));
        if (EXPAND_FRAMES) out.add(EXPAND_FRAMES_ARG);
        return out;
    }

    static String argfileQuote(String a) { return a.contains(" ") ? "\"" + a.replace("\\", "/") + "\"" : a; }

    // ------------------------------------------------------------------ prep: names files into the game dir, Forge mods
    /**
     * Copies the derived files the launch reads from the names folder into the places prep.properties names (gameJar,
     * universalMojang, srgNames), when they differ. Returns the names folder.
     */
    static Path syncNames(Installer in, Path game, Properties cfg) throws IOException {
        Path names = names(in);
        if (names == null) throw new IOException("the Minecraft " + Installer.MC + " names folder is missing in " + namesRoot(in));
        String[][] pairs = {{"gameJar", "game-mojang.jar"}, {"universalMojang", universalName()}, {"srgNames", "srg2mojang.tsv"}};
        for (String[] pr : pairs) {
            String v = cfg.getProperty(pr[0]);
            if (v == null || v.isBlank()) continue;
            Path dst = game.resolve(v).normalize(), src = names.resolve(pr[1]);
            if (Files.isRegularFile(dst) && Files.size(dst) == Files.size(src) && Files.mismatch(src, dst) == -1) continue;
            mkdirs(dst.getParent());
            Path tmp = dst.resolveSibling(dst.getFileName() + ".tmp");
            Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
        }
        return names;
    }

    static Path remapState(Path game) { return game.resolve(".alloy/remap120.properties"); }

    /** state key (not a jar name) that holds the translator build the copies were made with */
    static final String TRANSLATOR_KEY = ".translator";

    /** the bundled translator's identity: copies made by another build are redone */
    static String translatorId() {
        try { return sha1(resource("/alloy/remap/JarRemapper.class")).substring(0, 16); }
        catch (IOException e) { return "unknown"; }
    }

    static Properties loadState(Path f) {
        Properties p = new Properties();
        try { if (Files.exists(f)) try (Reader r = Files.newBufferedReader(f)) { p.load(r); } } catch (IOException e) { /* empty */ }
        return p;
    }

    static String stat(Path p) throws IOException { return Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis(); }

    /**
     * Collide rewrote (or restored) these Mojang-named copies in forgemods/: they are still ours, so the remap state takes
     * their new size and mtime and keeps the original's sha1 (otherwise the next prep would take them for the player's
     * own new jars and overwrite the SRG originals with them).
     */
    static void adopt(Path game, Collection<String> names) throws IOException {
        Properties st = loadState(remapState(game));
        boolean dirty = false;
        for (String n : names) {
            String v = st.getProperty(n);
            Path j = game.resolve("forgemods").resolve(n);
            if (v == null || !Files.exists(j)) continue;
            String nv = v.split("\\|")[0] + "|" + stat(j);
            if (!nv.equals(v)) { st.setProperty(n, nv); dirty = true; }
        }
        if (dirty) try (Writer w = Files.newBufferedWriter(remapState(game))) { st.store(w, "alloy 1.20.1 Forge mod remap: jar = original sha1|size|mtime of the Mojang-named copy"); }
    }

    /**
     * Our Mojang-named copy whose file time changed but not its bytes: the instance was copied, unpacked or synced without
     * timestamps. Taken for a new jar, the copy would overwrite the SRG original in .alloy/originals and be translated a
     * second time (VORTEX copied with plain cp: 390 "collision" renames like loadPacks$alloy, AbstractMethodError at start).
     * Same size and, when the state has it, the copy's sha1; older states: not the original itself, and the original intact.
     */
    static boolean movedCopy(Path j, String[] a, Path orig) throws IOException {
        if (a.length < 3 || !Files.exists(orig) || !a[1].equals(String.valueOf(Files.size(j)))) return false;
        String sha = digest(j, "SHA-1");
        if (a.length >= 5) return sha.equals(a[4]);
        return !sha.equals(a[0]) && digest(orig, "SHA-1").equals(a[0]);
    }

    /** the user's jar behind a forgemods/ file: the original's sha1 when the file is our remapped copy, else its size|mtime */
    static String identity(Path game, Path jar) throws IOException {
        String v = loadState(remapState(game)).getProperty(jar.getFileName().toString());
        if (v != null) {
            String[] a = v.split("\\|");
            if (a.length >= 3 && (a[1] + "|" + a[2]).equals(stat(jar))) return "R" + a[0];
        }
        return stat(jar);
    }

    /**
     * Forge mods are published in SRG names; the 1.20.1 runtime is Mojang-named. Every jar in forgemods/ that is not our
     * remapped copy is the player's file: it is kept in .alloy/originals/forgemods/, then replaced by its Mojang-named copy
     * (jar-in-jar MixinExtras entries dropped first, as launch120.py does). State: .alloy/remap120.properties
     * (name = original sha1|size|mtime of the copy).
     */
    static void remapForgeMods(Installer in, Path game, Path names) throws Exception {
        Path fmods = game.resolve("forgemods"), orig = game.resolve(".alloy/originals/forgemods"), work = game.resolve(".alloy/remap120");
        Properties st = loadState(remapState(game));
        // a newer translator (for example one that also rewrites text resources) redoes our copies from the originals
        String tool = translatorId();
        boolean newTool = !tool.equals(st.getProperty(TRANSLATOR_KEY));
        Set<String> present = new HashSet<>();
        List<String[]> todo = new ArrayList<>();   // {name, sha1}
        int moved = 0;
        for (Path j : jars(fmods)) {
            String n = j.getFileName().toString();
            present.add(n);
            String v = st.getProperty(n);
            if (v != null) {
                String[] a = v.split("\\|");
                boolean ours = a.length >= 3 && (a[1] + "|" + a[2]).equals(stat(j)) && Files.exists(orig.resolve(n));
                if (!ours && movedCopy(j, a, orig.resolve(n))) {   // our copy, only its mtime changed: keep it, do not take it for a new jar
                    a[2] = String.valueOf(Files.getLastModifiedTime(j).toMillis());
                    st.setProperty(n, String.join("|", a));
                    moved++;
                    ours = true;
                }
                if (ours && !newTool && a.length >= 4 && a[3].equals(REMAP_RULES)) continue;   // ours, up to date
                if (ours) {   // our copy from older rules or an older translator: redo it from the original, never from the copy (that remapped twice)
                    todo.add(new String[]{n, a[0]});
                    continue;
                }
            }
            mkdirs(orig);
            Files.copy(j, orig.resolve(n), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            todo.add(new String[]{n, digest(j, "SHA-1")});
        }
        boolean dirty = moved > 0;
        if (moved > 0) log("alloy-prep: " + moved + " translated Forge mod(s) only got a new file time (instance copied?): kept, not translated again");
        for (String k : new ArrayList<>(st.stringPropertyNames()))
            if (!k.startsWith(".") && !present.contains(k)) { st.remove(k); Files.deleteIfExists(orig.resolve(k)); dirty = true; }
        if (!todo.isEmpty() && NATIVE) {   // Forge 50+: Forge mods are Mojang-named as published; only the jar fixes below
            mkdirs(work);
            SrgLeft left = new SrgLeft(in, names, work);
            for (String[] t : todo) {
                Path src = orig.resolve(t[0]), stage = work.resolve(t[0] + ".in.jar"), dst = fmods.resolve(t[0]);
                if (!Prep.stripDanglingMixinExtras(src, stage)) Files.copy(src, stage, StandardCopyOption.REPLACE_EXISTING);
                dropMixinExtrasEntries(stage);
                if (dropNestedFfapi(stage)) log("alloy-prep: " + t[0] + ": its jar-in-jar Forgified Fabric API is left out of FML's list, Alloy's Fabric API is used");
                if (dropShadedFabric(stage)) log("alloy-prep: " + t[0] + " ships its own copy of Fabric Loader classes (net.fabricmc); left out, Alloy's Fabric Loader has them");
                left.fix(t[0], stage);
                Files.move(stage, dst, StandardCopyOption.REPLACE_EXISTING);
                st.setProperty(t[0], t[1] + "|" + stat(dst) + "|" + REMAP_RULES + "|" + digest(dst, "SHA-1"));
            }
            dirty = true;
        } else if (!todo.isEmpty()) {
            mkdirs(work);
            List<String> lines = new ArrayList<>();
            String extra = names.resolve("game-srg.jar") + ";" + in.fml.resolve(universalRel());
            for (String[] t : todo) {
                Path src = orig.resolve(t[0]), stage = work.resolve(t[0] + ".in.jar");
                if (!Prep.stripDanglingMixinExtras(src, stage)) Files.copy(src, stage, StandardCopyOption.REPLACE_EXISTING);
                dropMixinExtrasEntries(stage);
                if (dropNestedFfapi(stage)) log("alloy-prep: " + t[0] + ": its jar-in-jar Forgified Fabric API is left out of FML's list, Alloy's Fabric API is used");
                if (dropShadedFabric(stage)) log("alloy-prep: " + t[0] + " ships its own copy of Fabric Loader classes (net.fabricmc); left out, Alloy's Fabric Loader has them");
                lines.add("auto\tnamed\t" + stage + "\t" + work.resolve(t[0] + ".out.jar") + "\t" + extra);
            }
            Path list = work.resolve("forge-list.txt");
            Files.writeString(list, String.join("\n", lines) + "\n");
            log("name translator: " + todo.size() + " Forge mod(s) SRG -> Mojang names");
            remap(in, List.of("batch", "--list", list.toString(), "--classpath", joinPaths(vanillaLibs(in))),
                    work.resolve("forge-remap.log"));
            for (String[] t : todo) {
                Path out = work.resolve(t[0] + ".out.jar"), dst = fmods.resolve(t[0]);
                Files.move(out, dst, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(work.resolve(t[0] + ".in.jar"));
                st.setProperty(t[0], t[1] + "|" + stat(dst) + "|" + REMAP_RULES + "|" + digest(dst, "SHA-1"));
            }
            dirty = true;
        }
        if (newTool) { st.setProperty(TRANSLATOR_KEY, tool); dirty = true; }
        if (dirty) try (Writer w = Files.newBufferedWriter(remapState(game))) { st.store(w, "alloy 1.20.1 Forge mod remap: jar = original sha1|size|mtime of the Mojang-named copy"); }
    }

    /** what the copies in forgemods/ get besides the names; a change makes existing instances redo them once */
    static final String REMAP_RULES = "r4";   // r2: shaded net.fabricmc classes dropped, r3: jar-in-jar Forgified Fabric API entries dropped, r4: Forge 50+ SRG leftovers

    /**
     * Forge 50+: leftover SRG member names in a Forge mod (alloy.remap.SrgLeftover) renamed to Mojang names with Forge's
     * own srg2off table (libraries/de/oceanlabs/mcp/mcp_config/MC-V/mcp_config-MC-V-srg2off.jar). Off with
     * -Dalloy.srgLeftover=false, and when the table or the game jar is missing. The table and the game's class index are
     * loaded on the first jar that has an SRG-looking name, so a normal prep pays only a byte scan per class.
     * Log: .alloy/remap120/srgleft.log.
     */
    static final class SrgLeft {
        final Installer in; final Path names, work;
        final Object[] cache = new Object[1];
        Path csv;
        boolean tried;
        SrgLeft(Installer in, Path names, Path work) { this.in = in; this.names = names; this.work = work; }

        void fix(String name, Path jar) {
            if (!Boolean.parseBoolean(System.getProperty("alloy.srgLeftover", "true"))) return;
            try {
                if (!tried) {
                    tried = true;
                    csv = srg2off(in);
                    if (csv == null) log("alloy-prep: no srg2off table in Forge's libraries; leftover SRG names in Forge mods stay as they are");
                }
                if (csv == null) return;
                List<Path> game = new ArrayList<>();
                for (Path p : List.of(names.resolve("game-mojang.jar"), names.resolve(universalName()))) if (Files.isRegularFile(p)) game.add(p);
                if (game.isEmpty()) return;
                // alloy.remap is bundled in the installer jar; called by reflection so the installer sources compile on their own
                java.lang.reflect.Method m = Class.forName("alloy.remap.SrgLeftover")
                        .getMethod("prep", Object[].class, Path.class, Path.class, String.class, List.class);
                String[] r = (String[]) m.invoke(null, cache, csv, jar, name, game);
                if (r == null) return;
                Files.writeString(work.resolve("srgleft.log"), r[2], StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (r[0].equals("1")) log("alloy-prep: " + name + " still has SRG names from its build (" + r[1] + "), renamed to Mojang names as Forge "
                        + forge() + " runs (see .alloy/remap120/srgleft.log)");
            } catch (Exception e) {
                Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
                log("alloy-prep: SRG leftover check of " + name + " skipped: " + c);
            }
        }
    }

    /** Forge 50+: Forge's srg2off table in the Forge libraries, or null */
    static Path srg2off(Installer in) throws IOException {
        Path d = in.forgeLibs.resolve("de/oceanlabs/mcp/mcp_config");
        if (!Files.isDirectory(d)) return null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, Installer.MC + "-*")) {
            for (Path v : ds) {
                Path j = v.resolve("mcp_config-" + v.getFileName() + "-srg2off.jar");
                if (Files.isRegularFile(j)) return j;
            }
        }
        return null;
    }

    /**
     * FML's own jar-in-jar locator reads a Forge mod's META-INF/jarjar/metadata.json and loads the nested Forgified Fabric
     * API modules as Forge mods even when Prep leaves them out of the class path; their @Mod class is then missing
     * ("Fabric API Base has class loading errors", Sodium Dynamic Lights in Zombie Invade). Drops the metadata entries of
     * nested jars that Prep.forgifiedFabric matches; the nested files stay unused. True when something was dropped.
     */
    @SuppressWarnings("unchecked")
    static boolean dropNestedFfapi(Path jar) throws IOException {
        Map<String, Object> d;
        List<Object[]> entries = new ArrayList<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry me = z.getEntry("META-INF/jarjar/metadata.json");
            if (me == null) return false;
            try (InputStream is = z.getInputStream(me)) { d = Json.obj(Json.parse(new String(is.readAllBytes(), StandardCharsets.UTF_8))); }
            if (d == null || !(d.get("jars") instanceof List)) return false;
            List<Object> keep = new ArrayList<>();
            for (Object o : Json.arr(d.get("jars"))) {
                Object path = Json.obj(o).get("path");
                ZipEntry ne = path == null ? null : z.getEntry(path.toString());
                boolean ff = false;
                if (ne != null) try (InputStream is = z.getInputStream(ne)) { ff = Prep.forgifiedFabric(is.readAllBytes()); }
                if (!ff) keep.add(o);
            }
            if (keep.size() == Json.arr(d.get("jars")).size()) return false;
            d.put("jars", keep);
            for (var en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                try (InputStream is = z.getInputStream(e)) { entries.add(new Object[]{e, is.readAllBytes()}); }
            }
        }
        Path tmp = jar.resolveSibling(jar.getFileName() + ".tmp");
        try (ZipOutputStream o = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            for (Object[] x : entries) {
                ZipEntry e = (ZipEntry) x[0];
                ZipEntry ne = new ZipEntry(e.getName());
                ne.setTime(e.getTime());
                o.putNextEntry(ne);
                o.write(e.getName().equals("META-INF/jarjar/metadata.json") ? Json.write(d).getBytes(StandardCharsets.UTF_8) : (byte[]) x[1]);
                o.closeEntry();
            }
        }
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    /**
     * A Forge mod that shades Fabric Loader's API classes (CreativeCore: net.fabricmc.api.EnvType and Environment) puts a
     * second copy of them on Knot's class path. Dwellers stopped before FML with a loader constraint violation on EnvType.
     * Real Forge has no other copy, so this is harmless there. Drops net/fabricmc/ entries from a Forge jar (one without
     * fabric.mod.json); true when something was dropped.
     */
    static boolean dropShadedFabric(Path jar) throws IOException {
        List<Object[]> entries = new ArrayList<>();
        boolean drop = false;
        try (ZipFile z = new ZipFile(jar.toFile())) {
            if (z.getEntry("fabric.mod.json") != null) return false;
            for (var en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.getName().startsWith("net/fabricmc/")) { drop = true; continue; }
                if (e.isDirectory()) continue;
                try (InputStream is = z.getInputStream(e)) { entries.add(new Object[]{e, is.readAllBytes()}); }
            }
        }
        if (!drop) return false;
        Path tmp = jar.resolveSibling(jar.getFileName() + ".tmp");
        try (ZipOutputStream o = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            for (Object[] x : entries) {
                ZipEntry e = (ZipEntry) x[0];
                ZipEntry ne = new ZipEntry(e.getName());
                ne.setTime(e.getTime());
                o.putNextEntry(ne);
                o.write((byte[]) x[1]);
                o.closeEntry();
            }
        }
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    /**
     * build_bridge.copy_forge_jar: jar-in-jar metadata entries for MixinExtras are dropped (Fabric Loader provides
     * MixinExtras; FML 47 would load a second copy). Only the metadata line goes, the nested file stays unused.
     */
    @SuppressWarnings("unchecked")
    static void dropMixinExtrasEntries(Path jar) throws IOException {
        Map<String, Object> d;
        List<Object[]> entries = new ArrayList<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry me = z.getEntry("META-INF/jarjar/metadata.json");
            if (me == null) return;
            try (InputStream is = z.getInputStream(me)) { d = Json.obj(Json.parse(new String(is.readAllBytes(), StandardCharsets.UTF_8))); }
            if (d == null || !(d.get("jars") instanceof List)) return;
            List<Object> keep = new ArrayList<>();
            for (Object o : Json.arr(d.get("jars"))) {
                Map<String, Object> j = Json.obj(o);
                Object id = j.get("identifier");
                String art = id instanceof Map ? String.valueOf(((Map<String, Object>) id).get("artifact")) : "";
                if (!art.toLowerCase().contains("mixinextras")) keep.add(o);
            }
            if (keep.size() == Json.arr(d.get("jars")).size()) return;
            d.put("jars", keep);
            for (var en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                try (InputStream is = z.getInputStream(e)) { entries.add(new Object[]{e, is.readAllBytes()}); }
            }
        }
        Path tmp = jar.resolveSibling(jar.getFileName() + ".tmp");
        try (ZipOutputStream o = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            for (Object[] x : entries) {
                ZipEntry e = (ZipEntry) x[0];
                ZipEntry ne = new ZipEntry(e.getName());
                ne.setTime(e.getTime());
                o.putNextEntry(ne);
                o.write(e.getName().equals("META-INF/jarjar/metadata.json") ? Json.write(d).getBytes(StandardCharsets.UTF_8) : (byte[]) x[1]);
                o.closeEntry();
            }
        }
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
    }

    // ------------------------------------------------------------------ Fabric mods
    static Path fabricRemapDir(Path game) { return game.resolve(".alloy/remap120/fabric"); }

    /** Intermediary -> Mojang copies of the given Fabric jars (one translator run for all), kept by sha1 of the original. */
    static void remapFabric(Path game, List<Path> jars) throws Exception {
        Path dir = fabricRemapDir(game);
        Path stamp = dir.resolve(".translator");
        String tool = translatorId();
        if (Files.isDirectory(dir) && !(Files.isRegularFile(stamp) && Files.readString(stamp).trim().equals(tool)))
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) { for (Path p : ds) Files.deleteIfExists(p); }
        List<String> lines = new ArrayList<>();
        Set<String> want = new HashSet<>();
        for (Path j : jars) {
            String sha = digest(j, "SHA-1");
            want.add(sha + ".jar");
            if (Files.exists(dir.resolve(sha + ".jar"))) continue;
            // Fabric Loader's runtime namespace is "official" here (no mappings in the game jar): access wideners get that header
            lines.add("auto\tofficial\t" + j.toAbsolutePath() + "\t" + dir.resolve(sha + ".jar"));
        }
        if (lines.isEmpty()) return;
        mkdirs(dir);
        Files.writeString(stamp, tool);
        Path list = dir.resolve("fabric-list.txt");
        Files.writeString(list, String.join("\n", lines) + "\n");
        Installer in = installerFor(game);
        log("name translator: " + lines.size() + " Fabric mod(s) Intermediary -> Mojang names");
        remap(in, List.of("batch", "--list", list.toString(), "--classpath", joinPaths(vanillaLibs(in))), dir.resolve("fabric-remap.log"));
    }

    /** the Mojang-named copy of a Fabric jar (by the original's sha1), or null */
    static byte[] remappedFabric(Path game, String sha) throws IOException {
        Path p = fabricRemapDir(game).resolve(sha + ".jar");
        return Files.exists(p) ? Files.readAllBytes(p) : null;
    }

    static Installer installerFor(Path game) throws IOException {
        Properties cfg = new Properties();
        try (Reader r = Files.newBufferedReader(game.resolve(".alloy/prep.properties"))) { cfg.load(r); }
        return new Installer(cfg.getProperty("side", "client"), game, game.resolve(cfg.getProperty("cache", ".alloy/cache")));
    }

    /**
     * the fit lists of the selected version, bundled as one file: 1.20.1 = compat_replaces.1.20.1.txt, then prefit_strip.1.20.1.txt
     * and prefit_strip.portinglib.1.20.1.txt (/compat120/compat_replaces.txt); 1.20.4 = compat_replaces.1.20.4.txt, then
     * prefit_strip.1.20.4.txt with its Sodium and Iris strips (/compat120/1.20.4/compat_replaces.txt)
     */
    static String compatText() {
        if (!Installer.MC.equals("1.20.1") && Installer.class.getResource("/compat120/" + Installer.MC + "/compat_replaces.txt") != null)
            return Fit.readResourceText("/compat120/" + Installer.MC + "/compat_replaces.txt");
        return Fit.readResourceText("/compat120/compat_replaces.txt");
    }

    // ------------------------------------------------------------------ access widener drops
    /**
     * the "drop" lines of the selected version's bridge/src/alloy/compat120/aw_edits.MC.txt, bundled as
     * /compat120/aw_edits.MC.txt by build_installer.py ("" when the version has none). launch120.py applied them to every
     * Fabric mod it fitted; the installer fitted players' mods without them, so for example Axiom 6.1.5's AW kept widening
     * StairBlock.base and Forge's field_to_method coremod refused to run ("Field base is not private"): Forge mods' stairs
     * built from a Supplier then read an empty base block.
     */
    static String awEditsText() {
        String t = Fit.readResourceText("/compat120/aw_edits." + Installer.MC + ".txt");
        return t == null ? "" : t;
    }

    /** token lists of the drop lines in an aw_edits text (add and call lines are Fabric API only, launch120.py) */
    static List<List<String>> awDrops(String text) {
        List<List<String>> out = new ArrayList<>();
        for (String l : text.split("\\R")) {
            String[] t = l.trim().split("\\s+");
            if (t.length > 1 && t[0].equals("drop")) out.add(List.of(t).subList(1, t.length));
        }
        return out;
    }

    /** the AW text without the lines that start with one of the drop token lists (adapt_fabric.edit_aw), or null when none */
    static String dropAwLines(String aw, List<List<String>> drops, List<String> dropped) {
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (String l : aw.split("\\R", -1)) {
            String c = l.indexOf('#') >= 0 ? l.substring(0, l.indexOf('#')) : l;
            List<String> t = List.of(c.trim().split("\\s+"));
            boolean drop = false;
            for (List<String> d : drops) if (t.size() >= d.size() && t.subList(0, d.size()).equals(d)) drop = true;
            if (drop) { any = true; dropped.add(String.join(" ", t)); continue; }
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        return any ? sb.toString() : null;
    }

    /**
     * Drops the version's aw_edits lines from the jar's access wideners (every *.accesswidener / *.aw file named by a
     * fabric.mod.json, in the jar and in its nested META-INF/jars/ jars). Returns the jar itself when nothing matched.
     */
    static byte[] dropAccessWideners(byte[] jar, List<List<String>> drops, List<String> log, String name) throws IOException {
        if (drops.isEmpty()) return jar;
        Map<String, byte[]> data = new LinkedHashMap<>();
        Map<String, Long> times = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) if (!e.isDirectory()) { data.put(e.getName(), z.readAllBytes()); times.put(e.getName(), e.getTime()); }
        }
        boolean changed = false;
        byte[] fmj = data.get("fabric.mod.json");
        if (fmj != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"accessWidener\"\\s*:\\s*\"([^\"]+)\"").matcher(new String(fmj, StandardCharsets.UTF_8));
            if (m.find() && data.containsKey(m.group(1))) {
                List<String> dropped = new ArrayList<>();
                String out = dropAwLines(new String(data.get(m.group(1)), StandardCharsets.UTF_8), drops, dropped);
                if (out != null) {
                    data.put(m.group(1), out.getBytes(StandardCharsets.UTF_8));
                    changed = true;
                    for (String d : dropped)
                        log.add("  fixed: " + m.group(1) + ": access widener line dropped (Forge coremod needs the field private): " + d);
                }
            }
        }
        for (var e : data.entrySet()) {
            if (!e.getKey().startsWith("META-INF/jars/") || !e.getKey().endsWith(".jar")) continue;
            byte[] n = dropAccessWideners(e.getValue(), drops, log, name + "!" + e.getKey());
            if (n != e.getValue()) { e.setValue(n); changed = true; }
        }
        if (!changed) return jar;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream o = new ZipOutputStream(b)) {
            for (var e : data.entrySet()) {
                ZipEntry ne = new ZipEntry(e.getKey());
                Long t = times.get(e.getKey());
                if (t != null && t >= 0) ne.setTime(t);
                o.putNextEntry(ne);
                o.write(e.getValue());
                o.closeEntry();
            }
        }
        return b.toByteArray();
    }

    // ------------------------------------------------------------------ nested ANTLR (Iris 1.7)
    private static final java.util.regex.Pattern ANTLR_NESTED = java.util.regex.Pattern.compile("/antlr4-runtime-([\\d.]+?)\\.jar$");

    /**
     * launch120.py relocate_nested_antlr: a Fabric mod that nests another ANTLR 4 runtime (Iris 1.7: 4.13.1 for
     * glsl-transformer) puts it on Knot's class path, and Forge 47/49's access transformer parser (ANTLR 4.9) then fails
     * ("Could not deserialize ATN with version 3 (expected 4)"). The mod's copy moves to alloy/shadow/antlr&lt;version&gt;/ and
     * every class of the mod (outer jar and nested jars) is pointed at it. Returns the jar unchanged when there is none.
     */
    static byte[] relocateNestedAntlr(byte[] jar, List<String> log, String name) throws Exception {
        Map<String, byte[]> data = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) if (!e.isDirectory()) data.put(e.getName(), z.readAllBytes());
        }
        List<String> nested = new ArrayList<>();
        String antlr = null, ver = null;
        for (String n : data.keySet()) {
            if (!n.startsWith("META-INF/jars/") || !n.endsWith(".jar")) continue;
            nested.add(n);
            var m = ANTLR_NESTED.matcher(n);
            if (m.find() && !m.group(1).startsWith("4.9")) { antlr = n; ver = m.group(1); }
        }
        if (antlr == null) return jar;
        String to = "alloy/shadow/antlr" + ver.replace('.', '_') + "/org/antlr/";
        Path tmp = Files.createTempDirectory("alloy-antlr");
        try {
            var main = Class.forName("RelocatePackage").getMethod("main", String[].class);
            for (String n : nested) {
                Path src = tmp.resolve("in.jar"), dst = tmp.resolve("out.jar");
                Files.write(src, data.get(n));
                main.invoke(null, (Object) (n.equals(antlr) ? new String[]{src.toString(), dst.toString(), "org/antlr/", to, "--move"}
                        : new String[]{src.toString(), dst.toString(), "org/antlr/", to}));
                data.put(n, Files.readAllBytes(dst));
            }
            Path src = tmp.resolve("outer.jar"), dst = tmp.resolve("outer-out.jar");
            Files.write(src, jar);
            main.invoke(null, (Object) new String[]{src.toString(), dst.toString(), "org/antlr/", to});
            try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(dst)))) {
                for (ZipEntry e; (e = z.getNextEntry()) != null; ) if (!e.isDirectory() && !nested.contains(e.getName())) data.put(e.getName(), z.readAllBytes());
            }
        } finally { deleteTree(tmp); }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream zo = new ZipOutputStream(b)) {
            for (var e : data.entrySet()) {
                String n = e.getKey(), u = n.toUpperCase(Locale.ROOT);
                if (u.startsWith("META-INF/") && (u.endsWith(".SF") || u.endsWith(".RSA") || u.endsWith(".DSA") || u.endsWith(".EC"))) continue;
                zo.putNextEntry(new ZipEntry(n));
                zo.write(e.getValue());
                zo.closeEntry();
            }
        }
        log.add("  nested ANTLR " + ver + " moved to " + to + " (Forge 47 needs ANTLR 4.9 in org.antlr)");
        return b.toByteArray();
    }

    // ------------------------------------------------------------------ Forge Config API Port (Fabric build)
    /** launch120.py FORGE_COPIES: Forge classes that Forge Config API Port's Fabric build carries under Forge's names. */
    static final String[] FORGE_COPIES = {"net/minecraftforge/fml/config/", "net/minecraftforge/common/ForgeConfigSpec"};
    static final String FORGE_COPIES_TO = "alloy/shadow/fcap/";

    static boolean usesForgeCopies(byte[] jar) throws IOException { return uses(jar, List.of(FORGE_COPIES)); }

    /** a class or service file of the jar (not its nested jars) mentions one of the prefixes, in slash or dot form */
    static boolean uses(byte[] jar, List<String> prefixes) throws IOException {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                if (!n.endsWith(".class") && !n.startsWith("META-INF/services/")) continue;
                String b = new String(z.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                for (String f : prefixes) if (b.contains(f) || b.contains(f.replace('/', '.'))) return true;
            }
        }
        return false;
    }

    /**
     * launch120.py relocate_forge_copies: next to real Forge, the port's copies of ModConfig, ConfigTracker, IConfigSpec,
     * ConfigFileTypeHandler and ForgeConfigSpec shadow Forge's own on Knot, and every Forge mod with a config then fails
     * (NoSuchMethodError on ModConfig.&lt;init&gt;). In a Fabric jar (and its nested jars) these names move to
     * alloy/shadow/fcap/: the port keeps its copies, the Fabric mods that use it follow, Forge keeps its classes.
     */
    static byte[] relocateForgeCopies(byte[] jar, List<String> log, String name) throws Exception {
        byte[] out = relocate(jar, List.of(FORGE_COPIES), FORGE_COPIES_TO, null);
        if (out != jar) log.add("  Forge config class names of Forge Config API Port moved to " + FORGE_COPIES_TO + " (Forge keeps its own)");
        return out;
    }

    static final String FORGE_NAMED_TO = "alloy/shadow/forge/";

    /**
     * Classes a Fabric jar defines itself under Forge's names (net/minecraftforge/), other than Forge Config API Port's
     * (relocateForgeCopies). CreativeCore 2.11.26 (Fabric, 1.20.4) ships its own
     * net.minecraftforge.client.event.sound.PlayStreamingSourceEvent; on Knot it replaced Forge's, and Forge's
     * ForgeEventFactoryClient failed verification ("PlayStreamingSourceEvent is not assignable to Event", reg05 full boot).
     * CreativeCore 2.12.42 (1.20.1) ships Dist, OnlyIn and Mod. Those names move to FORGE_NAMED_TO inside that jar: the mod
     * keeps its copies, Forge keeps its classes. Only the jar's own top-level classes count (a nested Forge library such
     * as eventbus gets a stub from Prep instead, and the mod must keep linking to Forge's copy).
     */
    static byte[] relocateForgeNamed(byte[] jar, List<String> log, String name) throws Exception {
        Set<String> names = new TreeSet<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                if (!n.startsWith("net/minecraftforge/") || !n.endsWith(".class")) continue;
                String c = n.substring(0, n.length() - 6);
                int d = c.indexOf('$');
                if (d > 0) c = c.substring(0, d);
                boolean fcap = false;
                for (String f : FORGE_COPIES) if (c.startsWith(f)) fcap = true;
                if (!fcap) names.add(c);
            }
        }
        if (names.isEmpty()) return jar;
        byte[] out = relocate(jar, new ArrayList<>(names), FORGE_NAMED_TO, null);
        if (out != jar) log.add("  own copies of Forge classes " + names + " moved to " + FORGE_NAMED_TO + " (Forge keeps its own)");
        return out;
    }

    /** RelocatePackage over the jar and the nested jars that use the prefixes (plus every nested jar named in alsoNested):
     *  from -> to + from, class files moved too. Returns the jar itself when nothing uses them. */
    static byte[] relocate(byte[] jar, List<String> prefixes, String to, Set<String> alsoNested) throws Exception {
        Map<String, byte[]> data = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) if (!e.isDirectory()) data.put(e.getName(), z.readAllBytes());
        }
        List<String> todo = new ArrayList<>();
        for (var e : data.entrySet())
            if (e.getKey().startsWith("META-INF/jars/") && e.getKey().endsWith(".jar")
                    && ((alsoNested != null && alsoNested.contains(e.getKey())) || uses(e.getValue(), prefixes))) todo.add(e.getKey());
        boolean outer = uses(jar, prefixes);
        if (todo.isEmpty() && !outer) return jar;
        Path tmp = Files.createTempDirectory("alloy-reloc");
        try {
            var main = Class.forName("RelocatePackage").getMethod("main", String[].class);
            java.util.function.Function<byte[], byte[]> reloc = in -> {
                try {
                    byte[] b = in;
                    for (String f : prefixes) {
                        Path src = tmp.resolve("in.jar"), dst = tmp.resolve("out.jar");
                        Files.write(src, b);
                        main.invoke(null, (Object) new String[]{src.toString(), dst.toString(), f, to + f, "--move"});
                        b = Files.readAllBytes(dst);
                    }
                    return b;
                } catch (Exception ex) { throw new RuntimeException(ex); }
            };
            for (String n : todo) data.put(n, reloc.apply(data.get(n)));
            if (outer) {
                Map<String, byte[]> moved = new LinkedHashMap<>();
                try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(reloc.apply(jar)))) {
                    for (ZipEntry e; (e = z.getNextEntry()) != null; )
                        if (!e.isDirectory()) moved.put(e.getName(), todo.contains(e.getName()) ? data.get(e.getName()) : z.readAllBytes());
                }
                data = moved;
            }
        } finally { deleteTree(tmp); }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream zo = new ZipOutputStream(b)) {
            for (var e : data.entrySet()) {
                String n = e.getKey(), u = n.toUpperCase(Locale.ROOT);
                if (u.startsWith("META-INF/") && (u.endsWith(".SF") || u.endsWith(".RSA") || u.endsWith(".DSA") || u.endsWith(".EC"))) continue;
                zo.putNextEntry(new ZipEntry(n));
                zo.write(e.getValue());
                zo.closeEntry();
            }
        }
        return b.toByteArray();
    }

    // ---- loader-specific library twins ---------------------------------------------------------------------------
    /**
     * A library that ships one build per loader under the same mod id and packages: tr7zw's TRansition (Entity Culling,
     * Not Enough Animations, 3D Skin Layers). With Fabric TRansition 1.0.28 (nested in the Fabric mods) and Forge
     * TRansition 1.0.27 (nested in 3D Skin Layers Forge), both defined dev.tr7zw.transition.* on Knot; the Fabric copy won
     * and Forge's TRansitionBootstrap failed with NoSuchMethodError ModLoaderUtil.setModLoadingContext (reg05 dry boots on
     * 1.20.2, 1.21.4, 1.21.8). When the Forge copy is newer or the same, Prep's twin stub already drops the nested Fabric
     * copy. Otherwise the Fabric copy and the Fabric mods that use it get those packages under TWIN_TO, so each loader
     * keeps its own build.
     */
    static final String TWIN_TO = "alloy/twin/fabric/";

    /** Forge-side libraries in forgemods/ and their jar-in-jar (META-INF/jarjar, META-INF/jars): mod id -> {version, package dirs} */
    static Map<String, Object[]> forgeLibs(Path game) {
        Map<String, Object[]> out = new TreeMap<>();
        List<Path> jars;
        try { jars = Util.jars(game.resolve("forgemods")); } catch (IOException e) { return out; }
        for (Path j : jars) {
            try {
                byte[] b = Files.readAllBytes(j);
                addForgeLib(out, b);
                try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(b))) {
                    for (ZipEntry e; (e = z.getNextEntry()) != null; )
                        if ((e.getName().startsWith("META-INF/jarjar/") || e.getName().startsWith("META-INF/jars/")) && e.getName().endsWith(".jar"))
                            addForgeLib(out, z.readAllBytes());   // tr7zw's Forge mods nest TRansition in META-INF/jars
                }
            } catch (IOException ignored) { }
        }
        return out;
    }

    static void addForgeLib(Map<String, Object[]> out, byte[] jar) throws IOException {
        Map<String, String> vers = Prep.modVersions(jar);
        if (vers.isEmpty()) return;
        Set<String> pk = packages(jar);
        for (var v : vers.entrySet())
            out.merge(v.getKey(), new Object[]{v.getValue(), pk}, (a, c) -> Prep.compareVersions((String) a[0], (String) c[0]) >= 0 ? a : c);
    }

    /** package directories (a/b/c/) of the jar's own class files */
    static Set<String> packages(byte[] jar) throws IOException {
        Set<String> out = new TreeSet<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                int i = n.lastIndexOf('/');
                if (n.endsWith(".class") && i > 0 && !n.startsWith("META-INF/")) out.add(n.substring(0, i + 1));
            }
        }
        return out;
    }

    /** the fewest prefixes that cover these package directories (a/b/ covers a/b/c/) */
    static List<String> roots(Set<String> pkgs) {
        List<String> out = new ArrayList<>();
        for (String p : new TreeSet<>(pkgs)) if (out.stream().noneMatch(p::startsWith)) out.add(p);
        return out;
    }

    /** a stable text of the Forge twin candidates, for the fit fingerprint */
    static String forgeLibsKey(Map<String, Object[]> forge) {
        StringBuilder sb = new StringBuilder();
        for (var e : forge.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()[0]).append(';');
        return sb.toString();
    }

    /** nested Fabric copies of Forge-side twin libraries that are newer than the Forge copy -> relocated, see TWIN_TO */
    static byte[] relocateTwins(byte[] jar, Map<String, Object[]> forge, List<String> log, String name) throws Exception {
        if (forge == null || forge.isEmpty()) return jar;
        Set<String> shared = new TreeSet<>(), nestedNames = new TreeSet<>();
        List<String> what = new ArrayList<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (!e.getName().startsWith("META-INF/jars/") || !e.getName().endsWith(".jar")) continue;
                byte[] nb = z.readAllBytes();
                String[] idv = fabricIdVersion(nb);
                if (idv == null || !forge.containsKey(idv[0])) continue;
                Object[] f = forge.get(idv[0]);
                if (Prep.compareVersions((String) f[0], idv[1]) >= 0) continue;   // Prep's twin stub keeps the Forge copy for both
                @SuppressWarnings("unchecked") Set<String> fp = (Set<String>) f[1];
                Set<String> own = packages(nb), both = new TreeSet<>(own);
                both.retainAll(fp);
                if (both.isEmpty()) continue;
                shared.addAll(own);   // the whole Fabric build moves (package-private access stays inside one copy)
                nestedNames.add(e.getName());
                what.add(idv[0] + " " + idv[1] + " (Forge copy " + f[0] + ")");
            }
        }
        if (shared.isEmpty()) return jar;
        List<String> r = roots(shared);
        byte[] out = relocate(jar, r, TWIN_TO, nestedNames);
        if (out != jar) out = mapEntries(out, nestedNames, nb -> twinResources(nb, r));
        if (out != jar) log.add("  loader-specific library " + String.join(", ", what) + ": the Fabric copy's packages " + r
                + " moved to " + TWIN_TO + " (the Forge copy keeps them)");
        return out;
    }

    interface BytesFn { byte[] apply(byte[] b) throws Exception; }

    /** the jar with the named entries replaced by fn(entry) */
    static byte[] mapEntries(byte[] jar, Set<String> names, BytesFn fn) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar)); ZipOutputStream zo = new ZipOutputStream(b)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                byte[] d = z.readAllBytes();
                if (names.contains(e.getName())) d = fn.apply(d);
                zo.putNextEntry(new ZipEntry(e.getName()));
                zo.write(d);
                zo.closeEntry();
            }
        }
        return b.toByteArray();
    }

    /**
     * The relocated Fabric twin's text resources: class names in its JSON files (fabric.mod.json entrypoints, the mixin
     * config's package, the refmap) follow the classes, and its mixin configs and refmaps get a new file name, because the
     * Forge copy ships files of the same name on the same class path (Knot would read whichever comes first).
     */
    static byte[] twinResources(byte[] jar, List<String> prefixes) throws Exception {
        Map<String, byte[]> data = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) if (!e.isDirectory()) data.put(e.getName(), z.readAllBytes());
        }
        Map<String, String> renames = new LinkedHashMap<>();
        for (String n : data.keySet())
            if (n.indexOf('/') < 0 && n.endsWith(".json") && !n.equals("fabric.mod.json") && (n.contains("mixins") || n.contains("refmap")))
                renames.put(n, "alloytwin-" + n);
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (var e : data.entrySet()) {
            String n = e.getKey();
            byte[] d = e.getValue();
            if (n.endsWith(".json")) {
                String t = new String(d, StandardCharsets.UTF_8);
                for (String p : prefixes) {
                    t = t.replace(p, TWIN_TO + p);
                    t = t.replace(p.replace('/', '.'), TWIN_TO.replace('/', '.') + p.replace('/', '.'));
                }
                for (var r : renames.entrySet()) t = t.replace("\"" + r.getKey() + "\"", "\"" + r.getValue() + "\"");
                d = t.getBytes(StandardCharsets.UTF_8);
            }
            out.put(renames.getOrDefault(n, n), d);
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream zo = new ZipOutputStream(b)) {
            for (var e : out.entrySet()) { zo.putNextEntry(new ZipEntry(e.getKey())); zo.write(e.getValue()); zo.closeEntry(); }
        }
        return b.toByteArray();
    }

    /** {id, version} of a jar's fabric.mod.json, or null */
    static String[] fabricIdVersion(byte[] jar) {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (!e.getName().equals("fabric.mod.json")) continue;
                Map<String, Object> fm = Json.obj(Json.parse(new String(z.readAllBytes(), StandardCharsets.UTF_8)));
                if (fm != null && fm.get("id") instanceof String id && fm.get("version") instanceof String v) return new String[]{id, v};
                return null;
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ------------------------------------------------------------------ Sodium compat
    static final String SODIUM_JAR = "alloy-sodium120.jar";
    /** Minecraft 1.21.x: the compat for Sodium 0.6+ (bridge/src/alloy/compat121/sodium), one build per version */
    static final String SODIUM121_JAR = "alloy-sodium121.jar";

    /** The compat jar for this Minecraft version (alloy-sodium120.jar on 1.20.x, alloy-sodium121.jar on 1.21.x). */
    static String sodiumJar(String mc) { return mc.startsWith("1.21") ? SODIUM121_JAR : SODIUM_JAR; }

    /** True when the compat for this Minecraft version was made for this Sodium (version without +mc...). */
    static boolean sodiumFits(String mc, String v) { return alloy.ideas.ModDoctor.sodiumFamily(mc, v); }

    static String sodiumMade(String mc) {
        String m = alloy.ideas.ModDoctor.sodiumMade(mc);
        return m.isEmpty() ? "no Sodium yet" : "Sodium " + m;
    }

    /**
     * Alloy's Forge rendering compat for Sodium (bridge/src/alloy/compat120/sodium for Sodium 0.5 on 1.20.x,
     * bridge/src/alloy/compat121/sodium for Sodium 0.6+ on 1.21.1; prebuilt into the installer as its own small mod): in
     * mods/ only while a Sodium jar it was made for is there (it depends on Sodium).
     */
    static void sodium(Path game) throws IOException {
        String mc = Installer.MC, jar = sodiumJar(mc);
        Path mods = game.resolve("mods"), dst = mods.resolve(jar);
        for (String other : new String[]{SODIUM_JAR, SODIUM121_JAR})   // a compat jar of another Minecraft version
            if (!other.equals(jar) && Files.deleteIfExists(mods.resolve(other))) log("alloy-prep: " + other + " removed (made for another Minecraft version)");
        String ver = null;
        for (Path j : jars(mods)) {
            String n = j.getFileName().toString().toLowerCase(Locale.ROOT);
            if (n.startsWith("alloy-") || !n.contains("sodium")) continue;
            alloy.ideas.ModMeta.JarInfo ji = alloy.ideas.ModMeta.read(j);
            for (var m : ji.mods) if (m.id.equals("sodium") && m.loader == alloy.ideas.ModMeta.Loader.FABRIC) { ver = m.version == null ? "?" : m.version; break; }
            if (ver != null) break;
        }
        if (ver == null) {
            if (Files.deleteIfExists(dst)) log("alloy-prep: Sodium is gone, " + jar + " removed");
            return;
        }
        String v = ver.replaceAll("[+].*", "");
        if (!sodiumFits(mc, v)) {   // the compat's mixins target that Sodium's classes; Mod Doctor says so in plain words
            if (Files.deleteIfExists(dst)) log("alloy-prep: " + jar + " removed");
            log("alloy-prep: Sodium " + v + " found; Alloy's Sodium compat for Minecraft " + mc + " is made for " + sodiumMade(mc) + " and is not used (see the Mod Doctor line)");
            return;
        }
        byte[] b;
        try { b = resource("/bridges/" + mc + "/" + jar); }
        catch (IOException e) { log("alloy-prep: Sodium found, but this installer has no Sodium compat for Minecraft " + mc); return; }
        if (Files.exists(dst) && Arrays.equals(Files.readAllBytes(dst), b)) return;
        Files.write(dst, b);
        log("alloy-prep: Sodium found, Alloy's Sodium compat put in mods/" + jar);
    }
}
