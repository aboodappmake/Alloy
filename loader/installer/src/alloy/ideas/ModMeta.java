package alloy.ideas;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * Reads mod metadata from a jar without loading any of its classes (prototype, no dependencies).
 * Understands fabric.mod.json, quilt.mod.json (detect only), META-INF/mods.toml (Forge),
 * META-INF/neoforge.mods.toml (detect only), jar-in-jar (one or two levels), and MANIFEST
 * Implementation-Version for ${file.jarVersion}.
 */
public final class ModMeta {
    public enum Loader { FABRIC, FORGE, NEOFORGE, QUILT }

    public record Dep(String id, String range, boolean mandatory, boolean incompatible, String side) {}

    public static final class Mod {
        public final String id, version, name; public final Loader loader;
        public final List<Dep> deps = new ArrayList<>();
        public final List<String> provides = new ArrayList<>();
        public String env = "*";          // "*", "client", "server", or "either" (Forge displayTest IGNORE_*: the other side may lack it)
        public boolean nested;            // came from jar-in-jar
        /** Fabric: declares a "modmenu" entrypoint (its config screen is reachable through Mod Menu) */
        public boolean modmenuEntrypoint;
        Mod(String id, String version, String name, Loader l) { this.id = id; this.version = version; this.name = name; this.loader = l; }
        @Override public String toString() { return id + "@" + version + "(" + loader + ")"; }
    }

    public static final class JarInfo {
        public final Path file; public final String name;
        public final EnumSet<Loader> loaders = EnumSet.noneOf(Loader.class);
        public final List<Mod> mods = new ArrayList<>();   // top-level mods declared by this jar
        public final List<Mod> nested = new ArrayList<>(); // mods inside jar-in-jar
        public boolean hasPackMcmeta, hasClasses, hasModLauncherService, hasAccessWidenerOnly;
        /** OptiFine's jar (its ModLauncher service, or its own classes under optifine/ or net/optifine/ with no mod metadata) */
        public boolean optifine;
        public String error;
        /** mods.toml top level (Forge): modLoader and loaderVersion, null when absent */
        public String modLoader, loaderVersion;
        /** mods.toml problems that make Forge refuse the whole file (it then stops scanning every mod) */
        public final List<String> tomlProblems = new ArrayList<>();
        JarInfo(Path file, String name) { this.file = file; this.name = name; }
        public List<Mod> allMods() { var l = new ArrayList<>(mods); l.addAll(nested); return l; }
        public boolean isMulti() { return loaders.contains(Loader.FABRIC) && (loaders.contains(Loader.FORGE) || loaders.contains(Loader.NEOFORGE)); }
    }

    public static JarInfo read(Path jar) {
        JarInfo ji = new JarInfo(jar, jar.getFileName().toString());
        try (InputStream in = new BufferedInputStream(Files.newInputStream(jar))) {
            readZip(in, ji, 0);
        } catch (Exception e) { ji.error = e.toString(); }
        return ji;
    }

