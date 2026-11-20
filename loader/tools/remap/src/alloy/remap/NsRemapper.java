package alloy.remap;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

import java.util.*;
import java.util.regex.*;

/**
 * ASM remapper from one source namespace to Mojang names.
 *
 * Member lookup: the owner's own table, then its super types (inheritance aware, through the class hierarchy of the
 * game and the mod), then for token namespaces (SRG, Intermediary) the global token table, because those ids are
 * unique per override family. Mod-own names that are not tokens stay as they are.
 */
public final class NsRemapper extends Remapper {
    final Mappings.Table t;
    final Hierarchy h;
    final Stats stats;
    static final boolean DEBUG = Boolean.getBoolean("alloy.remap.debug");
    private final Map<String, String> methodCache = new HashMap<>();
    private final Map<String, String> fieldCache = new HashMap<>();
    private final Pattern classPat, tokenPat;

    public NsRemapper(Mappings.Table t, Hierarchy h, Stats stats) {
        this.t = t; this.h = h; this.stats = stats;
        if (t.tokenNamespace()) {
            // one pattern for both token sets: they cannot clash, and mixed jars carry both
            // Intermediary classes: net/minecraft/class_N[$class_M], and inner classes of classes Mojang does not obfuscate
            // (com/mojang/blaze3d/platform/GlStateManager$class_1017 in access wideners)
            // An anonymous class keeps its number in Intermediary (class_2357$17) but Mojang can number it differently
            // (DispenseItemBehavior$8 on 1.21.1), so "$17" belongs to the match and the table maps the whole name.
            classPat = Pattern.compile("net[/.]minecraft[/.]class_\\d+(?:\\$(?:class_\\d+|\\d+(?![A-Za-z0-9_])))*"
                    + "|(?:(?<![A-Za-z0-9_$/.])|(?<=[^A-Za-z0-9_$/.]L)|(?<=^L))(?:[a-z_][A-Za-z0-9_]*[/.])+[A-Za-z_][A-Za-z0-9_]*\\$class_\\d+(?:\\$class_\\d+)*");
            tokenPat = Pattern.compile("(?<![A-Za-z0-9_])(?:(?:method|field|comp)_\\d+|[mf]_\\d+_)(?![A-Za-z0-9_])");
        } else { classPat = null; tokenPat = null; }
    }

    @Override public String map(String internalName) {
        String m = t.classes.get(internalName);
        if (m != null) return m;
        int d = internalName.lastIndexOf('$');
        if (d > 0 && t.tokenNamespace()) { // inner class of a mapped class that the table does not list
            String outer = t.classes.get(internalName.substring(0, d));
            if (outer != null) return outer + internalName.substring(d);
        }
        return internalName;
    }

    @Override public String mapMethodName(String owner, String name, String desc) {
        if (name.startsWith("<")) return name;
        if (owner.startsWith("[")) owner = "java/lang/Object";
        String key = owner + "." + name + desc;
        String c = methodCache.get(key);
        if (c != null) return c;
        String r = fixes.isEmpty() ? null : fixed(owner, name + desc);
        if (r == null) r = findMethod(owner, name, desc);
        if (r == null && t.tokenNamespace()) r = t.tokensByDesc.get(name + desc);
        if (r == null && t.tokenNamespace()) r = t.tokens.get(name);
        if (r == null && t.tokenNamespace()) r = prefixed(name);
        if (r == null) r = name;
        methodCache.put(key, r);
        return r;
    }

    /** Mixin prefixes: shadow$method_N, @Implements iface$method_N. Only the part after the last '$' is a token. */
    private String prefixed(String name) {
        int d = name.lastIndexOf('$');
        if (d <= 0 || d == name.length() - 1) return null;
        String moj = t.tokens.get(name.substring(d + 1));
        return moj == null ? null : name.substring(0, d + 1) + moj;
    }

