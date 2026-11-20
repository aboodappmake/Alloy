package alloy.remap;

import org.objectweb.asm.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * Rewrites a jar (and its nested jars) from a source namespace to Mojang names: class files, mixin refmaps,
 * Fabric access wideners, Forge access transformers, JS coremods and reflection strings. Signature files are
 * dropped because the class bytes change.
 */
public final class JarRemapper {
    final Mappings.Table t;
    final Hierarchy h;
    final NsRemapper r;
    final NsRemapper.Stats stats = new NsRemapper.Stats();
    final String awNamespace;

    private static final Pattern REFMAP_REF = Pattern.compile("\"refmap\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern AW_REF = Pattern.compile("\"(?:accessWidener|access_widener)\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern REFMAP_KEY = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"");
    private static final Pattern AW_HEAD = Pattern.compile("^(accessWidener|classTweaker)(\\s+v\\d+\\s+)(\\S+)");

    public JarRemapper(Mappings.Table t, Hierarchy h, String awNamespace) {
        this.t = t; this.h = h; this.awNamespace = awNamespace;
        this.r = new NsRemapper(t, h, stats);
    }

    static final class Item { final String name; final byte[] data; final long time;
        Item(String n, byte[] d, long t) { name = n; data = d; time = t; } }

    static List<Item> readJar(byte[] jar) throws IOException {
        List<Item> out = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                out.add(new Item(e.getName(), zin.readAllBytes(), e.getTime()));
            }
        }
        return out;
    }

    /** Adds all classes of a jar (and nested jars) to the hierarchy, under their source names. */
    void index(List<Item> items) throws IOException {
        for (Item it : items) {
            if (it.name.endsWith(".class") && !it.name.startsWith("META-INF/")) {
                try {
                    String cn = new ClassReader(it.data).getClassName();
                    h.addClass(cn, it.data, "input");
                    inputClasses.put(cn, it.data);
                } catch (RuntimeException ignored) { }
            } else if (isJar(it.name)) {
                index(readJar(it.data));
            }
        }
    }

    /** Reads the input and indexes its classes. Call before adding class path jars so input classes win. */
    public List<Item> prepare(Path in) throws IOException {
        List<Item> items = readJar(Files.readAllBytes(in));
        index(items);
        return items;
    }

    /**
     * Multi-version libraries keep one overload per game version, for example width(class_2561, ...) for Fabric and
     * width(Component, ...) for Forge in the same class (VMod's shaded Elementa). After the rename both have the same
     * name and descriptor and the JVM refuses the class (ClassFormatError: Duplicate method). Call sites are renamed
     * the same way, so they all reach one method: keep the overload that already had the target names (or the first)
     * and drop the others. Returns the source name+desc of the methods to drop.
     */
    Set<String> mergedOverloads(ClassReader cr) {
        String owner = cr.getClassName();
        List<String[]> ms = new ArrayList<>();
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] exc) {
                ms.add(new String[]{name, desc});
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        Map<String, List<String[]>> byTarget = new LinkedHashMap<>();
        for (String[] m : ms) {
            String to = r.mapMethodName(owner, m[0], m[1]) + r.mapMethodDesc(m[1]);
            byTarget.computeIfAbsent(to, k -> new ArrayList<>()).add(m);
        }
        Set<String> drop = new HashSet<>();
        for (Map.Entry<String, List<String[]>> e : byTarget.entrySet()) {
            List<String[]> g = e.getValue();
            if (g.size() < 2) continue;
            String[] keep = g.get(0);
            for (String[] m : g) if ((m[0] + m[1]).equals(e.getKey())) { keep = m; break; }
            for (String[] m : g) {
                if (m == keep) continue;
                drop.add(m[0] + m[1]);
                stats.overloadsDropped.add(owner + "." + m[0] + m[1]);
                System.err.println("warn: " + owner + "." + m[0] + m[1] + " becomes a copy of " + e.getKey() + " after the rename, dropped");
            }
        }
        return drop;
    }

    final Map<String, byte[]> inputClasses = new LinkedHashMap<>();
    /** The obfuscated game jar (member access of game methods). */
    public Path gameObf;
    /** Shared fix table (tab separated: owner.name+desc, new name): read before, appended after. */
    public Path fixesFile;
    final List<String> newFixes = new ArrayList<>();

    public void write(List<Item> items, Path out) throws IOException {
        if (t.tokenNamespace()) Collisions.find(this);
        byte[] res = remap(items);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        Files.write(tmp, res);
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
    }

    byte[] remap(List<Item> items) throws IOException {
        boolean text = t.tokenNamespace();
        Set<String> refmaps = new HashSet<>(), wideners = new HashSet<>();
        if (text) {
            for (Item it : items) {
                if (!it.name.endsWith(".json")) continue;
                String s = new String(it.data, StandardCharsets.UTF_8);
                Matcher m = REFMAP_REF.matcher(s);
                while (m.find()) refmaps.add(m.group(1));
                m = AW_REF.matcher(s);
                while (m.find()) wideners.add(m.group(1));
            }
        }
        // names the jar's refmaps translate: Mixin looks them up as written, so the selector pass must not touch them
        r.refmapKeys = new HashSet<>();
        if (text) for (Item it : items) {
            String base = it.name.substring(it.name.lastIndexOf('/') + 1);
            if (!(refmaps.contains(it.name) || refmaps.contains(base) || it.name.endsWith("refmap.json"))) continue;
            Matcher m = REFMAP_KEY.matcher(new String(it.data, StandardCharsets.UTF_8));
            while (m.find()) r.refmapKeys.add(m.group(1));
        }
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        Set<String> written = new HashSet<>();
        try (ZipOutputStream zout = new ZipOutputStream(bout)) {
            for (Item it : items) {
                String n = it.name;
                byte[] d = it.data;
                String upper = n.toUpperCase(Locale.ROOT);
                if (upper.startsWith("META-INF/") && upper.indexOf('/', 9) < 0
                        && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"))) continue;
                if (upper.equals("META-INF/MANIFEST.MF")) d = cleanManifest(d);
                else if (n.endsWith(".class")) {
                    String prefix = "";
                    if (n.startsWith("META-INF/versions/")) {
                        int s = n.indexOf('/', 18);
                        prefix = n.substring(0, s + 1);
                    }
                    try {
                        ClassReader cr = new ClassReader(d);
                        ClassWriter cw = new ClassWriter(0);
                        Set<String> drop = mergedOverloads(cr);
                        ClassVisitor cv = r.classVisitor(cw);
                        if (!drop.isEmpty()) {
                            cv = new ClassVisitor(Opcodes.ASM9, cv) {
                                @Override public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] exc) {
                                    return drop.contains(name + desc) ? null : super.visitMethod(acc, name, desc, sig, exc);
                                }
                            };
                        }
                        cr.accept(cv, 0);
                        d = cw.toByteArray();
                        String mapped = r.map(cr.getClassName());
                        stats.classes++;
                        if (!mapped.equals(cr.getClassName())) stats.renamedClasses++;
                        if (!n.startsWith("META-INF/") || !prefix.isEmpty()) n = prefix + mapped + ".class";
                    } catch (RuntimeException ex) {
                        System.err.println("warn: could not remap " + n + ": " + ex);
                    }
                } else if (isJar(n)) {
                    stats.nestedJars++;
                    Set<String> keep = r.refmapKeys;
                    d = remap(readJar(d));
                    r.refmapKeys = keep;
                } else if (text) {
                    String base = n.substring(n.lastIndexOf('/') + 1);
                    boolean refmap = refmaps.contains(n) || refmaps.contains(base) || n.endsWith("refmap.json");
                    boolean aw = wideners.contains(n) || n.endsWith(".accesswidener") || n.endsWith(".classtweaker");
                    boolean at = upper.startsWith("META-INF/") && (upper.endsWith("ACCESSTRANSFORMER.CFG") || upper.endsWith("_AT.CFG"));
                    boolean js = n.endsWith(".js");
                    boolean meta = base.equals("fabric.mod.json") || base.equals("quilt.mod.json") || n.endsWith(".mixins.json")
                            || (n.endsWith(".json") && n.toLowerCase(Locale.ROOT).contains("mixin"));
                    if (refmap || aw || at || js || meta) {
                        String s = new String(d, StandardCharsets.UTF_8);
                        String o = r.remapText(s);
                        if (aw) {
                            Matcher m = AW_HEAD.matcher(o);
                            if (m.find()) o = m.replaceFirst("$1$2" + Matcher.quoteReplacement(awNamespace));
                            stats.awFiles++;
                        } else if (refmap) {
                            stats.refmaps++;
                            if (!o.equals(s)) stats.refmapValues += countDiff(s, o);
                        } else if (at) stats.atFiles++;
                        else if (js) stats.jsFiles++;
                        else stats.textFiles++;
                        d = o.getBytes(StandardCharsets.UTF_8);
                    } else if (base.equals("fabRelRefMap.txt") || base.equals("fabAbsRefMap.txt")) {
                        String s = new String(d, StandardCharsets.UTF_8);
                        String o = r.remapFabRefMap(s);
                        stats.fabRefMaps++;
                        if (!o.equals(s)) d = o.getBytes(StandardCharsets.UTF_8);
                    } else {
                        String s = sweepable(n, d);
                        if (s != null) {
                            String o = r.remapText(s);
                            stats.resourceFiles++;
                            if (!o.equals(s)) d = o.getBytes(StandardCharsets.UTF_8);
                        }
                    }
                }
                if (!written.add(n)) { System.err.println("warn: duplicate entry " + n); continue; }
                ZipEntry ze = new ZipEntry(n);
                if (it.time > 0) ze.setTime(it.time);
                zout.putNextEntry(ze);
                zout.write(d);
                zout.closeEntry();
            }
        }
        return bout.toByteArray();
    }

    /**
     * A resource the generic sweep may rewrite: valid UTF-8 text (no NUL in the first 8 KB), not a lang file, not a
     * mapping file, holding at least one id the table knows. Returns the text, or null to copy the entry unchanged.
     */
    String sweepable(String name, byte[] d) {
        if (d.length == 0 || d.length > 16 << 20 || skipResource(name)) return null;
        String s = textOf(d);
        if (s == null || isMappingFile(s)) return null;
        return r.hasKnownId(s) ? s : null;
    }

    /** A nested jar: ".jar", or ".jarinjar", the name LuckPerms (Forge and Fabric) gives the jar its own class loader opens
     *  at run time. Left as it is, that inner jar keeps SRG or Intermediary names and fails on Alloy (NoSuchMethodError). */
    static boolean isJar(String name) {
        return name.endsWith(".jar") || name.endsWith(".jarinjar");
    }

    /** Lang files, mapping files and obvious binaries by name; the content checks are in textOf and isMappingFile. */
    static boolean skipResource(String name) {
        String l = name.toLowerCase(Locale.ROOT);
        if (l.endsWith(".class") || isJar(l)) return true;
        if (l.matches("(?:.*/)?lang/[^/]+\\.(?:json|lang)")) return true;
        for (String ext : new String[]{".tiny", ".tsrg", ".srg", ".csrg", ".mappings", ".png", ".ogg", ".nbt", ".so", ".dll", ".dylib", ".jnilib"})
            if (l.endsWith(ext)) return true;
        return l.endsWith("/mappings.txt") || l.equals("mappings.txt");
    }

    /** The entry as text, or null when it looks binary (NUL in the first 8 KB) or is not valid UTF-8. */
    static String textOf(byte[] d) {
        for (int i = 0; i < Math.min(d.length, 8192); i++) if (d[i] == 0) return null;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(d)).toString();
        } catch (java.nio.charset.CharacterCodingException e) { return null; }
    }

    /** Tiny v1/v2, TSRG, SRG and ProGuard mapping files: their ids are the point of the file, never rewrite them. */
    static boolean isMappingFile(String s) {
        String h = s.startsWith("﻿") ? s.substring(1) : s;
        if (h.startsWith("tiny\t") || h.startsWith("v1\t") || h.startsWith("tsrg2 ")) return true;
        if (h.startsWith("PK: ") || h.startsWith("CL: ") || h.startsWith("FD: ") || h.startsWith("MD: ")) return true;
        return h.matches("(?s)(?:#[^\n]*\n)*[\\w.$]+ -> [\\w.$]+:\r?\n.*");
    }

    private static int countDiff(String a, String b) {
        String[] x = a.split("\"", -1), y = b.split("\"", -1);
        int n = 0;
        for (int i = 0; i < Math.min(x.length, y.length); i++) if (!x[i].equals(y[i])) n++;
        return n;
    }

    /** Drops per-entry digests (they no longer match) and keeps the main section. */
    /**
     * Keeps the manifest as it is, all sections included (Forge reads per-package sections such as
     * Specification-Version, "Missing forge spec" otherwise). Only per-entry digest lines (xxx-Digest), which no
     * longer match, are removed, and a section that held nothing but a Name and digests is dropped.
     * A manifest without digests is returned byte for byte.
     */
    static byte[] cleanManifest(byte[] d) {
        String text = new String(d, StandardCharsets.UTF_8);
        if (!text.contains("-Digest")) return d;
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        // logical lines (a line starting with one space continues the previous one), kept with their raw form
        List<String> raw = new ArrayList<>(), logical = new ArrayList<>();
        for (String l : text.split("\r?\n", -1)) {
            if (l.startsWith(" ") && !raw.isEmpty()) {
                int i = raw.size() - 1;
                raw.set(i, raw.get(i) + nl + l);
                logical.set(i, logical.get(i) + l.substring(1));
            } else { raw.add(l); logical.add(l); }
        }
        StringBuilder out = new StringBuilder();
        List<String> section = new ArrayList<>();
        boolean main = true, hasOther = false;
        for (int i = 0; i <= raw.size(); i++) {
            boolean end = i == raw.size() || logical.get(i).isEmpty();
            if (end) {
                if (!section.isEmpty() && (main || hasOther)) {
                    for (String s : section) out.append(s).append(nl);
                    out.append(nl);
                }
                section.clear(); main = false; hasOther = false;
                continue;
            }
            String l = logical.get(i);
            int c = l.indexOf(':');
            String key = c > 0 ? l.substring(0, c) : l;
            if (key.endsWith("-Digest")) continue;
            if (!main && !key.equalsIgnoreCase("Name")) hasOther = true;
            section.add(raw.get(i));
        }
        String s = out.toString();
        while (s.endsWith(nl + nl + nl)) s = s.substring(0, s.length() - nl.length());
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
