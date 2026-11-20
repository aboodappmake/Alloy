package alloy;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs FML's ModLauncher launch plugins (fmlloader META-INF/services ILaunchPluginService: capability token
 * subclasses, runtime enum extension, dist cleaner, SLF4J fixer) on every class Knot loads, by wrapping Knot's
 * IMixinTransformer: bytes go through the Forge plugins first, then through Mixin as usual.
 */
public final class ForgeLaunchPlugins {
    private static List<ILaunchPluginService> plugins = List.of();
    /** target internal name -> interfaces to add (from <game>/alloy-inject.txt, written by the Fabric mod adapter) */
    private static java.util.Map<String, List<String>> inject = java.util.Map.of();
    private static final ThreadLocal<Boolean> BUSY = ThreadLocal.withInitial(() -> false);

    private static volatile boolean hooked, ready;
    private static final List<String> EARLY = java.util.Collections.synchronizedList(new ArrayList<>());

    /** Wraps Knot's IMixinTransformer once. Called before FMLConfig.load() (so night-config's ConfigWriter, loaded there, gets
     *  the config-move redirect) and again from install(dist), which then switches on the Forge launch plugins (ready). */
    static synchronized void installHook() throws Exception {
        if (hooked) return;
        // warm up classes used inside the hook, so they are not first loaded (and re-entered) mid-transform
        new ClassWriter(0);
        new ClassNode();
        Type.getObjectType("a/B");
        new HierarchyWriter(0);
        ILaunchPluginService.Phase.values();
        KeyMapView.LOG = (lvl, msg) -> { if (lvl.equals("warn")) Bridge.LOG.warn(msg); else Bridge.LOG.info(msg); };

        Object knot = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher();
        Field clf = knot.getClass().getDeclaredField("classLoader");
        clf.setAccessible(true);
        Object delegate = clf.get(knot);
        Field mtf = delegate.getClass().getDeclaredField("mixinTransformer");
        mtf.setAccessible(true);
        Object real = mtf.get(delegate);
        if (real == null) throw new IllegalStateException("Knot mixin transformer not initialised yet");
        // a real class with a 'delegate' field, not a Proxy: Fabric mods follow that field to find Mixin's own transformer
        mtf.set(delegate, new MixinTransformerWrapper((org.spongepowered.asm.mixin.transformer.IMixinTransformer) real));
        hooked = true;
    }

    /** FML loader and language provider classes (real Forge keeps them in its boot and plugin layers, untransformed);
     *  the scan threads load them, see installHook. Kotlin for Forge's provider and its Kotlin runtime too. */
    private static final String[] LOADER_PREFIXES = {"net.minecraftforge.fml.loading.", "cpw.mods.", "net.minecraftforge.fml.javafmlmod.",
            "net.minecraftforge.fml.mclanguage.", "net.minecraftforge.fml.lowcode.", "net.minecraftforge.forgespi.",
            "thedarkcolour.kotlinforforge.", "thedarkcolour.kfflang.", "kotlin."};

    private static volatile Thread discovery;
    private static volatile boolean scanWindow;

    /** From mod discovery until every background scan is done, classes loaded by threads other than the discovery thread
     *  are FML scan work (loader classes, language providers and their libraries): Mixin is skipped for them, as above. */
    static void beginScanWindow(Thread t) { discovery = t; scanWindow = true; }
    static void endScanWindow() { scanWindow = false; }
    private static volatile Thread scanThread;

    /**
     * Only FML's scan work: a thread that runs FML's mod discovery code (BackgroundScanHandler's executor, language
     * provider visitors). Other threads keep the full transform during the window: the game goes on while the scan
     * runs, and its Bootstrap thread (registries, IdMapper) needs Forge's access transformers and every mixin
     * (IllegalAccessError on IdMapper.tToId in Forgeulously Optimized when the scan was slow).
     */
    static boolean inScanThread() {
        if (!scanWindow) return false;
        Thread t = Thread.currentThread();
        if (t == discovery) return false;
        if (t == scanThread) return true;
        if (!t.isDaemon()) return false;
        boolean scan = StackWalker.getInstance().walk(s -> s.anyMatch(f -> f.getClassName().startsWith("net.minecraftforge.fml.loading.moddiscovery.")));
        if (scan) scanThread = t;
        return scan;
    }

