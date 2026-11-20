package alloy.installer;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Small helpers shared by the installer: logging (console and GUI), hashes, checked downloads, child processes, jar lists. */
final class Util {
    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    static final String OS_NAME = osKey(System.getProperty("os.name", ""));
    static final String ARCH = archKey(System.getProperty("os.arch", ""));
    static final List<String> JVM_PROPS = List.of("-Duser.language=en", "-Duser.region=US");
    static volatile java.util.function.Consumer<String> sink = null;   // GUI log

    /** the version.json rule name of an os.name value: windows, osx or linux */
    static String osKey(String osName) {
        String n = osName.toLowerCase(Locale.ROOT);
        return n.contains("win") ? "windows" : n.contains("mac") || n.contains("darwin") || n.equals("osx") ? "osx" : "linux";
    }

    /** an os.arch value as arm64, x86 (32-bit) or x64 */
    static String archKey(String osArch) {
        String a = osArch.toLowerCase(Locale.ROOT);
        if (a.contains("aarch64") || a.contains("arm64")) return "arm64";
        if (a.startsWith("arm")) return "arm32";
        return a.contains("64") ? "x64" : a.matches("x86|i[3-6]86") ? "x86" : "x64";
    }

    /** lazy: building an HttpClient costs ~0.2 s, and every launch runs --prep, which never touches the network. */
    private static final class Net {
        static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
    }

