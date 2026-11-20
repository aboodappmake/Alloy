package alloy.api.impl;

import alloy.api.PlainData;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binary form of plain-type data for the network. One tag byte per value; lengths are int32. Decoding checks every
 * length against the bytes left and limits nesting, so a malformed or hostile payload fails cleanly.
 */
final class PlainCodec {
    private PlainCodec() {}

    private static final byte NULL = 0, BOOL = 1, BYTE = 2, SHORT = 3, INT = 4, LONG = 5, FLOAT = 6, DOUBLE = 7,
            STRING = 8, BYTES = 9, LIST = 10, MAP = 11;

    static byte[] encode(Map<String, Object> data) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(64);
        try (DataOutputStream o = new DataOutputStream(bo)) {
            write(o, data);
        } catch (IOException e) {
            throw new IllegalStateException(e);   // in-memory stream
        }
        return bo.toByteArray();
    }

    private static void write(DataOutputStream o, Object v) throws IOException {
        if (v == null) o.writeByte(NULL);
        else if (v instanceof Boolean b) { o.writeByte(BOOL); o.writeBoolean(b); }
        else if (v instanceof Byte b) { o.writeByte(BYTE); o.writeByte(b); }
        else if (v instanceof Short s) { o.writeByte(SHORT); o.writeShort(s); }
        else if (v instanceof Integer i) { o.writeByte(INT); o.writeInt(i); }
        else if (v instanceof Long l) { o.writeByte(LONG); o.writeLong(l); }
        else if (v instanceof Float f) { o.writeByte(FLOAT); o.writeFloat(f); }
        else if (v instanceof Double d) { o.writeByte(DOUBLE); o.writeDouble(d); }
        else if (v instanceof String s) { o.writeByte(STRING); bytes(o, s.getBytes(StandardCharsets.UTF_8)); }
        else if (v instanceof byte[] b) { o.writeByte(BYTES); bytes(o, b); }
        else if (v instanceof Map<?, ?> m) {
            o.writeByte(MAP);
            o.writeInt(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                bytes(o, ((String) e.getKey()).getBytes(StandardCharsets.UTF_8));
                write(o, e.getValue());
            }
        } else if (v instanceof Collection<?> c) {
            o.writeByte(LIST);
            o.writeInt(c.size());
            for (Object x : c) write(o, x);
        } else throw new IllegalArgumentException("not a plain type: " + v.getClass().getName());
    }

    private static void bytes(DataOutputStream o, byte[] b) throws IOException {
        o.writeInt(b.length);
        o.write(b);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> decode(byte[] data) {
        ByteBuffer in = ByteBuffer.wrap(data);
        Object v = read(in, 0);
        if (!(v instanceof Map)) throw new IllegalArgumentException("payload is not a map");
        if (in.hasRemaining()) throw new IllegalArgumentException(in.remaining() + " trailing byte(s)");
        return (Map<String, Object>) v;
    }

    private static Object read(ByteBuffer in, int depth) {
        if (depth > PlainData.MAX_DEPTH) throw new IllegalArgumentException("nested too deep");
        byte tag = in.get();
        return switch (tag) {
            case NULL -> null;
            case BOOL -> in.get() != 0;
            case BYTE -> in.get();
            case SHORT -> in.getShort();
            case INT -> in.getInt();
            case LONG -> in.getLong();
            case FLOAT -> in.getFloat();
            case DOUBLE -> in.getDouble();
            case STRING -> new String(bytes(in), StandardCharsets.UTF_8);
            case BYTES -> bytes(in);
            case LIST -> {
                int n = count(in);
                List<Object> l = new ArrayList<>(n);
                for (int i = 0; i < n; i++) l.add(read(in, depth + 1));
                yield Collections.unmodifiableList(l);
            }
            case MAP -> {
                int n = count(in);
                Map<String, Object> m = new LinkedHashMap<>(Math.max(4, n * 2));
                for (int i = 0; i < n; i++) {
                    String k = new String(bytes(in), StandardCharsets.UTF_8);
                    m.put(k, read(in, depth + 1));
                }
                yield Collections.unmodifiableMap(m);
            }
            default -> throw new IllegalArgumentException("unknown tag " + tag);
        };
    }

    /** element count: every element takes at least one byte */
    private static int count(ByteBuffer in) {
        int n = in.getInt();
        if (n < 0 || n > in.remaining()) throw new IllegalArgumentException("bad element count " + n);
        return n;
    }

    private static byte[] bytes(ByteBuffer in) {
        int n = in.getInt();
        if (n < 0 || n > in.remaining()) throw new IllegalArgumentException("bad length " + n);
        byte[] b = new byte[n];
        in.get(b);
        return b;
    }
}
