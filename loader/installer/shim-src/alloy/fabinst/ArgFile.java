package alloy.fabinst;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fabric instance mode: reads the Alloy launch file {@code .alloy/client.args} of a game dir and
 * works out what a plain Fabric Loader instance is missing compared with it. Plain Java, no Fabric classes: the Java agent
 * (alloy-agent.jar, before Fabric starts) and the forgemods language adapter (inside Fabric) both use it.
 */
public final class ArgFile {
    /** The file that turns instance mode on for a game dir. Its keys are optional (see FabricInstance and the agent). */
    public static final String MARKER = ".alloy/fabric-instance.properties";
    /** JVM properties a launcher sets for its own natives folder; the launcher's values are kept. */
    static final Set<String> LAUNCHER_OWNED = Set.of("java.library.path", "jna.tmpdir", "org.lwjgl.system.SharedLibraryExtractPath",
            "io.netty.native.workdir");

    private ArgFile() { }

    /** Splits a Java @argfile into tokens: whitespace separates, double quotes group (as written by Installer.writeScripts). */
    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false, any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') { inQ = !inQ; any = true; continue; }
            if (!inQ && Character.isWhitespace(c)) {
                if (any) { out.add(cur.toString()); cur.setLength(0); any = false; }
                continue;
            }
            cur.append(c);
            any = true;
        }
        if (any) out.add(cur.toString());
        return out;
    }

    /** JVM options that take their value as the next token. */
    static final Set<String> WITH_VALUE = Set.of("-cp", "-classpath", "--class-path", "--add-opens", "--add-exports", "--add-reads",
            "--add-modules", "-p", "--module-path");

    /** The JVM part of the tokens (everything before the main class), each option with its value as one or two tokens. */
    static List<String> jvmPart(List<String> tokens) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (!t.startsWith("-")) break;   // the main class
            out.add(t);
            if (WITH_VALUE.contains(t) && i + 1 < tokens.size()) out.add(tokens.get(++i));
        }
        return out;
    }

    /** The -cp / -classpath / --class-path value, split on the path separator; empty when there is none. */
    public static List<String> classpath(List<String> tokens) {
        List<String> jvm = jvmPart(tokens);
        for (int i = 0; i + 1 < jvm.size(); i++) {
            String t = jvm.get(i);
            if (t.equals("-cp") || t.equals("-classpath") || t.equals("--class-path")) {
                List<String> out = new ArrayList<>();
                for (String s : jvm.get(i + 1).split(File.pathSeparator)) if (!s.isBlank()) out.add(s);
                return out;
            }
        }
        return List.of();
    }

    /** The -Dkey=value JVM properties in order, without the launcher owned natives ones. */
    public static Map<String, String> properties(List<String> tokens) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> jvm = jvmPart(tokens);
        for (int i = 0; i < jvm.size(); i++) {
            String t = jvm.get(i);
            if (WITH_VALUE.contains(t)) { i++; continue; }
            if (!t.startsWith("-D")) continue;
            int eq = t.indexOf('=');
            String k = eq < 0 ? t.substring(2) : t.substring(2, eq);
            if (k.isEmpty() || LAUNCHER_OWNED.contains(k)) continue;
            out.put(k, eq < 0 ? "" : t.substring(eq + 1));
        }
        return out;
    }

    /** "--add-opens"/"--add-exports" values ("java.base/java.util.jar=ALL-UNNAMED"), as [kind, value] pairs. */
    public static List<String[]> moduleFlags(List<String> tokens) {
        List<String[]> out = new ArrayList<>();
        List<String> jvm = jvmPart(tokens);
        for (int i = 0; i < jvm.size(); i++) {
            String t = jvm.get(i);
            boolean kind = t.equals("--add-opens") || t.equals("--add-exports");
            if (kind && i + 1 < jvm.size()) { out.add(new String[]{t, jvm.get(++i)}); continue; }
            if (WITH_VALUE.contains(t)) { i++; continue; }
            for (String k : new String[]{"--add-opens", "--add-exports"})
                if (t.startsWith(k + "=")) out.add(new String[]{k, t.substring(k.length() + 1)});
        }
        return out;
    }

    /** File name of a class path entry (both separators). */
    static String name(String entry) {
        String s = entry.replace('\\', '/');
        int i = s.lastIndexOf('/');
        return i < 0 ? s : s.substring(i + 1);
    }

    /**
     * The entries of Alloy's class path that the running JVM does not have, compared by file name (Alloy's class path keeps
     * one copy per file name, Installer.clientClasspath, so the same Fabric and vanilla libraries match). Relative entries
     * are resolved against the game dir. Order is kept.
     */
    public static List<Path> missing(List<String> alloyCp, String runningCp, Path game) {
        Set<String> have = new HashSet<>();
        for (String s : runningCp.split(File.pathSeparator)) if (!s.isBlank()) have.add(name(s));
        List<Path> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String e : alloyCp) {
            String n = name(e);
            if (have.contains(n) || !seen.add(n)) continue;
            Path p = Path.of(e);
            out.add((p.isAbsolute() ? p : game.resolve(p)).toAbsolutePath().normalize());
        }
        return out;
    }

    /** .alloy/fabric-instance.args (written by --fabric-instance: JVM part only), else the dev launch file .alloy/client.args. */
    public static Path argsFile(Path game) {
        Path fi = game.resolve(".alloy").resolve("fabric-instance.args");
        return Files.isRegularFile(fi) ? fi : game.resolve(".alloy").resolve("client.args");
    }

    public static List<String> read(Path game) throws IOException {
        return tokens(new String(Files.readAllBytes(argsFile(game)), StandardCharsets.UTF_8));
    }

    /** Instance mode is on when the marker file exists, unless -Dalloy.fabricInstance=false. */
    public static boolean on(Path game) {
        return !"false".equalsIgnoreCase(System.getProperty("alloy.fabricInstance", "")) && Files.isRegularFile(game.resolve(MARKER));
    }
}
