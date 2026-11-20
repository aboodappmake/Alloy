import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * Alloy game-jar prep: builds the jar that Fabric Loader runs as "Minecraft".
 *
 * Input: Mojang's vanilla server jar, the Forge-patched server jar (made locally by the Forge installer),
 * and Forge access-transformer files (from Forge itself and from each Forge mod).
 * Output: the Forge-patched jar with
 *   1. MethodParameters attributes restored from vanilla (Forge's recompiled classes lack them, and Fabric Loader
 *      >= 25w14a uses them to find the server entrypoint lambda), and
 *   2. access transformers applied statically (Fabric has no runtime AT support; doing it at prep time keeps the
 *      runtime bridge simple).
 *
 * usage: java PrepGame <vanilla.jar> <forge-patched.jar> <out.jar> [at.cfg | mod.jar ...]
 *        (a mod jar contributes META-INF/accesstransformer.cfg if present)
 */
public class PrepGame {
    record AT(String cls, String member, String desc, int acc, int finalMode) {} // finalMode: 0 keep, 1 remove (-f), 2 add (+f)

    public static void main(String[] a) throws Exception {
        Path vanilla = Path.of(a[0]), forge = Path.of(a[1]), out = Path.of(a[2]);
        List<AT> ats = new ArrayList<>();
        for (int i = 3; i < a.length; i++) ats.addAll(readAts(Path.of(a[i])));
        for (int i = 3; i < a.length; i++) if (a[i].endsWith(".jar")) ANCHOR_JARS.add(Path.of(a[i]));   // Forge universal jar: source of the hook bodies we inline
        HIER_JARS.add(forge); HIER_JARS.addAll(ANCHOR_JARS);
        keepModTargets(ANCHOR_JARS);
        for (String h : System.getProperty("alloy.hier", "").split(";")) if (!h.isBlank()) HIER_JARS.add(Path.of(h));
        Map<String, List<AT>> atByClass = new HashMap<>();
        for (AT t : ats) atByClass.computeIfAbsent(t.cls, k -> new ArrayList<>()).add(t);
        Store store = Store.open(System.getProperty("alloy.gamejar.store"), a, vanilla, forge, out, ats);
        if (store != null && store.restore()) return;
        try {
            build(vanilla, forge, out, ats, atByClass);
        } finally {
            if (store != null) store.endCapture();
        }
        if (store != null) store.save();
    }

