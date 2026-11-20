package alloy.ideas;

import java.util.*;

/**
 * Lenient version-range matching for the two formats mods use:
 * Fabric/semver predicates (">=0.18 <1", "~26.1-", "^1.2", "26.1.x", "*", "a || b") and
 * Maven ranges used by Forge ("[26.1,26.2)", "[64,)", "26.1.2", "[1,2),[3,4)").
 * Returns null when the range cannot be understood (callers treat that as "unknown", never as failure).
 */
public final class Versions {
    private Versions() {}

    public static Boolean matches(String range, String version) {
        if (range == null || version == null) return null;
        String r = range.trim();
        if (r.isEmpty() || r.equals("*")) return true;
        try {
            if (r.startsWith("[") || r.startsWith("(")) return maven(r, version);
            boolean any = false;
            for (String alt : r.split("\\|\\|")) {
                Boolean ok = fabricAnd(alt.trim(), version);
                if (ok == null) return null;
                any |= ok;
            }
            return any;
        } catch (RuntimeException e) { return null; }
    }

    /**
     * Would Forge (Maven's VersionRange.createFromVersionSpec) refuse this range? Returns Maven's error text, or null
     * when the range is fine. Forge stops before loading any mod on such a range, plain Forge included.
     */
    public static String mavenRangeError(String spec) {
        if (spec == null) return null;
        String process = spec.trim();
        if (process.isEmpty() || process.equals("*")) return null;
        if (!(process.startsWith("[") || process.startsWith("("))) return null;   // a plain "recommended" version
        int sets = 0;
        while (process.startsWith("[") || process.startsWith("(")) {
            int i1 = process.indexOf(')'), i2 = process.indexOf(']'), index = i2;
            if (i2 < 0 || (i1 >= 0 && i1 < i2)) index = i1;
            if (index < 0) return "Unbounded range: " + spec;
            String r = process.substring(0, index + 1);
            String err = restrictionError(r, spec);
            if (err != null) return err;
            sets++;
            process = process.substring(index + 1).trim();
            if (process.startsWith(",")) process = process.substring(1).trim();
        }
        if (!process.isEmpty() && sets > 0) return "Only fully-qualified sets allowed in multiple set scenario: " + spec;
        return null;
    }

    private static String restrictionError(String r, String spec) {
        boolean loInc = r.startsWith("["), hiInc = r.endsWith("]");
        String body = r.substring(1, r.length() - 1).trim();
        int comma = body.indexOf(',');
        if (comma < 0) return loInc && hiInc ? null : "Single version must be surrounded by []: " + spec;
        String lo = body.substring(0, comma).trim(), hi = body.substring(comma + 1).trim();
        if (!lo.isEmpty() && !hi.isEmpty()) {
            if (lo.equals(hi)) return "Range cannot have identical boundaries: " + spec;
            // only plain numbers: a lenient compare must never turn a valid range into a blocker
            if (lo.matches("\\d+(\\.\\d+)*") && hi.matches("\\d+(\\.\\d+)*")) try { if (cmp(hi, lo) < 0) return "Range defies version ordering: " + spec; } catch (RuntimeException ignored) { }
        }
        return null;
    }

    private static Boolean maven(String r, String v) {
        // split "[a,b),[c,d)" into ranges
        List<String> parts = new ArrayList<>();
        int depth = 0, st = 0;
        for (int i = 0; i < r.length(); i++) {
            char c = r.charAt(i);
            if (c == '[' || c == '(') { if (depth++ == 0) st = i; }
            else if (c == ']' || c == ')') { if (--depth == 0) parts.add(r.substring(st, i + 1)); }
        }
        if (depth != 0 || parts.isEmpty()) return null;   // nested or unbalanced brackets: unknown
        for (String p : parts) {
            boolean incLo = p.charAt(0) == '[', incHi = p.charAt(p.length() - 1) == ']';
            String body = p.substring(1, p.length() - 1);
            int comma = body.indexOf(',');
            if (comma < 0) { if (cmp(v, body.trim()) == 0) return true; continue; }
            String lo = body.substring(0, comma).trim(), hi = body.substring(comma + 1).trim();
            // Maven reads odd bounds leniently ("[1.20, 1.20.1,)" in Distant Horizons, "[[40,41),..." in WaterMedia) and
            // Forge then loads the mod: an odd bound is "unknown" here, never a blocker
            if (!lo.matches("[\\w.+\\-]*") || !hi.matches("[\\w.+\\-]*") || body.indexOf(',', comma + 1) >= 0) return null;
            boolean ok = true;
            if (!lo.isEmpty()) { int c = cmp(v, lo); ok = incLo ? c >= 0 : c > 0; }
            if (ok && !hi.isEmpty()) { int c = cmp(v, hi); ok = incHi ? c <= 0 : c < 0; }
            if (ok) return true;
        }
        return false;
    }