    private static final Pattern ACCESSOR = Pattern.compile("(get|set|is|invoke|call|new|create)(Field|Method|Comp|F|M)_(\\d+_?)");

    /**
     * Mixin @Accessor/@Invoker without a value infer the target from the method name. Jars remapped without a
     * refmap (Loom "static" remap) carry names like getField_4988. The method keeps its name (callers stay valid)
     * and gets an explicit value with the Mojang target name.
     */
    String inferredTarget(String name) {
        Matcher m = ACCESSOR.matcher(name);
        if (!m.matches()) return null;
        String tok = m.group(2).toLowerCase(Locale.ROOT) + "_" + m.group(3);
        return t.tokens.get(tok);
    }

    /** Mod methods renamed to avoid an accidental override: src owner + "." + name + desc -> new name. */
    final Map<String, String> fixes = new HashMap<>();

    void clearCaches() { methodCache.clear(); }

    private String fixed(String owner, String nd) {
        String r = fixes.get(owner + "." + nd);
        if (r != null) return r;
        for (String s : h.supers(owner)) { r = fixes.get(s + "." + nd); if (r != null) return r; }
        return null;
    }

    private String findMethod(String owner, String name, String desc) {
        String r = lookup(t.methods.get(owner), name + desc);
        if (r != null) return r;
        for (String s : h.supers(owner)) {
            Hierarchy.ClassInfo ci = h.get(s);
            Integer acc = ci == null ? null : ci.macc.get(name + desc);
            if (acc != null && (acc & Opcodes.ACC_PRIVATE) != 0) continue; // private methods are not inherited
            r = lookup(t.methods.get(s), name + desc);
            if (r != null) return r;
        }
        return null;
    }

    @Override public String mapFieldName(String owner, String name, String desc) {
        String key = owner + "." + name + ":" + desc;
        String c = fieldCache.get(key);
        if (c != null) return c;
        String r = findField(owner, name, desc);
        if (r == null && t.tokenNamespace()) r = t.tokensByDesc.get(name + ":" + desc);
        if (r == null && t.tokenNamespace()) r = t.tokens.get(name);
        if (r == null && t.tokenNamespace()) r = prefixed(name);
        if (r == null) r = name;
        fieldCache.put(key, r);
        return r;
    }

    private String findField(String owner, String name, String desc) {
        String r = fieldIn(owner, name, desc);
        if (r != null) return r;
        for (String s : h.supers(owner)) {
            r = fieldIn(s, name, desc);
            if (r != null) return r;
        }
        return null;
    }

    private String fieldIn(String owner, String name, String desc) {
        Map<String, String> m = t.fields.get(owner);
        if (m == null) return null;
        String r = desc == null ? null : m.get(name + ":" + desc);
        return r != null ? r : m.get(name);
    }

    private static String lookup(Map<String, String> m, String k) { return m == null ? null : m.get(k); }

    @Override public String mapRecordComponentName(String owner, String name, String desc) {
        return mapFieldName(owner, name, desc);
    }

    @Override public Object mapValue(Object value) {
        if (value instanceof String && t.tokenNamespace()) return remapString((String) value);
        return super.mapValue(value);
    }