    private static void readZip(InputStream raw, JarInfo ji, int depth) throws IOException {
        ZipInputStream zin = new ZipInputStream(raw);
        String fabric = null, forge = null, manifest = null;
        boolean neo = false, quilt = false;
        List<byte[]> nestedJars = new ArrayList<>();
        ZipEntry e;
        boolean ofMain = false, ofClasses = false;   // OptiFine: optifine/Installer + its service class; net/optifine/ code
        while ((e = zin.getNextEntry()) != null) {
            String n = e.getName();
            if (depth == 0 && (n.equals("optifine/OptiFineTransformationService.class") || n.equals("optifine/Installer.class"))) ofMain = true;
            if (depth == 0 && (n.startsWith("net/optifine/") || n.startsWith("notch/net/optifine/")) && n.endsWith(".class")) ofClasses = true;
            if (n.equals("fabric.mod.json")) fabric = new String(zin.readAllBytes(), StandardCharsets.UTF_8);
            else if (n.equals("META-INF/mods.toml")) forge = new String(zin.readAllBytes(), StandardCharsets.UTF_8);
            else if (n.equals("META-INF/neoforge.mods.toml")) neo = true;
            else if (n.equals("quilt.mod.json")) quilt = true;
            else if (n.equals("META-INF/MANIFEST.MF")) manifest = new String(zin.readAllBytes(), StandardCharsets.UTF_8);
            else if (depth == 0 && n.equals("pack.mcmeta")) ji.hasPackMcmeta = true;
            else if (depth == 0 && n.endsWith(".class")) ji.hasClasses = true;
            else if (depth == 0 && n.equals("META-INF/services/cpw.mods.modlauncher.api.ITransformationService")) {
                ji.hasModLauncherService = true;
                if (new String(zin.readAllBytes(), StandardCharsets.UTF_8).contains("OptiFineTransformationService")) ji.optifine = true;
            }
            else if (depth < 2 && n.endsWith(".jar") && n.startsWith("META-INF/")) nestedJars.add(zin.readAllBytes());
        }
        if (depth == 0 && (ofMain || ofClasses && fabric == null && forge == null)) ji.optifine = true;
        boolean nested = depth > 0;
        if (fabric != null) {
            if (!nested) ji.loaders.add(Loader.FABRIC);
            try { addFabric(fabric, ji, nested); } catch (RuntimeException ex) { if (!nested) ji.error = "bad fabric.mod.json: " + ex.getMessage(); }
        }
        if (forge != null) {
            if (!nested) ji.loaders.add(Loader.FORGE);
            try { addForge(forge, manifestVersion(manifest), ji, nested); } catch (RuntimeException ex) { if (!nested) ji.error = "bad mods.toml: " + ex.getMessage(); }
        }
        if (neo && !nested) ji.loaders.add(Loader.NEOFORGE);
        if (quilt && !nested && fabric == null) ji.loaders.add(Loader.QUILT);
        for (byte[] b : nestedJars) {
            try { readZip(new ByteArrayInputStream(b), ji, depth + 1); } catch (IOException ignored) {}
        }
    }

    private static String manifestVersion(String mf) {
        if (mf == null) return null;
        for (String line : mf.split("\r?\n")) if (line.startsWith("Implementation-Version:")) return line.substring(23).trim();
        return null;
    }

    // ---------------- Fabric ----------------
    @SuppressWarnings("unchecked")
    private static void addFabric(String json, JarInfo ji, boolean nested) {
        Map<String, Object> m = (Map<String, Object>) MiniJson.parse(json);
        String id = str(m.get("id"));
        if (id == null) return;
        Mod mod = new Mod(id, str(m.get("version")), str(m.get("name")), Loader.FABRIC);
        mod.nested = nested;
        if (m.get("environment") instanceof String s) mod.env = s;
        if (m.get("entrypoints") instanceof Map<?, ?> ep && ep.get("modmenu") != null) mod.modmenuEntrypoint = true;
        addFabricDeps(mod, m.get("depends"), true, false);
        addFabricDeps(mod, m.get("breaks"), false, true);
        if (m.get("provides") instanceof List<?> p) for (Object o : p) if (o instanceof String s) mod.provides.add(s);
        (nested ? ji.nested : ji.mods).add(mod);
    }

    @SuppressWarnings("unchecked")
    private static void addFabricDeps(Mod mod, Object o, boolean mandatory, boolean incompatible) {
        if (!(o instanceof Map<?, ?> deps)) return;
        for (var en : ((Map<String, Object>) deps).entrySet()) {
            Object v = en.getValue();
            String range = v instanceof List<?> l ? String.join(" || ", l.stream().map(String::valueOf).toList()) : String.valueOf(v);
            mod.deps.add(new Dep(en.getKey(), range, mandatory, incompatible, "BOTH"));
        }
    }