    private static Boolean fabricAnd(String expr, String v) {
        if (expr.isEmpty() || expr.equals("*")) return true;
        for (String t : expr.split("\\s+")) {
            String op = "";
            int k = 0;
            while (k < t.length() && "<>=~^".indexOf(t.charAt(k)) >= 0) k++;
            op = t.substring(0, k);
            String ver = t.substring(k);
            if (ver.endsWith("-")) ver = ver.substring(0, ver.length() - 1);   // "26.1-" = any pre-release of 26.1
            boolean ok;
            if (ver.contains("x") || ver.contains("X") || ver.contains("*")) {
                String prefix = ver.replaceAll("[.]?[xX*].*$", "");
                ok = prefix.isEmpty() || prefixMatch(v, prefix);
            } else switch (op) {
                case "", "=" -> ok = cmp(v, ver) == 0;
                case ">=" -> ok = cmp(v, ver) >= 0;
                case ">" -> ok = cmp(v, ver) > 0;
                case "<=" -> ok = cmp(v, ver) <= 0;
                case "<" -> ok = cmp(v, ver) < 0;
                case "~" -> ok = cmp(v, ver) >= 0 && cmp(v, bump(ver, Math.min(1, dots(ver)))) < 0;
                case "^" -> ok = cmp(v, ver) >= 0 && cmp(v, bump(ver, 0)) < 0;
                default -> { return null; }
            }
            if (!ok) return false;
        }
        return true;
    }

    private static int dots(String v) { return (int) v.chars().filter(c -> c == '.').count(); }
    private static boolean prefixMatch(String v, String prefix) {
        int[] a = nums(v), p = nums(prefix);
        for (int i = 0; i < p.length; i++) if (i >= a.length || a[i] != p[i]) return false;
        return true;
    }
    /** bump component idx: bump("26.1", 1) = "26.2"; bump("1.2.3", 0) = "2". */
    private static String bump(String v, int idx) {
        int[] n = nums(v);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i <= idx; i++) { if (i > 0) b.append('.'); b.append(i < idx ? n[i] : (i < n.length ? n[i] : 0) + 1); }
        return b.toString();
    }

    static int[] nums(String v) {
        String core = v.split("[+]")[0];
        String[] parts = core.split("[.\\-]");
        List<Integer> out = new ArrayList<>();
        for (String p : parts) {
            String d = p.replaceAll("^(\\d+).*$", "$1");
            if (!d.matches("\\d+")) break;
            out.add(Integer.parseInt(d.length() > 9 ? d.substring(0, 9) : d));
            if (!p.matches("\\d+")) break;    // "rc1" etc. ends the numeric core
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    public static int cmp(String a, String b) {
        // Forge versions like "1.20.1-1.2.5": the part before the first '-' decides first, as in Maven's ordering
        // ("1.20.1-1.2.5" is above "1.20-1.2": Chef's Delight on Farmer's Delight in MMC4). Equal heads fall back to
        // the whole numeric core, as before.
        int da = a.indexOf('-'), db = b.indexOf('-');
        if (da > 0 && db > 0) {
            int[] ha = nums(a.substring(0, da)), hb = nums(b.substring(0, db));
            if (ha.length > 0 && hb.length > 0) {
                for (int i = 0; i < Math.max(ha.length, hb.length); i++) {
                    int p = i < ha.length ? ha[i] : 0, q = i < hb.length ? hb[i] : 0;
                    if (p != q) return Integer.compare(p, q);
                }
            }
        }
        int[] x = nums(a), y = nums(b);
        if (x.length == 0 || y.length == 0) throw new IllegalArgumentException("not a version");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    /** self-check, run with: java -cp out alloy.ideas.Versions */
    public static void main(String[] a) {
        String[][] t = {
            {"~26.1-", "26.1.2", "true"}, {"~26.1-", "26.2", "false"}, {">=0.18.4", "0.20.1", "true"},
            {"[26.1, 26.2)", "26.1.2", "true"}, {"[64,)", "64.1.6", "true"}, {"[65,)", "64.1.6", "false"},
            {"1.21.x", "26.1.2", "false"}, {"26.1.x", "26.1.2", "true"}, {">=1.21 <1.22", "26.1.2", "false"},
            {"*", "26.1.2", "true"}, {"[1.20,1.21),[26,27)", "26.1.2", "true"}, {"26.1.2", "26.1.2", "true"},
            {"^0.20.0", "0.20.1", "true"}, {">=1.21.4 || 26.1.2", "26.1.2", "true"}, {"1.21.4", "26.1.2", "false"},
            {"[1.20-1.2,)", "1.20.1-1.2.5", "true"}, {"[1.20.1-1.3,)", "1.20.1-1.2.5", "false"}, {"[1.20, 1.20.1,)", "1.20.1", "null"},
            {"[[40,41),[43,44),[47,48),[54,)", "47", "null"}, {"[47,48)", "47", "true"},
        };
        int bad = 0;
        for (String[] c : t) {
            Boolean r = matches(c[0], c[1]);
            boolean ok = String.valueOf(r).equals(c[2]);
            if (!ok) bad++;
            System.out.println((ok ? "ok   " : "FAIL ") + c[0] + " vs " + c[1] + " -> " + r);
        }
        System.out.println(bad == 0 ? "ALL OK" : bad + " FAILED");
        if (bad > 0) System.exit(1);
    }
}
