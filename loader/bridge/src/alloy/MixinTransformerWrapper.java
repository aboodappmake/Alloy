package alloy;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.IExtensionRegistry;

import java.util.List;

/**
 * Alloy's wrapper around Knot's IMixinTransformer (ForgeLaunchPlugins). It is a plain class with a field named
 * {@code delegate}, not a java.lang.reflect.Proxy, because Fabric mods walk wrapper chains by that field name: LibJF Unsafe
 * wraps the transformer the same way, and MixinSquared-based code (AsyncParticles' class adjuster) finds the real
 * MixinTransformer by following {@code delegate} fields. A Proxy has no such field, so Fresh &amp; Smooth (LibJF + AsyncParticles)
 * stopped at start with "Unsupported mixin transformer".
 */
public final class MixinTransformerWrapper implements IMixinTransformer {
    public final IMixinTransformer delegate;
    private final LenientRetry.Drop drop;
    private final java.util.function.Consumer<String> log;
    private final LenientRetry.Finder finder;

    MixinTransformerWrapper(IMixinTransformer delegate) {
        this.delegate = delegate;
        // Every class and lambda the retry needs is made here: a class first loaded inside a transform goes through Knot
        // and Mixin again, which failed the whole start (LenientRetry$Pass, "Mixin transformation ... failed").
        LenientRetry.warm();
        this.drop = (m, t) -> { synchronized (delegate) { return LenientRetry.dropFromConfig(m, t); } };
        this.log = msg -> Bridge.LOG.warn(msg);
        this.finder = (t, msg, err) -> {
            Object mi;
            synchronized (delegate) { mi = LenientRetry.findMixin(delegate, t, msg); }
            if (mi instanceof org.spongepowered.asm.mixin.extensibility.IMixinInfo info)   // the report names it like any lenient skip
                ConflictReport.record("APPLY", info.getConfig().getName(), info.getClassName(), t, err);
            return mi;
        };
        pass(null, null, null);
    }

    @Override public byte[] transformClassBytes(String name, String transformedName, byte[] basicClass) {
        // FML / ModLauncher / language provider classes, and anything FML's background scan threads load while the scan runs,
        // skip Mixin: main holds Mixin's transformer lock while FML starts inside a Mixin plugin (EarlyInit), so a scan thread
        // that needs the lock deadlocks the start (Zombie Invade 100 Days on 1.20.1). Nothing targets these classes.
        // (Bridge.onPreLaunch waits for the scan, so the window is closed before the game's Main and its Bootstrap worker run)
        // The game's main class loads on main once Mixin startup (and so EarlyInit) is over: from then on no thread waits on
        // main for the lock, and the window must end. Forge 48's Main runs Bootstrap on a worker thread while the scan of
        // the big Minecraft jar can still be running; that worker counted as a scan thread, got no access transformers and
        // the game stopped with IllegalAccessError on IdMapper (1.20.2, Mojang-named runtime).
        if ("net.minecraft.client.main.Main".equals(name) || "net.minecraft.server.Main".equals(name)) ForgeLaunchPlugins.endScanWindow();
        if (ForgeLaunchPlugins.loaderClass(name) || ForgeLaunchPlugins.inScanThread()) return ForgeLaunchPlugins.loaderOnly(name, basicClass);
        boolean noMixin = otherThreadInStart(name);
        byte[] b = basicClass != null ? ForgeLaunchPlugins.transform(name, basicClass)
                : ForgeLaunchPlugins.NESTED ? ForgeLaunchPlugins.transformEmpty(name) : null;   // 1.20.1: classes made from nothing
        if (b != null && name.equals("net.minecraft.world.item.CreativeModeTab")) b = CreativeTabAnchor.apply(b);
        if (noMixin) return b;
        if (b != null && b != basicClass) AccessSync.sync(name, b);
        byte[] out = mixin(name, transformedName, b);
        // Conflicting IForge* / Fabric library default methods on Minecraft classes (ForgeLaunchPlugins.DefaultClash)
        return out != null && name.startsWith("net.minecraft.") ? ForgeLaunchPlugins.DefaultClash.fix(out) : out;
    }

    /** Mixin pass. Lenient mode: a class whose mixins failed is transformed again without the failed one (LenientRetry). */
    private byte[] mixin(String name, String transformedName, byte[] b) {
        if (b == null || !(ConflictReport.LENIENT || ConflictReport.ON)) return delegate.transformClassBytes(name, transformedName, b);
        return LenientRetry.transform(name, b, pass(name, transformedName, b), drop, finder, log);
    }

    private LenientRetry.Pass pass(String name, String transformedName, byte[] b) {
        return () -> delegate.transformClassBytes(name, transformedName, b);
    }

    // ---- start window ------------------------------------------------------------------------------------------------
    // FML starts inside a Mixin config plugin (EarlyInit), so the start thread holds Mixin's transformer lock until every
    // mixin config is selected. Threads FML or a mod start meanwhile (night-config's file watcher with ModernFix's
    // throttler in MMC4) load classes too: such a thread holds Knot's lock for its class and waits for Mixin's lock,
    // while the start thread, loading the same class (a Guava superclass), waits for Knot's lock. The game hung there.
    // While the window is open, classes other threads load outside Minecraft's own packages skip Mixin (Forge's launch
    // plugins still run); they are listed in the log. The window closes when the start thread runs a transform without
    // holding Mixin's lock.
    private static volatile Thread startThread;
    private static final java.util.Set<String> skipped = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static void openStartWindow() { startThread = Thread.currentThread(); }

