package alloy.remap;

import org.objectweb.asm.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Checks a remapped jar against Mojang-named game jars without starting the game: every reference to a game class
 * (instructions, handles, descriptors, super types), every mixin refmap value, and mixin @Shadow / @Overwrite /
 * @Accessor / @Invoker members must resolve. Also counts obfuscated tokens left in names and strings.
 */
public final class Checker {
    final Hierarchy h = new Hierarchy();
    final Set<String> game = new HashSet<>();
    final Set<String> input = new HashSet<>();
    final Map<String, Integer> unresolved = new TreeMap<>();
    final Map<String, Integer> leftovers = new TreeMap<>();
    /** ids left in text resources (not classes): token -> count, and the files that hold them */
    final Map<String, Integer> resourceLeftovers = new TreeMap<>();
    final Set<String> resourceLeftoverFiles = new TreeSet<>();
    int refs, classRefs, refmapValues, refmapBad, shadowChecked, shadowBad, mixins, selectorsChecked, selectorsBad;
    static final Pattern TOKEN = Pattern.compile("(?<![A-Za-z0-9_])(?:(?:method|field|comp)_\\d+|[mf]_\\d+_|class_\\d+)(?![A-Za-z0-9_])");

    boolean gameName(String n) {
        return game.contains(n) || n.startsWith("net/minecraft/") || n.startsWith("com/mojang/blaze3d/")
                || n.startsWith("com/mojang/math/") || n.startsWith("com/mojang/realmsclient/");
    }

    public void run(Path jar, List<Path> gameJars, List<Path> libs, Path report) throws IOException {
        for (Path p : gameJars) game.addAll(h.addJar(p, null, p.getFileName().toString()));
        for (Path p : libs) h.addJar(p, null, p.getFileName().toString());
        List<JarRemapper.Item> classes = new ArrayList<>();
        List<String[]> refmaps = new ArrayList<>();
        collect(JarRemapper.readJar(Files.readAllBytes(jar)), "", classes, refmaps);
        for (String[] rm : refmaps) indexRefmap(rm[1]);
        for (JarRemapper.Item it : classes) premerge(it);
        for (JarRemapper.Item it : classes) checkClass(it);
        for (String[] rm : refmaps) checkRefmap(rm[0], rm[1]);
        if (original != null) collisions(classes, JarRemapper.readJar(Files.readAllBytes(original)));

        int unresolvedTotal = unresolved.values().stream().mapToInt(Integer::intValue).sum();
        StringBuilder sb = new StringBuilder();
        sb.append("jar: ").append(jar).append('\n');
        sb.append(String.format("classes %d, mixins %d, game member refs %d, game class refs %d%n", classes.size(), mixins, refs, classRefs));
        sb.append(String.format("UNRESOLVED: %d distinct, %d sites%n", unresolved.size(), unresolvedTotal));
        sb.append(String.format("refmap values %d, bad %d; mixin shadow/overwrite/accessor checks %d, bad %d%n",
                refmapValues, refmapBad, shadowChecked, shadowBad));
        sb.append(String.format("annotation selectors checked %d, bad %d%n", selectorsChecked, selectorsBad));
        sb.append(String.format("LEFTOVER obfuscated tokens: %d distinct%n", leftovers.size()));
        sb.append(String.format("LEFTOVER ids in resources: %d distinct in %d files%n", resourceLeftovers.size(), resourceLeftoverFiles.size()));
        sb.append(String.format("COLLISIONS (new accidental overrides/merges): %s; PACKAGE ACCESS (new, from mixins): %d%n",
                original == null ? "not checked (no --original)" : String.valueOf(collisions.size()), access.size()));
        String head = sb.toString();
        System.out.print(head);
        int shown = 0;
        for (Map.Entry<String, Integer> e : unresolved.entrySet()) {
            if (shown++ >= 40) { System.out.println("  ... (" + (unresolved.size() - 40) + " more, see report)"); break; }
            System.out.println("  " + e.getKey() + "  x" + e.getValue());
        }
        for (String c : collisions) System.out.println("  collision " + c);
        shown = 0;
        for (String c : access) { if (shown++ >= 20) break; System.out.println("  access " + c); }
        shown = 0;
        for (Map.Entry<String, Integer> e : leftovers.entrySet()) {
            if (shown++ >= 15) { System.out.println("  ..."); break; }
            System.out.println("  leftover " + e.getKey() + "  x" + e.getValue());
        }
        shown = 0;
        for (String f : resourceLeftoverFiles) {
            if (shown++ >= 15) { System.out.println("  ..."); break; }
            System.out.println("  resource with ids " + f);
        }
        if (report != null) {
            StringBuilder r = new StringBuilder(head);
            unresolved.forEach((k, v) -> r.append("unresolved\t").append(k).append('\t').append(v).append('\n'));
            leftovers.forEach((k, v) -> r.append("leftover\t").append(k).append('\t').append(v).append('\n'));
            resourceLeftovers.forEach((k, v) -> r.append("resource-leftover\t").append(k).append('\t').append(v).append('\n'));
            resourceLeftoverFiles.forEach(k -> r.append("resource-file\t").append(k).append('\n'));
            collisions.forEach(k -> r.append("collision\t").append(k).append('\n'));
            access.forEach(k -> r.append("access\t").append(k).append('\n'));
            Files.createDirectories(report.toAbsolutePath().getParent());
            Files.writeString(report, r.toString());
        }
        h.close();
    }