    // ---------------- Forge mods.toml (minimal TOML) ----------------
    private static void addForge(String toml, String jarVersion, JarInfo ji, boolean nested) {
        List<Map<String, Object>> mods = new ArrayList<>();
        Map<String, List<Map<String, Object>>> deps = new LinkedHashMap<>();
        Map<String, Object> cur = new HashMap<>(), top = cur;
        String[] lines = toml.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = stripComment(lines[i]).trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[[")) {
                String t = line.substring(2, line.indexOf("]]")).trim();
                cur = new HashMap<>();
                if (t.equals("mods")) mods.add(cur);
                else if (t.startsWith("dependencies.")) deps.computeIfAbsent(unq(t.substring(13)), k -> new ArrayList<>()).add(cur);
                continue;
            }
            if (line.startsWith("[")) { cur = new HashMap<>(); continue; }
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
            if (v.startsWith("'''") || v.startsWith("\"\"\"")) {           // multi-line string: skip to end
                String q = v.substring(0, 3);
                if (v.length() > 3 && v.substring(3).contains(q)) { cur.put(k, v.substring(3, v.indexOf(q, 3))); continue; }
                while (i + 1 < lines.length && !lines[++i].contains(q)) {}
                continue;
            }
            cur.put(k, tomlValue(v));
        }
        if (!nested && ji.modLoader == null) { ji.modLoader = str(top.get("modLoader")); ji.loaderVersion = str(top.get("loaderVersion")); }
        for (Map<String, Object> mm : mods) {
            String id = str(mm.get("modId"));
            if (id == null) continue;
            String ver = str(mm.get("version"));
            if (ver != null && ver.contains("${file.jarVersion}")) ver = jarVersion != null ? jarVersion : ver;
            Mod mod = new Mod(id, ver, str(mm.get("displayName")), Loader.FORGE);
            mod.nested = nested;
            String dt = str(mm.get("displayTest"));
            if ("IGNORE_SERVER_VERSION".equals(dt) || "IGNORE_ALL_VERSION".equals(dt)) mod.env = "either";   // other side may lack it
            if (Boolean.TRUE.equals(mm.get("clientSideOnly"))) mod.env = "client";
            for (Map<String, Object> d : deps.getOrDefault(id, List.of())) {
                String did = str(d.get("modId"));
                if (did == null) continue;
                if (!nested && !d.containsKey("mandatory")) ji.tomlProblems.add("Missing required field mandatory in dependency " + did + " of " + id);
                String type = str(d.get("type"));
                // Forge reads "mandatory"; "type" is NeoForge's key. A jar for both (CPM SVC compat in VORTEX: neoforge with
                // mandatory=false, type="required") is optional on Forge
                boolean mandatory = d.containsKey("mandatory") ? Boolean.TRUE.equals(d.get("mandatory")) : type != null && type.equalsIgnoreCase("required");
                boolean incompat = type != null && type.equalsIgnoreCase("incompatible");
                mod.deps.add(new Dep(did, str(d.get("versionRange")), mandatory, incompat, Objects.requireNonNullElse(str(d.get("side")), "BOTH")));
            }
            (nested ? ji.nested : ji.mods).add(mod);
        }
    }