    static boolean loaderClass(String n) {
        for (String p : LOADER_PREFIXES) if (n.startsWith(p)) return true;
        return false;
    }

    /**
     * Forge's IForge* interfaces and interfaces that Fabric libraries add to the same Minecraft class (Porting Lib's
     * ItemExtensions, PlayerAttributesExtensions, BlockStateExtensions, ...) often have a default method with the same name
     * and descriptor. Every call that no mod overrides then throws "IncompatibleClassChangeError: Conflicting default
     * methods" (Farmer's Delight Refabricated: getDefaultTooltipHideFlags, getBlockReach, ...). After Mixin has added the
     * Fabric interfaces, this adds a concrete method to the Minecraft class that calls Forge's default (what Forge mods
     * expect, and Forge's own code paths). Only methods the class does not declare itself; reads interfaces as resources,
     * never loads classes mid-transform. Same idea as block.BlockAppearanceCompat120, for every clash.
     */
    static final class DefaultClash {
        private static final java.util.Map<String, java.util.Map<String, Boolean>> DEFAULTS = new java.util.concurrent.ConcurrentHashMap<>();

        static byte[] fix(byte[] bytes) {
            try {
                ClassReader cr = new ClassReader(bytes);
                String[] itfs = cr.getInterfaces();
                if (itfs.length < 2 || (cr.getAccess() & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0) return bytes;
                List<String> forge = new ArrayList<>(), other = new ArrayList<>();
                for (String i : itfs) {
                    if (i.startsWith("net/minecraftforge/") && i.substring(i.lastIndexOf('/') + 1).startsWith("IForge")) forge.add(i);
                    else if (!i.startsWith("java/") && !i.startsWith("net/minecraft/")) other.add(i);
                }
                if (forge.isEmpty() || other.isEmpty()) return bytes;
                java.util.Set<String> otherDefaults = new java.util.HashSet<>();
                for (String i : other) otherDefaults.addAll(defaults(i).keySet());
                if (otherDefaults.isEmpty()) return bytes;
                ClassNode cn = new ClassNode();
                cr.accept(cn, 0);
                java.util.Set<String> declared = new java.util.HashSet<>();
                for (var mn : cn.methods) declared.add(mn.name + mn.desc);
                int added = 0;
                for (String f : forge)
                    for (String key : defaults(f).keySet()) {
                        if (!otherDefaults.contains(key) || !declared.add(key)) continue;
                        int p = key.indexOf('(');
                        String name = key.substring(0, p), desc = key.substring(p);
                        var mn = new org.objectweb.asm.tree.MethodNode(org.objectweb.asm.Opcodes.ACC_PUBLIC, name, desc, null, null);
                        mn.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
                        int slot = 1;
                        for (Type t : Type.getArgumentTypes(desc)) { mn.visitVarInsn(t.getOpcode(org.objectweb.asm.Opcodes.ILOAD), slot); slot += t.getSize(); }
                        mn.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, f, name, desc, true);
                        mn.visitInsn(Type.getReturnType(desc).getOpcode(org.objectweb.asm.Opcodes.IRETURN));
                        mn.visitMaxs(0, 0);
                        cn.methods.add(mn);
                        added++;
                    }
                if (added == 0) return bytes;
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                cn.accept(cw);
                return cw.toByteArray();
            } catch (Throwable t) {
                return bytes;
            }
        }

        /** name+desc of the public non-static default methods of an interface and its superinterfaces (cached). */
        private static java.util.Map<String, Boolean> defaults(String itf) {
            java.util.Map<String, Boolean> c = DEFAULTS.get(itf);
            if (c != null) return c;
            java.util.Map<String, Boolean> out = new java.util.HashMap<>();
            try (InputStream in = ForgeLaunchPlugins.class.getClassLoader().getResourceAsStream(itf + ".class")) {
                if (in != null) {
                    ClassReader r = new ClassReader(in);
                    ClassNode n = new ClassNode();
                    r.accept(n, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    for (var mn : n.methods)
                        if ((mn.access & (org.objectweb.asm.Opcodes.ACC_STATIC | org.objectweb.asm.Opcodes.ACC_ABSTRACT | org.objectweb.asm.Opcodes.ACC_PRIVATE)) == 0
                                && !mn.name.startsWith("<")) out.put(mn.name + mn.desc, true);
                    for (String sup : n.interfaces) out.putAll(defaults(sup));
                }
            } catch (Throwable ignored) {
            }
            DEFAULTS.put(itf, out);
            return out;
        }
    }