    /**
     * Rewrites a string constant or annotation value that names obfuscated classes or members.
     * Only strings without whitespace are touched, and only known tokens are replaced.
     */
    public String remapString(String s) {
        if (s.isEmpty() || s.length() > 2000) return s;
        for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i))) return s;
        String out = remapText(s);
        if (!out.equals(s)) { stats.strings++; if (DEBUG) System.err.println("string: " + s + " -> " + out); }
        return out;
    }

    /** Token replacement over any text (refmaps, access wideners, access transformers, JS coremods). */
    public String remapText(String s) {
        if (tokenPat == null) return s;
        String out = s;
        if (!t.ambiguous.isEmpty()) out = qualified(out);
        if (classPat != null && out.contains("class_")) {
            Matcher m = classPat.matcher(out);
            StringBuffer b = new StringBuffer();
            while (m.find()) {
                String sep = m.group().contains("/") ? "/" : ".";
                String slash = m.group().replace('.', '/');
                String mapped = map(slash);
                if (mapped.equals(slash)) stats.unknownToken(m.group());
                else if (sep.equals(".")) mapped = mapped.replace('/', '.');
                m.appendReplacement(b, Matcher.quoteReplacement(mapped));
            }
            m.appendTail(b);
            out = b.toString();
        }
        Matcher m = tokenPat.matcher(out);
        if (!m.find()) return out;
        m.reset();
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String moj = t.tokens.get(m.group());
            if (moj == null) { stats.unknownToken(m.group()); moj = m.group(); }
            m.appendReplacement(b, Matcher.quoteReplacement(moj));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** True when the text holds at least one SRG or Intermediary id this table knows (member token or class). */
    public boolean hasKnownId(String s) {
        if (tokenPat == null) return false;
        Matcher m = tokenPat.matcher(s);
        while (m.find()) if (t.tokens.containsKey(m.group()) || t.ambiguous.contains(m.group())) return true;
        if (classPat != null && s.contains("class_")) {
            m = classPat.matcher(s);
            while (m.find()) {
                String slash = m.group().replace('.', '/');
                if (!map(slash).equals(slash)) return true;
            }
        }
        return false;
    }

    /**
     * Fabrication's own name maps (fabRelRefMap.txt, fabAbsRefMap.txt). Each entry is "key value": the key is a Yarn
     * name that Fabrication looks up and must stay as it is, the value holds game ids. fabRelRefMap.txt puts several
     * tab separated entries on one line, fabAbsRefMap.txt one per line. Lines without a space (the mixin class lines)
     * are kept.
     */
    public String remapFabRefMap(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int nl = s.indexOf('\n', i);
            int end = nl < 0 ? s.length() : nl + 1;
            String line = s.substring(i, end);
            String body = line, eol = "";
            if (body.endsWith("\n")) { body = body.substring(0, body.length() - 1); eol = "\n"; }
            if (body.endsWith("\r")) { body = body.substring(0, body.length() - 1); eol = "\r" + eol; }
            String[] entries = body.split("\t", -1);
            for (int k = 0; k < entries.length; k++) {
                int sp = entries[k].indexOf(' ');
                if (sp < 0) continue;
                entries[k] = entries[k].substring(0, sp + 1) + remapText(entries[k].substring(sp + 1));
            }
            out.append(String.join("\t", entries)).append(eol);
            i = end;
        }
        return out.toString();
    }

    private static final Pattern QUAL =Pattern.compile("L([A-Za-z0-9_/$]+);([A-Za-z0-9_$]+)(\\([^)]*\\)[^\"\\s]*|:[^\"\\s]+)?");

    /** Owner-qualified selectors ("Lowner;name(desc)" or "Lowner;name:desc") for ids that are not unique. */
    private String qualified(String s) {
        Matcher m = QUAL.matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String owner = m.group(1), name = m.group(2), rest = m.group(3);
            String rep = m.group();
            if (t.ambiguous.contains(name)) {
                String moj = null;
                if (rest != null && rest.startsWith("(")) moj = findMethod(owner, name, rest);
                else if (rest != null) moj = findField(owner, name, rest.substring(1));
                if (moj == null) moj = findField(owner, name, null);
                if (moj != null) rep = "L" + owner + ";" + moj + (rest == null ? "" : rest);
            }
            m.appendReplacement(b, Matcher.quoteReplacement(rep));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** ClassRemapper that also fixes lambda and record bootstrap names. */
    public ClassVisitor classVisitor(ClassVisitor next) {
        return new ClassRemapper(Opcodes.ASM9, next, this) {
            String handlerDesc;
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                handlerDesc = desc;
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exc);
                String target = t.tokenNamespace() ? inferredTarget(name) : null;
                if (target == null || mv == null) return mv;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override public AnnotationVisitor visitAnnotation(String ad, boolean vis) {
                        AnnotationVisitor av = super.visitAnnotation(ad, vis);
                        if (av == null || !(ad.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")
                                || ad.equals("Lorg/spongepowered/asm/mixin/gen/Invoker;"))) return av;
                        return new AnnotationVisitor(Opcodes.ASM9, av) {
                            boolean hasValue;
                            @Override public void visit(String n, Object v) { if ("value".equals(n)) hasValue = true; super.visit(n, v); }
                            @Override public void visitEnd() {
                                if (!hasValue) { super.visit("value", target); stats.accessorValues++; }
                                super.visitEnd();
                            }
                        };
                    }
                };
            }
            @Override protected MethodVisitor createMethodRemapper(MethodVisitor mv) {
                return new MethodRemapper(Opcodes.ASM9, mv, NsRemapper.this) {
                    final String handler = handlerDesc;
                    @Override protected AnnotationVisitor createAnnotationRemapper(String desc, AnnotationVisitor av) {
                        return new SelectorRemapper(desc, av, handler);
                    }
                    @Override public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
                        String mapped = name;
                        Object[] margs = new Object[args.length];
                        for (int i = 0; i < args.length; i++) margs[i] = NsRemapper.this.mapValue(args[i]);
                        String bo = bsm.getOwner();
                        if ((bo.equals("java/lang/invoke/LambdaMetafactory")) && args.length > 0 && args[0] instanceof Type) {
                            String itf = Type.getReturnType(desc).getInternalName();
                            mapped = mapMethodName(itf, name, ((Type) args[0]).getDescriptor());
                        } else if (bo.equals("java/lang/runtime/ObjectMethods") && args.length >= 2 && args[1] instanceof String) {
                            StringBuilder names = new StringBuilder();
                            for (int i = 2; i < margs.length; i++) {
                                if (!(margs[i] instanceof Handle)) continue;
                                if (names.length() > 0) names.append(';');
                                names.append(((Handle) margs[i]).getName());
                            }
                            if (!((String) args[1]).isEmpty()) margs[1] = names.toString();
                        } else {
                            mapped = NsRemapper.this.mapInvokeDynamicMethodName(name, desc);
                        }
                        if (mv != null) mv.visitInvokeDynamicInsn(mapped, NsRemapper.this.mapMethodDesc(desc),
                                (Handle) NsRemapper.this.mapValue(bsm), margs);
                    }
                };
            }
        };
    }

    /**
     * Mixin "method" selectors that are a bare id (m_104306_, no descriptor). Mixin takes the first method of that
     * name, which is fine while the name is unique, but after the rename to Mojang names an id can share its name
     * with an overload (ModelPart.render has a 4 and an 8 argument form, m_104301_ and m_104306_). For such ids the
     * selector gets the Mojang descriptor so it keeps pointing at the same method. Ids whose name has no overload stay
     * bare, so a descriptor Forge changed still matches.
     */
    private Map<String, String> overloadSel;

    String overloadSelector(String token) {
        if (overloadSel == null) {
            Map<String, String> sel = new HashMap<>();
            Set<String> clash = new HashSet<>();
            for (Map<String, String> ms : t.methods.values()) {
                Map<String, Integer> count = new HashMap<>();
                for (String moj : ms.values()) count.merge(moj, 1, Integer::sum);
                for (Map.Entry<String, String> e : ms.entrySet()) {
                    if (count.get(e.getValue()) < 2) continue;
                    String k = e.getKey();
                    int p = k.indexOf('(');
                    if (p <= 0 || !tokenPat.matcher(k.substring(0, p)).matches()) continue;
                    String v = e.getValue() + Mappings.mapDesc(k.substring(p), t.classes);
                    String old = sel.putIfAbsent(k.substring(0, p), v);
                    if (old != null && !old.equals(v)) clash.add(k.substring(0, p));
                }
            }
            sel.keySet().removeAll(clash);
            for (Map<String, String> ms : t.methods.values()) {
                Set<String> seen = new HashSet<>();
                for (String moj : ms.values()) if (!seen.add(moj)) overloadedNames.add(moj);
            }
            overloadSel = sel;
        }
        return overloadSel.get(token);
    }

    /** Strings the jar's refmaps translate (set per jar by JarRemapper): bare names there are refmap keys, not selectors */
    Set<String> refmapKeys = new HashSet<>();

    /** Mojang method names that have overloads in some game class */
    private final Set<String> overloadedNames = new HashSet<>();
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";

    /**
     * Annotation remapper that gives bare-id "method" selectors a descriptor when the Mojang name is overloaded. Also
     * bare plain names in Forge mods: on Forge the vanilla overloads carry SRG ids, so "dropResources" there can only
     * mean the method Forge added under that name (Fabrication's instant pickup: the 7 argument Block.dropResources).
     * With Mojang names the vanilla overloads share the name and Mixin takes the first. For a callback injector
     * (handler with CallbackInfo, void target) the target arguments are the handler's arguments before the
     * CallbackInfo, so the selector gets that descriptor.
     */
    final class SelectorRemapper extends AnnotationRemapper {
        final String handler;
        SelectorRemapper(String desc, AnnotationVisitor av, String handler) { super(Opcodes.ASM9, desc, av, NsRemapper.this); this.handler = handler; }

        @Override public void visit(String name, Object value) {
            if ("method".equals(name)) value = select(value);
            super.visit(name, value);
        }

        @Override public AnnotationVisitor visitArray(String name) {
            AnnotationVisitor v = super.visitArray(name);
            if (v == null || !"method".equals(name)) return v;
            return new AnnotationVisitor(Opcodes.ASM9, v) {
                @Override public void visit(String n, Object value) { super.visit(n, select(value)); }
            };
        }

        @Override protected AnnotationVisitor createAnnotationRemapper(String desc, AnnotationVisitor av) {
            return new SelectorRemapper(desc, av, handler);
        }

        /** "m_1_" or "Lowner;m_1_" (owner given, no descriptor) */
        private Object select(Object value) {
            if (tokenPat == null || !(value instanceof String)) return value;
            String v = (String) value, owner = "";
            int semi = v.indexOf(';');
            if (v.startsWith("L") && semi > 0) { owner = v.substring(0, semi + 1); v = v.substring(semi + 1); }
            if (!tokenPat.matcher(v).matches()) return plain(owner, v, value);
            String s = overloadSelector(v);
            if (s == null) return value;
            stats.selectorsQualified++;
            return owner + s;
        }

        private Object plain(String owner, String v, Object value) {
            if (handler == null || !PLAIN_NAME.matcher(v).matches() || refmapKeys.contains(v)) return value;
            overloadSelector("");   // builds overloadedNames
            if (!overloadedNames.contains(v)) return value;
            int ci = handler.indexOf(CI);
            if (ci <= 1 || !handler.startsWith("(")) return value;
            stats.selectorsQualified++;
            return owner + v + mapMethodDesc(handler.substring(0, ci) + ")V");
        }
    }

    /** Counters for a remap run. */
    public static final class Stats {
        public int accessorValues, selectorsQualified;
        public final List<String> collisionsFixed = new ArrayList<>(), collisionsReported = new ArrayList<>(), overloadsDropped = new ArrayList<>();
        public int classes, renamedClasses, strings, refmaps, refmapValues, awFiles, atFiles, jsFiles, nestedJars, textFiles, fabRefMaps, resourceFiles;
        public final Map<String, Integer> unknown = new TreeMap<>();
        void unknownToken(String tok) { unknown.merge(tok, 1, Integer::sum); }
    }
}
