package alloy.ideas;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The list of mods Alloy treats specially (Connector and friends, Forge ports of Fabric renderers, risky libraries,
 * KubeJS). One data file, {@code installer/res/mod_risks.tsv}, bundled at the root of the installer jar.
 */
public final class ModRisks {
    private ModRisks() { }

    /** PREFER = a Forge port that is the better pick than its Fabric original on this Minecraft version */
    public enum Action { BLOCK, PORT, PREFER, RISK, NOTE }

    /** mc: empty = every version, "1.20" = 1.20 and 1.20.x, "!1.20" = every other version. fix: own fix text (optional). */
    public record Risk(Set<String> ids, Action action, String loader, Set<String> originals, String message, String mc, String fix) {
        public Risk(Set<String> ids, Action action, String loader, Set<String> originals, String message) { this(ids, action, loader, originals, message, "", ""); }

        public boolean appliesTo(ModMeta.Loader l) {
            return loader.equals("any") || loader.equals("forge") && l == ModMeta.Loader.FORGE || loader.equals("fabric") && l == ModMeta.Loader.FABRIC;
        }

        public boolean appliesToMc(String v) {
            if (mc.isEmpty() || v == null) return true;
            boolean not = mc.startsWith("!");
            String p = not ? mc.substring(1) : mc;
            return (v.equals(p) || v.startsWith(p + ".")) != not;
        }

        public boolean appliesTo(ModMeta.Loader l, String v) { return appliesTo(l) && appliesToMc(v); }
    }

    private static List<Risk> cached;

    public static synchronized List<Risk> load() {
        if (cached != null) return cached;
        String text = null;
        try (var in = ModRisks.class.getResourceAsStream("/mod_risks.tsv")) {
            if (in != null) text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ignored) { }
        if (text == null) for (Path p : List.of(Path.of("mod_risks.tsv"), Path.of("installer", "res", "mod_risks.tsv"))) {
            try { if (Files.isRegularFile(p)) { text = Files.readString(p, StandardCharsets.UTF_8); break; } } catch (Exception ignored) { }
        }
        cached = text == null ? List.of() : parse(text);
        return cached;
    }

    public static List<Risk> parse(String text) {
        List<Risk> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] c = line.split("\t", 7);
            if (c.length < 5) continue;
            out.add(new Risk(set(c[0]), Action.valueOf(c[1].trim()), c[2].trim(), set(c[3]), c[4].trim(),
                    c.length > 5 ? c[5].trim() : "", c.length > 6 ? c[6].trim() : ""));
        }
        return out;
    }

    private static Set<String> set(String s) {
        Set<String> r = new LinkedHashSet<>();
        for (String x : s.split(",")) if (!x.isBlank()) r.add(x.trim());
        return r;
    }
}
