package alloy.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Config files of a mod by name, in a config folder: config/&lt;id&gt;/, config/&lt;id&gt;.*, -*, _* (plain Java, no Minecraft). */
public final class ConfigFiles {
    private ConfigFiles() {}

    /** true when a file or folder name in config/ belongs to the mod id (ids shorter than 3 never match: too vague) */
    public static boolean named(String file, String id) {
        file = file.toLowerCase(Locale.ROOT);
        id = id.toLowerCase(Locale.ROOT);
        if (id.length() < 3) return false;
        if (file.equals(id)) return true;   // a folder config/<id>/
        if (!file.startsWith(id) || file.length() == id.length()) return false;
        char c = file.charAt(id.length());
        return c == '.' || c == '-' || c == '_';
    }

    /** the matching entries of dir, sorted; empty when dir is missing */
    public static List<Path> find(Path dir, String id) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> named(p.getFileName().toString(), id)).sorted().forEach(out::add);
        } catch (Exception ignored) { }
        return out;
    }
}