    private void collect(List<JarRemapper.Item> items, String where, List<JarRemapper.Item> classes, List<String[]> refmaps) throws IOException {
        for (JarRemapper.Item it : items) {
            if (!it.name.endsWith(".class") && !JarRemapper.isJar(it.name)) resourceIds(where + it.name, it.name, it.data);
            if (it.name.endsWith(".class") && !it.name.startsWith("META-INF/") && !it.name.endsWith("module-info.class")) {
                String cn = new ClassReader(it.data).getClassName();
                input.add(cn);
                h.addClass(cn, it.data, "input");
                classes.add(it);
            } else if (JarRemapper.isJar(it.name)) {
                collect(JarRemapper.readJar(it.data), where + it.name + "!", classes, refmaps);
            } else if (it.name.endsWith("fabric.mod.json")) {
                fabricJar = true;
            } else if (it.name.endsWith(".accesswidener") || it.name.endsWith(".classtweaker")) {
                readWidener(new String(it.data, StandardCharsets.UTF_8));
            } else if (it.name.endsWith("refmap.json")) {
                refmaps.add(new String[]{where + it.name, new String(it.data, StandardCharsets.UTF_8)});
            }
        }
    }

    /**
     * Counts SRG and Intermediary ids left in a text resource (refmaps, Fabrication maps, configs, anything but lang and
     * mapping files). A Mojang-named jar should have none; a hit means some file format the translator did not rewrite.
     */
    void resourceIds(String where, String name, byte[] data) {
        if (JarRemapper.skipResource(name) || data.length > 16 << 20) return;
        if (name.toUpperCase(Locale.ROOT).equals("META-INF/MANIFEST.MF")) return;
        String s = JarRemapper.textOf(data);
        if (s == null || JarRemapper.isMappingFile(s)) return;
        if (name.endsWith("fabRelRefMap.txt") || name.endsWith("fabAbsRefMap.txt")) s = fabValues(s);
        Matcher m = TOKEN.matcher(s);
        boolean any = false;
        while (m.find()) { resourceLeftovers.merge(m.group(), 1, Integer::sum); any = true; }
        if (any) resourceLeftoverFiles.add(where);
    }

    /** Only the values of a Fabrication map (the keys are the mod's own Yarn names and never change). */
    static String fabValues(String s) {
        StringBuilder b = new StringBuilder();
        for (String line : s.split("\r?\n"))
            for (String e : line.split("\t")) {
                int sp = e.indexOf(' ');
                if (sp >= 0) b.append(e, sp + 1, e.length()).append('\n');
            }
        return b.toString();
    }

    /** Distinct ids left in resources (for callers that fail a pack on them). */
    public int resourceLeftoverCount() { return resourceLeftovers.size(); }

    private void miss(String what, String from) {
        unresolved.merge(what, 1, Integer::sum);
    }

    private void leftover(String s) {
        if (s.indexOf(' ') >= 0) return; // free text (comments in @Dynamic and similar), not a name
        Matcher m = TOKEN.matcher(s);
        while (m.find()) leftovers.merge(m.group(), 1, Integer::sum);
    }

