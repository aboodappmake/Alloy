package alloy;

import java.io.*;
import java.util.*;

/**
 * Jar Main-Class, compiled for Java 8: checks the Java version before the other classes are touched. Minecraft 26.x
 * needs Java 25; Minecraft 1.20.1 to 1.20.4 (--mc 1.20.x, or --prep/--doctor/--lock/--server-start on a 1.20.x game dir) need 17,
 * Minecraft 1.20.5 to 1.21.11 need 21.
 *
 * On an older Java it looks for a new enough one and relaunches the jar with it. Places, in order: --java, ALLOY_JAVA or
 * -Dalloy.java, JAVA_HOME, the Minecraft Launcher's own runtimes (java-runtime-epsilon is Java 25, delta 21, gamma 17),
 * Prism Launcher's java folder, the usual JDK folders (Adoptium, Microsoft, Zulu, Corretto, ...), then PATH. The version
 * comes from each Java's "release" file (no process start); the chosen one is run once with -version to prove it works.
 *   java -jar alloy-installer.jar --find-java [--mc VERSION]   lists what it found and which one it would use.
 * Test hooks: -Dalloy.os=windows|linux|mac, -Dalloy.noRegistry=true, -Dalloy.noProbe=true, -Dalloy.printArgs=true,
 * -Dalloy.sysroot=DIR (prefix for /usr/lib/jvm, /opt/homebrew and the other system folders), -Dalloy.javaHomeOut=FILE
 * (the output of /usr/libexec/java_home -V), -Dalloy.console=true|false (tests/test_java_pick.py).
 * OpenJ9 (IBM Semeru) and 32-bit Javas are skipped with the reason: Forge does not run on OpenJ9, and a 32-bit Java
 * cannot give Minecraft enough memory.
 */
public final class Launch {
    static int NEED = 25;
    static String GAME120;   // the exact 1.x version when --mc or the game dir names it (for the messages)
    static String URL = "https://adoptium.net/temurin/releases/?version=25";
    static final String OS = System.getProperty("alloy.os", System.getProperty("os.name", "")).toLowerCase(Locale.ROOT);
    static final boolean WIN = OS.contains("win"), MAC = OS.contains("mac");
    static final String SEP = WIN ? "\\" : "/";

    /** one Java found on this PC */
    static final class Cand {
        final File exe; final String from; int major = -1;
        String why;   // set when it cannot be used whatever its version: "32-bit" or "OpenJ9"
        Cand(File exe, String from) { this.exe = exe; this.from = from; }
        public String toString() { return "Java " + (major > 0 ? String.valueOf(major) : "?") + " (" + from + ") " + exe + (why != null ? ", " + why + ", not usable" : ""); }
    }

    static final List<Cand> seen = new ArrayList<Cand>();
    static boolean launcherRuntime;   // a Minecraft Launcher runtime folder exists (changes the advice)