    private static String stripComment(String s) {
        boolean inS = false, inD = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && !inS) inD = !inD; else if (c == '\'' && !inD) inS = !inS;
            else if (c == '#' && !inS && !inD) return s.substring(0, i);
        }
        return s;
    }
    private static Object tomlValue(String v) {
        if (v.equals("true")) return Boolean.TRUE;
        if (v.equals("false")) return Boolean.FALSE;
        if ((v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) || (v.startsWith("'") && v.endsWith("'") && v.length() >= 2)) return v.substring(1, v.length() - 1);
        return v;
    }
    private static String unq(String s) { s = s.trim(); return (s.startsWith("\"") && s.endsWith("\"")) ? s.substring(1, s.length() - 1) : s; }
    private static String str(Object o) { return o instanceof String s ? s : null; }

    /** {@link #read} for each jar, on up to 4 threads, results in the same order */
    public static List<JarInfo> readAll(List<Path> jars) throws Exception {
        if (jars.size() < 2) { List<JarInfo> o = new ArrayList<>(); for (Path p : jars) o.add(read(p)); return o; }
        int n = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n, r -> {
            Thread th = new Thread(r, "alloy-modmeta"); th.setDaemon(true); th.setPriority(Thread.MIN_PRIORITY); return th;
        });
        try {
            List<java.util.concurrent.Future<JarInfo>> fs = new ArrayList<>();
            for (Path p : jars) fs.add(pool.submit(() -> read(p)));
            List<JarInfo> out = new ArrayList<>();
            for (var f : fs) {
                try { out.add(f.get()); }
                catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof RuntimeException r) throw r;
                    if (e.getCause() instanceof Error r) throw r;
                    throw e;
                }
            }
            return out;
        } finally { pool.shutdownNow(); }
    }

    // ---------------- hashing ----------------
    public static String hash(Path p, String algo) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algo);
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 16]; int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Jars Alloy itself writes into mods/; never user mods. */
    public static boolean isAlloyGenerated(String name) {
        return name.equals("alloy-bridge.jar") || name.equals("alloy-modmixins.jar") || name.startsWith("alloy-");
    }

    public static List<Path> jars(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".jar") && Files.isRegularFile(p)).sorted().toList();
        }
    }

    // ---------------- tiny JSON parser (objects, arrays, strings, numbers, literals; // and /* */ comments tolerated) -------------
    public static final class MiniJson {
        private final String s; private int i;
        private MiniJson(String s) { this.s = s; }
        public static Object parse(String s) { MiniJson p = new MiniJson(s); p.ws(); Object o = p.val(); return o; }
        private void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c) || c == '﻿') i++;
                else if (s.startsWith("//", i)) { while (i < s.length() && s.charAt(i) != '\n') i++; }
                else if (s.startsWith("/*", i)) { int e = s.indexOf("*/", i + 2); i = e < 0 ? s.length() : e + 2; }
                else break;
            }
        }
        private Object val() {
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(i);
            if (c == '{') {
                i++; Map<String, Object> m = new LinkedHashMap<>(); ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws(); String k = (String) val(); ws(); expect(':'); ws(); m.put(k, val()); ws();
                    if (s.charAt(i) == ',') { i++; ws(); if (s.charAt(i) == '}') { i++; return m; } continue; }
                    expect('}'); return m;
                }
            }
            if (c == '[') {
                i++; List<Object> l = new ArrayList<>(); ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    ws(); l.add(val()); ws();
                    if (s.charAt(i) == ',') { i++; ws(); if (s.charAt(i) == ']') { i++; return l; } continue; }
                    expect(']'); return l;
                }
            }
            if (c == '"') {
                StringBuilder b = new StringBuilder(); i++;
                while (s.charAt(i) != '"') {
                    char ch = s.charAt(i++);
                    if (ch == '\\') {
                        char x = s.charAt(i++);
                        switch (x) {
                            case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r');
                            case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                            case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                            default -> b.append(x);
                        }
                    } else b.append(ch);
                }
                i++; return b.toString();
            }
            int st = i;
            while (i < s.length() && ",}] \t\r\n".indexOf(s.charAt(i)) < 0) i++;
            String t = s.substring(st, i);
            return switch (t) { case "true" -> Boolean.TRUE; case "false" -> Boolean.FALSE; case "null" -> null; default -> t; };
        }
        private void expect(char c) { if (s.charAt(i) != c) throw new IllegalArgumentException("expected " + c + " at " + i); i++; }

        public static String quote(String v) {
            if (v == null) return "null";
            StringBuilder b = new StringBuilder("\"");
            for (char c : v.toCharArray()) {
                switch (c) {
                    case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\"); case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
                    default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
                }
            }
            return b.append('"').toString();
        }
    }
}
