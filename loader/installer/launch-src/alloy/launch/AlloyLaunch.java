package alloy.launch;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Properties;

/**
 * Main class of the Alloy launcher version (version.json "mainClass"). Compiled for Java 8 on purpose (see
 * build_installer.py): when a launcher starts the instance with an old Java, this prints one plain line instead of
 * an UnsupportedClassVersionError. Everything else is in {@link AlloyLaunchMain}.
 *
 * The Java it needs follows the Minecraft version, as vanilla does: alloy-launch.properties "java" (written by
 * --launcher-profile), else from "minecraft" (17 up to 1.20.4, 21 for 1.20.5 to 1.21.x, 25 for 26.x), else 25.
 */
public final class AlloyLaunch {
    private AlloyLaunch() { }

    /** when nothing says otherwise (a jar without alloy-launch.properties): the 26.x need */
    static final int NEED = 25;

    public static void main(String[] args) throws Throwable {
        String spec = System.getProperty("java.specification.version", "0");
        int need = need(props());
        if (major(spec) < need) {
            String msg = "ALLOY: Alloy needs Java " + need + " or newer. This game runs on Java " + spec + "."
                    + " Fix: In your launcher, set this instance's Java to Java " + need
                    + " (for example Eclipse Temurin " + need + ": https://adoptium.net/temurin/releases/?version=" + need + ").";
            System.out.println(msg);
            System.err.println(msg);
            throw new IllegalStateException(msg);
        }
        try {
            Class.forName("alloy.launch.AlloyLaunchMain").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    static Properties props() {
        Properties p = new Properties();
        try {
            InputStream in = AlloyLaunch.class.getResourceAsStream("alloy-launch.properties");
            if (in != null) try { p.load(in); } finally { in.close(); }
        } catch (Exception ignored) { }
        return p;
    }

    /** the Java major this jar's Minecraft version needs (see the class comment) */
    public static int need(Properties p) {
        int j = major(p.getProperty("java", "").trim());
        if (j > 0) return j;
        return needFor(p.getProperty("minecraft", "").trim());
    }

    /** vanilla's Java major for MC: 17 for 1.17 to 1.20.4, 21 for 1.20.5 to 1.21.x, 25 for 26.x and anything unknown */
    public static int needFor(String mc) {
        if (!mc.startsWith("1.")) return NEED;
        String[] v = mc.split("\\.");
        int minor = v.length > 1 ? num(v[1]) : 0, patch = v.length > 2 ? num(v[2]) : 0;
        return minor >= 21 || minor == 20 && patch >= 5 ? 21 : 17;
    }

    private static int num(String s) {
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        return i == 0 ? 0 : Integer.parseInt(s.substring(0, i));
    }

    static int major(String spec) {
        try {
            String s = spec.startsWith("1.") ? spec.substring(2) : spec;
            int dot = s.indexOf('.');
            return Integer.parseInt(dot < 0 ? s : s.substring(0, dot));
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