    static void build(Path vanilla, Path forge, Path out, List<AT> ats, Map<String, List<AT>> atByClass) throws Exception {

        Map<String, List<ParameterNode>> vParams = new HashMap<>();
        Map<String, List<LocalVariableNode>> vLocals = new HashMap<>();
        Map<String, Map<String, Integer>> vOrder = new HashMap<>();
        try (JarFile v = new JarFile(vanilla.toFile(), false)) {
            for (JarEntry e : Collections.list(v.entries())) {
                if (!e.getName().endsWith(".class")) continue;
                ClassNode cn = new ClassNode();
                new ClassReader(v.getInputStream(e).readAllBytes()).accept(cn, ClassReader.SKIP_FRAMES);
                for (MethodNode m : cn.methods) {
                    if (m.parameters != null) vParams.put(cn.name + "." + m.name + m.desc, m.parameters);
                    if (m.localVariables != null && !m.localVariables.isEmpty()) vLocals.put(cn.name + "." + m.name + m.desc, m.localVariables);
                }
                Map<String, Integer> idx = new HashMap<>();   // first index of each name+desc, as List.indexOf gave
                for (MethodNode m : cn.methods) idx.putIfAbsent(m.name + m.desc, idx.size());
                vOrder.put(cn.name, idx);
            }
        }
        loadForgeTransformers();
        if (AUTO) Auto.init(vanilla, forge, ANCHOR_JARS.isEmpty() ? null : ANCHOR_JARS.get(0));
        int restored = 0, atApplied = 0, classes = 0, reordered = 0, compatFixes = 0, anchorFixes = 0;
        Set<String> atHit = new HashSet<>();
        // write to a temp file and move it into place at the end: an interrupted run never leaves a broken game jar
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        try (JarFile f = new JarFile(forge.toFile(), false);
             JarOutputStream jo = new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20))) {
            for (JarEntry e : Collections.list(f.entries())) {
                byte[] b = f.getInputStream(e).readAllBytes();
                String n = e.getName();
                if (n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA"))) continue;
                if (n.endsWith(".class")) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(b).accept(cn, 0);
                    boolean changed = false;
                    // Fabric Loader's entrypoint patcher takes the *first* matching synthetic lambda in Main; Forge's
                    // recompile inserts new lambdas earlier. Keep vanilla's method order, Forge-added methods after.
                    Map<String, Integer> order = vOrder.get(cn.name);
                    if (order != null) {
                        List<MethodNode> sorted = new ArrayList<>(cn.methods);
                        sorted.sort(Comparator.comparingInt(m -> order.getOrDefault(m.name + m.desc, Integer.MAX_VALUE)));
                        if (!sorted.equals(cn.methods)) { cn.methods = sorted; reordered++; changed = true; }
                    }
                    for (MethodNode m : cn.methods) {
                        List<ParameterNode> p = vParams.get(cn.name + "." + m.name + m.desc);
                        if (m.parameters == null && p != null) { m.parameters = new ArrayList<>(p); restored++; changed = true; }
                        // Forge's decompile/recompile renamed locals (level -> levelx, e -> var5); Fabric mixins that
                        // capture @Local(name=...) need vanilla's names. Restore a name when every vanilla local in the
                        // same slot with the same type has that one name.
                        List<LocalVariableNode> vl = vLocals.get(cn.name + "." + m.name + m.desc);
                        if (vl != null && m.localVariables != null) {
                            for (LocalVariableNode lv : m.localVariables) {
                                Set<String> names = new HashSet<>();
                                for (LocalVariableNode o : vl) if (o.index == lv.index && o.desc.equals(lv.desc)) names.add(o.name);
                                if (names.size() == 1) {
                                    String nm = names.iterator().next();
                                    if (!nm.equals(lv.name) && m.localVariables.stream().noneMatch(x -> x != lv && x.name.equals(nm) && x.index != lv.index)) {
                                        lv.name = nm; localsRenamed++; changed = true;
                                    }
                                }
                            }
                        }
                    }
                    if (FORGE_TRANSFORMERS.containsKey(cn.name.replace('/', '.'))) {
                        for (Object t : FORGE_TRANSFORMERS.get(cn.name.replace('/', '.'))) {
                            try {
                                Object r = TRANSFORM.invoke(t, cn, null);
                                if (r instanceof ClassNode rn) cn = rn;
                                forgeTransformed++; changed = true;
                                if (Boolean.getBoolean("alloy.transformers.list")) System.out.println("  transformed " + cn.name);
                            } catch (Exception ex) {
                                System.out.println("  forge transformer failed on " + cn.name + ": " + (ex.getCause() != null ? ex.getCause() : ex));
                            }
                        }
                    }
                    if (compat(cn)) { compatFixes++; changed = true; }
                    Set<String> ovBefore = new HashSet<>();      // overload record: methods present before the anchors run
                    for (MethodNode m : cn.methods) ovBefore.add(m.name + m.desc);
                    Map<String, Integer> sizes = null;           // method sizes before the hand-written anchors: rewritten methods are "owned"
                    if (AUTO && ANCHORS) { sizes = new HashMap<>(); for (MethodNode m : cn.methods) sizes.put(m.name + m.desc, m.instructions.size()); }
                    boolean anchored = ANCHORS && anchors(cn);   // -Dalloy.anchors=true (testing; becomes default once the matrices pass)
                    if (anchored) { anchorFixes++; changed = true; }
                    if (AUTO) {                                  // -Dalloy.autoanchors=true: runs after the hand-written anchors
                        Set<String> owned = new HashSet<>();
                        if (anchored && sizes != null) for (MethodNode m : cn.methods) { Integer o = sizes.get(m.name + m.desc); if (o == null || o != m.instructions.size()) owned.add(m.name + m.desc); }
                        ClassNode auto = Auto.run(cn, b, owned);
                        if (auto != null) { cn = auto; anchored = true; changed = true; }
                    }
                    recordOverloads(cn, ovBefore);
                    List<AT> list = atByClass.get(cn.name);
                    if (list != null) for (AT t0 : list) { AT t = translateAt(t0); if (apply(cn, t)) { atApplied++; changed = true; atHit.add(t0.toString()); } }
                    if (changed) {
                        // stack shapes are unchanged by every step here (forge-transformers swap GETFIELD->INVOKEVIRTUAL
                        // and invoke->invoke with the same arguments); COMPUTE_MAXS keeps existing frames
                        // anchored classes got new branches: recompute frames (class hierarchy from the jars, not by loading classes)
                        ClassWriter cw = anchored ? new FrameWriter() : new ClassWriter(ClassWriter.COMPUTE_MAXS);
                        cn.accept(cw);
                        b = cw.toByteArray();
                    }
                    classes++;
                }
                JarEntry ne = new JarEntry(n);
                ne.setTime(e.getTime());
                jo.putNextEntry(ne);
                jo.write(b);
                jo.closeEntry();
            }
        }
        // Windows: a reader (antivirus scan, a just-killed JVM, a static checker) can hold the old jar for a moment
        for (int attempt = 1; ; attempt++) {
            try { Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); break; }
            catch (IOException e) { if (attempt >= 20) throw e; Thread.sleep(500L * attempt); }
        }
        Files.write(out.resolveSibling(out.getFileName() + ".overloads.txt"), (String.join("\n", OVERLOADS) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (AUTO) {
            writeLambdaMap(out);
            for (String l : Auto.LOG) System.out.println("  " + l);
            System.out.println("auto anchors: classes " + Auto.classesChanged + ", wrapper calls inlined " + Auto.inlinedCalls + ", lambdas renamed " + Auto.lambdasRenamed + ", verify failures (reverted) " + Auto.verifyFailures);
        }
        System.out.println("classes " + classes + ", forge-transformers " + forgeTransformed + ", locals renamed " + localsRenamed + ", compat anchors " + compatFixes + ", fabric anchors " + anchorFixes + ", reordered " + reordered + ", MethodParameters restored on " + restored + " methods, AT entries " + ats.size()
                + ", AT changes applied " + atApplied);
        for (AT t : ats) if (!atHit.contains(t.toString())) System.out.println("  AT not matched: " + t);
    }

    /**
     * Fabric-compat anchors. Forge sometimes disables a vanilla call with `if (false)`; javac then drops the call, and
     * Fabric mixins that inject at that call find nothing. Put the call back (the bridge mixes the callee to a no-op,
     * so Forge's behaviour is kept) right where Forge's own replacement runs.
     *  - Bootstrap.bootStrap: wrapStreams() after GameData.vanillaSnapshot()  [fabric-registry-sync BootstrapMixin]
     */
    static boolean compat(ClassNode cn) {
        if (cn.name.equals("net/minecraft/world/entity/LivingEntity")) return restoreOriginalDamage(cn);
        if (!cn.name.equals("net/minecraft/server/Bootstrap")) return false;
        for (MethodNode m : cn.methods) {
            if (!m.name.equals("bootStrap") || !m.desc.equals("()V")) continue;
            boolean has = false; MethodInsnNode snap = null;
            for (AbstractInsnNode n : m.instructions) if (n instanceof MethodInsnNode mi) {
                if (mi.name.equals("wrapStreams") && mi.owner.equals(cn.name)) has = true;
                if (mi.owner.equals("net/minecraftforge/registries/GameData") && mi.name.equals("vanillaSnapshot")) snap = mi;
            }
            if (has || snap == null) return false;
            m.instructions.insert(snap, new MethodInsnNode(Opcodes.INVOKESTATIC, cn.name, "wrapStreams", "()V", false));
            return true;
        }
        return false;
    }

    /** dotted class name -> Forge ITransformer instances (forge-transformers' ForgeModTransformers) */
    static final Map<String, List<Object>> FORGE_TRANSFORMERS = new HashMap<>();
    static java.lang.reflect.Method TRANSFORM;
    static int forgeTransformed, localsRenamed;

    /**
     * Forge's forge-transformers (a ModLauncher ITransformationService) rewrite Minecraft classes that Forge's
     * patches rely on: field reads -> Forge getters (Biome climate/effects, LiquidBlock/BucketItem fluid,
     * FlowerPotBlock, MobEffectInstance) and finalizeSpawn calls -> ForgeEventFactory.onFinalizeSpawn. They only
     * target Minecraft classes, so Alloy applies them here, once, instead of at runtime.
     * Enabled by -Dalloy.transformers=<forge-transformers.jar;modlauncher.jar;log4j-api.jar;...>
     */
    @SuppressWarnings("unchecked")
    static void loadForgeTransformers() throws Exception {
        String prop = System.getProperty("alloy.transformers");
        if (prop == null || prop.isBlank()) return;
        List<java.net.URL> urls = new ArrayList<>();
        for (String p : prop.split(";")) if (!p.isBlank()) urls.add(Path.of(p).toUri().toURL());
        // parent = this loader, so ASM's ClassNode is shared with the transformers
        ClassLoader cl = new java.net.URLClassLoader(urls.toArray(new java.net.URL[0]), PrepGame.class.getClassLoader());
        Class<?> svc = Class.forName("net.minecraftforge.forge.transformers.ForgeModTransformers", true, cl);
        Object s = svc.getConstructor().newInstance();
        // the transformer classes are package-private: go through ModLauncher's public ITransformer interface
        Class<?> itf = Class.forName("cpw.mods.modlauncher.api.ITransformer", false, cl);
        Class<?> tgt = Class.forName("cpw.mods.modlauncher.api.ITransformer$Target", false, cl);
        TRANSFORM = itf.getMethod("transform", Object.class, Class.forName("cpw.mods.modlauncher.api.ITransformerVotingContext", false, cl));
        java.lang.reflect.Method targets = itf.getMethod("targets"), className = tgt.getMethod("getClassName");
        for (Object t : (List<Object>) svc.getMethod("transformers").invoke(s)) {
            for (Object target : (Set<Object>) targets.invoke(t))
                FORGE_TRANSFORMERS.computeIfAbsent(((String) className.invoke(target)).replace('/', '.'), k -> new ArrayList<>()).add(t);
        }
        System.out.println("forge-transformers: " + FORGE_TRANSFORMERS.size() + " target class(es)");
        if (Boolean.getBoolean("alloy.transformers.list")) FORGE_TRANSFORMERS.keySet().stream().sorted().forEach(k -> System.out.println("  target " + k));
    }

    /**
     * LivingEntity.hurtServer: vanilla has `float originalDamage = damage;` right after `if (damage < 0) damage = 0`
     * (before the first getUseItem()). Forge's decompile/recompile folded that copy away, and Fabric API's
     * entity-events afterDamage captures @Local originalDamage. Put the copy back in a fresh slot with its LVT entry.
     * Forge never reads it, so its behaviour is unchanged.
     */
    static boolean restoreOriginalDamage(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if (!m.name.equals("hurtServer") || !m.desc.equals("(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z")) continue;
            if (m.localVariables != null && m.localVariables.stream().anyMatch(l -> l.name.equals("originalDamage"))) return false;
            AbstractInsnNode use = null;
            for (AbstractInsnNode n : m.instructions)
                if (n instanceof MethodInsnNode mi && mi.name.equals("getUseItem") && mi.owner.equals(cn.name)) { use = n; break; }
            if (use == null) return false;
            AbstractInsnNode before = use.getPrevious();
            while (before != null && before.getOpcode() < 0) before = before.getPrevious();   // skip labels/lines
            if (!(before instanceof VarInsnNode v0) || v0.getOpcode() != Opcodes.ALOAD || v0.var != 0) return false;
            int slot = m.maxLocals;
            LabelNode start = new LabelNode(), end = new LabelNode();
            InsnList add = new InsnList();
            add.add(new VarInsnNode(Opcodes.FLOAD, 3));
            add.add(new VarInsnNode(Opcodes.FSTORE, slot));
            add.add(start);
            m.instructions.insertBefore(before, add);
            m.instructions.add(end);
            if (m.localVariables == null) m.localVariables = new ArrayList<>();
            m.localVariables.add(new LocalVariableNode("originalDamage", "F", null, start, end, slot));
            m.maxLocals = slot + 1;
            return true;
        }
        return false;
    }

    // =====================================================================================================================
    // Fabric mixin anchors
    //
    // Forge's patches move or replace vanilla call sites that popular Fabric mods inject into (@At INVOKE of
    // Screen.keyPressed, FluidState.is, Map.copyOf, ...). The mixin then fails with "target not found" and the mod is
    // stripped. These anchors give the VANILLA call back at the VANILLA place while keeping Forge's behaviour and
    // events: Forge's hook body is inlined at its call site (so the vanilla call shows up in the vanilla method, with
    // Forge's pre/post events around it), or the vanilla call is evaluated next to Forge's replacement and combined.
    // Every rewrite is local to one call site and keeps the Forge result for the non-Fabric case.
    // =====================================================================================================================
    static final List<Path> ANCHOR_JARS = new ArrayList<>(), HIER_JARS = new ArrayList<>();
    static final Map<String, ClassNode> FORGE_CLS = new HashMap<>();
    static final String HOOKS = "net/minecraftforge/client/ForgeHooksClient", FACTORY = "net/minecraftforge/client/event/ForgeEventFactoryClient";
    static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    /** Forge wrappers of the form `Pre event; Screen.x(...); Post event` that hide the vanilla call. All public, no lambdas. */
    static final Set<String> INPUT_WRAPPERS = Set.of("onScreenKeyPressed", "onScreenKeyReleased", "onScreenCharTyped",
            "onScreenMouseDrag", "onScreenMouseClicked", "onScreenMouseReleased");

    /** A class from the Forge jars given on the command line (universal jar), or null. */
    static ClassNode forgeClass(String name) {
        ClassNode have = FORGE_CLS.get(name);
        if (have != null) return have;
        HIER_LOOKUPS.add(name);
        for (Path p : ANCHOR_JARS) {
            try (ZipFile z = new ZipFile(p.toFile())) {
                ZipEntry e = z.getEntry(name + ".class");
                if (e == null) continue;
                ClassNode cn = new ClassNode();
                new ClassReader(z.getInputStream(e).readAllBytes()).accept(cn, ClassReader.SKIP_FRAMES);
                FORGE_CLS.put(name, cn);
                return cn;
            } catch (IOException ex) { /* try next */ }
        }
        return null;
    }

    static MethodNode method(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return m;
        return null;
    }

    static final boolean ANCHORS = !"false".equals(System.getProperty("alloy.anchors"));   // default on (client 29/30, server 185/185 with it); -Dalloy.anchors=false to disable

    /** `class name vanillaDesc` lines for every method an anchor (hand-written or auto) added next to an existing method of the same
     *  name but another descriptor. A Fabric mixin with a name-only selector would match both; the fitters qualify such selectors. */
    static final Set<String> OVERLOADS = new TreeSet<>();
    static void recordOverloads(ClassNode cn, Set<String> before) {
        Set<String> names = new HashSet<>();
        for (String k : before) names.add(k.substring(0, k.indexOf('(')));
        for (MethodNode m : cn.methods)
            if (!before.contains(m.name + m.desc) && names.contains(m.name) && !m.name.startsWith("lambda$") && !m.name.startsWith("<"))
                OVERLOADS.add(cn.name + " " + m.name + " " + m.desc);
    }

    static boolean anchors(ClassNode cn) {
        boolean any = false;
        switch (cn.name) {
            // -Dalloy.autoanchors=true: the automatic engine re-finds these wrappers by itself (KeyboardHandler 12 + MouseHandler 11)
            case "net/minecraft/client/KeyboardHandler", "net/minecraft/client/MouseHandler" -> { if (!AUTO) any |= anchorScreenInput(cn); }
            case "net/minecraft/client/renderer/GameRenderer" -> any |= anchorExtractGui(cn);
            case "net/minecraft/client/gui/Gui" -> any |= anchorGuiDrawScreen(cn);                       // 26.2: screen drawing moved here
            case "net/minecraft/locale/Language" -> any |= anchorLanguage(cn);
            case "net/minecraft/client/particle/BlockMarker" -> any |= anchorBlockMarker(cn);
            case "net/minecraft/client/renderer/chunk/SectionCompiler" -> any |= anchorSectionCompiler(cn);
            case "net/minecraft/client/renderer/LevelRenderer",
                 "net/minecraft/client/renderer/extract/LevelExtractor" -> any |= anchorVisibleBlockEntities(cn);   // 26.2: LevelExtractor
            case "net/minecraft/world/entity/vehicle/boat/AbstractBoat" -> any |= anchorBoatFluid(cn);
            default -> { }
        }
        any |= anchorSwimmable(cn);
        for (String[] e : INSERTED_PARAM) if (e[0].equals(cn.name)) any |= anchorInsertedParam(cn, e[1], e[2], e[3]);
        if (cn.name.equals("net/minecraft/world/item/CreativeModeTab")) any |= anchorCreativeTabCtor(cn);
        return any;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // inliner: replace `invokestatic callee` by a copy of the callee's body (args -> fresh locals, returns -> goto end)
    // ---------------------------------------------------------------------------------------------------------------
    static final class LabelMap extends HashMap<LabelNode, LabelNode> {
        @Override public LabelNode get(Object k) { return computeIfAbsent((LabelNode) k, x -> new LabelNode()); }
    }

    static void checkPublic(ClassNode origin, MethodNode callee) {
        for (AbstractInsnNode n : callee.instructions) {
            String owner = null, name = null, desc = null; boolean field = false;
            if (n instanceof MethodInsnNode mi) { owner = mi.owner; name = mi.name; desc = mi.desc; }
            else if (n instanceof FieldInsnNode fi) { owner = fi.owner; name = fi.name; desc = fi.desc; field = true; }
            else if (n instanceof InvokeDynamicInsnNode) throw new IllegalStateException("cannot inline " + callee.name + ": invokedynamic");
            else if (n instanceof TypeInsnNode ti && forgeClass(ti.desc) != null && (forgeClass(ti.desc).access & Opcodes.ACC_PUBLIC) == 0)
                throw new IllegalStateException("cannot inline " + callee.name + ": non-public class " + ti.desc);
            if (owner == null) continue;
            ClassNode oc = owner.equals(origin.name) ? origin : forgeClass(owner);
            if (oc == null) continue;                       // Minecraft / library class: public API
            if (field) { for (FieldNode f : oc.fields) if (f.name.equals(name) && (f.access & Opcodes.ACC_PUBLIC) == 0) throw new IllegalStateException("cannot inline " + callee.name + ": non-public field " + owner + "." + name); }
            else { for (MethodNode m : oc.methods) if (m.name.equals(name) && m.desc.equals(desc) && (m.access & Opcodes.ACC_PUBLIC) == 0) throw new IllegalStateException("cannot inline " + callee.name + ": non-public method " + owner + "." + name); }
        }
    }

    static void inlineStatic(MethodNode m, MethodInsnNode call, MethodNode callee) {
        if (!callee.tryCatchBlocks.isEmpty()) throw new IllegalStateException("cannot inline " + callee.name + ": try/catch");
        Type[] args = Type.getArgumentTypes(callee.desc);
        int base = m.maxLocals, s = base;
        int[] slot = new int[args.length];
        for (int i = 0; i < args.length; i++) { slot[i] = s; s += args[i].getSize(); }
        InsnList out = new InsnList();
        for (int i = args.length - 1; i >= 0; i--) out.add(new VarInsnNode(args[i].getOpcode(Opcodes.ISTORE), slot[i]));
        LabelMap lm = new LabelMap();
        LabelNode end = new LabelNode();
        for (AbstractInsnNode n : callee.instructions) {
            if (n instanceof FrameNode || n instanceof LineNumberNode) continue;
            if (n instanceof LabelNode l) { out.add(lm.get(l)); continue; }
            int op = n.getOpcode();
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) { out.add(new JumpInsnNode(Opcodes.GOTO, end)); continue; }
            AbstractInsnNode c = n.clone(lm);
            if (c instanceof VarInsnNode v) v.var += base;
            if (c instanceof IincInsnNode ii) ii.var += base;
            out.add(c);
        }
        out.add(end);
        m.maxLocals = base + Math.max(callee.maxLocals, s - base);
        m.instructions.insert(call, out);
        m.instructions.remove(call);
    }

    static MethodNode copyMethod(MethodNode src, String name) {
        MethodNode c = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name, src.desc, null, null);
        LabelMap lm = new LabelMap();
        for (AbstractInsnNode n : src.instructions) {
            if (n instanceof FrameNode || n instanceof LineNumberNode) continue;
            c.instructions.add(n instanceof LabelNode l ? lm.get(l) : n.clone(lm));
        }
        c.maxLocals = src.maxLocals; c.maxStack = src.maxStack + 4;
        return c;
    }

    static AbstractInsnNode realPrev(AbstractInsnNode n) {
        AbstractInsnNode p = n.getPrevious();
        while (p != null && p.getOpcode() < 0) p = p.getPrevious();
        return p;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 1. Screen.keyPressed / keyReleased / charTyped / mouseClicked / mouseReleased / mouseDragged
    //    Vanilla: `screen.keyPressed(event)` inside KeyboardHandler.keyPress. Forge: `ForgeHooksClient.onScreenKeyPressed(screen,
    //    event)` = ScreenEvent Pre; if not cancelled screen.keyPressed(event); ScreenEvent Post. Inline the Forge wrapper so the
    //    vanilla call is back in keyPress / charTyped / onButton / handleAccumulatedMovement, events unchanged.
    // ---------------------------------------------------------------------------------------------------------------
    static boolean anchorScreenInput(ClassNode cn) {
        boolean any = false;
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode n : m.instructions.toArray()) {
                if (!(n instanceof MethodInsnNode mi) || mi.getOpcode() != Opcodes.INVOKESTATIC || !INPUT_WRAPPERS.contains(mi.name)) continue;
                if (!mi.owner.equals(HOOKS) && !mi.owner.equals(FACTORY)) continue;
                if (AUTO_KEEP.contains(mi.owner + "." + mi.name)) continue;   // an installed Forge mod's mixin targets this hook call
                ClassNode fc = forgeClass(mi.owner);
                MethodNode w = fc == null ? null : method(fc, mi.name, mi.desc);
                if (w == null) continue;
                checkPublic(fc, w);
                inlineStatic(m, mi, w);
                any = true;
            }
        }
        return any;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 2. GameRenderer.extractGui: vanilla `screen.extractRenderStateWithTooltipAndSubtitles(g, mx, my, dt)`. Forge:
    //    ForgeHooksClient.drawScreen(...) = push pose; for each Forge gui layer drawScreenInternal; drawScreenInternal(screen);
    //    pop pose. Inline drawScreen; its layer loop keeps working through two private helpers added to GameRenderer
    //    (reflection on the private guiLayers stack, a copy of drawScreenInternal); the main-screen drawScreenInternal is inlined
    //    again so the vanilla call sits in extractGui.
    // ---------------------------------------------------------------------------------------------------------------
    static boolean anchorExtractGui(ClassNode cn) {
        MethodNode m = method(cn, "extractGui", "(Lnet/minecraft/client/DeltaTracker;ZZ)V");
        ClassNode hc = forgeClass(HOOKS);
        if (m == null || hc == null || AUTO_KEEP.contains(HOOKS + ".drawScreen")) return false;
        MethodInsnNode call = null;
        for (AbstractInsnNode n : m.instructions) if (n instanceof MethodInsnNode mi && mi.owner.equals(HOOKS) && mi.name.equals("drawScreen")) call = mi;
        MethodNode draw = method(hc, "drawScreen", null), internal = method(hc, "drawScreenInternal", null);
        if (call == null || draw == null || internal == null) return false;
        checkPublic(hc, internal);
        MethodNode ic = copyMethod(internal, "alloy$drawScreenInternal");
        cn.methods.add(ic);
        cn.methods.add(guiLayersHelper());
        MethodNode dw = copyMethod(draw, "alloy$drawScreen");
        for (AbstractInsnNode n : dw.instructions.toArray()) {
            if (n instanceof FieldInsnNode fi && fi.owner.equals(HOOKS) && fi.name.equals("guiLayers"))
                dw.instructions.set(n, new MethodInsnNode(Opcodes.INVOKESTATIC, cn.name, "alloy$guiLayers", "()Ljava/util/Stack;", false));
            else if (n instanceof MethodInsnNode mi && mi.owner.equals(HOOKS) && mi.name.equals("drawScreenInternal")) {
                mi.owner = cn.name; mi.name = "alloy$drawScreenInternal";
            }
        }
        inlineStatic(m, call, dw);
        MethodInsnNode last = null;
        for (AbstractInsnNode n : m.instructions) if (n instanceof MethodInsnNode mi && mi.owner.equals(cn.name) && mi.name.equals("alloy$drawScreenInternal")) last = mi;
        inlineStatic(m, last, ic);      // the call after the layer loop: the main screen
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 2b. 26.2: the screen is drawn in Gui.extractRenderState. Vanilla `screen.extractRenderStateWithTooltipAndSubtitles(...)`;
    //     Forge's Gui extends ForgeLayerInstance and calls its `drawScreen(g, mx, my, dt)` = push pose; each Forge gui layer
    //     drawScreenInternal; drawScreenInternal(screen()); pop pose. Same treatment as 2: inline drawScreen (as a static copy
    //     with the receiver as first argument), the private guiLayers field through a reflective helper, then inline the
    //     main-screen drawScreenInternal so the vanilla call sits in extractRenderState again.
    // ---------------------------------------------------------------------------------------------------------------
    static final String LAYERS = "net/minecraftforge/client/gui/overlay/ForgeLayerInstance";
    static boolean anchorGuiDrawScreen(ClassNode cn) {
        final String d = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";
        if (!LAYERS.equals(cn.superName) || AUTO_KEEP.contains(LAYERS + ".drawScreen")) return false;
        MethodNode m = method(cn, "extractRenderState", "(Lnet/minecraft/client/DeltaTracker;ZZ)V");
        ClassNode lc = forgeClass(LAYERS);
        if (m == null || lc == null) return false;
        MethodInsnNode call = null;
        for (AbstractInsnNode n : m.instructions)
            if (n instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEVIRTUAL && mi.name.equals("drawScreen") && mi.desc.equals(d)
                    && (mi.owner.equals(cn.name) || mi.owner.equals(LAYERS))) call = mi;
        MethodNode draw = method(lc, "drawScreen", d), internal = method(lc, "drawScreenInternal", null);
        if (call == null || draw == null || internal == null || !internal.tryCatchBlocks.isEmpty()) return false;
        checkPublic(lc, internal);
        MethodNode ic = copyMethod(internal, "alloy$drawScreenInternal");
        cn.methods.add(ic);
        cn.methods.add(layersHelper());
        MethodNode dw = copyMethod(draw, "alloy$drawScreen");
        dw.desc = "(L" + cn.name + ";" + d.substring(1);
        for (AbstractInsnNode n : dw.instructions.toArray()) {
            if (n instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETFIELD && fi.owner.equals(LAYERS) && fi.name.equals("guiLayers"))
                dw.instructions.set(n, new MethodInsnNode(Opcodes.INVOKESTATIC, cn.name, "alloy$guiLayers", "(Ljava/lang/Object;)Ljava/util/Stack;", false));
            else if (n instanceof MethodInsnNode mi && mi.owner.equals(LAYERS) && mi.name.equals("drawScreenInternal")) {
                mi.owner = cn.name; mi.name = "alloy$drawScreenInternal";
            } else if (n instanceof MethodInsnNode mi && mi.owner.equals(LAYERS) && mi.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                mi.owner = cn.name;                       // screen(): protected, implemented by Gui
            } else if (n instanceof FieldInsnNode fi && fi.owner.equals(LAYERS)) {
                throw new IllegalStateException("cannot inline ForgeLayerInstance.drawScreen: field " + fi.name);
            }
        }
        inlineStatic(m, call, dw);
        MethodInsnNode last = null;
        for (AbstractInsnNode n : m.instructions) if (n instanceof MethodInsnNode mi && mi.owner.equals(cn.name) && mi.name.equals("alloy$drawScreenInternal")) last = mi;
        inlineStatic(m, last, ic);      // the call after the layer loop: the main screen
        return true;
    }

    /** private static Stack alloy$guiLayers(Object self): ForgeLayerInstance.guiLayers is private, read it reflectively. */
    static MethodNode layersHelper() {
        MethodNode g = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "alloy$guiLayers", "(Ljava/lang/Object;)Ljava/util/Stack;", null, null);
        LabelNode ts = new LabelNode(), te = new LabelNode(), h = new LabelNode();
        InsnList i = g.instructions;
        i.add(ts);
        i.add(new LdcInsnNode(Type.getObjectType(LAYERS)));
        i.add(new LdcInsnNode("guiLayers"));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false));
        i.add(new InsnNode(Opcodes.DUP));
        i.add(new InsnNode(Opcodes.ICONST_1));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "setAccessible", "(Z)V", false));
        i.add(new VarInsnNode(Opcodes.ALOAD, 0));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        i.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Stack"));
        i.add(te);
        i.add(new InsnNode(Opcodes.ARETURN));
        i.add(h);
        i.add(new VarInsnNode(Opcodes.ASTORE, 1));
        i.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        i.add(new InsnNode(Opcodes.DUP));
        i.add(new VarInsnNode(Opcodes.ALOAD, 1));
        i.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/Throwable;)V", false));
        i.add(new InsnNode(Opcodes.ATHROW));
        g.tryCatchBlocks.add(new TryCatchBlockNode(ts, te, h, "java/lang/ReflectiveOperationException"));
        g.maxLocals = 2; g.maxStack = 4;
        return g;
    }

    /** private static Stack alloy$guiLayers(): ForgeHooksClient.guiLayers is private, read it reflectively. */
    static MethodNode guiLayersHelper() {
        MethodNode g = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "alloy$guiLayers", "()Ljava/util/Stack;", null, null);
        LabelNode ts = new LabelNode(), te = new LabelNode(), h = new LabelNode();
        InsnList i = g.instructions;
        i.add(ts);
        i.add(new LdcInsnNode("net.minecraftforge.client.ForgeHooksClient"));
        i.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false));
        i.add(new LdcInsnNode("guiLayers"));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false));
        i.add(new InsnNode(Opcodes.DUP));
        i.add(new InsnNode(Opcodes.ICONST_1));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "setAccessible", "(Z)V", false));
        i.add(new InsnNode(Opcodes.ACONST_NULL));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        i.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Stack"));
        i.add(te);
        i.add(new InsnNode(Opcodes.ARETURN));
        i.add(h);
        i.add(new VarInsnNode(Opcodes.ASTORE, 0));
        i.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        i.add(new InsnNode(Opcodes.DUP));
        i.add(new VarInsnNode(Opcodes.ALOAD, 0));
        i.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/Throwable;)V", false));
        i.add(new InsnNode(Opcodes.ATHROW));
        g.tryCatchBlocks.add(new TryCatchBlockNode(ts, te, h, "java/lang/ReflectiveOperationException"));
        g.maxLocals = 1; g.maxStack = 4;
        return g;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 3. Language.loadDefault: vanilla `Language$1(Map.copyOf(map))`. Forge keeps the live HashMap (LanguageHook.captureLanguageMap
    //    adds mod translations into it later). Insert `map = new HashMap(Map.copyOf(map))` before Forge's capture: the vanilla
    //    Map.copyOf call is back (Redirect handlers can add entries), and the map stays mutable for Forge.
    // ---------------------------------------------------------------------------------------------------------------
    static boolean anchorLanguage(ClassNode cn) {
        MethodNode m = method(cn, "loadDefault", "()Lnet/minecraft/locale/Language;");
        if (m == null) return false;
        for (AbstractInsnNode n : m.instructions) if (n instanceof MethodInsnNode mi && mi.name.equals("copyOf") && mi.owner.equals("java/util/Map")) return false;
        MethodInsnNode cap = null;
        for (AbstractInsnNode n : m.instructions) if (n instanceof MethodInsnNode mi && mi.name.equals("captureLanguageMap")) { cap = mi; break; }
        if (cap == null || !(realPrev(cap) instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) return false;
        int slot = load.var;
        InsnList add = new InsnList();
        add.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
        add.add(new InsnNode(Opcodes.DUP));
        add.add(new VarInsnNode(Opcodes.ALOAD, slot));
        add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Map", "copyOf", "(Ljava/util/Map;)Ljava/util/Map;", true));
        add.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "(Ljava/util/Map;)V", false));
        add.add(new VarInsnNode(Opcodes.ASTORE, slot));
        m.instructions.insertBefore(load, add);
        m.maxStack = Math.max(m.maxStack, 4);
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 4. BlockMarker.<init>: vanilla `blockStateModelSet.getParticleMaterial(state)`. Forge: `set.get(state).particleMaterial(
    //    level.getModelDataManager().getAtOrEmpty(x, y, z))`. Without model data the two are the same, so: model data EMPTY ->
    //    vanilla call (mixin target), otherwise Forge's call.
    // ---------------------------------------------------------------------------------------------------------------
    static boolean anchorBlockMarker(ClassNode cn) {
        MethodNode m = null;
        for (MethodNode x : cn.methods) if (x.name.equals("<init>")) m = x;
        if (m == null) return false;
        MethodInsnNode get = null, pm = null;
        for (AbstractInsnNode n : m.instructions) {
            if (n instanceof MethodInsnNode mi && mi.owner.equals("net/minecraft/client/renderer/block/BlockStateModelSet")) {
                if (mi.name.equals("getParticleMaterial")) return false;
                if (mi.name.equals("get")) get = mi;
            }
            if (n instanceof MethodInsnNode mi && mi.name.equals("particleMaterial") && mi.desc.startsWith("(Lnet/minecraftforge/client/model/data/ModelData;)")) pm = mi;
        }
        if (get == null || pm == null || !(realPrev(get) instanceof VarInsnNode state)) return false;
        int tmp = m.maxLocals++;
        InsnList tail = new InsnList();
        tail.add(new VarInsnNode(Opcodes.ASTORE, tmp));
        modelDataBranch(tail, tmp,
                list(new VarInsnNode(Opcodes.ALOAD, state.var), new MethodInsnNode(Opcodes.INVOKEVIRTUAL, get.owner, "getParticleMaterial", "(Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/client/resources/model/sprite/Material$Baked;", false)),
                list(new VarInsnNode(Opcodes.ALOAD, state.var), get.clone(null), new VarInsnNode(Opcodes.ALOAD, tmp), pm.clone(null)));
        m.instructions.insert(pm, tail);
        m.instructions.remove(state); m.instructions.remove(get); m.instructions.remove(pm);
        return true;
    }

    static InsnList list(AbstractInsnNode... ns) { InsnList l = new InsnList(); for (AbstractInsnNode n : ns) l.add(n); return l; }

    /** `if (local == ModelData.EMPTY) { vanilla } else { forge }` (both leave the same stack effect). */
    static void modelDataBranch(InsnList out, int mdLocal, InsnList vanilla, InsnList forge) {
        LabelNode forgeL = new LabelNode(), end = new LabelNode();
        out.add(new VarInsnNode(Opcodes.ALOAD, mdLocal));
        out.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraftforge/client/model/data/ModelData", "EMPTY", "Lnet/minecraftforge/client/model/data/ModelData;"));
        out.add(new JumpInsnNode(Opcodes.IF_ACMPNE, forgeL));
        out.add(vanilla);
        out.add(new JumpInsnNode(Opcodes.GOTO, end));
        out.add(forgeL);
        out.add(forge);
        out.add(end);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 5. SectionCompiler.compile: vanilla ModelBlockRenderer.tesselateBlock(out, x, y, z, getter, pos, state, model, seed).
    //    Forge adds a trailing ModelData argument (and keeps the vanilla overload, which passes ModelData.EMPTY). Empty model
    //    data -> vanilla overload (mixin target); otherwise Forge's.
    // ---------------------------------------------------------------------------------------------------------------
    static boolean anchorSectionCompiler(ClassNode cn) {
        boolean any = false;
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode n : m.instructions.toArray()) {
                if (!(n instanceof MethodInsnNode mi) || !mi.name.equals("tesselateBlock") || !mi.desc.endsWith("Lnet/minecraftforge/client/model/data/ModelData;)V")) continue;
                if (!(realPrev(mi) instanceof VarInsnNode md) || md.getOpcode() != Opcodes.ALOAD) continue;
                String vdesc = mi.desc.replace("Lnet/minecraftforge/client/model/data/ModelData;)V", ")V");
                InsnList repl = new InsnList();
                modelDataBranch(repl, md.var,
                        list(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, mi.owner, mi.name, vdesc, false)),
                        list(new VarInsnNode(Opcodes.ALOAD, md.var), mi.clone(null)));
                m.instructions.insertBefore(md, repl);
                m.instructions.remove(md); m.instructions.remove(mi);
                any = true;
            }
        }
        return any;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 6. LevelRenderer.extractVisibleBlockEntities: vanilla (Camera, float, LevelRenderState); Forge adds a Frustum parameter.
    //    Keep Forge's 4-arg method; add the vanilla 3-arg method as a thin delegate (frustum kept in a field set by the caller),
    //    and make the caller invoke the vanilla signature.
    // ---------------------------------------------------------------------------------------------------------------
    static boolean anchorVisibleBlockEntities(ClassNode cn) {
        final String d3 = "(Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/state/level/LevelRenderState;)V";
        final String d4 = "(Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/culling/Frustum;)V";
        final String fd = "Lnet/minecraft/client/renderer/culling/Frustum;";
        if (method(cn, "extractVisibleBlockEntities", d3) != null || method(cn, "extractVisibleBlockEntities", d4) == null) return false;
        boolean any = false;
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode n : m.instructions.toArray()) {
                if (!(n instanceof MethodInsnNode mi) || !mi.name.equals("extractVisibleBlockEntities") || !mi.desc.equals(d4) || !mi.owner.equals(cn.name)) continue;
                if (!(realPrev(mi) instanceof VarInsnNode fr) || fr.getOpcode() != Opcodes.ALOAD) continue;
                // stash the frustum at the statement boundary after popPush("blockEntities")
                AbstractInsnNode at = null;
                for (AbstractInsnNode x : m.instructions)
                    if (x instanceof LdcInsnNode l && "blockEntities".equals(l.cst) && x.getNext() instanceof MethodInsnNode pp && pp.name.equals("popPush")) at = pp;
                if (at == null) continue;
                m.instructions.insert(at, list(new VarInsnNode(Opcodes.ALOAD, 0), new VarInsnNode(Opcodes.ALOAD, fr.var),
                        new FieldInsnNode(Opcodes.PUTFIELD, cn.name, "alloy$frustum", fd)));
                m.instructions.remove(fr);
                mi.desc = d3;
                any = true;
            }
        }
        if (!any) return false;
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, "alloy$frustum", fd, null, null));
        MethodNode stub = new MethodNode(Opcodes.ACC_PRIVATE, "extractVisibleBlockEntities", d3, null, null);
        stub.instructions.add(list(new VarInsnNode(Opcodes.ALOAD, 0), new VarInsnNode(Opcodes.ALOAD, 1), new VarInsnNode(Opcodes.FLOAD, 2),
                new VarInsnNode(Opcodes.ALOAD, 3), new VarInsnNode(Opcodes.ALOAD, 0), new FieldInsnNode(Opcodes.GETFIELD, cn.name, "alloy$frustum", fd),
                new MethodInsnNode(Opcodes.INVOKEVIRTUAL, cn.name, "extractVisibleBlockEntities", d4, false), new InsnNode(Opcodes.RETURN)));
        stub.maxLocals = 4; stub.maxStack = 6;
        cn.methods.add(stub);
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 7. Entity fluid checks. Forge replaced `isInWater()` by `isInWaterOrSwimmable()` (= isInWater() || fluid-type check) in
    //    Entity.updateSwimming/isVisuallyCrawling, LocalPlayer.shouldStopSwimSprinting, ... Evaluate the vanilla call first and OR it
    //    with Forge's: `isInWater() | isInWaterOrSwimmable()` (no side effects, so double evaluation is harmless).
    //    AbstractBoat.checkInWater/isUnderwater: vanilla `fluidState.is(FluidTags.WATER)`; Forge `canBoatInFluid(fluidState)`:
    //    `fluidState.is(WATER) | canBoatInFluid(fluidState)`.
    //    NOT covered (different structure, needs a compat mixin): LivingEntity.travelInFluid/baseTick, Entity.updateSwimming's
    //    FluidState.is(WATER) (Forge: canStartSwimming).
    // ---------------------------------------------------------------------------------------------------------------
    static boolean anchorSwimmable(ClassNode cn) {
        boolean any = false;
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode n : m.instructions.toArray()) {
                if (!(n instanceof MethodInsnNode mi) || mi.getOpcode() != Opcodes.INVOKEVIRTUAL || !mi.name.equals("isInWaterOrSwimmable") || !mi.desc.equals("()Z")) continue;
                if (m.name.equals("isInWaterOrSwimmable")) continue;
                InsnList l = new InsnList();                                             // [this]
                l.add(new InsnNode(Opcodes.DUP));                                        // [this this]
                l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, mi.owner, "isInWater", "()Z", false));   // [this z]
                l.add(new InsnNode(Opcodes.SWAP));                                       // [z this]
                m.instructions.insertBefore(mi, l);
                m.instructions.insert(mi, new InsnNode(Opcodes.IOR));                    // [z | isInWaterOrSwimmable]
                any = true;
            }
        }
        return any;
    }

    static boolean anchorBoatFluid(ClassNode cn) {
        boolean any = false;
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode n : m.instructions.toArray()) {
                if (!(n instanceof MethodInsnNode mi) || !mi.name.equals("canBoatInFluid") || !mi.desc.equals("(Lnet/minecraft/world/level/material/FluidState;)Z")) continue;
                int tmp = m.maxLocals++;
                InsnList l = new InsnList();                                             // [this fs]
                l.add(new InsnNode(Opcodes.DUP2));                                       // [this fs this fs]
                l.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraft/tags/FluidTags", "WATER", "Lnet/minecraft/tags/TagKey;"));
                l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/material/FluidState", "is", "(Lnet/minecraft/tags/TagKey;)Z", false));  // [this fs this z]
                l.add(new VarInsnNode(Opcodes.ISTORE, tmp));                             // [this fs this]
                l.add(new InsnNode(Opcodes.POP));                                        // [this fs]
                m.instructions.insertBefore(mi, l);
                m.instructions.insert(mi, list(new VarInsnNode(Opcodes.ILOAD, tmp), new InsnNode(Opcodes.IOR)));
                any = true;
            }
        }
        return any;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Inserted-parameter anchors. Forge sometimes adds one parameter to a vanilla method to pass event data in:
    //   AnvilMenu lambda$onTake$0(Player, Level, BlockPos)        + float breakChance (AnvilRepairEvent)
    //   ServerExplosion hurtEntities()                             + List<BlockPos> (ExplosionEvent.Detonate)
    //   SpongeBlock lambda$removeWaterBreadthFirstSearch$1(...)    + BlockState
    //   Entity playMuffledStepSound(BlockState)                    + BlockPos
    // Fabric mixins (Ledger, BCLib, Enchancement, Wilder Wild, Carpet, Strawberry Lib) select the vanilla method and fail.
    // The anchor adds the vanilla-shaped method with Forge's body; the extra value comes from a synthetic field (instance
    // field for instance methods, static for static ones) that is copied into a local on entry. Forge's method becomes a
    // wrapper that stores the value and calls the vanilla-shaped one, so Forge's events and every Forge caller keep working.
    // Private and static methods also get their own class's call sites switched to the vanilla call (Carpet expects
    // `hurtEntities()` inside explode()). Overridable methods keep their call sites, so Forge-desc overrides still run.
    // The vanilla method is recorded as an overload, so the fitters qualify name-only selectors to it.
    // Cost: a Forge mod mixin that targets a call inside Forge's method body no longer finds it there (the body moved),
    // and a Forge mod's name-only @Inject that takes Forge's arguments would also match the vanilla method and fail its
    // signature check. Forge mods are not qualified the way Fabric mods are, so only methods no checked Forge mod targets
    // that way are listed. Not listed: FireBlock.checkBurnOut (+ Direction): Open Parties and Claims injects at its HEAD
    // by name with Forge's arguments.
    // ---------------------------------------------------------------------------------------------------------------
    static final String[][] INSERTED_PARAM = {
        {"net/minecraft/world/inventory/AnvilMenu", "lambda$onTake$0",
         "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V",
         "(Lnet/minecraft/world/entity/player/Player;FLnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"},
        {"net/minecraft/world/level/ServerExplosion", "hurtEntities", "()V", "(Ljava/util/List;)V"},
        {"net/minecraft/world/level/block/SpongeBlock", "lambda$removeWaterBreadthFirstSearch$1",
         "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos$TraversalNodeStatus;",
         "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos$TraversalNodeStatus;"},
        {"net/minecraft/world/entity/Entity", "playMuffledStepSound",
         "(Lnet/minecraft/world/level/block/state/BlockState;)V",
         "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V"},
    };

    static boolean anchorInsertedParam(ClassNode cn, String name, String vdesc, String fdesc) {
        MethodNode f = method(cn, name, fdesc);
        if (f == null || method(cn, name, vdesc) != null || (f.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return false;
        Type[] va = Type.getArgumentTypes(vdesc), fa = Type.getArgumentTypes(fdesc);
        if (fa.length != va.length + 1 || !Type.getReturnType(vdesc).equals(Type.getReturnType(fdesc))) return false;
        int ins = 0;
        while (ins < va.length && va[ins].equals(fa[ins])) ins++;
        for (int i = ins; i < va.length; i++) if (!va[i].equals(fa[i + 1])) return false;   // exactly one inserted parameter
        boolean st = (f.access & Opcodes.ACC_STATIC) != 0, priv = (f.access & Opcodes.ACC_PRIVATE) != 0;
        Type it = fa[ins];
        int fs = st ? 0 : 1;
        for (int i = 0; i < ins; i++) fs += fa[i].getSize();
        int sz = it.getSize(), tmp = f.maxLocals - sz;
        String fld = "alloy$" + name.replace('$', '_') + "$" + ins;
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC | (st ? Opcodes.ACC_STATIC : 0), fld, it.getDescriptor(), null, null));
        // vanilla-shaped method: Forge's body, the inserted parameter's slot -> a local filled from the field on entry
        MethodNode v = new MethodNode(f.access, name, vdesc, null, f.exceptions == null ? null : f.exceptions.toArray(new String[0]));
        if (!st) v.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        v.instructions.add(new FieldInsnNode(st ? Opcodes.GETSTATIC : Opcodes.GETFIELD, cn.name, fld, it.getDescriptor()));
        v.instructions.add(new VarInsnNode(it.getOpcode(Opcodes.ISTORE), tmp));
        LabelMap lm = new LabelMap();
        for (AbstractInsnNode n : f.instructions) {
            if (n instanceof FrameNode || n instanceof LineNumberNode) continue;
            if (n instanceof LabelNode l) { v.instructions.add(lm.get(l)); continue; }
            AbstractInsnNode c = n.clone(lm);
            if (c instanceof VarInsnNode vi) vi.var = vi.var == fs ? tmp : vi.var > fs ? vi.var - sz : vi.var;
            if (c instanceof IincInsnNode ii) ii.var = ii.var == fs ? tmp : ii.var > fs ? ii.var - sz : ii.var;
            v.instructions.add(c);
        }
        for (TryCatchBlockNode t : f.tryCatchBlocks) v.tryCatchBlocks.add(new TryCatchBlockNode(lm.get(t.start), lm.get(t.end), lm.get(t.handler), t.type));
        v.maxLocals = f.maxLocals; v.maxStack = f.maxStack + 2;
        int invoke = st ? Opcodes.INVOKESTATIC : priv ? Opcodes.INVOKESPECIAL : Opcodes.INVOKEVIRTUAL;
        // own call sites of a private/static method: spill the arguments, store the inserted one, call the vanilla method
        if (st || priv) {
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode n : m.instructions.toArray()) {
                    if (!(n instanceof MethodInsnNode mi) || !mi.owner.equals(cn.name) || !mi.name.equals(name) || !mi.desc.equals(fdesc)) continue;
                    int[] slot = new int[fa.length];
                    int s = m.maxLocals;
                    for (int i = 0; i < fa.length; i++) { slot[i] = s; s += fa[i].getSize(); }
                    InsnList l = new InsnList();
                    for (int i = fa.length - 1; i >= 0; i--) l.add(new VarInsnNode(fa[i].getOpcode(Opcodes.ISTORE), slot[i]));
                    if (!st) l.add(new InsnNode(Opcodes.DUP));                                   // [this this]
                    l.add(new VarInsnNode(it.getOpcode(Opcodes.ILOAD), slot[ins]));
                    l.add(new FieldInsnNode(st ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD, cn.name, fld, it.getDescriptor()));
                    for (int i = 0; i < fa.length; i++) if (i != ins) l.add(new VarInsnNode(fa[i].getOpcode(Opcodes.ILOAD), slot[i]));
                    l.add(new MethodInsnNode(invoke, cn.name, name, vdesc, false));
                    m.instructions.insertBefore(mi, l);
                    m.instructions.remove(mi);
                    m.maxLocals = s; m.maxStack += 2;
                }
            }
        }
        cn.methods.add(v);
        // Forge's method: store the inserted value, run the vanilla-shaped body
        f.instructions.clear(); f.tryCatchBlocks.clear(); f.localVariables = null;
        if (!st) f.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        f.instructions.add(new VarInsnNode(it.getOpcode(Opcodes.ILOAD), fs));
        f.instructions.add(new FieldInsnNode(st ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD, cn.name, fld, it.getDescriptor()));
        if (!st) f.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int slot = st ? 0 : 1;
        for (int i = 0; i < fa.length; i++) { if (i != ins) f.instructions.add(new VarInsnNode(fa[i].getOpcode(Opcodes.ILOAD), slot)); slot += fa[i].getSize(); }
        f.instructions.add(new MethodInsnNode(invoke, cn.name, name, vdesc, false));
        f.instructions.add(new InsnNode(Type.getReturnType(fdesc).getOpcode(Opcodes.IRETURN)));
        f.maxStack = Math.max(4, slot + 2);
        OVERLOADS.add(cn.name + " " + name + " " + vdesc);
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // CreativeModeTab: vanilla's constructor (Row, int, Type, Component, Supplier, DisplayItemsGenerator) got a seventh
    // parameter on Forge, the Builder (search bar, colours, tab order). Fabric libraries that subclass CreativeModeTab
    // through an access widener (owo's OwoItemGroup, used by Alloy Forgery and Mythic Metals) call vanilla's constructor
    // and fail with NoSuchMethodError at startup. The anchor adds vanilla's constructor; it calls Forge's with
    // CreativeModeTab.builder(row, column), the defaults a vanilla tab has. Recorded as an overload so the fitters
    // qualify name-only "<init>" selectors to it (no checked Forge mod injects into CreativeModeTab's constructors).
    // ---------------------------------------------------------------------------------------------------------------
    static final String TAB = "net/minecraft/world/item/CreativeModeTab";
    static final String TAB_VANILLA = "(L" + TAB + "$Row;IL" + TAB + "$Type;Lnet/minecraft/network/chat/Component;Ljava/util/function/Supplier;L" + TAB + "$DisplayItemsGenerator;)V";
    static final String TAB_FORGE = "(L" + TAB + "$Row;IL" + TAB + "$Type;Lnet/minecraft/network/chat/Component;Ljava/util/function/Supplier;L" + TAB + "$DisplayItemsGenerator;L" + TAB + "$Builder;)V";

    static boolean anchorCreativeTabCtor(ClassNode cn) {
        MethodNode f = method(cn, "<init>", TAB_FORGE);
        if (f == null || method(cn, "<init>", TAB_VANILLA) != null || method(cn, "builder", "(L" + TAB + "$Row;I)L" + TAB + "$Builder;") == null) return false;
        MethodNode v = new MethodNode(f.access, "<init>", TAB_VANILLA, null, null);
        v.instructions.add(list(new VarInsnNode(Opcodes.ALOAD, 0), new VarInsnNode(Opcodes.ALOAD, 1), new VarInsnNode(Opcodes.ILOAD, 2),
                new VarInsnNode(Opcodes.ALOAD, 3), new VarInsnNode(Opcodes.ALOAD, 4), new VarInsnNode(Opcodes.ALOAD, 5), new VarInsnNode(Opcodes.ALOAD, 6),
                new VarInsnNode(Opcodes.ALOAD, 1), new VarInsnNode(Opcodes.ILOAD, 2),
                new MethodInsnNode(Opcodes.INVOKESTATIC, TAB, "builder", "(L" + TAB + "$Row;I)L" + TAB + "$Builder;", false),
                new MethodInsnNode(Opcodes.INVOKESPECIAL, TAB, "<init>", TAB_FORGE, false), new InsnNode(Opcodes.RETURN)));
        v.maxLocals = 7; v.maxStack = 10;
        cn.methods.add(v);
        OVERLOADS.add(TAB + " <init> " + TAB_VANILLA);
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // frames for anchored classes: class hierarchy from the jars (Minecraft, Forge) and the JDK, never by loading game classes
    // ---------------------------------------------------------------------------------------------------------------
    static final Map<String, String> SUPER_CACHE = new HashMap<>();
    static final List<ZipFile> HIER_ZIPS = new ArrayList<>();
    /** class names looked up in HIER_JARS (forge, universal, mod jars): Store keys on what the mod jars answer for them */
    static final Set<String> HIER_LOOKUPS = new TreeSet<>();

    /**
     * Built game jars, remembered by their inputs (-Dalloy.gamejar.store=DIR, set by alloy-prep). The jar depends on the
     * mod jars only through their access transformers / wideners, the Forge hook calls their mixins target (AUTO_KEEP)
     * and the classes the frame/verifier hierarchy found in them. So adding or removing a Forge mod without ATs, or going
     * back to an earlier mod set, reuses a stored jar instead of rebuilding it (same bytes, same printed output).
     *
     * DIR/<key>/ holds game.jar (hard link to the output when possible), the .overloads.txt / .lambdas.txt sidecars,
     * out.txt (what this run printed) and lookups.txt (the hierarchy names and what the extra jars held for them).
     * key = sha1 of: PrepGame's own code, Java version, vanilla / Forge / universal jar contents, every AT entry in order,
     * AUTO_KEEP, all alloy.* properties and the size/mtime of the jars they name. The newest 3 entries are kept.
     */
    static final class Store {
        final Path dir;
        final String key;
        final Path out;
        final PrintStream realOut = System.out;
        final ByteArrayOutputStream cap = new ByteArrayOutputStream();
        PrintStream tee;
        boolean replayed;

        Store(Path root, String key, Path out) { this.dir = root.resolve(key); this.key = key; this.out = out; }

        static Store open(String root, String[] args, Path vanilla, Path forge, Path out, List<AT> ats) {
            if (root == null || root.isBlank()) return null;
            try {
                StringBuilder sb = new StringBuilder("pgstore1|").append(codeId()).append('|').append(System.getProperty("java.version")).append('\n');
                for (Path p : List.of(vanilla, forge)) sb.append(p.getFileName()).append('|').append(sha1(p)).append('\n');
                for (int i = 3; i < args.length; i++) {   // universal + Forge mods: their name and content matter only through what is below
                    Path p = Path.of(args[i]);
                    if (!args[i].endsWith(".jar")) sb.append("cfg|").append(sha1(p)).append('\n');
                }
                if (!ANCHOR_JARS.isEmpty()) sb.append("universal|").append(sha1(ANCHOR_JARS.get(0))).append('\n');
                for (AT t : ats) sb.append("AT|").append(t).append('\n');
                for (String k : new TreeSet<>(AUTO_KEEP)) sb.append("K|").append(k).append('\n');
                for (String k : new TreeSet<>(System.getProperties().stringPropertyNames())) {
                    if (!k.startsWith("alloy.") || k.equals("alloy.gamejar.store")) continue;
                    String v = System.getProperty(k);
                    sb.append("P|").append(k).append('=').append(v).append('\n');
                    for (String part : v.split(";")) {
                        if (!part.endsWith(".jar")) continue;
                        Path j = Path.of(part);
                        if (Files.exists(j)) sb.append("PJ|").append(Files.size(j)).append('|').append(Files.getLastModifiedTime(j).toMillis()).append('\n');
                    }
                }
                Store s = new Store(Path.of(root), sha1(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), out);
                s.tee = new PrintStream(new OutputStream() {   // tee: the printed output is replayed on a store hit
                    @Override public void write(int b) { s.realOut.write(b); s.cap.write(b); }
                    @Override public void write(byte[] b, int o, int l) { s.realOut.write(b, o, l); s.cap.write(b, o, l); }
                    @Override public void flush() { s.realOut.flush(); }
                }, true);
                System.setOut(s.tee);
                return s;
            } catch (Exception e) {
                return null;   // no store for this run
            }
        }

        void endCapture() { System.out.flush(); System.setOut(realOut); }

        /** puts the stored jar in place and replays its output; false when there is no valid entry */
        boolean restore() {
            try {
                Path jar = dir.resolve("game.jar"), txt = dir.resolve("out.txt"), lk = dir.resolve("lookups.txt");
                if (!Files.exists(jar) || !Files.exists(txt) || !Files.exists(lk)) return false;   // a miss: keep capturing for save()
                List<String> lines = Files.readAllLines(lk);
                if (lines.isEmpty() || !lines.get(0).equals(key) || !lookupsMatch(lines.subList(1, lines.size()))) return false;
                endCapture();
                Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
                Files.deleteIfExists(tmp);
                try { Files.createLink(tmp, jar); } catch (Exception e) { Files.copy(jar, tmp, StandardCopyOption.REPLACE_EXISTING); }
                for (int attempt = 1; ; attempt++) {
                    try { Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); break; }
                    catch (IOException e) { if (attempt >= 20) throw e; Thread.sleep(500L * attempt); }
                }
                for (String side : List.of(".overloads.txt", ".lambdas.txt")) {
                    Path s = dir.resolve("game.jar" + side), d = Path.of(out + side);
                    if (Files.exists(s)) Files.copy(s, d, StandardCopyOption.REPLACE_EXISTING);
                    else Files.deleteIfExists(d);
                }
                Files.setLastModifiedTime(dir, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
                byte[] text = Files.readAllBytes(txt);
                replayed = true;
                System.out.write(text);
                System.out.flush();
                return true;
            } catch (Exception e) {
                if (replayed) throw new IllegalStateException("game jar store: restore failed after replaying its output", e);
                System.setOut(tee);   // nothing replayed: build normally (and keep capturing for save)
                return false;
            }
        }

        void save() {
            try {
                Path tmpDir = dir.resolveSibling(key + ".tmp" + ProcessHandle.current().pid());
                Files.createDirectories(tmpDir);
                Path jar = tmpDir.resolve("game.jar");
                try { Files.createLink(jar, out); } catch (Exception e) { Files.copy(out, jar, StandardCopyOption.REPLACE_EXISTING); }
                for (String side : List.of(".overloads.txt", ".lambdas.txt")) {
                    Path s = Path.of(out + side);
                    if (Files.exists(s)) Files.copy(s, tmpDir.resolve("game.jar" + side), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.write(tmpDir.resolve("out.txt"), cap.toByteArray());
                List<String> lk = new ArrayList<>();
                lk.add(key);
                lk.addAll(lookupLines(HIER_LOOKUPS));
                Files.write(tmpDir.resolve("lookups.txt"), lk);
                deleteTree(dir);
                Files.move(tmpDir, dir);
                prune(dir.getParent(), 3);
            } catch (Exception e) { /* the store is optional */ }
        }

        /** one line per looked-up class: name, then index:sha1 of the first extra jar (not the Forge jar) holding it, or - */
        static List<String> lookupLines(Collection<String> names) throws IOException {
            List<String> out = new ArrayList<>();
            List<ZipFile> zips = new ArrayList<>();
            try {
                for (int i = 1; i < HIER_JARS.size(); i++) zips.add(new ZipFile(HIER_JARS.get(i).toFile()));
                for (String n : names) {
                    String v = "-";
                    for (int i = 0; i < zips.size(); i++) {
                        ZipEntry e = zips.get(i).getEntry(n + ".class");
                        if (e == null) continue;
                        try (InputStream in = zips.get(i).getInputStream(e)) { v = i + ":" + sha1(in.readAllBytes()); }
                        break;
                    }
                    out.add(n + " " + v);
                }
            } finally { for (ZipFile z : zips) z.close(); }
            return out;
        }

        static boolean lookupsMatch(List<String> stored) throws IOException {
            List<String> names = new ArrayList<>();
            for (String l : stored) { int sp = l.indexOf(' '); if (sp < 0) return false; names.add(l.substring(0, sp)); }
            return lookupLines(names).equals(stored);
        }

        static void prune(Path root, int keep) throws IOException {
            List<Path> dirs = new ArrayList<>();
            try (var s = Files.list(root)) { for (Path p : s.toList()) if (Files.isDirectory(p)) dirs.add(p); }
            long now = System.currentTimeMillis();
            for (Path p : dirs) if (p.getFileName().toString().contains(".tmp") && now - Files.getLastModifiedTime(p).toMillis() > 3_600_000L) deleteTree(p);
            dirs.removeIf(p -> p.getFileName().toString().contains(".tmp") || !Files.exists(p));
            dirs.sort(Comparator.comparingLong((Path p) -> { try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0L; } }).reversed());
            for (int i = keep; i < dirs.size(); i++) deleteTree(dirs.get(i));
        }

        static void deleteTree(Path p) throws IOException {
            if (!Files.exists(p)) return;
            try (var s = Files.walk(p)) { for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(x); }
        }

        static String codeId() throws Exception {
            Path src = Path.of(PrepGame.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(src)) return sha1(src);
            StringBuilder sb = new StringBuilder();
            try (var s = Files.list(src)) {
                for (Path p : s.filter(x -> x.getFileName().toString().startsWith("PrepGame")).sorted().toList()) sb.append(sha1(p));
            }
            return sha1(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        static String sha1(Path p) throws Exception {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            try (InputStream in = new BufferedInputStream(Files.newInputStream(p), 1 << 16)) {
                byte[] buf = new byte[1 << 16];
                for (int r; (r = in.read(buf)) > 0; ) md.update(buf, 0, r);
            }
            return java.util.HexFormat.of().formatHex(md.digest());
        }

        static String sha1(byte[] b) {
            try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(b)); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    static String superOf(String t) {
        String c = SUPER_CACHE.get(t);
        if (c != null) return c;
        String sup = "java/lang/Object";
        try {
            if (HIER_ZIPS.isEmpty()) for (Path p : HIER_JARS) HIER_ZIPS.add(new ZipFile(p.toFile()));
            HIER_LOOKUPS.add(t);
            boolean found = false;
            for (ZipFile z : HIER_ZIPS) {
                ZipEntry e = z.getEntry(t + ".class");
                if (e == null) continue;
                ClassReader r = new ClassReader(z.getInputStream(e).readAllBytes());
                sup = (r.getAccess() & Opcodes.ACC_INTERFACE) != 0 ? "<interface>" : r.getSuperName();
                found = true;
                break;
            }
            if (!found) {
                Class<?> k = Class.forName(t.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
                sup = k.isInterface() ? "<interface>" : k.getSuperclass() == null ? null : k.getSuperclass().getName().replace('.', '/');
            }
        } catch (Throwable ex) { /* unknown library class: Object */ }
        SUPER_CACHE.put(t, sup);
        return sup;
    }

    static final class FrameWriter extends ClassWriter {
        FrameWriter() { super(ClassWriter.COMPUTE_FRAMES); }
        @Override protected String getCommonSuperClass(String a, String b) {
            if (a.equals(b)) return a;
            if ("<interface>".equals(superOf(a)) || "<interface>".equals(superOf(b))) return "java/lang/Object";
            Set<String> chain = new LinkedHashSet<>();
            for (String c = a; c != null; c = superOf(c)) chain.add(c);
            for (String c = b; c != null; c = superOf(c)) if (chain.contains(c)) return c;
            return "java/lang/Object";
        }
    }

    static boolean apply(ClassNode cn, AT t) {
        if (t.member == null) { int o = cn.access; cn.access = widen(cn.access, t.acc, t.finalMode); fixInner(cn, t); return o != cn.access || true; }
        boolean hit = false;
        if (t.desc == null) {
            for (FieldNode fd : cn.fields) if (t.member.equals("*") || fd.name.equals(t.member)) { fd.access = widen(fd.access, t.acc, t.finalMode); hit = true; }
        } else {
            for (MethodNode m : cn.methods)
                if ((t.member.equals("*") && !m.name.equals("<clinit>")) || (m.name.equals(t.member) && (t.desc.equals("*") || m.desc.equals(t.desc)))) {
                    m.access = widen(m.access, t.acc, t.finalMode);
                    // a private method made non-private must be called with INVOKEVIRTUAL; javac-compiled callers in
                    // the same class use INVOKESPECIAL/INVOKEVIRTUAL (nestmates) - both valid for non-private too.
                    hit = true;
                }
        }
        return hit;
    }

    static void fixInner(ClassNode cn, AT t) {
        if (cn.innerClasses != null) for (InnerClassNode ic : cn.innerClasses) if (ic.name.equals(cn.name)) ic.access = widen(ic.access, t.acc, t.finalMode);
    }

    static final int VIS = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE;
    static int rank(int acc) { return (acc & Opcodes.ACC_PUBLIC) != 0 ? 3 : (acc & Opcodes.ACC_PROTECTED) != 0 ? 2 : (acc & Opcodes.ACC_PRIVATE) != 0 ? 0 : 1; }

    static int widen(int access, int want, int finalMode) {
        int r = access;
        if (rank(want) > rank(access)) r = (access & ~VIS) | want; // ATs only ever widen
        if (finalMode == 1) r &= ~Opcodes.ACC_FINAL;
        if (finalMode == 2) r |= Opcodes.ACC_FINAL;
        return r;
    }

    static List<AT> readAts(Path p) throws IOException {
        List<String> lines;
        List<AT> out = new ArrayList<>();
        if (p.toString().endsWith(".jar")) {
            try (ZipFile z = new ZipFile(p.toFile())) {
                // Multi-loader mods sometimes ship only a Fabric access widener (root *.accesswidener) and rely on it at
                // runtime by mistake: real Forge ignores it and the mod hits IllegalAccessError. Alloy is Fabric
                // underneath, so honour it (widen-only, like ATs). Example: Better Advancements 0.6.0.76.
                for (ZipEntry e : Collections.list(z.entries()))
                    if (!e.getName().contains("/") && e.getName().endsWith(".accesswidener"))
                        out.addAll(readAccessWidener(new String(z.getInputStream(e).readAllBytes()).lines().toList()));
                ZipEntry e = z.getEntry("META-INF/accesstransformer.cfg");
                if (e == null) return out;
                lines = new String(z.getInputStream(e).readAllBytes()).lines().toList();
            }
        } else lines = Files.readAllLines(p);
        for (String raw : lines) {
            String l = raw.replaceAll("#.*", "").trim();
            if (l.isEmpty()) continue;
            String[] s = l.split("\\s+");
            String mod = s[0];
            int fm = mod.endsWith("-f") ? 1 : mod.endsWith("+f") ? 2 : 0;
            String v = mod.replaceAll("[-+]f$", "");
            int acc = switch (v) { case "public" -> Opcodes.ACC_PUBLIC; case "protected" -> Opcodes.ACC_PROTECTED; case "private" -> Opcodes.ACC_PRIVATE; default -> 0; };
            String cls = s[1].replace('.', '/');
            if (s.length == 2) { out.add(new AT(cls, null, null, acc, fm)); continue; }
            String mem = s[2];
            int paren = mem.indexOf('(');
            if (paren >= 0) out.add(new AT(cls, mem.substring(0, paren), mem.substring(paren), acc, fm));
            else out.add(new AT(cls, mem, null, acc, fm));
        }
        return out;
    }

    /** Fabric access widener v1/v2 (official = Mojang names on 26.1) as widen-only ATs. */
    static List<AT> readAccessWidener(List<String> lines) {
        List<AT> out = new ArrayList<>();
        for (String raw : lines) {
            String l = raw.replaceAll("#.*", "").trim();
            if (l.isEmpty() || l.startsWith("accessWidener")) continue;
            String[] s = l.split("\\s+");
            if (s.length < 3) continue;
            String kind = s[0].replace("transitive-", "");
            int acc = kind.equals("extendable") ? Opcodes.ACC_PROTECTED : Opcodes.ACC_PUBLIC;
            int fm = kind.equals("accessible") ? 0 : 1;           // mutable / extendable remove final
            switch (s[1]) {
                case "class" -> out.add(new AT(s[2], null, null, kind.equals("extendable") ? Opcodes.ACC_PUBLIC : acc, fm));
                case "field" -> { if (s.length >= 4) out.add(new AT(s[2], s[3], null, kind.equals("mutable") ? Opcodes.ACC_PRIVATE : acc, fm)); }
                case "method" -> { if (s.length >= 5) out.add(new AT(s[2], s[3], s[4], acc, fm)); }
                default -> { }
            }
        }
        return out;
    }

    // =====================================================================================================================
    // Automatic anchors (-Dalloy.autoanchors=true). Per class, after the hand-written anchors: diff the call / field / NEW
    // references of each method against the vanilla class. A vanilla reference that only lives inside a Forge STATIC wrapper
    // (depth <= 3, public members only, no try/catch) is brought back by inlining the wrapper at its call site (Forge's events
    // stay); Forge's recompile renumbers lambdas, so lambdas are paired with vanilla's by body and renamed back. The class is
    // written, re-read and data-flow verified; if anything fails the class is left exactly as the hand-written steps made it.
    // The rename map (forge lambda name -> vanilla name, per class) is written to <out>.lambdas.txt for RemapForgeMixins.
    // =====================================================================================================================
    static final boolean AUTO = !"false".equals(System.getProperty("alloy.autoanchors"));   // default on since 2026-11-11 (server 185/185, client 29/30, Fabric top-40 with it); -Dalloy.autoanchors=false to disable
    /** Forge hook calls the automatic engine must never inline: Alloy compat mixins (bridge/src/alloy/compat)
     *  target these call sites (@At INVOKE). Keep in sync with: grep -rhoE "Lnet/minecraftforge/[A-Za-z/]+;[A-Za-z_$]+[(]" bridge/src/alloy/compat
     *  Extra entries: -Dalloy.autoanchors.keep=owner.name,owner.name */
    static final Set<String> AUTO_KEEP = new HashSet<>(List.of(
            "net/minecraftforge/common/ForgeHooks.onBlockBreakEvent", "net/minecraftforge/common/ForgeHooks.onLivingDamage",
            "net/minecraftforge/common/ForgeHooks.onLivingHurt", "net/minecraftforge/common/ForgeHooks.readConditional",
            "net/minecraftforge/event/ForgeEventFactory.onRightClickEmpty"));
    static { for (String k : System.getProperty("alloy.autoanchors.keep", "").split(",")) if (!k.isBlank()) AUTO_KEEP.add(k.trim()); }

    /** Forge hook calls that installed Forge mods' mixins target (@At target="Lnet/minecraftforge/...;name(..."): a mod
     *  made for Forge expects the hook call itself, so neither the hand-written nor the automatic anchors may inline it.
     *  Mixin target strings are the only place a class file holds the "Lowner;name(" form. The Forge jars themselves are skipped. */
    static final java.util.regex.Pattern MOD_TARGET = java.util.regex.Pattern.compile("L(net/minecraftforge/[A-Za-z0-9_/$]+);([A-Za-z0-9_$]+)[(]");
    static void keepModTargets(List<Path> jars) {
        Set<String> found = new TreeSet<>();
        for (Path p : jars) {
            try (ZipFile z = new ZipFile(p.toFile())) {
                if (z.getEntry("net/minecraftforge/common/ForgeHooks.class") != null) continue;   // Forge's own jar
                for (ZipEntry e : Collections.list(z.entries())) {
                    if (!e.getName().endsWith(".class")) continue;
                    String t = new String(z.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                    if (!t.contains("Lnet/minecraftforge/")) continue;
                    var mt = MOD_TARGET.matcher(t);
                    while (mt.find()) found.add(mt.group(1) + "." + mt.group(2));
                }
            } catch (Exception ignored) { }
        }
        found.removeIf(AUTO_KEEP::contains);
        if (!found.isEmpty()) System.out.println("anchors: kept " + found.size() + " Forge hook call(s) that installed Forge mods target: " + found);
        AUTO_KEEP.addAll(found);
    }
    /** class -> (forge name+desc -> vanilla name) for the lambdas renamed so far */
    static final Map<String, Map<String, String>> LAMBDA_RENAMES = new TreeMap<>();

    static AT translateAt(AT t) {
        if (!AUTO || t.member == null || t.desc == null) return t;
        Map<String, String> r = LAMBDA_RENAMES.get(t.cls);
        if (r == null) return t;
        String n = r.get(t.member + t.desc);
        return n == null ? t : new AT(t.cls, n, t.desc, t.acc, t.finalMode);
    }

    static void writeLambdaMap(Path out) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# Alloy lambda renames: class<TAB>forge name<TAB>descriptor<TAB>vanilla name (written by PrepGame, read by RemapForgeMixins)");
        for (var c : LAMBDA_RENAMES.entrySet())
            for (var e : new TreeMap<>(c.getValue()).entrySet()) {
                String k = e.getKey();
                int p = k.indexOf('(');
                lines.add(c.getKey() + "\t" + k.substring(0, p) + "\t" + k.substring(p) + "\t" + e.getValue());
            }
        Files.write(Path.of(out + ".lambdas.txt"), lines);
    }

    static final class Auto {
        static ZipFile vanillaZip, forgeZip, universalZip;
        static final Map<String, ClassNode> VAN = new HashMap<>(), PAT = new HashMap<>();
        static ClassNode cur;                    // the class being processed (current state), so own-class lookups see it
        static int classesChanged, inlinedCalls, lambdasRenamed, verifyFailures;
        static final List<String> LOG = new ArrayList<>();

        static void init(Path vanilla, Path forge, Path universal) throws IOException {
            vanillaZip = new ZipFile(vanilla.toFile()); forgeZip = new ZipFile(forge.toFile());
            universalZip = universal == null ? null : new ZipFile(universal.toFile());
        }
        static byte[] bytes(ZipFile z, String name) {
            if (z == null) return null;
            try { ZipEntry e = z.getEntry(name + ".class"); return e == null ? null : z.getInputStream(e).readAllBytes(); }
            catch (IOException ex) { return null; }
        }
        static ClassNode node(Map<String, ClassNode> cache, ZipFile z, String name) {
            if (cache != null && cache.containsKey(name)) return cache.get(name);
            byte[] b = bytes(z, name);
            ClassNode cn = null;
            if (b != null) { cn = new ClassNode(); new ClassReader(b).accept(cn, ClassReader.SKIP_FRAMES); }
            if (cache != null) cache.put(name, cn);
            return cn;
        }
        static ClassNode vanilla(String n) { return node(VAN, vanillaZip, n); }
        static ClassNode patched(String n) { return cur != null && cur.name.equals(n) ? cur : node(PAT, forgeZip, n); }
        static ClassNode forgeOrPatched(String n) {
            ClassNode c = universalZip == null ? null : node(UNI, universalZip, n);
            return c != null ? c : patched(n);
        }
        static final Map<String, ClassNode> UNI = new HashMap<>();

        static MethodNode method(ClassNode cn, String name, String desc) {
            if (cn == null) return null;
            for (MethodNode m : cn.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
            return null;
        }
        static MethodNode resolve(String owner, String name, String desc) {
            ClassNode c = resolveOwner(owner, name, desc);
            return c == null ? null : method(c, name, desc);
        }
        static ClassNode resolveOwner(String owner, String name, String desc) {
            Deque<String> q = new ArrayDeque<>(List.of(owner));
            Set<String> seen = new HashSet<>();
            while (!q.isEmpty()) {
                String o = q.poll();
                if (!seen.add(o)) continue;
                ClassNode c = forgeOrPatched(o);
                if (c == null) continue;
                if (method(c, name, desc) != null) return c;
                if (c.superName != null) q.add(c.superName);
                q.addAll(c.interfaces);
            }
            return null;
        }
        static MethodNode vanillaResolve(String owner, String name, String desc) {
            Deque<String> q = new ArrayDeque<>(List.of(owner));
            Set<String> seen = new HashSet<>();
            while (!q.isEmpty()) {
                String o = q.poll();
                if (!seen.add(o)) continue;
                ClassNode c = vanilla(o);
                if (c == null) continue;
                MethodNode m = method(c, name, desc);
                if (m != null) return m;
                if (c.superName != null) q.add(c.superName);
                q.addAll(c.interfaces);
            }
            return null;
        }

        static String ref(AbstractInsnNode n) {
            if (n instanceof MethodInsnNode mi) return "M " + mi.owner + "." + mi.name + mi.desc;
            if (n instanceof FieldInsnNode fi) return "F " + fi.owner + "." + fi.name + ":" + fi.desc;
            if (n instanceof TypeInsnNode ti && n.getOpcode() == Opcodes.NEW) return "N " + ti.desc;
            return null;
        }
        static Map<String, Integer> refs(MethodNode m) {
            Map<String, Integer> r = new HashMap<>();
            for (AbstractInsnNode n : m.instructions) { String s = ref(n); if (s != null) r.merge(s, 1, Integer::sum); }
            return r;
        }
        static boolean contains(MethodNode m, String r) {
            for (AbstractInsnNode n : m.instructions) if (r.equals(ref(n))) return true;
            return false;
        }
        static boolean isForgeCall(MethodInsnNode mi) {
            if (mi.owner.startsWith("net/minecraftforge/")) return true;
            if (vanilla(mi.owner) == null) return false;
            return vanillaResolve(mi.owner, mi.name, mi.desc) == null && patched(mi.owner) != null;
        }

        /** null if the static method can be copied into another class, else the reason */
        static String blocker(ClassNode origin, MethodNode callee) {
            if ((callee.access & Opcodes.ACC_STATIC) == 0) return "instance";
            if (!callee.tryCatchBlocks.isEmpty()) return "try/catch";
            if ((callee.access & Opcodes.ACC_SYNCHRONIZED) != 0) return "synchronized";
            for (AbstractInsnNode n : callee.instructions) {
                if (n instanceof InvokeDynamicInsnNode id) {
                    if (id.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")) continue;
                    for (Object a : id.bsmArgs)
                        if (a instanceof Handle h) {
                            ClassNode hc = forgeOrPatched(h.getOwner());
                            MethodNode hm = method(hc, h.getName(), h.getDesc());
                            if (hm != null && (hm.access & Opcodes.ACC_PUBLIC) == 0) return "private lambda " + h.getName();
                            if (hc != null && (hc.access & Opcodes.ACC_PUBLIC) == 0) return "non-public class " + h.getOwner();
                        }
                    continue;
                }
                String owner = null, name = null, desc = null; boolean field = false;
                if (n instanceof MethodInsnNode mi) { owner = mi.owner; name = mi.name; desc = mi.desc; }
                else if (n instanceof FieldInsnNode fi) { owner = fi.owner; name = fi.name; desc = fi.desc; field = true; }
                else if (n instanceof TypeInsnNode ti) owner = ti.desc;
                else if (n instanceof LdcInsnNode l && l.cst instanceof Type t && t.getSort() == Type.OBJECT) owner = t.getInternalName();
                if (owner == null || owner.startsWith("[")) continue;
                ClassNode oc = owner.equals(origin.name) ? origin : forgeOrPatched(owner);
                if (oc == null) continue;
                if ((oc.access & Opcodes.ACC_PUBLIC) == 0) return "non-public class " + owner;
                if (name == null) continue;
                if (field) {
                    ClassNode fo = oc; FieldNode found = null;
                    while (fo != null && found == null) { for (FieldNode f : fo.fields) if (f.name.equals(name)) found = f; if (found == null) fo = fo.superName == null ? null : forgeOrPatched(fo.superName); }
                    if (found != null && (found.access & Opcodes.ACC_PUBLIC) == 0) return "non-public field " + owner + "." + name;
                } else {
                    ClassNode mo = resolveOwner(owner, name, desc);
                    MethodNode mm = mo == null ? null : method(mo, name, desc);
                    if (mm != null && (mm.access & Opcodes.ACC_PUBLIC) == 0) return "non-public method " + owner + "." + name;
                }
            }
            return null;
        }

        record Path_(List<MethodInsnNode> calls, List<MethodNode> bodies) {}
        static Path_ findPath(MethodNode m, String r, int depth, Set<String> seen) {
            for (AbstractInsnNode n : m.instructions) {
                if (!(n instanceof MethodInsnNode mi) || !isForgeCall(mi)) continue;
                if (!seen.add(mi.owner + "." + mi.name + mi.desc)) continue;
                MethodNode body = resolve(mi.owner, mi.name, mi.desc);
                if (body == null) continue;
                if (contains(body, r)) return new Path_(new ArrayList<>(List.of(mi)), new ArrayList<>(List.of(body)));
                if (depth > 1) {
                    Path_ p = findPath(body, r, depth - 1, seen);
                    if (p != null) { p.calls.add(0, mi); p.bodies.add(0, body); return p; }
                }
            }
            return null;
        }
        /** true when ref r of vanilla is only lost into an inlinable chain of Forge static wrappers */
        static boolean inlinable(MethodNode pm, String r) {
            Path_ p = findPath(pm, r, 3, new HashSet<>());
            if (p == null) return false;
            String block = null;
            for (int i = 0; i < p.calls.size(); i++) {
                MethodInsnNode c = p.calls.get(i);
                if (c.getOpcode() != Opcodes.INVOKESTATIC) return false;
                if (PrepGame.AUTO_KEEP.contains(c.owner + "." + c.name)) return false;   // an Alloy compat mixin hooks this call
                if (block == null) block = blocker(resolveOwner(c.owner, c.name, c.desc), p.bodies.get(i));
            }
            return block == null;
        }
        /** inline the first wrapper of the path for ref r (every call of it in pm); returns inlined calls, < 0 when r is still missing */
        static int materialize(MethodNode pm, String r) {
            int done = 0;
            for (int iter = 0; iter < 4 && !contains(pm, r); iter++) {
                Path_ p = findPath(pm, r, 3, new HashSet<>());
                if (p == null) break;
                MethodInsnNode first = p.calls.get(0);
                if (first.getOpcode() != Opcodes.INVOKESTATIC) break;
                if (PrepGame.AUTO_KEEP.contains(first.owner + "." + first.name)) break;
                if (blocker(resolveOwner(first.owner, first.name, first.desc), p.bodies.get(0)) != null) break;
                for (AbstractInsnNode n : pm.instructions.toArray())
                    if (n instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC && mi.owner.equals(first.owner) && mi.name.equals(first.name) && mi.desc.equals(first.desc)) {
                        inlineStatic(pm, mi, p.bodies.get(0)); done++;
                    }
            }
            return contains(pm, r) ? done : -done - 1;
        }

        // ---- lambda pairing (Forge's recompile renumbers lambdas): by body, then fuzzy
        static boolean isLambda(MethodNode m) { return m.name.startsWith("lambda$"); }
        static String prefix(String n) { return n.substring(0, n.lastIndexOf('$') + 1); }
        static int insnCount(MethodNode m) { int c = 0; for (AbstractInsnNode n : m.instructions) if (n.getOpcode() >= 0) c++; return c; }
        static String sig(MethodNode m) { return m.desc + "|" + new TreeMap<>(refs(m)) + "|" + insnCount(m); }
        static double jaccard(Map<String, Integer> x, Map<String, Integer> y) {
            int inter = 0, uni = 0;
            Set<String> keys = new HashSet<>(x.keySet()); keys.addAll(y.keySet());
            for (String k : keys) { int a = x.getOrDefault(k, 0), b = y.getOrDefault(k, 0); inter += Math.min(a, b); uni += Math.max(a, b); }
            return uni == 0 ? 1 : (double) inter / uni;
        }
        static Map<String, String> matchLambdas(ClassNode vc, ClassNode pc) {
            Map<String, String> pair = new HashMap<>();
            List<MethodNode> vl = new ArrayList<>(), pl = new ArrayList<>();
            for (MethodNode m : vc.methods) if (isLambda(m)) vl.add(m);
            for (MethodNode m : pc.methods) if (isLambda(m)) pl.add(m);
            if (vl.isEmpty()) return pair;
            Map<String, List<MethodNode>> vs = new HashMap<>(), ps = new HashMap<>();
            for (MethodNode m : vl) vs.computeIfAbsent(sig(m), k -> new ArrayList<>()).add(m);
            for (MethodNode m : pl) ps.computeIfAbsent(sig(m), k -> new ArrayList<>()).add(m);
            Set<MethodNode> usedP = new HashSet<>();
            for (Map.Entry<String, List<MethodNode>> e : vs.entrySet()) {
                List<MethodNode> pm = ps.get(e.getKey());
                if (pm == null) continue;
                List<MethodNode> left = new ArrayList<>(pm), todo = new ArrayList<>();
                for (MethodNode v : e.getValue()) {
                    MethodNode hit = null;
                    for (MethodNode p : left) if (p.name.equals(v.name)) hit = p;
                    if (hit == null) { todo.add(v); continue; }
                    left.remove(hit); usedP.add(hit); pair.put(v.name + v.desc, hit.name + hit.desc);
                }
                for (MethodNode v : todo) {
                    if (left.isEmpty()) break;
                    MethodNode hit = left.remove(0);
                    usedP.add(hit); pair.put(v.name + v.desc, hit.name + hit.desc);
                }
            }
            Map<MethodNode, MethodNode> best = new HashMap<>();
            Map<MethodNode, Double> score = new HashMap<>();
            for (MethodNode v : vl) {
                if (pair.containsKey(v.name + v.desc)) continue;
                MethodNode bp = null; double bs = 0.5;
                for (MethodNode p : pl) if (!usedP.contains(p) && p.desc.equals(v.desc) && prefix(p.name).equals(prefix(v.name))) {
                    double sc = jaccard(refs(v), refs(p)) + (p.name.equals(v.name) ? 0.01 : 0);
                    if (sc > bs) { bs = sc; bp = p; }
                }
                if (bp != null) { best.put(v, bp); score.put(v, bs); }
            }
            for (Map.Entry<MethodNode, MethodNode> e : best.entrySet()) {
                MethodNode p = e.getValue(), v = e.getKey();
                boolean mutual = true;
                for (Map.Entry<MethodNode, MethodNode> o : best.entrySet())
                    if (o.getKey() != v && o.getValue() == p && score.get(o.getKey()) >= score.get(v)) mutual = false;
                if (mutual && usedP.add(p)) pair.put(v.name + v.desc, p.name + p.desc);
            }
            return pair;
        }

        record Fix(String pkey, String ref) {}

        /**
         * Returns the changed copy of cn (verified), or null when nothing changed or verification failed (cn untouched).
         * owned: "name+desc" of methods the hand-written anchors already rewrote (left alone).
         */
        static ClassNode run(ClassNode cn, byte[] orig, Set<String> owned) {
            byte[] vb = bytes(vanillaZip, cn.name);
            if (vb == null || Arrays.equals(vb, orig)) return null;
            ClassNode vc = new ClassNode();
            new ClassReader(vb).accept(vc, ClassReader.SKIP_FRAMES);
            VAN.remove(cn.name);
            cur = cn;
            Map<String, MethodNode> pms = new HashMap<>();
            for (MethodNode m : cn.methods) pms.put(m.name + m.desc, m);
            Map<String, String> lp = matchLambdas(vc, cn);
            Map<String, String> ren = new HashMap<>();   // forge key (name+desc) -> new name
            for (Map.Entry<String, String> e : lp.entrySet()) if (!e.getKey().equals(e.getValue()))
                ren.put(e.getValue(), e.getKey().substring(0, e.getKey().indexOf('(')));
            if (!ren.isEmpty()) {
                Set<String> targets = new HashSet<>();
                for (Map.Entry<String, String> e : ren.entrySet()) targets.add(e.getValue() + e.getKey().substring(e.getKey().indexOf('(')));
                int k = 0;
                for (String t : new TreeSet<>(targets)) if (pms.containsKey(t) && !ren.containsKey(t)) ren.put(t, t.substring(0, t.indexOf('(')) + "$alloy" + (k++));
            }
            List<Fix> fixes = new ArrayList<>();
            for (MethodNode vm : vc.methods) {
                String vkey = vm.name + vm.desc;
                MethodNode pm = isLambda(vm) ? (lp.containsKey(vkey) ? pms.get(lp.get(vkey)) : null) : pms.get(vkey);
                if (pm == null || owned.contains(pm.name + pm.desc)) continue;
                Map<String, Integer> vr = refs(vm), pr = refs(pm);
                for (Map.Entry<String, Integer> e : vr.entrySet())
                    if (e.getValue() - pr.getOrDefault(e.getKey(), 0) > 0 && inlinable(pm, e.getKey())) fixes.add(new Fix(pm.name + pm.desc, e.getKey()));
            }
            if (fixes.isEmpty() && ren.isEmpty()) { cur = null; return null; }
            ClassNode w = new ClassNode();
            cn.accept(w);
            cur = w;
            Set<String> touched = new HashSet<>();
            int inl = 0;
            for (Fix f : fixes) {
                String key = f.pkey;
                MethodNode pm = method(w, key.substring(0, key.indexOf('(')), key.substring(key.indexOf('(')));
                int n = materialize(pm, f.ref);
                int c = n >= 0 ? n : -n - 1;
                if (c > 0) { touched.add(key); inl += c; }
            }
            if (!ren.isEmpty()) {
                for (MethodNode m : w.methods) { String nn = ren.get(m.name + m.desc); if (nn != null) { m.name = nn; touched.add(m.name + m.desc); } }
                for (MethodNode m : w.methods) for (AbstractInsnNode n : m.instructions) {
                    if (n instanceof MethodInsnNode mi && mi.owner.equals(w.name) && ren.containsKey(mi.name + mi.desc)) mi.name = ren.get(mi.name + mi.desc);
                    if (n instanceof InvokeDynamicInsnNode id) for (int i = 0; i < id.bsmArgs.length; i++)
                        if (id.bsmArgs[i] instanceof Handle h && h.getOwner().equals(w.name) && ren.containsKey(h.getName() + h.getDesc()))
                            id.bsmArgs[i] = new Handle(h.getTag(), h.getOwner(), ren.get(h.getName() + h.getDesc()), h.getDesc(), h.isInterface());
                }
            }
            if (touched.isEmpty()) { cur = null; return null; }
            // verify the WRITTEN class (frames and max stack recomputed) with a data-flow check; never load a class
            try {
                ClassWriter cw = new FrameWriter();
                w.accept(cw);
                ClassNode back = new ClassNode();
                new ClassReader(cw.toByteArray()).accept(back, 0);
                for (MethodNode m : back.methods)
                    if (touched.contains(m.name + m.desc)) new org.objectweb.asm.tree.analysis.Analyzer<>(new HierVerifier()).analyze(back.name, m);
            } catch (Throwable ex) {
                verifyFailures++;
                LOG.add("auto-anchors reverted " + cn.name + ": " + ex + (ex.getCause() != null ? " cause " + ex.getCause() + " @ " + Arrays.toString(Arrays.copyOf(ex.getCause().getStackTrace(), 4)) : ""));
                cur = null;
                return null;
            }
            cur = null;
            classesChanged++; inlinedCalls += inl; lambdasRenamed += ren.size();
            if (!ren.isEmpty()) LAMBDA_RENAMES.put(cn.name, ren);
            return w;
        }

        // ---- class hierarchy for the verifier (forge jar, universal jar, extra libs, JDK; nothing is loaded)
        static final Map<String, String[]> HIER = new HashMap<>();
        static String[] hier(String name) {
            if (HIER.containsKey(name)) return HIER.get(name);
            String[] r = null;
            byte[] b = bytes(forgeZip, name);
            if (b == null) b = bytes(universalZip, name);
            if (b == null) {
                if (HIER_ZIPS.isEmpty()) try { for (Path p : HIER_JARS) HIER_ZIPS.add(new ZipFile(p.toFile())); } catch (IOException ex) { /* ignore */ }
                HIER_LOOKUPS.add(name);
                for (ZipFile z : HIER_ZIPS) if ((b = bytes(z, name)) != null) break;
            }
            if (b != null) {
                ClassReader cr = new ClassReader(b);
                String[] ifs = cr.getInterfaces();
                r = new String[2 + ifs.length];
                r[0] = cr.getSuperName(); r[1] = (cr.getAccess() & Opcodes.ACC_INTERFACE) != 0 ? "1" : "0";
                System.arraycopy(ifs, 0, r, 2, ifs.length);
            } else {
                try {
                    Class<?> c = Class.forName(name.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
                    Class<?>[] ifs = c.getInterfaces();
                    r = new String[2 + ifs.length];
                    r[0] = c.getSuperclass() == null ? null : c.getSuperclass().getName().replace('.', '/');
                    r[1] = c.isInterface() ? "1" : "0";
                    for (int i = 0; i < ifs.length; i++) r[2 + i] = ifs[i].getName().replace('.', '/');
                } catch (Throwable t) { /* unknown */ }
            }
            HIER.put(name, r);
            return r;
        }
        static boolean isIface(String n) { String[] h = hier(n); return h != null && h[1].equals("1"); }
        static boolean subtype(String sub, String sup) {
            if (sup.equals("java/lang/Object") || sub.equals(sup)) return true;
            String[] h = hier(sub);
            if (h == null) return false;
            if (h[0] != null && subtype(h[0], sup)) return true;
            for (int i = 2; i < h.length; i++) if (subtype(h[i], sup)) return true;
            return false;
        }
        static final class HierVerifier extends org.objectweb.asm.tree.analysis.SimpleVerifier {
            HierVerifier() { super(Opcodes.ASM9, null, null, null, false); }
            @Override protected boolean isInterface(Type t) { return isIface(t.getInternalName()); }
            @Override protected Type getSuperClass(Type t) { String[] h = hier(t.getInternalName()); return h == null || h[0] == null ? null : Type.getObjectType(h[0]); }
            @Override protected boolean isSubTypeOf(org.objectweb.asm.tree.analysis.BasicValue value, org.objectweb.asm.tree.analysis.BasicValue expected) {
                Type t = value.getType(), e = expected.getType();
                if (t != null && e != null && (t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY) && (e.getSort() == Type.OBJECT || e.getSort() == Type.ARRAY)) {
                    // a class that is in none of the jars (a library) cannot be checked: assume it fits
                    if (t.getSort() == Type.OBJECT && e.getSort() == Type.OBJECT && (hier(t.getInternalName()) == null || hier(e.getInternalName()) == null)) return true;
                    return isAssignableFrom(e, t);
                }
                return super.isSubTypeOf(value, expected);
            }
            @Override protected boolean isAssignableFrom(Type t, Type u) {
                if (t.equals(u)) return true;
                if (t.getSort() == Type.OBJECT && u.getSort() == Type.OBJECT && (hier(t.getInternalName()) == null || hier(u.getInternalName()) == null)) return true;
                if (t.getSort() == Type.OBJECT && u.getSort() == Type.OBJECT) {
                    if (isIface(t.getInternalName())) return true;
                    return subtype(u.getInternalName(), t.getInternalName());
                }
                if (t.getSort() == Type.ARRAY && u.getSort() == Type.ARRAY) {
                    Type te = t.getElementType(), ue = u.getElementType();
                    if (t.getDimensions() == u.getDimensions() && te.getSort() == Type.OBJECT && ue.getSort() == Type.OBJECT) return isAssignableFrom(te, ue);
                    return t.equals(u);
                }
                if (t.getSort() == Type.OBJECT && u.getSort() == Type.ARRAY)
                    return t.getInternalName().equals("java/lang/Object") || t.getInternalName().equals("java/lang/Cloneable") || t.getInternalName().equals("java/io/Serializable");
                return false;
            }
            @Override protected Class<?> getClass(Type t) { throw new UnsupportedOperationException(t.toString()); }
        }
    }
}