    private static List<ILaunchPluginService> loaded;

    /** The launch plugins on Knot's class path, loaded once (Forge 47's FMLLoader keeps some of them in fields before install). */
    static synchronized List<ILaunchPluginService> loaded() {
        if (loaded == null) {
            List<ILaunchPluginService> ps = new ArrayList<>();
            for (ILaunchPluginService p : java.util.ServiceLoader.load(ILaunchPluginService.class, BridgeLayers.loader())) ps.add(p);
            loaded = List.copyOf(ps);
        }
        return loaded;
    }

    /** The loaded launch plugin with this name(), or null. */
    static ILaunchPluginService plugin(String name) {
        for (ILaunchPluginService p : loaded()) if (p.name().equals(name)) return p;
        return null;
    }

    @SuppressWarnings("unchecked")
    static void install(net.minecraftforge.api.distmarker.Dist dist) throws Exception {
        List<ILaunchPluginService> ps = new ArrayList<>();
        for (ILaunchPluginService p : loaded()) {
            try {
                Object ext = p.getExtension();
                if (p.name().equals("runtimedistcleaner") && ext instanceof Consumer<?> c) ((Consumer<Object>) c).accept(dist);
            } catch (Throwable ignored) {
            }
            ps.add(p);
        }
        for (ILaunchPluginService p : Bridge.addedLaunchPlugins())
            if (ps.stream().noneMatch(q -> q.name().equals(p.name()))) {
                ps.add(p);
                Bridge.LOG.info("Alloy: launch plugin '{}' ({}) added by a mod", p.name(), p.getClass().getName());
            }
        plugins = List.copyOf(ps);
        java.nio.file.Path inj = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("alloy-inject.txt");
        if (java.nio.file.Files.exists(inj)) {
            java.util.Map<String, List<String>> m = new java.util.HashMap<>();
            for (String line : java.nio.file.Files.readAllLines(inj)) {
                String[] p2 = line.trim().split("\\s+");
                if (p2.length == 2) m.computeIfAbsent(p2[0], k -> new ArrayList<>()).add(p2[1]);
            }
            inject = m;
            Bridge.LOG.info("Alloy: {} interface injection(s) for Fabric accessors Forge already satisfies: {}", m.size(), m);
        }
        installHook();
        alloy.presence.PresenceRewrite.warm();
        ready = true;
        if (!EARLY.isEmpty())
            Bridge.LOG.info("Alloy: {} game class(es) loaded before Forge's launch plugins (no access transformers): {}",
                    EARLY.size(), EARLY);
        Bridge.LOG.info("Alloy: {} Forge launch plugin(s) active: {}", plugins.size(), plugins.stream().map(ILaunchPluginService::name).toList());
    }

    /**
     * A class loaded while a launch plugin is working on another class (Forge 47's eventbus engine loads super classes to
     * decide if a class is an Event) must still get the plugins, or it misses e.g. its access transformers or the event
     * constructor. Allowed for game and Forge classes on Forge 47 only (not for the plugins' own code); Forge 64 keeps skipping every nested load as before.
     */
    static final boolean NESTED = Fml.runtimeAT();   // every launch120.py family (runtime ATs), not Forge 64

    private static boolean nestedOk(String name) {
        if (!NESTED || !ready) return false;
        String n = name.replace('/', '.');
        if (n.startsWith("net.minecraft.") || n.startsWith("com.mojang.")) return true;
        // Forge classes (events, registries, ...), but not the code of the launch plugins themselves
        if (n.startsWith("net.minecraftforge."))
            return !n.startsWith("net.minecraftforge.eventbus.") && !n.startsWith("net.minecraftforge.accesstransformer.")
                && !n.startsWith("net.minecraftforge.fml.loading.") && !n.startsWith("net.minecraftforge.fml.common.asm.") && !n.startsWith("net.minecraftforge.coremod");
        // Forge mods' classes too: eventbus 6 loads an event's super class while it works on the subclass (AppleSkin's
        // HUDOverlayEvent got no default constructor and every post of it failed). Loader, library and plugin code is left out.
        for (String p : NOT_NESTED) if (n.startsWith(p)) return false;
        return true;
    }