    static void log(String s) {
        System.out.println("[alloy] " + s);
        System.out.flush();
        var k = sink;
        if (k != null) k.accept(s);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static String digest(Path p, String algo) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(p), 1 << 16)) {
            MessageDigest md = MessageDigest.getInstance(algo);
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    static String sha1(String text) { return sha1(text.getBytes(StandardCharsets.UTF_8)); }

    static String sha1(byte[] b) {
        try { return hex(MessageDigest.getInstance("SHA-1").digest(b)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    static byte[] get(String url, int timeoutSec) throws IOException, InterruptedException {
        HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSec))
                .header("User-Agent", "alloy-installer").build();
        HttpResponse<byte[]> r = Net.HTTP.send(rq, HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
        return r.body();
    }

    /**
     * A Mojang file (or one whose SHA-1 comes from a pinned source such as the Forge installer's own profile): checked
     * against that SHA-1 when cached and when downloaded. There is no "no hash" case: a file Alloy cannot check is not used.
     * Everything from a maven goes through {@link #fetchPinned} instead.
     */
    static Path fetch(List<String> urls, Path dest, String sha1, Long size, String label) throws IOException {
        if (sha1 == null || sha1.isBlank())
            throw new IOException("refusing to download " + (label != null ? label : dest.getFileName()) + ": no checksum is known for it");
        return checked(urls, dest, "SHA-1", sha1.toLowerCase(Locale.ROOT), size, label);
    }

    /**
     * A non-Mojang download (Forge installer, Fabric Loader, Mixin, ASM, Fabric API, Intermediary, MCPConfig): its SHA-256
     * must be pinned in /pins.txt (written at build time from the official mavens). A cached copy is hashed on every call;
     * a wrong one is downloaded again once, and a download that still does not match is refused.
     */
    static Path fetchPinned(List<String> repos, String rel, Path dest, String label) throws IOException {
        String want = Pins.get(rel);
        if (want == null)
            throw new IOException("refusing to download " + rel + ": this Alloy installer has no SHA-256 pin for it, so it cannot check the file. "
                    + "Use the Minecraft and loader versions this installer offers, or a newer Alloy installer");
        return checked(mavenUrls(repos, rel), dest, "SHA-256", want, null, label);
    }

    private static Path checked(List<String> urls, Path dest, String algo, String want, Long size, String label) throws IOException {
        String name = label != null ? label : dest.getFileName().toString();
        if (Files.exists(dest)) {
            String have = digest(dest, algo);
            if (have.equals(want) && (size == null || Files.size(dest) == size)) return dest;
            log("cached " + name + " (" + dest + ") has the wrong " + algo + " (" + have + ", expected " + want + "): downloading it again");
            Files.delete(dest);
        }
        Files.createDirectories(dest.getParent());
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        Exception last = null;
        String mismatch = null;
        for (String url : urls) {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    log("download " + name);
                    HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10))
                            .header("User-Agent", "alloy-installer").build();
                    HttpResponse<Path> r = Net.HTTP.send(rq, HttpResponse.BodyHandlers.ofFile(part));
                    if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
                    String got = digest(part, algo);
                    if (!got.equals(want) || (size != null && Files.size(part) != size)) {
                        mismatch = url + " sent a file with " + algo + " " + got + " (" + Files.size(part) + " bytes)";
                        Files.deleteIfExists(part);
                        log("WARNING: " + name + " from " + url + " does not match the expected " + algo + ": not used");
                        break;   // the same server sends the same bytes again: try the next mirror, no retry
                    }
                    Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
                    return dest;
                } catch (Exception e) {
                    last = e;
                    try { Files.deleteIfExists(part); } catch (IOException ignored) { }
                    try { Thread.sleep(1000L * (1 + attempt)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        if (mismatch != null)
            throw new IOException("refused " + name + ": " + mismatch + ", but Alloy expects " + want + ". The file was not saved or run. "
                    + "Someone may have changed it on the server or on the way; try again later, and report it to the Alloy team if it stays");
        throw new IOException("download failed: " + urls.get(0) + ": " + last);
    }

    /** SHA-256 pins by maven path (resource /pins.txt, written by pins.py from the official mavens). */
    static final class Pins {
        private static Map<String, String> map;

        static synchronized Map<String, String> all() {
            if (map != null) return map;
            Map<String, String> m = new HashMap<>();
            try (InputStream in = Util.class.getResourceAsStream("/pins.txt")) {
                if (in != null) for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                    l = l.strip();
                    if (l.isEmpty() || l.startsWith("#")) continue;
                    String[] p = l.split("\\s+", 2);
                    if (p.length == 2 && p[0].matches("[0-9a-fA-F]{64}")) m.put(p[1].strip(), p[0].toLowerCase(Locale.ROOT));
                }
            } catch (IOException e) { /* no pins: every pinned download is refused */ }
            return map = m;
        }

        static String get(String rel) { return all().get(rel); }

        /** maven paths the installer downloads for the selected Minecraft version (pins.py required() lists the same) */
        static List<String> required() {
            List<String> out = new ArrayList<>();
            out.add(Installer.forgeInstallerRel());
            out.addAll(Installer.fabricLibRels());
            String api = Modpack.shippedFabricApi();
            if (api != null) out.add(Modpack.fabricApiRel(api));
            if (V120.on()) {
                out.add(V120.intermediaryRel());
                if (!V120.NATIVE) out.add(V120.mcpRel());   // Forge 50+ has no MCPConfig
            }
            return out;
        }

        /** --check-pins: every bundled version's downloads are pinned (the build runs this on the new jar). Exit code. */
        static int check() throws IOException {
            int missing = 0;
            for (String v : Installer.versions()) {
                Installer.select(v);
                for (String rel : required())
                    if (get(rel) == null) { System.out.println("no SHA-256 pin: " + v + ": " + rel); missing++; }
            }
            System.out.println(missing == 0 ? "pins: every download of " + String.join(", ", Installer.versions()) + " is pinned"
                    : missing + " download(s) without a pin: run python -I loader/installer/pins.py fetch --tmp DIR");
            return missing == 0 ? 0 : 1;
        }
    }

    static List<String> mavenUrls(List<String> repos, String rel) {
        List<String> out = new ArrayList<>();
        for (String r : repos) out.add(r + "/" + rel.replace("+", "%2B"));
        return out;
    }

    /** Runs a command, output to logFile (or inherited when null). */
    static int run(List<String> cmd, Path logFile, Path cwd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (cwd != null) pb.directory(cwd.toFile());
        if (logFile != null) pb.redirectErrorStream(true).redirectOutput(logFile.toFile());
        else pb.inheritIO();
        return pb.start().waitFor();
    }

    /** Runs a command and returns {exit code, combined output}. */
    static Object[] runCapture(List<String> cmd, Path cwd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (cwd != null) pb.directory(cwd.toFile());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Object[]{p.waitFor(), out};
    }

    static Path selfJar() {
        try { return Path.of(Util.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    static byte[] resource(String name) throws IOException {
        try (InputStream in = Util.class.getResourceAsStream(name)) {
            if (in == null) throw new IOException("missing installer resource " + name);
            return in.readAllBytes();
        }
    }

    /** sha1 of the embedded PrepGame/PrepFml classes: part of the stamps, so a new installer re-preps. */
    static String toolsId() {
        try { return sha1(resource("/PrepGame.class")) + sha1(resource("/PrepFml.class")); }
        catch (IOException e) { throw new RuntimeException(e); }
    }

    static int javaMajorOf(Path exe) {
        try {
            Object[] r = runCapture(List.of(exe.toString(), "-version"), null);
            return javaMajor((String) r[1]);
        } catch (Exception e) { return 0; }
    }

    /** the major version in `java -version` output: "25.0.1" is 25, "1.8.0_431" is 8 (the old 1.x form), 0 when not found */
    static int javaMajor(String versionOutput) {
        var m = java.util.regex.Pattern.compile("version \"(\\d+)(?:\\.(\\d+))?").matcher(versionOutput == null ? "" : versionOutput);
        if (!m.find()) return 0;
        int major = Integer.parseInt(m.group(1));
        return major == 1 && m.group(2) != null ? Integer.parseInt(m.group(2)) : major;
    }

    /**
     * The java executable for a --java value: the file itself, HOME/bin/java, or, for a macOS bundle copied from Finder
     * (/Library/Java/JavaVirtualMachines/temurin-25.jdk), HOME/Contents/Home/bin/java. When none exists: HOME/bin/java
     * (the error then names that path).
     */
    static Path javaExe(Path p, boolean windows) {
        if (Files.isRegularFile(p)) return p;
        String exe = windows ? "java.exe" : "java";
        Path plain = p.resolve("bin").resolve(exe), bundle = p.resolve("Contents").resolve("Home").resolve("bin").resolve(exe);
        return !Files.isRegularFile(plain) && Files.isRegularFile(bundle) ? bundle : plain;
    }

    /** this JVM is a bundled runtime (the jpackage image of Alloy-Installer-VERSION.exe), not a Java the player installed */
    static boolean bundledRuntime() {
        return System.getProperty("jpackage.app-path") != null || Boolean.getBoolean("alloy.bundledRuntime");
    }

    /** the Java the game gets ({@code game}, null = none found) and the one the install's own child JVMs run on ({@code child}) */
    record JavaChoice(Path child, Path game, String note) { }

    /**
     * Picks the game's Java for Minecraft MC when no --java was given. OWN is this JVM (major OWN_MAJOR). A Java the player
     * installed is kept when it fits (17 or 21 for 1.20.1 to 1.20.4, 21 for 1.20.5 to 1.21.x, 25 for 26.x). Otherwise, and always
     * for a bundled runtime, FINDER searches this PC (21 first for 1.20.x, as tested). A bundled runtime is never the game's
     * Java: when nothing is found it only runs the install itself, and the note says which Java to install.
     */
    static JavaChoice chooseJava(String mc, Path own, int ownMajor, boolean bundled, java.util.function.Function<String, Path> finder,
                                 java.util.function.Function<String, String> missing) {
        int need = LauncherProfile.javaMajor(mc);
        boolean fits = ownMajor == need || need == 17 && ownMajor == 21;
        if (!bundled && fits) return new JavaChoice(own, own, null);
        Path found = finder.apply(mc);
        if (found != null)
            return new JavaChoice(found, found, "game Java: " + found + " (Java for Minecraft " + mc + (bundled ? "; the installer's own runtime is only for the installer" : "") + ")");
        if (bundled) return new JavaChoice(own, null, "note: " + missing.apply(mc) + ". Prism Launcher and the Minecraft Launcher pick their own Java");
        return new JavaChoice(own, own, null);
    }

    /** the installer stub's Java search (alloy.Launch, compiled for Java 8 on its own, so called by name): the game's Java or null */
    static Path findGameJava(String mc) {
        try {
            Object r = Class.forName("alloy.Launch").getMethod("gameJava", String.class).invoke(null, mc);
            return r == null ? null : Path.of((String) r);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) { return null; }
    }

    static String gameJavaMissing(String mc) {
        try { return (String) Class.forName("alloy.Launch").getMethod("gameJavaMissing", String.class).invoke(null, mc); }
        catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            int j = LauncherProfile.javaMajor(mc) == 17 ? 21 : LauncherProfile.javaMajor(mc);   // 1.20.1 to 1.20.4: 21, as tested
            return "no Java " + j + " found for Minecraft " + mc + ": install Java " + j + " from https://adoptium.net/temurin/releases/?version=" + j + ", or set ALLOY_JAVA";
        }
    }

    static Path ownJava() {
        return Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java");
    }

    static void mkdirs(Path p) { try { Files.createDirectories(p); } catch (IOException e) { throw new UncheckedIOException(e); } }

    static List<Path> jars(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".jar") && Files.isRegularFile(p))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        }
    }

    static List<Path> rglobJars(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".jar") && Files.isRegularFile(p))
                    .sorted(Comparator.comparing(p -> dir.relativize(p).toString().replace('\\', '/'))).toList();
        }
    }

    /** path relative to base with forward slashes when inside it, else absolute with forward slashes. */
    static String rel(Path base, Path p) {
        p = p.toAbsolutePath().normalize();
        base = base.toAbsolutePath().normalize();
        if (p.startsWith(base)) return base.relativize(p).toString().replace('\\', '/');
        // reached through a symlink on one side only (macOS /tmp is /private/tmp, Silverblue /home is /var/home): compare real paths
        Path rp = real(p), rb = real(base);
        if (rp.startsWith(rb)) return rb.relativize(rp).toString().replace('\\', '/');
        return p.toString().replace('\\', '/');
    }

    /** p is base or inside it, also when one of them is reached through a symlink (see {@link #rel}) */
    static boolean inside(Path base, Path p) {
        p = p.toAbsolutePath().normalize();
        base = base.toAbsolutePath().normalize();
        return p.startsWith(base) || real(p).startsWith(real(base));
    }

    /**
     * p with symlinks resolved as far as it exists: toRealPath of the deepest existing folder plus the rest unchanged.
     * Falls back to p when nothing can be resolved (no access, odd file system).
     */
    static Path real(Path p) {
        p = p.toAbsolutePath().normalize();
        Path head = p;
        while (head != null && !Files.exists(head)) head = head.getParent();
        if (head == null) return p;
        try { return head.toRealPath().resolve(head.relativize(p)).normalize(); }
        catch (IOException | SecurityException e) { return p; }
    }

    /** client heap in MB: 4096 on PCs with at least 12 GB of RAM (big packs need ~2.1 GB live heap), else 3072 */
    static int clientHeapMb() {
        try {
            long total = ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
            return total >= 12L * 1024 * 1024 * 1024 ? 4096 : 3072;
        } catch (Throwable t) {
            return 3072;
        }
    }
}
