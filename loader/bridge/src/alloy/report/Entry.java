package alloy.report;

/** One finding in the Alloy report. Plain data; built by {@link Classifier}, shown by {@link AlloyReport} and {@link ReportScreen}. */
public final class Entry {
    public enum Severity { WARN, ERROR }

    public final Severity severity;
    /** "Forge", "Fabric", "Minecraft", "Alloy" or "Unknown". */
    public final String side;
    /** Display name of the mod the finding is about ("" if none known), e.g. "Biomes O Plenty (biomesoplenty 26.1.2.0.22)". */
    public final String mod;
    /** Jar file name of that mod ("" if unknown). */
    public final String jar;
    public final String title;
    /** Plain-language explanation. */
    public final String detail;
    /** What the player can do about it ("" if nothing useful to say). */
    public final String fix;
    /** Technical lines (root cause, top stack frames) for the report file only. */
    public final String technical;

    public Entry(Severity severity, String side, String mod, String jar, String title, String detail, String fix, String technical) {
        this.severity = severity;
        this.side = side == null ? "Unknown" : side;
        this.mod = mod == null ? "" : mod;
        this.jar = jar == null ? "" : jar;
        this.title = title == null ? "" : title;
        this.detail = detail == null ? "" : detail;
        this.fix = fix == null ? "" : fix;
        this.technical = technical == null ? "" : technical;
    }

    public Entry with(Severity s) { return new Entry(s, side, mod, jar, title, detail, fix, technical); }

    /** One plain line for the top of the log, the crash report and alloy-report.txt: what broke, which mod, what to do. */
    public String headline() {
        String t = title.endsWith(".") ? title : title + ".";
        String m = mod.isEmpty() || title.contains(mod) || (!jar.isEmpty() && title.contains(jar)) ? "" : " (" + (jar.isEmpty() ? mod : jar) + ")";
        if (!m.isEmpty()) t = t.substring(0, t.length() - 1) + m + ".";
        return ("ALLOY: " + t + (fix.isEmpty() ? "" : " Fix: " + fix)).replaceAll("\\s+", " ").trim();
    }

    /** Key for de-duplication (the same failure often surfaces through several hooks). */
    String key() { return severity + "|" + side + "|" + jar + "|" + title; }
}