    private static final String[] NOT_NESTED = {"java.", "javax.", "jdk.", "sun.", "alloy.", "net.fabricmc.", "org.spongepowered.",
            "com.llamalad7.", "org.objectweb.", "cpw.mods.", "net.jodah.", "com.google.", "org.apache.", "org.slf4j.", "io.netty.",
            "it.unimi.", "org.lwjgl.", "org.openjdk.", "com.electronwill.", "org.jline.", "net.minecrell.", "kotlin.", "org.jetbrains."};

    /**
     * Forge 47: ModLauncher offers launch plugins classes that do not exist (isEmpty), and eventbus 6 generates its event
     * dispatcher classes (__Owner_method_Event) that way. Knot asks Mixin's transformer with null bytes for a missing class;
     * give the plugins the same chance. Returns null when no plugin makes the class.
     */
    static byte[] transformEmpty(String name) {
        if (!ready || BUSY.get() || name == null) return null;
        boolean prev = BUSY.get();
        BUSY.set(true);
        try {
            Type t = Type.getObjectType(name.replace('.', '/'));
            ClassNode node = null;
            int flags = 0;
            for (ILaunchPluginService.Phase phase : ILaunchPluginService.Phase.values())
                for (ILaunchPluginService p : plugins) {
                    if (!p.handlesClass(t, true, "classloading").contains(phase)) continue;
                    if (node == null) node = new ClassNode();
                    flags |= p.processClassWithFlags(phase, node, t, "classloading");
                }
            if (node == null || flags == ILaunchPluginService.ComputeFlags.NO_REWRITE || node.name == null) return null;
            ClassWriter w = new HierarchyWriter((flags & ILaunchPluginService.ComputeFlags.COMPUTE_FRAMES) != 0 ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS);
            node.accept(w);
            return w.toByteArray();
        } catch (Throwable e) {
            Bridge.LOG.error("Alloy: Forge launch plugin failed to make class {}", name, e);
            return null;
        } finally {
            BUSY.set(prev);
        }
    }

    static byte[] transform(String name, byte[] bytes) {
        if (bytes == null || (BUSY.get() && !nestedOk(name))) return bytes;
        //#if fml64   (26.x shape fixes and the MixinConstraints patch are not in the Forge 47 build)
        if (ForgeShapeFixes.SPRITE_LOADER.equals(name)) {
            try { bytes = ForgeShapeFixes.spriteLoader(bytes); } catch (Throwable e) { Bridge.LOG.error("Alloy: SpriteLoader mip fix failed", e); }
        }
        if (ForgeShapeFixes.BOOTSTRAP.equals(name)) {
            try { bytes = ForgeShapeFixes.bootstrap(bytes); } catch (Throwable e) { Bridge.LOG.error("Alloy: Bootstrap shape fix failed", e); }
        }
        if (MixinConstraintsSide.CLASS.equals(name)) {
            try { bytes = MixinConstraintsSide.patch(bytes); } catch (Throwable e) { Bridge.LOG.error("Alloy: MixinConstraints patch failed", e); }
        }
        //#endif
        // vanilla's Map-typed KeyMapping.MAP back next to Forge's KeyMappingLookup (Fabric accessors, VoxelMap); before
        // the ready check, so it also runs if KeyMapping loads before Forge's plugins are on, and before Mixin
        if (KeyMapView.KEY_MAPPING.equals(name)) {
            try { bytes = KeyMapView.patch(bytes); } catch (Throwable e) { Bridge.LOG.error("Alloy: KeyMapping vanilla map field failed", e); }
        }
        if (NESTED && FABRIC_AFTER_FORGE && "net.minecraft.client.Minecraft".equals(name)) {
            try { bytes = fabricEntrypointsAfterForge(bytes); } catch (Throwable e) { Bridge.LOG.error("Alloy: entrypoint reorder failed", e); }
        }
        if (!ready) {   // early (before Forge's plugins and isolation are set up): only the config-move redirect
            // game classes loaded this early miss Forge's access transformers for good (seen once: IdMapper.tToId
            // IllegalAccessError in Bootstrap on a slow start); keep their names for the log line in install()
            if (name.startsWith("net.minecraft.") && EARLY.size() < 50) EARLY.add(name + " (" + Thread.currentThread().getName() + ")");
            if (CONFIG_WRITER.equals(name)) {
                try { return redirectConfigMove(bytes); } catch (Throwable e) { Bridge.LOG.error("Alloy: config move redirect failed", e); }
            }
            return bytes;
        }
        byte[] r = transform0(name, bytes);
        // Fabric isModLoaded call sites -> alloy.presence (cross-side answers); before isolation renames anything
        if (alloy.presence.PresenceRewrite.eligible(name) && !forgeOwned(name)) r = alloy.presence.PresenceRewrite.apply(name, r);
        if (Isolation.MODE == Isolation.Mode.OFF) return r;
        boolean prev = BUSY.get();
        BUSY.set(true);
        try { return Isolation.remapIfNeeded(name, r); } finally { BUSY.set(prev); }
    }