    private static String elem(String owner) {
        if (!owner.startsWith("[")) return owner;
        Type t = Type.getType(owner).getElementType();
        return t.getSort() == Type.OBJECT ? t.getInternalName() : null;
    }

    private void classRef(String n, String from) {
        if (n == null) return;
        n = elem(n);
        if (n == null || !gameName(n)) return;
        classRefs++;
        leftover(n);
        if (!h.has(n)) miss("class " + n, from);
    }

    private void descRefs(String desc, String from) {
        if (desc == null) return;
        Type t = desc.startsWith("(") ? Type.getMethodType(desc) : Type.getType(desc);
        if (t.getSort() == Type.METHOD) {
            for (Type a : t.getArgumentTypes()) typeRef(a, from);
            typeRef(t.getReturnType(), from);
        } else typeRef(t, from);
    }

    private void typeRef(Type t, String from) {
        if (t.getSort() == Type.ARRAY) t = t.getElementType();
        if (t.getSort() == Type.OBJECT) classRef(t.getInternalName(), from);
    }

    /** True when the owner is a game class or an input class with a game super type. */
    private boolean checkable(String owner) {
        if (gameName(owner)) return true;
        if (!input.contains(owner)) return false;
        for (String s : h.supers(owner)) if (gameName(s)) return true;
        return false;
    }

    boolean hasMethod(String owner, String name, String desc) {
        Hierarchy.ClassInfo ci = h.get(owner);
        if (ci == null) return false;
        if (desc == null ? ci.methodNames.contains(name) : ci.methods.contains(name + desc)) return true;
        if (name.startsWith("<")) return false;
        for (String s : h.supers(owner)) {
            Hierarchy.ClassInfo c = h.get(s);
            if (c != null && (desc == null ? c.methodNames.contains(name) : c.methods.contains(name + desc))) return true;
        }
        // signature polymorphic and unknown library supers: be lenient if a super is missing
        for (String s : h.supers(owner)) if (h.get(s) == null && !gameName(s)) return true;
        return false;
    }

    boolean hasField(String owner, String name, String desc) {
        Hierarchy.ClassInfo ci = h.get(owner);
        if (ci == null) return false;
        if (desc == null ? ci.fieldNames.contains(name) : ci.fields.contains(name + ":" + desc)) return true;
        for (String s : h.supers(owner)) {
            Hierarchy.ClassInfo c = h.get(s);
            if (c != null && (desc == null ? c.fieldNames.contains(name) : c.fields.contains(name + ":" + desc))) return true;
        }
        for (String s : h.supers(owner)) if (h.get(s) == null && !gameName(s)) return true;
        return false;
    }

    private void memberRef(boolean method, String owner, String name, String desc, String from) {
        owner = elem(owner);
        if (owner == null) return;
        if (method && owner.equals("java/lang/Object")) return;
        if (!checkable(owner)) return;
        refs++;
        leftover(name);
        if (!h.has(owner)) { miss("class " + owner, from); return; }
        boolean ok = method ? hasMethod(owner, name, desc) : hasField(owner, name, desc);
        if (ok && fabricJar && curTargets != null && !curTargets.isEmpty()) accessCheck(method, owner, name, desc, from);
        if (!ok) miss((method ? "method " : "field ") + owner + "." + name + (method ? desc : ":" + desc), from);
    }

    private void handle(Handle hd, String from) {
        boolean m = hd.getTag() >= Opcodes.H_INVOKEVIRTUAL;
        memberRef(m, hd.getOwner(), hd.getName(), hd.getDesc(), from);
    }

    private void constant(Object v, String from) {
        if (v instanceof Type) {
            Type t = (Type) v;
            if (t.getSort() == Type.METHOD) descRefs(t.getDescriptor(), from); else typeRef(t, from);
        } else if (v instanceof Handle) handle((Handle) v, from);
        else if (v instanceof String) leftover((String) v);
        else if (v instanceof ConstantDynamic) {
            ConstantDynamic c = (ConstantDynamic) v;
            handle(c.getBootstrapMethod(), from);
            for (int i = 0; i < c.getBootstrapMethodArgumentCount(); i++) constant(c.getBootstrapMethodArgument(i), from);
        }
    }