    public static void main(String[] args) throws Exception {
        args = hostStart(args);
        if (Boolean.getBoolean("alloy.printArgs")) {   // test hook (tests/test_java_pick.py): the arguments after hostStart, and the Java needed
            System.out.println("args: " + Arrays.toString(args) + " need " + need(args));
            return;
        }
        int n = need(args);
        if (n < 25) { NEED = n; URL = "https://adoptium.net/temurin/releases/?version=21"; }
        int have = major(System.getProperty("java.specification.version", "0"));
        if (args.length > 0 && args[0].equals("--find-java")) {
            String ex = null;
            for (int i = 0; i + 1 < args.length; i++) if (args[i].equals("--java")) ex = args[i + 1];
            System.exit(findJava(ex));
            return;
        }
        if (args.length > 0 && args[0].equals("--find-game-java")) {   // --find-game-java --mc VERSION: the Java the game would get
            String mc = null;
            for (int i = 0; i + 1 < args.length; i++) if (args[i].equals("--mc")) mc = args[i + 1];
            String g = gameJava(mc);
            System.out.println(g != null ? "game java: " + g : "game java: none (" + gameJavaMissing(mc) + ")");
            System.exit(g != null ? 0 : 1);
            return;
        }
        if (args.length > 0 && args[0].equals("--processor")) { processor(have, Arrays.copyOfRange(args, 1, args.length)); return; }
        String self = unusable(System.getProperty("os.arch"), System.getProperty("sun.arch.data.model"), System.getProperty("java.vm.name"), null);
        if (have >= NEED && self == null) { call(args); return; }
        boolean gui = !java.awt.GraphicsEnvironment.isHeadless() && (args.length == 0 || args[0].equals("--gui"));
        String explicit = null;
        for (int i = 0; i + 1 < args.length; i++) if (args[i].equals("--java")) explicit = args[i + 1];
        if (self != null) System.err.println("[alloy] this Java (" + System.getProperty("java.home") + ") is " + self + ". " + reason(self) + " Looking for another one.");
        if (System.getProperty("alloy.relaunched") == null) {
            Cand c = explicit != null ? check(new Cand(exe(new File(explicit)), "--java")) : pick();
            if (c == null && explicit != null) System.err.println(explicitMessage(explicit));
            if (c != null) { System.exit(relaunch(c, args, gui)); return; }
        }
        if (have >= NEED) {   // OpenJ9 or 32-bit and nothing better found: try it anyway, the line above says why it may fail
            call(args);
            return;
        }
        String msg = missing();
        if (!gui) {
            System.err.println(msg);
            System.err.println("Or run with  --java <path to a Java " + NEED + " home or java.exe>");
            System.exit(1);
            return;
        }
        try { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()); } catch (Throwable t) { }
        Object[] opts = {"Download Java " + NEED, "Close"};
        int r = javax.swing.JOptionPane.showOptionDialog(null, msg, "Alloy installer", javax.swing.JOptionPane.DEFAULT_OPTION,
                javax.swing.JOptionPane.ERROR_MESSAGE, null, opts, opts[0]);
        if (r == 0) {
            try { java.awt.Desktop.getDesktop().browse(new java.net.URI(URL)); }
            catch (Throwable t) { System.err.println("Open " + URL); }
        }
        System.exit(1);
    }

    /** --find-java: print every Java found, the one Alloy would use, or what is missing. Exit 0 when one is new enough. */
    static int findJava(String explicit) {
        System.out.println("Alloy needs Java " + NEED + " or newer for Minecraft " + game() + ". This Java: "
                + System.getProperty("java.specification.version") + " (" + System.getProperty("java.home") + ")");
        Cand c = explicit != null ? check(new Cand(exe(new File(explicit)), "--java")) : pick();
        for (Cand s : seen) System.out.println("  found " + s);
        if (c != null) { System.out.println("would use: " + c); return 0; }
        System.out.println(explicit != null ? explicitMessage(explicit) : missing());
        return 1;
    }

    /** why a --java value was not used */
    static String explicitMessage(String explicit) {
        Cand last = seen.isEmpty() ? null : seen.get(seen.size() - 1);
        if (last != null && last.from.equals("--java") && last.why != null)
            return "[alloy] --java " + explicit + " is not usable: it is " + last.why + ". " + reason(last.why);
        return "[alloy] --java " + explicit + " is not a Java " + NEED + "+ install (a Java home, its bin/java, or a macOS .jdk folder).";
    }

    static String game() { return NEED >= 25 ? "26.1.2" : GAME120 != null ? GAME120 : "1.20.x"; }

    /** the plain-words message when no new enough Java was found */
    static String missing() {
        StringBuilder b = new StringBuilder("Alloy for Minecraft " + game() + " needs Java " + NEED + " or newer. This Java is "
                + System.getProperty("java.specification.version") + ".\n");
        int best = 0;
        for (Cand s : seen) if (s.from.startsWith("Minecraft Launcher")) best = Math.max(best, s.major);
        if (seen.isEmpty()) b.append("No other Java was found on this PC.\n");
        else {
            b.append("Found, but not usable: ");
            Set<String> whys = new LinkedHashSet<String>();
            for (int i = 0; i < seen.size(); i++) {
                Cand s = seen.get(i);
                b.append(i > 0 ? ", " : "").append(s.major > 0 ? "Java " + s.major : "a Java that did not start").append(" (").append(s.from).append(")");
                if (s.why != null) { b.append(" is ").append(s.why); whys.add(s.why); }
            }
            b.append(".\n");
            for (String w : whys) b.append(reason(w)).append("\n");
        }
        b.append("Missing: Java ").append(NEED).append(".\n");
        if (launcherRuntime) b.append("Your Minecraft Launcher has ").append(best > 0 ? "Java " + best + " only" : "no new enough Java yet").append(". Open the official Minecraft Launcher, ")
                .append("start Minecraft ").append(game()).append(" once, close it, then run this installer again.\n");
        else b.append("If you use the official Minecraft Launcher, start Minecraft ").append(game()).append(" there once, then run this installer again.\n");
        b.append("Or install Java ").append(NEED).append(": ").append(URL);
        return b.toString();
    }

    /**
     * Install-profile processor (docs/launchers.md). Launchers may run this inside their own JVM (Forge installer,
     * ATLauncher), so: never System.exit, throw on failure. On an old Java the work runs in a Java 25 child JVM.
     */
    static void processor(int have, String[] args) throws Exception {
        if (have >= NEED) {
            try {
                Class.forName("alloy.installer.Proc").getMethod("main", String[].class).invoke(null, (Object) args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable c = e.getCause();
                if (c instanceof Exception) throw (Exception) c;
                if (c instanceof Error) throw (Error) c;
                throw e;
            }
            return;
        }
        Cand j = pick();
        if (j == null)
            throw new IllegalStateException("Alloy processor needs Java " + NEED + " (running " + System.getProperty("java.specification.version")
                    + "); install it from " + URL + " or set ALLOY_JAVA");
        File self = new File(Launch.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> cmd = new ArrayList<String>(Arrays.asList(j.exe.getPath(), "-Duser.language=en", "-Duser.region=US",
                "-cp", self.getPath(), "alloy.installer.Proc"));
        cmd.addAll(Arrays.asList(args));
        int rc = new ProcessBuilder(cmd).inheritIO().start().waitFor();
        if (rc != 0) throw new IllegalStateException("Alloy processor failed (exit " + rc + "): " + Arrays.toString(args));
    }

    // vanilla server options a host or panel may add after the jar (Minecraft's own server main takes these)
    static final List<String> VANILLA_FLAGS = Arrays.asList("nogui", "--nogui", "-nogui", "--forceUpgrade", "--eraseCache", "--safeMode",
            "--demo", "--bonusChest", "--initSettings", "--recreateRegionFiles", "--jfrProfile");
    static final List<String> VANILLA_VALUES = Arrays.asList("--port", "--world", "--universe", "--serverId", "--pidFile");

    /**
     * Hosts and panels start a custom server jar as plain {@code java -jar server.jar} (or {@code custom.jar}), maybe with
     * {@code nogui}: no Alloy arguments at all. In an Alloy server folder that means --server-start, so the stock egg or
     * "custom jar" option works without an Alloy start line. The folder is the working dir, or the folder of the started
     * jar (the small server.jar at the folder root), or, with no screen, the folder above .alloy/ when the panel started
     * .alloy/alloy-installer.jar itself. A double click on Windows (javaw.exe, no console) still opens the installer window.
     */
    static String[] hostStart(String[] args) {
        if (!vanillaOnly(args)) return args;
        boolean headless = java.awt.GraphicsEnvironment.isHeadless();
        if (!headless && args.length == 0 && (jvmIsJavaw() || !WIN && !hasConsole())) return args;   // a double click: the installer window
        if (serverDir(headless) == null) return args;
        String[] out = new String[args.length + 1];
        out[0] = "--server-start";
        System.arraycopy(args, 0, out, 1, args.length);
        return out;
    }

    static boolean vanillaOnly(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (VANILLA_FLAGS.contains(args[i])) continue;
            if (VANILLA_VALUES.contains(args[i]) && i + 1 < args.length) { i++; continue; }
            return false;
        }
        return true;
    }

    /** the Alloy server folder this start belongs to (has .alloy/server.args), or null */
    static File serverDir(boolean headless) {
        File cwd = new File(System.getProperty("user.dir", "."));
        if (isServer(cwd)) return cwd;
        String cp = System.getProperty("java.class.path", "");   // with -jar: exactly the started jar
        if (cp.endsWith(".jar") && cp.indexOf(File.pathSeparatorChar) < 0) {
            File jar = new File(cp).getAbsoluteFile(), dir = jar.getParentFile();
            if (dir != null && isServer(dir)) return dir;
            if (headless && dir != null && dir.getName().equals(".alloy") && dir.getParentFile() != null && isServer(dir.getParentFile()))
                return dir.getParentFile();
        }
        return null;
    }

    static boolean isServer(File dir) {
        return new File(new File(dir, ".alloy"), "server.args").isFile();
    }

    /**
     * This JVM has a terminal. A double click on macOS (Jar Launcher) or in a Linux file manager has none; a start from a
     * terminal or over SSH has one. Java 22+ always returns a Console, so its isTerminal() decides there.
     */
    static boolean hasConsole() {
        String o = System.getProperty("alloy.console");
        if (o != null) return Boolean.parseBoolean(o);
        Object c = System.console();
        if (c == null) return false;
        try { return Boolean.TRUE.equals(Class.forName("java.io.Console").getMethod("isTerminal").invoke(c)); }
        catch (NoSuchMethodException e) { return true; }   // Java 8 to 21: a Console means a terminal
        catch (Throwable t) { return true; }
    }

    /** this JVM is javaw.exe (a double click on Windows). ProcessHandle is Java 9+, so it is looked up by reflection. */
    static boolean jvmIsJavaw() {
        if (!WIN) return false;
        try {
            Class<?> ph = Class.forName("java.lang.ProcessHandle");
            Object info = ph.getMethod("info").invoke(ph.getMethod("current").invoke(null));
            Object cmd = Class.forName("java.lang.ProcessHandle$Info").getMethod("command").invoke(info);   // the public interface, not the JDK's hidden class
            Object s = Class.forName("java.util.Optional").getMethod("orElse", Object.class).invoke(cmd, "");
            return String.valueOf(s).toLowerCase(Locale.ROOT).endsWith("javaw.exe");
        } catch (Throwable t) { return false; }
    }

    /** Java a Minecraft version needs: 17 for 1.20 to 1.20.4, 21 for 1.20.5 to 1.21.x, 25 for 26.x */
    static int needFor(String mc) {
        if (!mc.startsWith("1.")) return 25;
        String[] p = mc.split("[.]");
        int minor = p.length > 1 ? num(p[1]) : 0, patch = p.length > 2 ? num(p[2]) : 0;
        return minor < 20 || minor == 20 && patch <= 4 ? 17 : 21;
    }

    static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9].*", "")); } catch (NumberFormatException e) { return 0; }
    }

    /** the Java the run needs: by --mc, the game dir's version or the pack's, else 25 (Minecraft 26.x) */
    static int need(String[] args) {
        if (args.length > 0 && args[0].equals("--server-start")) {
            File d = serverDir(true);
            if (is120(d != null ? d : new File("."))) return needFor(GAME120);
        }
        for (int i = 0; i + 1 < args.length; i++) {
            String a = args[i], v = args[i + 1];
            if (a.equals("--mc")) { if (!v.startsWith("1.")) return 25; GAME120 = v; return needFor(v); }
            if (a.equals("--modpack") && packIs120(new File(v))) return 17;
            if ((a.equals("--prep") || a.equals("--doctor") || a.equals("--lock") || a.equals("--restore-disabled") || a.equals("--disable-mod")
                    || a.equals("--recover-check")) && is120(new File(v))) return needFor(GAME120);
        }
        return 25;
    }

    /** the game dir's .alloy/prep.properties says Minecraft 1.x (1.20.1 to 1.21.11) */
    static boolean is120(File dir) {
        File f = new File(new File(dir, ".alloy"), "prep.properties");
        if (!f.isFile()) return false;
        try {
            Properties p = new Properties();
            InputStream in = new FileInputStream(f);
            try { p.load(in); } finally { in.close(); }
            String mc = p.getProperty("minecraft", "");
            if (!mc.startsWith("1.")) return false;
            GAME120 = mc;
            return true;
        } catch (IOException e) { return false; }
    }

    /** a Modrinth (.mrpack) or CurseForge zip whose index names Minecraft 1.20.x */
    static boolean packIs120(File f) {
        try {
            java.util.zip.ZipFile z = new java.util.zip.ZipFile(f);
            try {
                for (String n : new String[]{"modrinth.index.json", "manifest.json"}) {
                    java.util.zip.ZipEntry e = z.getEntry(n);
                    if (e == null) continue;
                    String s = new String(read(z.getInputStream(e)), "UTF-8").replace(" ", "");
                    return s.contains("\"minecraft\":\"1.20") || s.contains("\"version\":\"1.20");
                }
            } finally { z.close(); }
        } catch (Exception e) { /* not a pack */ }
        return false;
    }

    static byte[] read(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int r; (r = in.read(buf)) > 0; ) b.write(buf, 0, r);
            return b.toByteArray();
        } finally { in.close(); }
    }

    static void call(String[] args) throws Exception {
        try {
            Class.forName("alloy.installer.Main").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            if (c instanceof Error) throw (Error) c;
            throw e;
        }
    }

    /** run this jar again on the chosen Java, with this JVM's own options (-Xmx and so on) */
    static int relaunch(Cand c, String[] args, boolean gui) throws Exception {
        File self = new File(Launch.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        File jexe = c.exe;
        File w = new File(jexe.getParentFile(), "javaw.exe");
        if (WIN && gui && w.isFile()) jexe = w;   // no console window behind the installer
        List<String> cmd = new ArrayList<String>();
        cmd.add(jexe.getPath());
        cmd.add("-Dalloy.relaunched=1");
        if (java.awt.GraphicsEnvironment.isHeadless()) cmd.add("-Djava.awt.headless=true");
        try {
            for (String a : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments())
                if (!a.startsWith("-Dalloy.relaunched") && !a.startsWith("-Djava.awt.headless") && !a.startsWith("-agentlib:jdwp")) cmd.add(a);
        } catch (Throwable t) { /* no java.management: options are not passed on */ }
        String ul = System.getProperty("user.language");
        if (ul != null && !cmd.contains("-Duser.language=" + ul)) cmd.add("-Duser.language=" + ul);
        cmd.add("-jar");
        cmd.add(self.getPath());
        cmd.addAll(Arrays.asList(args));
        System.err.println("[alloy] Java " + System.getProperty("java.specification.version") + " is too old for the Alloy installer (it needs Java " + NEED
                + "; the game's own Java is picked later), using " + c);
        return new ProcessBuilder(cmd).inheritIO().start().waitFor();
    }

    /** the java of a Java home, of a macOS .jdk bundle (HOME/Contents/Home/bin/java, as copied from Finder), or f itself */
    static File exe(File f) {
        if (f.isFile()) return f;
        String n = WIN ? "java.exe" : "java";
        File e = new File(new File(f, "bin"), n);
        if (e.isFile()) return e;
        File b = new File(new File(new File(new File(f, "Contents"), "Home"), "bin"), n);
        return b.isFile() ? b : null;
    }

    // ------------------------------------------------------------------ finding a Java

    /** the first Java that is new enough and runs, in search order; every candidate looked at lands in {@link #seen} */
    static Cand pick() {
        Set<String> done = new HashSet<String>();
        for (Cand c : candidates()) {
            if (c.exe == null) continue;
            String key;
            try { key = c.exe.getCanonicalPath().toLowerCase(Locale.ROOT); } catch (IOException e) { key = c.exe.getPath(); }
            if (!done.add(key)) continue;
            if (check(c) != null) return c;
        }
        return null;
    }

    /** the Java majors the game of Minecraft MC runs on, best first: 21 then 17 for 1.20.1 to 1.20.4, 21 for 1.20.5 to 1.21.x, 25 for 26.x */
    static int[] gameMajors(String mc) {
        if (mc == null || !mc.startsWith("1.")) return new int[]{25};
        return needFor(mc) == 17 ? new int[]{21, 17} : new int[]{21};
    }

    /** this JVM is a runtime bundled into an app (jpackage, as in Alloy-Installer-VERSION.exe), not a Java the player installed */
    public static boolean bundledRuntime() {
        return System.getProperty("jpackage.app-path") != null || Boolean.getBoolean("alloy.bundledRuntime");
    }

    /**
     * The java executable for the game of Minecraft MC: the first Java of a major from {@link #gameMajors} (in that order)
     * in the usual places (the same search as for the installer itself, the Minecraft Launcher's runtimes included), never
     * this JVM's own folder when it is a bundled runtime. null when none is found. Called by the installer by reflection.
     */
    public static String gameJava(String mc) {
        String skip = null;
        if (bundledRuntime())
            try { skip = new File(System.getProperty("java.home", "")).getCanonicalPath().toLowerCase(Locale.ROOT); } catch (IOException e) { skip = null; }
        List<Cand> all = candidates();
        int saved = NEED;
        try {
            for (int want : gameMajors(mc)) {
                NEED = want;
                Set<String> done = new HashSet<String>();
                for (Cand c : all) {
                    if (c.exe == null) continue;
                    String key;
                    try { key = c.exe.getCanonicalPath().toLowerCase(Locale.ROOT); } catch (IOException e) { key = c.exe.getPath(); }
                    if (!done.add(key) || skip != null && key.startsWith(skip + SEP)) continue;
                    File home = c.exe.getParentFile() == null ? null : c.exe.getParentFile().getParentFile();
                    Map<String, String> rel = home != null ? release(home) : new HashMap<String, String>();
                    if (rel.containsKey("JAVA_VERSION") && major(rel.get("JAVA_VERSION")) != want) continue;   // no process start
                    Cand x = new Cand(c.exe, c.from);
                    if (check(x) != null && x.major == want) return x.exe.getPath();
                }
            }
        } finally { NEED = saved; }
        return null;
    }

    /** one short line: which Java to install for Minecraft MC when {@link #gameJava} found none */
    public static String gameJavaMissing(String mc) {
        int j = gameMajors(mc)[0];
        return "no Java " + j + " found for Minecraft " + (mc == null ? "26.x" : mc) + ": install Java " + j
                + " from https://adoptium.net/temurin/releases/?version=" + j + " (or start Minecraft " + (mc == null ? "26.x" : mc)
                + " once in the official Minecraft Launcher), or set ALLOY_JAVA";
    }

    /** c when it is Java NEED or newer and starts; also records it in {@link #seen} */
    static Cand check(Cand c) {
        if (c.exe == null || !c.exe.isFile()) return null;
        File home = c.exe.getParentFile().getParentFile();
        Map<String, String> rel = home != null ? release(home) : new HashMap<String, String>();
        c.major = rel.containsKey("JAVA_VERSION") ? major(rel.get("JAVA_VERSION")) : 0;
        c.why = unusable(rel.get("OS_ARCH"), null, rel.get("JVM_VARIANT"), rel.get("IMPLEMENTOR"));
        boolean probe = !Boolean.getBoolean("alloy.noProbe");
        if (c.major <= 0 || c.major >= NEED && c.why == null && probe) {
            // no release file: ask the Java itself (also proves it runs, and gives os.arch and java.vm.name)
            Map<String, String> p = properties(c.exe);
            String sv = p == null ? null : p.get("java.specification.version");
            if (sv == null) c.major = 0;   // does not run here
            else {
                if (c.major <= 0) c.major = major(sv);
                c.why = unusable(p.get("os.arch"), p.get("sun.arch.data.model"), p.get("java.vm.name"), null);
            }
        }
        seen.add(c);
        return c.major >= NEED && c.why == null ? c : null;
    }

    /** every place a Java may be, best guesses first */
    static List<Cand> candidates() {
        List<Cand> out = new ArrayList<Cand>();
        String ex = System.getProperty("alloy.java", env("ALLOY_JAVA"));
        if (ex != null) out.add(new Cand(exe(new File(ex)), "ALLOY_JAVA"));
        if (env("JAVA_HOME") != null) out.add(new Cand(exe(new File(env("JAVA_HOME"))), "JAVA_HOME"));
        String home = System.getProperty("user.home", "");
        List<String> rt = new ArrayList<String>();   // Minecraft Launcher runtime folders
        List<String[]> roots = new ArrayList<String[]>();   // {folder whose children are Java homes, label}
        if (WIN) {
            String la = env("LOCALAPPDATA"), ad = env("APPDATA");
            if (la != null) rt.add(la + "\\Packages\\Microsoft.4297127D64EC6_8wekyb3d8bbwe\\LocalCache\\Local\\runtime");
            String reg = registryLauncher();
            if (reg != null) rt.add(reg + "\\runtime");
            for (String pf : programFiles()) rt.add(pf + "\\Minecraft Launcher\\runtime");
            if (ad != null) rt.add(ad + "\\.minecraft\\runtime");
            String pd = env("PRISMLAUNCHER_DATA_DIR");
            if (pd != null) roots.add(new String[]{pd + "\\java", "Prism Launcher"});
            if (ad != null) roots.add(new String[]{ad + "\\PrismLauncher\\java", "Prism Launcher"});
            for (String pf : programFiles())
                for (String v : new String[]{"Eclipse Adoptium", "Java", "Microsoft", "Zulu", "Amazon Corretto", "BellSoft", "Semeru"})
                    roots.add(new String[]{pf + "\\" + v, v});
        } else {
            if (MAC) rt.add(home + "/Library/Application Support/minecraft/runtime");
            rt.add(home + "/.minecraft/runtime");
            rt.add(home + "/.var/app/com.mojang.Minecraft/.minecraft/runtime");
            String pd = env("PRISMLAUNCHER_DATA_DIR"), xdg = env("XDG_DATA_HOME");
            if (pd != null) roots.add(new String[]{pd + "/java", "Prism Launcher"});
            roots.add(new String[]{(MAC ? home + "/Library/Application Support" : xdg != null ? xdg : home + "/.local/share") + "/PrismLauncher/java", "Prism Launcher"});
            roots.add(new String[]{home + "/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java", "Prism Launcher"});
            if (MAC) for (String h : javaHomes()) out.add(new Cand(exe(new File(h)), "java_home"));   // /usr/libexec/java_home -V
            for (String r : new String[]{"/Library/Java/JavaVirtualMachines", "/usr/lib/jvm", "/usr/lib64/jvm", "/usr/java", "/opt"})
                roots.add(new String[]{sys(r), r});
            roots.add(new String[]{home + "/Library/Java/JavaVirtualMachines", "~/Library/Java/JavaVirtualMachines"});
            String sdk = env("SDKMAN_DIR");
            if (sdk != null) roots.add(new String[]{sdk + "/candidates/java", "SDKMAN"});
            roots.add(new String[]{home + "/.sdkman/candidates/java", "SDKMAN"});
            if (MAC) out.addAll(homebrew());
        }
        roots.add(new String[]{home + SEP + ".jdks", "~/.jdks"});
        for (String r : rt) out.addAll(launcherRuntimes(new File(r)));
        for (String[] r : roots) {
            File[] kids = sortedDirs(new File(r[0]));
            for (File k : kids) {
                File h = new File(k, "Contents/Home").isDirectory() ? new File(k, "Contents/Home") : k;
                out.add(new Cand(exe(h), r[1] + " " + k.getName()));
            }
        }
        String path = env("PATH");
        if (path != null)
            for (String p : path.split(WIN ? ";" : ":")) {
                if (p.length() == 0) continue;
                File j = new File(p.replace("\"", ""), WIN ? "java.exe" : "java");
                // macOS /usr/bin/java is a stub: with no JDK installed, starting it opens the "install Java" dialog
                if (MAC && j.getPath().equals("/usr/bin/java") && javaHomes().isEmpty()) continue;
                out.add(new Cand(fileIf(j), "PATH"));
            }
        return out;
    }

    /** a system folder (/usr/lib/jvm, /opt/homebrew) under -Dalloy.sysroot (tests), else as it is */
    static String sys(String p) {
        String r = System.getProperty("alloy.sysroot");
        return r == null ? p : r + p;
    }

    static List<String> javaHomesCache;

    /**
     * macOS: the Java homes /usr/libexec/java_home -V lists (one line per JDK, the path after the last quote):
     *     25.0.1 (arm64) "Eclipse Adoptium" - "OpenJDK 25.0.1" /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
     */
    static List<String> javaHomes() {
        if (javaHomesCache != null) return javaHomesCache;
        List<String> out = new ArrayList<String>();
        String text = null, hook = System.getProperty("alloy.javaHomeOut");
        if (hook != null) {
            try { text = new String(read(new FileInputStream(hook)), "UTF-8"); } catch (IOException e) { text = null; }
        } else if (MAC && new File(sys("/usr/libexec/java_home")).isFile())
            text = run(new String[]{sys("/usr/libexec/java_home"), "-V"}, 5);
        if (text != null)
            for (String l : text.split("\r?\n")) {
                int q = l.lastIndexOf("\" ");
                if (q > 0 && l.trim().matches("\\d.*")) out.add(l.substring(q + 2).trim());
            }
        return javaHomesCache = out;
    }

    /**
     * Homebrew's openjdk formulas are keg-only (not linked into PATH or /Library/Java unless the user runs the symlink
     * command brew prints): PREFIX/opt/openjdk[@N]/libexec/openjdk.jdk/Contents/Home, PREFIX /opt/homebrew (Apple
     * Silicon), /usr/local (Intel) or $HOMEBREW_PREFIX.
     */
    static List<Cand> homebrew() {
        List<Cand> out = new ArrayList<Cand>();
        List<String> prefixes = new ArrayList<String>();
        if (env("HOMEBREW_PREFIX") != null) prefixes.add(env("HOMEBREW_PREFIX"));
        for (String p : new String[]{sys("/opt/homebrew"), sys("/usr/local")}) if (!prefixes.contains(p)) prefixes.add(p);
        for (String pre : prefixes)
            for (File k : sortedDirs(new File(pre, "opt"))) {
                if (!k.getName().startsWith("openjdk")) continue;
                out.add(new Cand(exe(new File(k, "libexec/openjdk.jdk/Contents/Home")), "Homebrew " + k.getName()));
            }
        return out;
    }

    static File fileIf(File f) { return f.isFile() ? f : null; }

    /**
     * Mojang layout: runtime/COMPONENT/PLATFORM/COMPONENT/bin/java (macOS: .../COMPONENT/jre.bundle/Contents/Home/bin/java).
     * Unknown java-runtime-* names first (a future Java), then epsilon (25), delta (21), gamma (17), beta, alpha. jre-legacy
     * (Java 8) is never new enough. This CPU's platform folder first.
     */
    static List<Cand> launcherRuntimes(File root) {
        List<Cand> out = new ArrayList<Cand>();
        if (!root.isDirectory()) return out;
        launcherRuntime = true;
        final List<String> known = Arrays.asList("java-runtime-epsilon", "java-runtime-delta", "java-runtime-gamma",
                "java-runtime-gamma-snapshot", "java-runtime-beta", "java-runtime-alpha");
        List<File> comps = new ArrayList<File>();
        for (File c : sortedDirs(root)) if (c.getName().startsWith("java-runtime-")) comps.add(c);
        Collections.sort(comps, new Comparator<File>() {
            public int compare(File a, File b) { return rank(a) - rank(b); }
            int rank(File f) { int i = known.indexOf(f.getName()); return i < 0 ? -1 : i; }
        });
        String arch = (env("PROCESSOR_ARCHITEW6432") != null ? env("PROCESSOR_ARCHITEW6432") : env("PROCESSOR_ARCHITECTURE") != null
                ? env("PROCESSOR_ARCHITECTURE") : System.getProperty("os.arch", "")).toLowerCase(Locale.ROOT);
        final String mine = arch.contains("arm") || arch.contains("aarch") ? "arm64" : arch.contains("86") && !arch.contains("64") ? "x86" : "x64";
        for (File c : comps) {
            List<File> plats = new ArrayList<File>(Arrays.asList(sortedDirs(c)));
            Collections.sort(plats, new Comparator<File>() {
                public int compare(File a, File b) { return score(b) - score(a); }
                int score(File f) {
                    String n = f.getName();
                    return n.endsWith(mine) || mine.equals("x64") && (n.equals("linux") || n.equals("mac-os")) ? 1 : 0;
                }
            });
            for (File p : plats) {
                File h = new File(p, c.getName());
                if (new File(h, "jre.bundle/Contents/Home").isDirectory()) h = new File(h, "jre.bundle/Contents/Home");
                out.add(new Cand(exe(h), "Minecraft Launcher " + c.getName()));
            }
        }
        return out;
    }

    /** child folders, newest-looking name first */
    static File[] sortedDirs(File d) {
        File[] k = d.listFiles(new FileFilter() { public boolean accept(File f) { return f.isDirectory(); } });
        if (k == null) return new File[0];
        Arrays.sort(k, Collections.reverseOrder());
        return k;
    }

    static List<String> programFiles() {
        List<String> l = new ArrayList<String>();
        for (String k : new String[]{"ProgramW6432", "ProgramFiles", "ProgramFiles(x86)"}) {
            String v = env(k);
            if (v != null && !l.contains(v)) l.add(v);
        }
        return l;
    }

    static String env(String k) {
        String v = System.getenv(k);
        return v == null || v.length() == 0 ? null : v;
    }

    /** the old Minecraft Launcher's install folder from the registry (HKCU\SOFTWARE\Mojang\InstalledProducts) */
    static String registryLauncher() {
        if (Boolean.getBoolean("alloy.noRegistry")) return null;
        String out = run(new String[]{"reg", "query", "HKCU\\SOFTWARE\\Mojang\\InstalledProducts\\Minecraft Launcher", "/v", "InstallLocation"}, 3);
        if (out == null) return null;
        for (String l : out.split("\r?\n")) {
            int i = l.indexOf("REG_SZ");
            if (l.contains("InstallLocation") && i > 0) {
                String p = l.substring(i + 6).trim();
                while (p.endsWith("\\")) p = p.substring(0, p.length() - 1);
                return p.toLowerCase(Locale.ROOT).endsWith("minecraft launcher") ? p : p + "\\Minecraft Launcher";
            }
        }
        return null;
    }

    /** HOME/release as KEY to value (every JDK and every Mojang runtime has one: JAVA_VERSION, OS_ARCH, IMPLEMENTOR, JVM_VARIANT) */
    static Map<String, String> release(File home) {
        Map<String, String> out = new HashMap<String, String>();
        File f = new File(home, "release");
        if (!f.isFile()) return out;
        try {
            for (String l : new String(read(new FileInputStream(f)), "UTF-8").split("\r?\n")) {
                int i = l.indexOf('=');
                if (i > 0) out.put(l.substring(0, i).trim(), l.substring(i + 1).replace("\"", "").trim());
            }
        } catch (IOException e) { /* unreadable */ }
        return out;
    }

    /** the Java's own system properties (starts it once), null when it does not run within 10 s */
    static Map<String, String> properties(File jexe) {
        String out = run(new String[]{jexe.getPath(), "-XshowSettings:properties", "-version"}, 10);
        if (out == null) return null;
        Map<String, String> p = new HashMap<String, String>();
        for (String l : out.split("\r?\n")) {
            int i = l.indexOf(" = ");
            if (i > 0) p.put(l.substring(0, i).trim(), l.substring(i + 3).trim());
        }
        return p;
    }

    /**
     * "32-bit" or "OpenJ9" when a Java cannot run Alloy well, else null. From the release file (OS_ARCH, JVM_VARIANT,
     * IMPLEMENTOR) or from the Java's properties (os.arch, sun.arch.data.model, java.vm.name).
     */
    static String unusable(String arch, String dataModel, String vm, String implementor) {
        String a = arch == null ? "" : arch.toLowerCase(Locale.ROOT).trim();
        if ("32".equals(dataModel) || a.matches("x86|i[3-6]86|x86_32|arm|arm32|armv[5-7].*|aarch32")) return "32-bit";
        String v = ((vm == null ? "" : vm) + " " + (implementor == null ? "" : implementor)).toLowerCase(Locale.ROOT);
        if (v.contains("openj9") || v.matches("(.*\\W)?j9(\\W.*)?") || v.contains("international business machines")) return "OpenJ9";
        return null;
    }

    /** the plain-words reason for an {@link #unusable} result */
    static String reason(String why) {
        if ("32-bit".equals(why)) return "A 32-bit Java cannot give Minecraft enough memory. Install the 64-bit Java " + NEED + ".";
        return "This Java uses the OpenJ9 engine (IBM Semeru), which Forge does not support. Install a HotSpot Java such as Eclipse Temurin " + NEED + ".";
    }

    /** a command's output (stdout + stderr), null when it fails or takes longer than the timeout */
    static String run(String[] cmd, int seconds) {
        File tmp = null;
        try {
            tmp = File.createTempFile("alloy-java", ".txt");
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(tmp).start();
            p.getOutputStream().close();
            if (!p.waitFor(seconds, java.util.concurrent.TimeUnit.SECONDS)) { p.destroy(); return null; }
            return p.exitValue() == 0 ? new String(read(new FileInputStream(tmp)), "UTF-8") : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    static int major(String s) {
        try {
            if (s.startsWith("1.")) s = s.substring(2);
            int d = 0;
            while (d < s.length() && Character.isDigit(s.charAt(d))) d++;
            return Integer.parseInt(s.substring(0, d));
        } catch (Exception e) { return 0; }
    }
}