    /**
     * Forge 47 (1.20.1): Fabric Loader's patch calls {@code Hooks.startClient(gameDirectory, this)} (the Fabric main and client
     * entrypoints) early in {@code Minecraft.<init>}, before {@code ClientModLoader.begin}, which constructs the Forge mods and
     * fires the registry events (GATHER phase). Fabric mods then ran before Forge's own registry objects existed
     * (Forge-patched vanilla code calling ForgeMod.SWIM_SPEED.get(): "Registry Object not present"). Move the call (and its
     * three argument loads: this, this.gameDirectory, this) to right after ClientModLoader.begin, which is also where
     * 26.1's Forge has its registries filled. A missing or different pattern leaves the class unchanged.
     */
    // Off by default (-Dalloy.fabricAfterForge=true turns it on). Tried in the 1.20.1 worldgen lane test (2026-11-13): after the move,
    // Forge has frozen the root registry (BCLib's FabricRegistryBuilder registry: "Registry is already frozen"; covered by
    // compat120 RootRegistryLateCompat120, not in the default mixin list) and then ForgeHooks.modifyAttributes failed with a
    // ConcurrentModificationException over the entity type registry inside ClientModLoader.begin. The default keeps Fabric's
    // order and handles the early cases instead (compat120 EarlyAttributesCompat120, BlockItemRegisterCompat120).
    // It also broke every Fabric key registration ("GameOptions has already been initialised": options exist by then);
    // compat120 input/LateFabricKeys120 (applied only with this flag) lets those through and appends them to options.keyMappings.
    static final boolean FABRIC_AFTER_FORGE = Boolean.getBoolean("alloy.fabricAfterForge");

    static byte[] fabricEntrypointsAfterForge(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        for (var m : cn.methods) {
            if (!m.name.equals("<init>")) continue;
            org.objectweb.asm.tree.MethodInsnNode start = null, begin = null;
            for (var n : m.instructions.toArray())
                if (n instanceof org.objectweb.asm.tree.MethodInsnNode mi) {
                    if (mi.owner.equals("net/fabricmc/loader/impl/game/minecraft/Hooks") && mi.name.equals("startClient")) start = mi;
                    else if (mi.owner.equals("net/minecraftforge/client/loading/ClientModLoader") && mi.name.equals("begin")) begin = mi;
                }
            if (start == null || begin == null || m.instructions.indexOf(start) > m.instructions.indexOf(begin)) continue;
            List<org.objectweb.asm.tree.AbstractInsnNode> args = new ArrayList<>();
            org.objectweb.asm.tree.AbstractInsnNode p = start.getPrevious();
            while (p != null && args.size() < 3) {
                if (p.getOpcode() >= 0) args.add(0, p);
                p = p.getPrevious();
            }
            boolean shape = args.size() == 3 && args.get(0).getOpcode() == org.objectweb.asm.Opcodes.ALOAD
                    && args.get(1).getOpcode() == org.objectweb.asm.Opcodes.GETFIELD && args.get(2).getOpcode() == org.objectweb.asm.Opcodes.ALOAD;
            if (!shape) { Bridge.LOG.warn("Alloy: Fabric startClient call in Minecraft.<init> has an unknown shape; entrypoints stay before Forge"); return bytes; }
            org.objectweb.asm.tree.InsnList moved = new org.objectweb.asm.tree.InsnList();
            for (var a : args) { m.instructions.remove(a); moved.add(a); }
            m.instructions.remove(start);
            moved.add(start);
            m.instructions.insert(begin, moved);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            Bridge.LOG.info("Alloy: Fabric entrypoints moved after ClientModLoader.begin (Forge 47 registers first)");
            return cw.toByteArray();
        }
        return bytes;
    }

