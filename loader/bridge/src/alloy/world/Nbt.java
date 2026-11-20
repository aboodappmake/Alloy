package alloy.world;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * A small NBT reader and writer with no Minecraft classes, so the same code runs in the game (any Minecraft version),
 * in the installer's offline world check and in unit tests.
 *
 * Model: compound = LinkedHashMap&lt;String, Object&gt; (key order kept), list = {@link NList} (keeps its element type, so an
 * empty list round trips), Byte, Short, Integer, Long, Float, Double, String, byte[], int[], long[].
 * Files: gzip (level.dat, player files, data/*.dat), zlib or uncompressed are detected by their first bytes.
 * Region files (.mca): {@link #readRegion} gives every chunk's root compound.
 */
public final class Nbt {
    private Nbt() {}

    public static final int END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6, BYTE_ARRAY = 7,
            STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;
    static final int MAX_DEPTH = 512;

    /** A list tag: element type plus items. */
    public static final class NList {
        public int type;
        public final List<Object> items = new ArrayList<>();
        public NList(int type) { this.type = type; }
        public NList add(Object o) {
            if (items.isEmpty() && type == END) type = typeOf(o);
            items.add(o);
            return this;
        }
        @Override public boolean equals(Object o) { return o instanceof NList l && l.type == type && deepEquals(items, l.items); }
        @Override public int hashCode() { return items.size() * 31 + type; }
        @Override public String toString() { return "NList" + items; }
    }

    // ------------------------------------------------------------------ reading

    /** Reads a file: gzip, zlib or raw, decided by the first bytes. Returns the root compound. */
    public static Map<String, Object> readFile(Path p) throws IOException {
        return readBytes(Files.readAllBytes(p));
    }

    public static Map<String, Object> readBytes(byte[] b) throws IOException {
        InputStream in = new ByteArrayInputStream(b);
        if (b.length >= 2 && (b[0] & 0xff) == 0x1f && (b[1] & 0xff) == 0x8b) in = new GZIPInputStream(in);
        else if (b.length >= 2 && (b[0] & 0xff) == 0x78) in = new InflaterInputStream(in);
        return readRoot(new DataInputStream(new BufferedInputStream(in)));
    }

    /** A named root tag (type byte, name, payload) that must be a compound. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readRoot(DataInput in) throws IOException {
        int t = in.readUnsignedByte();
        if (t != COMPOUND) throw new IOException("root tag is not a compound (type " + t + ")");
        in.readUTF();
        return (Map<String, Object>) read(in, COMPOUND, 0);
    }

    static Object read(DataInput in, int t, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nested too deep");
        switch (t) {
            case BYTE: return in.readByte();
            case SHORT: return in.readShort();
            case INT: return in.readInt();
            case LONG: return in.readLong();
            case FLOAT: return in.readFloat();
            case DOUBLE: return in.readDouble();
            case BYTE_ARRAY: { byte[] a = new byte[len(in)]; in.readFully(a); return a; }
            case STRING: return in.readUTF();
            case LIST: {
                int et = in.readUnsignedByte(), n = in.readInt();
                NList l = new NList(et);
                if (n > 0 && et == END) throw new IOException("list of END tags");
                for (int i = 0; i < n; i++) l.items.add(read(in, et, depth + 1));
                return l;
            }
            case COMPOUND: {
                Map<String, Object> m = new LinkedHashMap<>();
                for (;;) {
                    int ct = in.readUnsignedByte();
                    if (ct == END) return m;
                    String name = in.readUTF();
                    m.put(name, read(in, ct, depth + 1));
                }
            }
            case INT_ARRAY: { int n = len(in); int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = in.readInt(); return a; }
            case LONG_ARRAY: { int n = len(in); long[] a = new long[n]; for (int i = 0; i < n; i++) a[i] = in.readLong(); return a; }
            default: throw new IOException("unknown NBT tag type " + t);
        }
    }

    static int len(DataInput in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 64 << 20) throw new IOException("bad array length " + n);
        return n;
    }

    // ------------------------------------------------------------------ writing

    /** gzip file, written to a temp file next to it and moved over (like Minecraft does for level.dat). */
    public static void writeFile(Path p, Map<String, Object> root) throws IOException {
        Path tmp = p.resolveSibling(p.getFileName() + ".alloy-tmp");
        if (p.toAbsolutePath().getParent() != null) Files.createDirectories(p.toAbsolutePath().getParent());
        try (OutputStream o = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            writeRoot(new DataOutputStream(o), "", root);
        }
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Uncompressed bytes of a root compound (what Minecraft's NbtIo.write/read use). */
    public static byte[] toBytes(Map<String, Object> root) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            writeRoot(new DataOutputStream(b), "", root);
            return b.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Map<String, Object> fromBytes(byte[] raw) {
        try {
            return readRoot(new DataInputStream(new ByteArrayInputStream(raw)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void writeRoot(DataOutput out, String name, Map<String, Object> root) throws IOException {
        out.writeByte(COMPOUND);
        out.writeUTF(name);
        write(out, root);
    }

    public static int typeOf(Object o) {
        if (o instanceof Byte) return BYTE;
        if (o instanceof Short) return SHORT;
        if (o instanceof Integer) return INT;
        if (o instanceof Long) return LONG;
        if (o instanceof Float) return FLOAT;
        if (o instanceof Double) return DOUBLE;
        if (o instanceof byte[]) return BYTE_ARRAY;
        if (o instanceof String) return STRING;
        if (o instanceof NList) return LIST;
        if (o instanceof Map) return COMPOUND;
        if (o instanceof int[]) return INT_ARRAY;
        if (o instanceof long[]) return LONG_ARRAY;
        if (o instanceof Boolean) return BYTE;
        throw new IllegalArgumentException("not an NBT value: " + (o == null ? "null" : o.getClass().getName()));
    }

    @SuppressWarnings("unchecked")
    static void write(DataOutput out, Object o) throws IOException {
        switch (typeOf(o)) {
            case BYTE -> out.writeByte(o instanceof Boolean b ? (b ? 1 : 0) : (Byte) o);
            case SHORT -> out.writeShort((Short) o);
            case INT -> out.writeInt((Integer) o);
            case LONG -> out.writeLong((Long) o);
            case FLOAT -> out.writeFloat((Float) o);
            case DOUBLE -> out.writeDouble((Double) o);
            case BYTE_ARRAY -> { byte[] a = (byte[]) o; out.writeInt(a.length); out.write(a); }
            case STRING -> out.writeUTF((String) o);
            case LIST -> {
                NList l = (NList) o;
                int et = l.items.isEmpty() ? l.type : typeOf(l.items.get(0));
                out.writeByte(et);
                out.writeInt(l.items.size());
                for (Object x : l.items) {
                    if (typeOf(x) != et) throw new IOException("mixed list element types");
                    write(out, x);
                }
            }
            case COMPOUND -> {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                    out.writeByte(typeOf(e.getValue()));
                    out.writeUTF(e.getKey());
                    write(out, e.getValue());
                }
                out.writeByte(END);
            }
            case INT_ARRAY -> { int[] a = (int[]) o; out.writeInt(a.length); for (int x : a) out.writeInt(x); }
            case LONG_ARRAY -> { long[] a = (long[]) o; out.writeInt(a.length); for (long x : a) out.writeLong(x); }
            default -> throw new IOException("bad type");
        }
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }

    public static Map<String, Object> compound(Map<String, Object> m, String... path) {
        Object o = m;
        for (String k : path) {
            Map<String, Object> c = compound(o);
            if (c == null) return null;
            o = c.get(k);
        }
        return compound(o);
    }

    public static NList list(Map<String, Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        return o instanceof NList l ? l : null;
    }

    public static String string(Map<String, Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        return o instanceof String s ? s : null;
    }

    /** Deep copy (arrays, lists and compounds are copied; strings and numbers are immutable). */
    @SuppressWarnings("unchecked")
    public static Object copy(Object o) {
        if (o instanceof Map) {
            Map<String, Object> m = new LinkedHashMap<>();
            ((Map<String, Object>) o).forEach((k, v) -> m.put(k, copy(v)));
            return m;
        }
        if (o instanceof NList l) {
            NList c = new NList(l.type);
            for (Object x : l.items) c.items.add(copy(x));
            return c;
        }
        if (o instanceof byte[] a) return a.clone();
        if (o instanceof int[] a) return a.clone();
        if (o instanceof long[] a) return a.clone();
        return o;
    }

    @SuppressWarnings("unchecked")
    public static boolean deepEquals(Object a, Object b) {
        if (a instanceof Map && b instanceof Map) {
            Map<String, Object> x = (Map<String, Object>) a, y = (Map<String, Object>) b;
            if (!x.keySet().equals(y.keySet())) return false;
            for (String k : x.keySet()) if (!deepEquals(x.get(k), y.get(k))) return false;
            return true;
        }
        if (a instanceof List && b instanceof List) {
            List<Object> x = (List<Object>) a, y = (List<Object>) b;
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!deepEquals(x.get(i), y.get(i))) return false;
            return true;
        }
        if (a instanceof byte[] x && b instanceof byte[] y) return Arrays.equals(x, y);
        if (a instanceof int[] x && b instanceof int[] y) return Arrays.equals(x, y);
        if (a instanceof long[] x && b instanceof long[] y) return Arrays.equals(x, y);
        return Objects.equals(a, b);
    }

    /** "ns" of "ns:path"; a name without ':' is in "minecraft" (Minecraft's own rule). */
    public static String namespace(String id) {
        if (id == null) return null;
        int i = id.indexOf(':');
        return i < 0 ? "minecraft" : id.substring(0, i);
    }

    /** UUID text of an int[4] "UUID" tag (how entities and players store it), or null. */
    public static String uuid(Object o) {
        if (!(o instanceof int[] a) || a.length != 4) return null;
        long hi = ((long) a[0] << 32) | (a[1] & 0xffffffffL), lo = ((long) a[2] << 32) | (a[3] & 0xffffffffL);
        return new UUID(hi, lo).toString();
    }

    // ------------------------------------------------------------------ region files

    /** One chunk of a region file. */
    public record Chunk(int x, int z, Map<String, Object> root) {}

    /**
     * Every chunk in an Anvil region file (r.X.Z.mca). Compression 1 gzip, 2 zlib, 3 none; chunks too big for the
     * file are in c.X.Z.mcc next to it (bit 128). LZ4 (4) and custom (127) are counted in {@code skipped} and left out.
     * A broken chunk is skipped and counted too; one bad chunk never stops the scan.
     */
    public static List<Chunk> readRegion(Path mca, int[] skipped) throws IOException {
        byte[] f = Files.readAllBytes(mca);
        List<Chunk> out = new ArrayList<>();
        if (f.length < 8192) return out;
        int rx = 0, rz = 0;
        String[] parts = mca.getFileName().toString().split("\\.");
        if (parts.length >= 4) {
            try { rx = Integer.parseInt(parts[1]); rz = Integer.parseInt(parts[2]); } catch (NumberFormatException ignored) { }
        }
        for (int i = 0; i < 1024; i++) {
            int loc = ((f[i * 4] & 0xff) << 16) | ((f[i * 4 + 1] & 0xff) << 8) | (f[i * 4 + 2] & 0xff);
            if (loc == 0) continue;
            int off = loc * 4096;
            int cx = rx * 32 + (i & 31), cz = rz * 32 + (i >> 5);
            try {
                if (off + 5 > f.length) throw new IOException("chunk past end of file");
                int length = ((f[off] & 0xff) << 24) | ((f[off + 1] & 0xff) << 16) | ((f[off + 2] & 0xff) << 8) | (f[off + 3] & 0xff);
                int comp = f[off + 4] & 0xff;
                byte[] data;
                if ((comp & 128) != 0) {
                    Path ext = mca.resolveSibling("c." + cx + "." + cz + ".mcc");
                    data = Files.readAllBytes(ext);
                    comp &= 127;
                } else {
                    if (length < 1 || off + 4 + length > f.length) throw new IOException("bad chunk length");
                    data = Arrays.copyOfRange(f, off + 5, off + 4 + length);
                }
                InputStream in = new ByteArrayInputStream(data);
                if (comp == 1) in = new GZIPInputStream(in);
                else if (comp == 2) in = new InflaterInputStream(in);
                else if (comp != 3) { if (skipped != null) skipped[0]++; continue; }
                out.add(new Chunk(cx, cz, readRoot(new DataInputStream(new BufferedInputStream(in)))));
            } catch (IOException | RuntimeException e) {
                if (skipped != null) skipped[0]++;
            }
        }
        return out;
    }

    /** Writes a region file with the given chunks (zlib), for tests. */
    public static void writeRegion(Path mca, List<Chunk> chunks) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] header = new byte[8192];
        int sector = 2;
        for (Chunk c : chunks) {
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            try (DeflaterOutputStream d = new DeflaterOutputStream(z)) { writeRoot(new DataOutputStream(d), "", c.root()); }
            byte[] data = z.toByteArray();
            int len = data.length + 1;
            int sectors = (len + 4 + 4095) / 4096;
            int i = (c.x() & 31) + (c.z() & 31) * 32;
            header[i * 4] = (byte) (sector >> 16); header[i * 4 + 1] = (byte) (sector >> 8); header[i * 4 + 2] = (byte) sector;
            header[i * 4 + 3] = (byte) sectors;
            byte[] s = new byte[sectors * 4096];
            s[0] = (byte) (len >> 24); s[1] = (byte) (len >> 16); s[2] = (byte) (len >> 8); s[3] = (byte) len; s[4] = 2;
            System.arraycopy(data, 0, s, 5, data.length);
            body.write(s);
            sector += sectors;
        }
        Files.createDirectories(mca.toAbsolutePath().getParent());
        try (OutputStream o = Files.newOutputStream(mca)) { o.write(header); body.writeTo(o); }
    }
}