    private boolean otherThreadInStart(String name) {
        Thread st = startThread;
        if (st == null) return false;
        Thread t = Thread.currentThread();
        if (t == st) {
            if (!Thread.holdsLock(delegate)) {
                startThread = null;
                if (!skipped.isEmpty())
                    Bridge.LOG.info("Alloy: {} class(es) loaded by other threads during the FML start skipped Mixin: {}", skipped.size(),
                            new java.util.TreeSet<>(skipped).stream().limit(40).toList());
            }
            return false;
        }
        if (name.startsWith("net.minecraft.") || name.startsWith("com.mojang.")) return false;
        skipped.add(name);
        return true;
    }

    /**
     * Mixin's ClassInfo of a class can be built before the class loads (hierarchy lookups), from the bytes Knot's bytecode
     * provider gives, which have no Forge access transformers. When the class then loads, the AT'd target and that
     * ClassInfo disagree on access: Locals.getLocalsAt092 looks a method up with the target's (now public) access and
     * misses the private one in ClassInfo ("LVTGeneratorError: Could not locate method metadata for onScroll", Fabric API's
     * screen events on Controller Support's AT for MouseHandler.onScroll in Forgeulously Optimized). On real Forge the
     * bytecode provider applies the ATs. Here the cached ClassInfo takes the access of the transformed bytes.
     */
    static final class AccessSync {
        static final int MASK = org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_PRIVATE
                | org.objectweb.asm.Opcodes.ACC_PROTECTED | org.objectweb.asm.Opcodes.ACC_FINAL;
        static java.lang.reflect.Field cache, methods, fields, modifiers, mName, mDesc;
        static boolean broken;

        static void sync(String name, byte[] b) {
            if (broken) return;
            try {
                if (cache == null) {
                    Class<?> ci = org.spongepowered.asm.mixin.transformer.ClassInfo.class;
                    Class<?> mem = Class.forName("org.spongepowered.asm.mixin.transformer.ClassInfo$Member");
                    cache = acc(ci.getDeclaredField("cache"));
                    methods = acc(ci.getDeclaredField("methods"));
                    fields = acc(ci.getDeclaredField("fields"));
                    modifiers = acc(mem.getDeclaredField("modifiers"));
                    mName = acc(mem.getDeclaredField("memberName"));
                    mDesc = acc(mem.getDeclaredField("memberDesc"));
                }
                Object info = ((java.util.Map<?, ?>) cache.get(null)).get(name.replace('.', '/'));
                if (info == null) return;
                java.util.Map<String, Integer> access = new java.util.HashMap<>();
                new org.objectweb.asm.ClassReader(b).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override public org.objectweb.asm.FieldVisitor visitField(int a, String n, String d, String sg, Object v) { access.put(n + ":" + d, a); return null; }
                    @Override public org.objectweb.asm.MethodVisitor visitMethod(int a, String n, String d, String sg, String[] e) { access.put(n + d, a); return null; }
                }, org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
                fix((java.util.Set<?>) methods.get(info), access, "");
                fix((java.util.Set<?>) fields.get(info), access, ":");
            } catch (Throwable t) {
                broken = true;
                Bridge.LOG.warn("Alloy: ClassInfo access sync off ({})", t.toString());
            }
        }

        static void fix(java.util.Set<?> members, java.util.Map<String, Integer> access, String sep) throws ReflectiveOperationException {
            if (members == null) return;
            synchronized (members) {
                for (Object m : members) {
                    Integer a = access.get(mName.get(m) + sep + mDesc.get(m));
                    if (a == null) continue;
                    int old = modifiers.getInt(m);
                    int nw = (old & ~MASK) | (a & MASK);
                    if (nw != old) modifiers.setInt(m, nw);
                }
            }
        }

        static java.lang.reflect.Field acc(java.lang.reflect.Field f) { f.setAccessible(true); return f; }
    }

    @Override public void audit(MixinEnvironment environment) { delegate.audit(environment); }
    @Override public List<String> reload(String mixinClass, ClassNode classNode) { return delegate.reload(mixinClass, classNode); }
    @Override public boolean computeFramesForClass(MixinEnvironment environment, String name, ClassNode classNode) { return delegate.computeFramesForClass(environment, name, classNode); }
    @Override public byte[] transformClass(MixinEnvironment environment, String name, byte[] classBytes) { return delegate.transformClass(environment, name, classBytes); }
    @Override public boolean transformClass(MixinEnvironment environment, String name, ClassNode classNode) { return delegate.transformClass(environment, name, classNode); }
    @Override public boolean couldTransformClass(MixinEnvironment environment, String name) { return delegate.couldTransformClass(environment, name); }
    @Override public byte[] generateClass(MixinEnvironment environment, String name) { return delegate.generateClass(environment, name); }
    @Override public boolean generateClass(MixinEnvironment environment, String name, ClassNode classNode) { return delegate.generateClass(environment, name, classNode); }
    @Override public IExtensionRegistry getExtensions() { return delegate.getExtensions(); }
}