    /**
     * Language providers shipped inside mod jars (not FML classes, so PrepFml cannot rewrite them) that build their mod
     * container like FMLModContainer: ModuleLayer.findModule + JPMS open + Class.forName(Module, String). Their container class is
     * loaded while FML starts (before the compat mixin configs are active), so a mixin cannot reach it; redirect the same
     * three calls to BridgeHooks here, as PrepFml does for FMLModContainer. Scalable Cats Force (QuarryPlus & add-ons), and
     * Kotlin for Forge 4.x on 1.20.1 (KotlinModContainer: gameLayer.findModule(...).orElseThrow() failed, so the
     * kotlinforforge mod and every Kotlin Forge mod stopped loading with "No value present").
     */
    private static final java.util.Set<String> MODULE_CONTAINERS = java.util.Set.of("com.kotori316.scala_lib.ScalaModContainer",
            "thedarkcolour.kotlinforforge.KotlinModContainer");

    /**
     * The one rewrite loader classes still get (they skip Mixin and the launch plugins): KotlinModContainer is under the
     * loader prefix thedarkcolour.kotlinforforge., so without this its findModule(...).orElseThrow() ran unchanged and Kotlin
     * for Forge and every Kotlin Forge mod failed with "No value present" (Forgeulously Optimized: Capes, then RRLS's
     * config NPE at the loading screen).
     */
    /** A Forge mod or Forge platform class (by the isolation index); false without the index. */
    private static boolean forgeOwned(String name) {
        if (!Isolation.indexed()) return false;
        Isolation.Side s = Isolation.sideOf(name.replace('.', '/'));
        return s == Isolation.Side.FORGE || s == Isolation.Side.FORGE_PLATFORM;
    }

    static byte[] loaderOnly(String name, byte[] bytes) {
        if (bytes == null || !MODULE_CONTAINERS.contains(name)) return bytes;
        try { return redirectModuleCalls(bytes); } catch (Throwable e) { Bridge.LOG.error("Alloy: module redirect failed on {}", name, e); return bytes; }
    }