    private void checkClass(JarRemapper.Item it) {
        ClassReader cr = new ClassReader(it.data);
        final String cls = cr.getClassName();
        curTargets = mixinTargets.get(cls);
        final List<String> targets = new ArrayList<>();
        final List<Object[]> members = new ArrayList<>(); // kind, name, desc, annotationValue
        final List<String[]> selectors = new ArrayList<>(); // attribute (method|target), value
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public void visit(int v, int a, String n, String s, String sup, String[] itf) {
                classRef(sup, cls);
                if (itf != null) for (String i : itf) classRef(i, cls);
            }
            @Override public AnnotationVisitor visitAnnotation(String desc, boolean vis) {
                descRefs(desc, cls);
                if (desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) {
                    mixins++;
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override public AnnotationVisitor visitArray(String name) {
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override public void visit(String n, Object v) {
                                    if (v instanceof Type) targets.add(((Type) v).getInternalName());
                                    else if (v instanceof String) targets.add(mixinTarget(cls, (String) v));
                                }
                            };
                        }
                    };
                }
                return stringScanner();
            }
            @Override public FieldVisitor visitField(int a, String n, String d, String s, Object v) {
                descRefs(d, cls);
                if (v instanceof String) leftover((String) v);
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String ad, boolean vis) {
                        if (ad.equals("Lorg/spongepowered/asm/mixin/Shadow;")) {
                            Object[] m = {"field", n, d, null, "shadow$"};
                            members.add(m);
                            return prefixReader(m);
                        }
                        return stringScanner();
                    }
                };
            }
            @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                descRefs(d, cls);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String ad, boolean vis) {
                        Object[] m = null;
                        switch (ad) {
                            case "Lorg/spongepowered/asm/mixin/Shadow;": m = new Object[]{"method", n, d, null, "shadow$"}; break;
                            case "Lorg/spongepowered/asm/mixin/Overwrite;": m = new Object[]{"method", n, d, null, null}; break;
                            case "Lorg/spongepowered/asm/mixin/gen/Accessor;": m = new Object[]{"accessor", n, d, null, null}; break;
                            case "Lorg/spongepowered/asm/mixin/gen/Invoker;": m = new Object[]{"invoker", n, d, null, null}; break;
                            default: return selectorScanner(selectors, null);
                        }
                        members.add(m);
                        return prefixReader(m);
                    }
                    @Override public AnnotationVisitor visitParameterAnnotation(int p, String ad, boolean vis) { return stringScanner(); }
                    @Override public void visitMethodInsn(int op, String o, String nm, String ds, boolean itf) {
                        memberRef(true, o, nm, ds, cls); classRef(o, cls); descRefs(ds, cls);
                    }
                    @Override public void visitFieldInsn(int op, String o, String nm, String ds) {
                        memberRef(false, o, nm, ds, cls); classRef(o, cls); descRefs(ds, cls);
                    }
                    @Override public void visitTypeInsn(int op, String t) { classRef(t, cls); }
                    @Override public void visitMultiANewArrayInsn(String ds, int dims) { descRefs(ds, cls); }
                    @Override public void visitTryCatchBlock(Label s, Label e, Label h, String t) { classRef(t, cls); }
                    @Override public void visitLdcInsn(Object v) { constant(v, cls); }
                    @Override public void visitInvokeDynamicInsn(String nm, String ds, Handle bsm, Object... args) {
                        descRefs(ds, cls); handle(bsm, cls);
                        for (Object o : args) constant(o, cls);
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        if (targets.isEmpty()) return;
        Map<String, String> rm = refmap.getOrDefault(cls, Collections.emptyMap());
        for (Object[] m : members) {
            String kind = (String) m[0], name = (String) m[1], desc = (String) m[2], value = (String) m[3];
            String pre = (String) m[4];
            String look;
            switch (kind) {
                case "field": case "method":
                    look = pre != null && name.startsWith(pre) ? name.substring(pre.length()) : name; break;
                case "accessor": look = value != null && !value.isEmpty() ? value : accessorName(name); break;
                default: look = value != null && !value.isEmpty() ? value : invokerName(name);
            }
            String lookDesc = kind.equals("field") || kind.equals("method") ? desc : null;
            String mapped = rm.get(look);
            if (mapped != null) { // refmap value: [Lowner;]name(desc) or [Lowner;]name:desc
                String v = mapped;
                if (v.startsWith("L") && v.indexOf(';') > 0) v = v.substring(v.indexOf(';') + 1);
                int c = v.indexOf(':'), p2 = v.indexOf('(');
                int cut = c >= 0 ? c : p2;
                look = cut >= 0 ? v.substring(0, cut) : v;
                lookDesc = cut < 0 ? null : (c >= 0 ? v.substring(c + 1) : v.substring(p2));
            }
            boolean fieldLike = kind.equals("field") || kind.equals("accessor");
            boolean any = false, ok = false;
            for (String target : targets) {
                if (!gameName(target)) continue;
                any = true;
                if (fieldLike ? hasField(target, look, lookDesc) || hasField(target, look, null)
                              : hasMethod(target, look, lookDesc) || hasMethod(target, look, null)) ok = true;
            }
            if (!any) continue;
            shadowChecked++;
            leftover(look);
            if (!ok) { shadowBad++; miss("mixin " + kind + " " + String.join(",", targets) + "." + look + " (in " + cls + ")", cls); }
        }
        // injector selectors written directly in the annotation (jars without refmap, or names the refmap skips)
        for (String[] sel : selectors) {
            String v = sel[1];
            if (rm.containsKey(v) || v.isEmpty() || v.indexOf('*') >= 0 || v.indexOf('=') >= 0 || v.startsWith("@")
                    || v.startsWith("^") || v.indexOf(' ') >= 0) continue;
            Matcher mm = MEMBER.matcher(v);
            boolean ok, any = false;
            if (mm.matches()) {
                String owner = mm.group(1), n = mm.group(2), rest = mm.group(3);
                if (!gameName(owner)) continue;
                any = true;
                if (!h.has(owner)) ok = false;
                else if (rest == null) ok = hasMethod(owner, n, null) || hasField(owner, n, null);
                else if (rest.startsWith(":")) ok = hasField(owner, n, rest.substring(1));
                else ok = hasMethod(owner, n, rest);
            } else if (sel[0].equals("method")) {
                int p2 = v.indexOf('(');
                String n = p2 >= 0 ? v.substring(0, p2) : v, d = p2 >= 0 ? v.substring(p2) : null;
                ok = false;
                for (String target : targets) {
                    if (!gameName(target)) continue;
                    any = true;
                    if (n.equals("<init>") || n.equals("<clinit>")) { Hierarchy.ClassInfo ci = h.get(target);
                        if (ci != null && (d == null ? ci.methodNames.contains(n) : ci.methods.contains(n + d))) ok = true; }
                    else if (hasMethod(target, n, d)) ok = true;
                }
            } else continue;
            if (!any) continue;
            selectorsChecked++;
            if (!ok) { selectorsBad++; miss("selector " + sel[0] + "=" + v + " (in " + cls + ")", cls); }
        }
        // ownerless refmap values of this mixin ("name(desc)", "name:desc") must exist in a target
        for (String v : rm.values()) {
            if (v.startsWith("L")) continue;
            int c = v.indexOf(':'), p2 = v.indexOf('(');
            if (c < 0 && p2 < 0) continue;
            boolean field = c >= 0 && (p2 < 0 || c < p2);
            String n = v.substring(0, field ? c : p2), d = v.substring(field ? c + 1 : p2);
            if (n.isEmpty() || n.indexOf('/') >= 0) continue;
            boolean any = false, ok = false;
            for (String target : targets) {
                if (!gameName(target)) continue;
                any = true;
                if (field ? hasField(target, n, d) : hasMethod(target, n, d)) ok = true;
            }
            if (!any) continue;
            refmapValues++;
            if (!ok) { refmapBad++; miss("refmap " + String.join(",", targets) + "." + v + " (in " + cls + ")", cls); }
        }
    }

    // ---------------------------------------------------------------- mixin merge (interface injection, added members)

    private String mixinTarget(String mixin, String s) {
        Map<String, String> rm = refmap.getOrDefault(mixin, Collections.emptyMap());
        String v = rm.get(s);
        if (v == null) v = rm.get(s.replace('.', '/'));
        return (v != null ? v : s).replace('.', '/');
    }

    private void premerge(JarRemapper.Item it) {
        ClassReader cr = new ClassReader(it.data);
        String cls = cr.getClassName();
        List<String> targets = new ArrayList<>();
        Set<String> methods = new LinkedHashSet<>(), fields = new LinkedHashSet<>();
        boolean[] isMixin = {false};
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public AnnotationVisitor visitAnnotation(String desc, boolean vis) {
                if (!desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) return null;
                isMixin[0] = true;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitArray(String name) {
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override public void visit(String n, Object v) {
                                if (v instanceof Type) targets.add(((Type) v).getInternalName());
                                else if (v instanceof String) targets.add(mixinTarget(cls, (String) v));
                            }
                        };
                    }
                };
            }
            @Override public FieldVisitor visitField(int a, String n, String d, String s, Object v) {
                return new FieldVisitor(Opcodes.ASM9) {
                    boolean shadow;
                    @Override public AnnotationVisitor visitAnnotation(String ad, boolean vis) {
                        if (ad.equals("Lorg/spongepowered/asm/mixin/Shadow;")) shadow = true;
                        return null;
                    }
                    @Override public void visitEnd() { if (!shadow) fields.add(n + ":" + d); }
                };
            }
            @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                    boolean skip;
                    @Override public AnnotationVisitor visitAnnotation(String ad, boolean vis) {
                        if (ad.equals("Lorg/spongepowered/asm/mixin/Shadow;") || ad.equals("Lorg/spongepowered/asm/mixin/Overwrite;")) skip = true;
                        return null;
                    }
                    @Override public void visitEnd() { if (!skip && !n.startsWith("<")) methods.add(n + d); }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        if (!isMixin[0]) return;
        mixinTargets.put(cls, new ArrayList<>(targets));
        boolean accessorMixin = (cr.getAccess() & Opcodes.ACC_INTERFACE) != 0;
        List<String> itfs = accessorMixin ? Collections.singletonList(cls) : Arrays.asList(cr.getInterfaces());
        for (String t : targets) {
            if (accessorMixin) h.merge(t, itfs, Collections.emptyList(), Collections.emptyList());
            else h.merge(t, itfs, methods, fields);
        }
    }

    // ---------------------------------------------------------------- refmap index

    final Map<String, Map<String, String>> refmap = new HashMap<>();
    private static final Pattern BLOCK = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\{([^{}]*)\\}");

    private static final Pattern DATA = Pattern.compile("\"data\"\\s*:\\s*\\{");

    /** Start of the top-level "data" object (not an accessor key that happens to be called data). */
    static int dataBlock(String text) {
        Matcher m = DATA.matcher(text);
        return m.find() ? m.start() : -1;
    }

    private void indexRefmap(String text) {
        int dataAt = dataBlock(text);
        String body = dataAt > 0 ? text.substring(0, dataAt) : text;
        Matcher b = BLOCK.matcher(body);
        while (b.find()) {
            Map<String, String> m = refmap.computeIfAbsent(b.group(1), k -> new HashMap<>());
            Matcher p = PAIR.matcher(b.group(2));
            while (p.find()) m.putIfAbsent(p.group(1), p.group(2));
        }
    }

    static String accessorName(String n) {
        for (String p : new String[]{"get", "set", "is"}) if (n.startsWith(p) && n.length() > p.length()) {
            String r = n.substring(p.length());
            if (r.equals(r.toUpperCase(Locale.ROOT))) return r;
            return Character.toLowerCase(r.charAt(0)) + r.substring(1);
        }
        return n;
    }

    static String invokerName(String n) {
        for (String p : new String[]{"invoke", "call", "new", "create"}) if (n.startsWith(p) && n.length() > p.length())
            return Character.toLowerCase(n.charAt(p.length())) + n.substring(p.length() + 1);
        return n;
    }

    private AnnotationVisitor prefixReader(Object[] m) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override public void visit(String name, Object v) {
                if ("prefix".equals(name) && v instanceof String) m[4] = v;
                if ("value".equals(name) && v instanceof String) m[3] = v;
                if (v instanceof String) leftover((String) v);
            }
            @Override public AnnotationVisitor visitArray(String name) { return stringScanner(); }
            @Override public AnnotationVisitor visitAnnotation(String name, String d) { return stringScanner(); }
        };
    }

    private AnnotationVisitor selectorScanner(List<String[]> out, String arrayName) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override public void visit(String name, Object v) {
                if (!(v instanceof String)) return;
                leftover((String) v);
                String n = name != null ? name : arrayName;
                if ("method".equals(n) || "target".equals(n)) out.add(new String[]{n, (String) v});
            }
            @Override public AnnotationVisitor visitArray(String name) { return selectorScanner(out, name); }
            @Override public AnnotationVisitor visitAnnotation(String name, String d) { return selectorScanner(out, null); }
        };
    }

    private AnnotationVisitor stringScanner() {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override public void visit(String name, Object v) { if (v instanceof String) leftover((String) v); }
            @Override public AnnotationVisitor visitArray(String name) { return this; }
            @Override public AnnotationVisitor visitAnnotation(String name, String d) { return this; }
        };
    }

    // ---------------------------------------------------------------- refmaps

    private static final Pattern PAIR = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern MEMBER = Pattern.compile("^L([^;]+);([^(:]+)(\\(.*|:.*)?$");

    private void checkRefmap(String where, String text) {
        // only the "mappings" block (the "data" block repeats it per environment)
        int dataAt = dataBlock(text);
        String body = dataAt > 0 ? text.substring(0, dataAt) : text;
        Matcher m = PAIR.matcher(body);
        while (m.find()) {
            String v = m.group(2);
            refmapValues++;
            leftover(v);
            Matcher mm = MEMBER.matcher(v);
            if (mm.matches()) {
                String owner = mm.group(1), name = mm.group(2), rest = mm.group(3);
                if (!gameName(owner)) continue;
                boolean ok;
                if (rest == null) ok = hasMethod(owner, name, null) || hasField(owner, name, null);
                else if (rest.startsWith(":")) ok = hasField(owner, name, rest.substring(1));
                else ok = hasMethod(owner, name, rest);
                if (!h.has(owner)) ok = false;
                if (!ok) { refmapBad++; miss("refmap " + v + " (" + where + ")", where); }
            } else if (v.startsWith("L") && v.endsWith(";") && v.indexOf('(') < 0) {
                String c = v.substring(1, v.length() - 1);
                if (gameName(c) && !h.has(c)) { refmapBad++; miss("refmap class " + c + " (" + where + ")", where); }
            } else if (v.indexOf('(') < 0 && v.indexOf(':') < 0 && v.indexOf('/') > 0) {
                if (gameName(v) && !h.has(v)) { refmapBad++; miss("refmap class " + v + " (" + where + ")", where); }
            }
        }
    }

    // ---------------------------------------------------------------- collisions and package access

    Path original;
    Set<String> renamedGame = Collections.emptySet(); // Mojang owner.name+desc of game methods whose name was obfuscated
    final List<String> collisions = new ArrayList<>();
    final Set<String> access = new TreeSet<>();
    final Map<String, List<String>> mixinTargets = new HashMap<>();
    final Set<String> widened = new HashSet<>();
    boolean fabricJar;
    List<String> curTargets;

    private void readWidener(String text) {
        for (String l : text.split("\r?\n")) {
            String[] p = l.trim().split("\\s+");
            if (p.length < 3 || l.trim().startsWith("#")) continue;
            String kind = p[1];
            if (kind.equals("class")) widened.add(p[2]);
            else if (p.length >= 5) widened.add(p[2] + "." + p[3] + (kind.equals("method") ? p[4] : ":" + p[4]));
        }
    }

    private static String pkg(String n) { int i = n.lastIndexOf('/'); return i < 0 ? "" : n.substring(0, i); }

    /** Intermediary puts all game classes in one package; Mojang names split them. Mixin code merged into a target
     *  that touches a package-private member of a class in another Mojang package now fails with IllegalAccessError. */
    private void accessCheck(boolean method, String owner, String name, String desc, String from) {
        String key = method ? name + desc : name + ":" + desc;
        Hierarchy.ClassInfo decl = h.declaring(owner, key);
        if (decl == null || !gameName(decl.name)) return;
        int a = decl.macc.get(key);
        if ((a & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE)) != 0) return;
        if (widened.contains(decl.name + "." + key)) return;
        for (String t : curTargets) {
            if (!gameName(t) || pkg(t).equals(pkg(decl.name))) continue;
            access.add((method ? "method " : "field ") + decl.name + "." + key + " used from mixin " + from + " into " + t);
        }
    }

    /**
     * A mod method that kept its name (no token) but now has the Mojang name and descriptor of an obfuscated game
     * method in a super type (or mixin target) overrides it by accident. The same for mixin fields and methods
     * without mixin annotations, which merge into the target.
     */
    private void collisions(List<JarRemapper.Item> out, List<JarRemapper.Item> orig) throws IOException {
        Map<String, byte[]> before = new HashMap<>();
        collectOrig(orig, before);
        for (JarRemapper.Item it : out) {
            ClassReader cr = new ClassReader(it.data);
            String cls = cr.getClassName();
            if (gameName(cls)) continue;
            byte[] ob = before.get(cls);
            if (ob == null) continue;
            List<String[]> now = members(cr), was = members(new ClassReader(ob));
            if (now.size() != was.size()) continue;
            List<String> targets = mixinTargets.getOrDefault(cls, Collections.emptyList());
            List<String> sup = new ArrayList<>(h.supers(cls));
            for (String t : targets) { sup.add(t); sup.addAll(h.supers(t)); }
            for (int i = 0; i < now.size(); i++) {
                String[] m = now.get(i);
                int acc = Integer.parseInt(m[3]);
                boolean field = m[0].equals("f");
                if (!m[1].equals(was.get(i)[1])) continue;                 // renamed by remap: intended
                if (m[1].startsWith("<") || (acc & Opcodes.ACC_PRIVATE) != 0 && targets.isEmpty()) continue;
                if ((acc & Opcodes.ACC_SYNTHETIC) != 0) continue;                // lambdas: Mixin renames them
                if (!targets.isEmpty() && m[4].equals("annotated")) continue; // @Shadow/@Unique/injectors
                if (targets.isEmpty() && (field || (acc & Opcodes.ACC_STATIC) != 0)) continue;
                String key = field ? m[1] + ":" + m[2] : m[1] + m[2];
                for (String s : sup) {
                    if (!gameName(s)) continue;
                    Hierarchy.ClassInfo ci = h.get(s);
                    if (ci == null || !ci.macc.containsKey(key)) continue;
                    if ((ci.macc.get(key) & Opcodes.ACC_PRIVATE) != 0 && targets.isEmpty()) continue;
                    if (!renamedGame.isEmpty() && !renamedGame.contains(s + "." + key)) continue; // name was never obfuscated
                    collisions.add(cls + "." + key + " now matches " + s + "." + key);
                    break;
                }
            }
        }
    }

    private void collectOrig(List<JarRemapper.Item> items, Map<String, byte[]> into) throws IOException {
        for (JarRemapper.Item it : items) {
            if (it.name.endsWith(".class") && !it.name.startsWith("META-INF/")) {
                try { into.put(new ClassReader(it.data).getClassName(), it.data); } catch (RuntimeException ignored) { }
            } else if (JarRemapper.isJar(it.name)) collectOrig(JarRemapper.readJar(it.data), into);
        }
    }

    /** kind (m/f), name, desc, access, "annotated" if it has a Mixin annotation. Order is the class file order. */
    private static List<String[]> members(ClassReader cr) {
        List<String[]> out = new ArrayList<>();
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public FieldVisitor visitField(int a, String n, String d, String s, Object v) {
                String[] m = {"f", n, d, String.valueOf(a), ""};
                out.add(m);
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String ad, boolean vis) {
                        if (ad.startsWith("Lorg/spongepowered/") || ad.startsWith("Lcom/llamalad7/")) m[4] = "annotated";
                        return null;
                    }
                };
            }
            @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                String[] m = {"m", n, d, String.valueOf(a), ""};
                out.add(m);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String ad, boolean vis) {
                        if (ad.startsWith("Lorg/spongepowered/") || ad.startsWith("Lcom/llamalad7/")) m[4] = "annotated";
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE);
        return out;
    }
}
