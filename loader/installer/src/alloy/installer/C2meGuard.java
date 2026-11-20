package alloy.installer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * C2ME (Fabric) next to real Forge: its async chunk IO saves unloading chunks on a worker thread through its own path, so
 * Forge's ChunkDataEvent.Save never fires and Forge chunk capabilities are written off the server thread. Mods that keep
 * per-chunk data lose it, silently. Its GC-free serializer replaces ChunkSerializer (skips
 * Forge's chunk caps), and recoverFromErrors regenerates a chunk whose data fails to parse (deletes builds).
 *
 * So whenever a C2ME jar is in mods/, config/c2me.toml gets ioSystem.async, ioSystem.gcFreeChunkSerializer and
 * ioSystem.recoverFromErrors set to false, on every launch (the user or the pack may have turned them on). Other keys and
 * comments are kept. C2ME's threaded world generation stays as configured.
 */
final class C2meGuard {
    private C2meGuard() { }

    static final String FILE = "config/c2me.toml";
    static final List<String> KEYS = List.of("async", "gcFreeChunkSerializer", "recoverFromErrors");

    /** true when mods/ has a C2ME jar (by file name: C2ME ships as c2me-fabric-*.jar, Alloy's fitted copy keeps the name) */
    static boolean present(Path game) {
        Path mods = game.resolve("mods");
        if (!Files.isDirectory(mods)) return false;
        try (var s = Files.list(mods)) {
            return s.map(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)).anyMatch(n -> n.endsWith(".jar") && n.startsWith("c2me"));
        } catch (IOException e) {
            return false;
        }
    }

    /** sets the keys when C2ME is installed; returns true when the file was changed */
    static boolean apply(Path game) {
        if (!present(game)) return false;
        Path f = game.resolve(FILE);
        try {
            String old = Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
            String now = fix(old);
            if (now.equals(old)) return false;
            Files.createDirectories(f.getParent());
            Files.writeString(f, now, StandardCharsets.UTF_8);
            Util.log("alloy-c2me: " + FILE + ": ioSystem.async, gcFreeChunkSerializer and recoverFromErrors set to false. C2ME's async chunk"
                    + " saving skips Forge's chunk save event (per-chunk mod data would be lost); its threaded world generation stays on");
            return true;
        } catch (IOException e) {
            Util.log("alloy-c2me: could not write " + f + " (" + e.getMessage() + "); C2ME's async chunk saving may skip Forge's chunk save event");
            return false;
        }
    }

    /** the toml text with the three ioSystem keys false (a new file when text is null) */
    static String fix(String text) {
        String nl = text != null && text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(text == null || text.isEmpty() ? List.of() : Arrays.asList(text.split("\\r?\\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        Set<String> done = new HashSet<>();
        String table = "";
        int ioEnd = -1, dotEnd = -1;   // index after the last line of the [ioSystem] table / of the top level ioSystem.x keys
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).strip();
            if (t.startsWith("[")) {
                table = t.replaceAll("^\\[+\\s*|\\s*\\]+.*$", "");
                if (table.equals("ioSystem")) ioEnd = i + 1;
                continue;
            }
            String key = keyOf(t);
            if (key == null) continue;
            String k = null;
            if (table.equals("ioSystem") && KEYS.contains(key)) k = key;
            else if (table.isEmpty() && key.startsWith("ioSystem.")) { dotEnd = i + 1; if (KEYS.contains(key.substring(9))) k = key.substring(9); }
            if (table.equals("ioSystem")) ioEnd = i + 1;
            if (k == null) continue;
            String indent = lines.get(i).substring(0, lines.get(i).indexOf(t.charAt(0)));
            lines.set(i, indent + (table.isEmpty() ? "ioSystem." : "") + k + " = false");
            done.add(k);
        }
        List<String> add = new ArrayList<>();
        for (String k : KEYS) if (!done.contains(k)) add.add(k + " = false");
        if (!add.isEmpty()) {
            if (ioEnd >= 0) lines.addAll(ioEnd, add);
            else if (dotEnd >= 0) lines.addAll(dotEnd, add.stream().map(x -> "ioSystem." + x).toList());   // TOML: no [ioSystem] after dotted keys
            else {
                if (!lines.isEmpty()) lines.add("");
                lines.add("# set by Alloy: Forge's chunk save event and chunk capabilities need C2ME's async chunk IO off");
                lines.add("[ioSystem]");
                lines.addAll(add);
            }
        }
        return String.join(nl, lines) + nl;
    }

    /** "async = true # x" -> "async", a quoted key without its quotes, null for comments and blank lines */
    static String keyOf(String t) {
        if (t.isEmpty() || t.startsWith("#")) return null;
        int eq = t.indexOf('=');
        if (eq <= 0) return null;
        String k = t.substring(0, eq).strip();
        if (k.length() >= 2 && (k.startsWith("\"") && k.endsWith("\"") || k.startsWith("'") && k.endsWith("'"))) k = k.substring(1, k.length() - 1);
        return k;
    }
}