    static byte[] redirectModuleCalls(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        final String H = "alloy/BridgeHooks";
        boolean changed = false;
        for (var m : cn.methods) {
            for (var n : m.instructions.toArray()) {
                if (!(n instanceof org.objectweb.asm.tree.MethodInsnNode mi)) continue;
                org.objectweb.asm.tree.MethodInsnNode r = null;
                if (mi.owner.equals("java/lang/ModuleLayer") && mi.name.equals("findModule"))
                    r = new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, H, "findModule", "(Ljava/lang/ModuleLayer;Ljava/lang/String;)Ljava/util/Optional;", false);
                else if (mi.owner.equals("java/lang/Class") && mi.name.equals("forName") && mi.desc.equals("(Ljava/lang/Module;Ljava/lang/String;)Ljava/lang/Class;"))
                    r = new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, H, "forName", mi.desc, false);
                else if (mi.name.equals("openModule") && mi.desc.equals("(Ljava/lang/ModuleLayer;Ljava/lang/Module;Lcpw/mods/jarhandling/SecureJar;)V"))
                    r = new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, H, "noOpenModules", mi.desc, false);
                if (r != null) { m.instructions.set(mi, r); changed = true; }
            }
        }
        if (!changed) return bytes;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** night-config's ConfigWriter: its one Files.move (REPLACE_ATOMIC) goes through BridgeHooks.configMove (Windows file-lock retry) */
    static final String CONFIG_WRITER = "com.electronwill.nightconfig.core.io.ConfigWriter";

    static byte[] redirectConfigMove(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean changed = false;
        for (var m : cn.methods)
            for (var n : m.instructions.toArray())
                if (n instanceof org.objectweb.asm.tree.MethodInsnNode mi && mi.getOpcode() == org.objectweb.asm.Opcodes.INVOKESTATIC
                        && mi.owner.equals("java/nio/file/Files") && mi.name.equals("move")) {
                    m.instructions.set(mi, new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, "alloy/BridgeHooks", "configMove", mi.desc, false));
                    changed = true;
                }
        if (!changed) return bytes;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** -Dalloy.tracePlugins=a.b.Class logs which launch plugins changed that class (debugging aid) */
    private static final String TRACE = System.getProperty("alloy.tracePlugins", "");
    /** launch plugin classes whose handlesClass threw (logged once each) */
    private static final java.util.Set<String> PLUGIN_ERRORS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static byte[] transform0(String name, byte[] bytes) {
        if (name.equals(TRACE)) Bridge.LOG.info("Alloy trace: {} reached the launch plugins ({} active)", name, plugins.size());
        if (MODULE_CONTAINERS.contains(name)) {
            try { bytes = redirectModuleCalls(bytes); } catch (Throwable e) { Bridge.LOG.error("Alloy: module redirect failed on {}", name, e); }
        }
        if (CONFIG_WRITER.equals(name)) {
            try { bytes = redirectConfigMove(bytes); } catch (Throwable e) { Bridge.LOG.error("Alloy: config move redirect failed", e); }
        }
        if (plugins.isEmpty() && inject.isEmpty() && !ModTransformationServices.handles(name)) return bytes;
        boolean prev = BUSY.get();
        BUSY.set(true);
        try {
            Type t = Type.getObjectType(name.replace('.', '/'));
            ClassNode node = null;
            int flags = 0;
            // mods' own ModLauncher ITransformationServices: PRE_CLASS first, CLASS/METHOD/FIELD between the launch
            // plugins' BEFORE and AFTER phases (ModLauncher's order); all of this runs before Mixin
            if (ModTransformationServices.hasPre(name)) {
                node = new ClassNode();
                new ClassReader(bytes).accept(node, 0);
                node = ModTransformationServices.applyPre(name, node);
                flags |= ILaunchPluginService.ComputeFlags.COMPUTE_FRAMES;
            }
            for (ILaunchPluginService.Phase phase : ILaunchPluginService.Phase.values()) {
                if (phase == ILaunchPluginService.Phase.AFTER && ModTransformationServices.handles(name)) {
                    if (node == null) {
                        node = new ClassNode();
                        new ClassReader(bytes).accept(node, 0);
                    }
                    node = ModTransformationServices.apply(name, node);
                    flags |= ILaunchPluginService.ComputeFlags.COMPUTE_FRAMES;
                }
                for (ILaunchPluginService p : plugins) {
                    // a plugin that throws in handlesClass is skipped for this class only, so the others (the access
                    // transformer) still run: a nested eventbus copy whose engine lookup failed for every class used to
                    // leave every game class without Forge's ATs (reg05, IllegalAccessError in Bootstrap)
                    java.util.Set<ILaunchPluginService.Phase> wants;
                    try {
                        wants = p.handlesClass(t, false, "classloading");
                    } catch (Throwable e) {
                        if (PLUGIN_ERRORS.add(p.getClass().getName())) Bridge.LOG.error("Alloy: launch plugin '{}' ({}) failed on {}; it is skipped for every class it fails on",
                                p.name(), p.getClass().getName(), name, e);
                        continue;
                    }
                    if (!wants.contains(phase)) continue;
                    if (node == null) {
                        node = new ClassNode();
                        new ClassReader(bytes).accept(node, 0);
                    }
                    int f = p.processClassWithFlags(phase, node, t, "classloading");
                    flags |= f;
                    if (name.equals(TRACE)) Bridge.LOG.info("Alloy trace: {} {} {} -> flags {}", name, phase, p.name(), f);
                }
            }
            List<String> add = inject.get(t.getInternalName());
            if (add != null) {
                if (node == null) {
                    node = new ClassNode();
                    new ClassReader(bytes).accept(node, 0);
                }
                for (String itf : add) if (!node.interfaces.contains(itf)) { node.interfaces.add(itf); flags |= ILaunchPluginService.ComputeFlags.SIMPLE_REWRITE; }
            }
            if (node == null || flags == ILaunchPluginService.ComputeFlags.NO_REWRITE) return bytes;
            syncClassInfo(node);
            int wf = (flags & ILaunchPluginService.ComputeFlags.COMPUTE_FRAMES) != 0 ? ClassWriter.COMPUTE_FRAMES
                    : (flags & ILaunchPluginService.ComputeFlags.COMPUTE_MAXS) != 0 ? ClassWriter.COMPUTE_MAXS : 0;
            ClassWriter w = new HierarchyWriter(wf);
            node.accept(w);
            return w.toByteArray();
        } catch (Throwable e) {
            Bridge.LOG.error("Alloy: Forge launch plugin failed on {}", name, e);
            return bytes;
        } finally {
            BUSY.set(prev);
        }
    }

    private static java.lang.reflect.Field ciCache, ciModifiers;
    private static boolean ciBroken;

    /**
     * Forge's access transformers run here, but Mixin builds its ClassInfo of a class from the class bytes before them
     * (Knot's pre-Mixin bytes). A method an AT made public then stays private in ClassInfo, and Mixin's LVT generator,
     * which looks the method up with its new access flags, fails: "Could not locate method metadata for renderHitOutline
     * generating LVT" (Puzzles Access API widens LevelRenderer.renderHitOutline, Fabric API injects into it). On real
     * Forge ClassInfo sees the transformed class. So the visibility bits of an already known ClassInfo follow the node.
     */
    static void syncClassInfo(ClassNode node) {
        if (ciBroken) return;
        try {
            if (ciCache == null) {
                Class<?> ci = org.spongepowered.asm.mixin.transformer.ClassInfo.class;
                ciCache = ci.getDeclaredField("cache");
                ciCache.setAccessible(true);
                ciModifiers = Class.forName(ci.getName() + "$Member").getDeclaredField("modifiers");
                ciModifiers.setAccessible(true);
            }
            Object info = ((java.util.Map<?, ?>) ciCache.get(null)).get(node.name);
            if (!(info instanceof org.spongepowered.asm.mixin.transformer.ClassInfo c)) return;
            int vis = Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED;
            for (var m : node.methods) {
                var cm = c.findMethod(m.name, m.desc, org.spongepowered.asm.mixin.transformer.ClassInfo.INCLUDE_ALL
                        | org.spongepowered.asm.mixin.transformer.ClassInfo.INCLUDE_INITIALISERS);
                if (cm != null && (cm.getAccess() & vis) != (m.access & vis)) ciModifiers.setInt(cm, (cm.getAccess() & ~vis) | (m.access & vis));
            }
            for (var f : node.fields) {
                var cf = c.findField(f.name, f.desc, org.spongepowered.asm.mixin.transformer.ClassInfo.INCLUDE_ALL);
                if (cf != null && (cf.getAccess() & vis) != (f.access & vis)) ciModifiers.setInt(cf, (cf.getAccess() & ~vis) | (f.access & vis));
            }
        } catch (Throwable t) {
            ciBroken = true;
            Bridge.LOG.warn("Alloy: cannot sync Mixin's ClassInfo with access transformers: {}", t.toString());
        }
    }

    /** COMPUTE_FRAMES needs common super classes; read them from class bytes instead of loading classes. */
    static final class HierarchyWriter extends ClassWriter {
        HierarchyWriter(int flags) {
            super(flags);
        }

        @Override
        protected String getCommonSuperClass(String a, String b) {
            if (a.equals(b)) return a;
            List<String> sa = supers(a);
            for (String s : supers(b)) if (sa.contains(s)) return s;
            return "java/lang/Object";
        }

        private static List<String> supers(String n) {
            List<String> out = new ArrayList<>();
            String cur = n;
            while (cur != null && out.size() < 64) {
                out.add(cur);
                if (cur.equals("java/lang/Object")) break;
                cur = superOf(cur);
            }
            if (!out.contains("java/lang/Object")) out.add("java/lang/Object");
            return out;
        }

        private static String superOf(String n) {
            try (InputStream in = BridgeLayers.loader().getResourceAsStream(n + ".class")) {
                if (in == null) {
                    Class<?> c = Class.forName(n.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
                    return c.getSuperclass() == null ? null : c.getSuperclass().getName().replace('.', '/');
                }
                return new ClassReader(in).getSuperName();
            } catch (Exception e) {
                return null;
            }
        }
    }
}
