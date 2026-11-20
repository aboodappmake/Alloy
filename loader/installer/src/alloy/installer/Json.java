package alloy.installer;

import java.util.*;

/** Minimal JSON reader/writer (Maps, Lists, Strings, Long/Double, Boolean, null). */
final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        return j.value();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    static List<Object> arr(Object o) { return (List<Object>) o; }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private Object value() {
        char c = s.charAt(i);
        switch (c) {
            case '{': {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = str();
                    ws();
                    i++; // :
                    ws();
                    m.put(k, value());
                    ws();
                    if (s.charAt(i++) == '}') return m;
                }
            }
            case '[': {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    ws();
                    l.add(value());
                    ws();
                    if (s.charAt(i++) == ']') return l;
                }
            }
            case '"': return str();
            case 't': i += 4; return Boolean.TRUE;
            case 'f': i += 5; return Boolean.FALSE;
            case 'n': i += 4; return null;
            default: {
                int st = i;
                while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
                String n = s.substring(st, i);
                if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
                return Long.parseLong(n);
            }
        }
    }

    private String str() {
        StringBuilder b = new StringBuilder();
        i++; // opening quote
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            } else b.append(c);
        }
    }

    static String write(Object o) {
        StringBuilder b = new StringBuilder();
        write(b, o, 0);
        return b.append('\n').toString();
    }

    private static void write(StringBuilder b, Object o, int ind) {
        if (o == null) b.append("null");
        else if (o instanceof String str) {
            b.append('"');
            for (char c : str.toCharArray()) {
                switch (c) {
                    case '"': b.append("\\\""); break;
                    case '\\': b.append("\\\\"); break;
                    case '\n': b.append("\\n"); break;
                    case '\r': b.append("\\r"); break;
                    case '\t': b.append("\\t"); break;
                    default: if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
                }
            }
            b.append('"');
        } else if (o instanceof Map<?, ?> m) {
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append("{\n");
            int n = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                b.append("    ".repeat(ind + 1));
                write(b, String.valueOf(e.getKey()), 0);
                b.append(": ");
                write(b, e.getValue(), ind + 1);
                if (++n < m.size()) b.append(',');
                b.append('\n');
            }
            b.append("    ".repeat(ind)).append('}');
        } else if (o instanceof List<?> l) {
            if (l.isEmpty()) { b.append("[]"); return; }
            b.append("[\n");
            for (int k = 0; k < l.size(); k++) {
                b.append("    ".repeat(ind + 1));
                write(b, l.get(k), ind + 1);
                if (k + 1 < l.size()) b.append(',');
                b.append('\n');
            }
            b.append("    ".repeat(ind)).append(']');
        } else b.append(o);
    }
}
