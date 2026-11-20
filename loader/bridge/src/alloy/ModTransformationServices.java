package alloy;

import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.*;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Runtime support for ModLauncher ITransformationServices shipped by Forge mods (e.g. CustomSkinLoader), which nothing
 * calls under Fabric. Discovery is a ServiceLoader over Knot's loader (forgemods/ and forgemods/.nested jars are all
 * on it). Lifecycle follows ModLauncher: onLoad(all) -> initialize(all) -> beginScanning/completeScan -> transformers().
 * ITransformers are applied to a ClassNode by {@link ForgeLaunchPlugins} between the launch plugins' BEFORE and AFTER
 * phases (ModLauncher's order), and always ahead of Mixin (the Mixin proxy runs after the plugins).
 * Services of FML/Forge itself are skipped here: forge-transformers is applied at prep time by PrepGame on Forge 64, and
 * by {@link #installForge()} on the older families.
 * Disable with -Dalloy.modtransformers=false; extra skipped service names: -Dalloy.modtransformers.skip=a,b.
 */
public final class ModTransformationServices {
    @SuppressWarnings("rawtypes")
    private record Holder(ITransformer t, ITransformer.Target target, String service) {}

    private static final Map<String, List<Holder>> CLASS = new HashMap<>(), PRE = new HashMap<>();
    private static final Set<String> SKIP_PKG_PREFIX = Set.of("net.minecraftforge.fml.", "net.minecraftforge.forge.",
            "net.minecraftforge.coremod", "net.minecraftforge.eventbus.", "cpw.mods.", "org.spongepowered.", "net.fabricmc.");
    private static boolean active;

    /** Call once after FML has started and before ForgeLaunchPlugins.install. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    /** true when the class was loaded from a jar directly in the game's mods/ folder (the Fabric side) */
    static boolean fromFabricMods(Class<?> c) {
        try {
            var cs = c.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return false;
            java.nio.file.Path jar = java.nio.file.Path.of(cs.getLocation().toURI()).toAbsolutePath().normalize();
            java.nio.file.Path mods = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("mods").toAbsolutePath().normalize();
            return jar.getParent() != null && jar.getParent().equals(mods);
        } catch (Throwable t) {
            return false;
        }
    }

    static void install() {
        if ("false".equals(System.getProperty("alloy.modtransformers"))) return;
        Set<String> skipNames = new HashSet<>(Arrays.asList(System.getProperty("alloy.modtransformers.skip", "forge-transformers").split(",")));
        List<ITransformationService> svcs = new ArrayList<>();
        Iterator<ServiceLoader.Provider<ITransformationService>> it = ServiceLoader.load(ITransformationService.class, BridgeLayers.loader()).stream().iterator();
        while (true) {
            try {
                if (!it.hasNext()) break;
                ServiceLoader.Provider<ITransformationService> pr = it.next();
                if (fromFabricMods(pr.type())) {
                    // checked before the service is built: its constructor may already need things only its Forge side has
                    // (MineDave's Preloading Tricks looks up its own Forge-side callbacks there and throws)
                    Bridge.LOG.info("Alloy: skipping ModLauncher service {}: its jar is a Fabric mod in mods/", pr.type().getName());
                    continue;
                }
                ITransformationService s = pr.get();
                String cn = s.getClass().getName();
                if (SKIP_PKG_PREFIX.stream().anyMatch(cn::startsWith) || skipNames.contains(s.name())) {
                    Bridge.LOG.info("Alloy: skipping ModLauncher service {} ({})", s.name(), cn);
                    continue;
                }
                if (fromFabricMods(s.getClass())) {
                    // a multi-loader jar loaded as a Fabric mod (mods/) also lists its Forge-side ModLauncher service;
                    // running it too would patch the same classes twice (CustomSkinLoader: SkinManager transformer + mixin)
                    Bridge.LOG.info("Alloy: skipping ModLauncher service {} ({}): its jar is a Fabric mod in mods/", s.name(), cn);
                    continue;
                }
                svcs.add(s);
            } catch (Throwable e) {
                Bridge.LOG.error("Alloy: cannot load a ModLauncher transformation service", e);
            }
        }
        if (svcs.isEmpty()) return;
        IEnvironment env = Launcher.INSTANCE.environment();
        Set<String> names = new HashSet<>();
        for (ITransformationService s : svcs) names.add(s.name());
        List<ITransformationService> ok = new ArrayList<>(svcs);
        for (ITransformationService s : svcs) {
            try {
                Set<String> others = new HashSet<>(names);
                others.remove(s.name());
                s.onLoad(env, others);
            } catch (Throwable e) { Bridge.LOG.error("Alloy: {}.onLoad failed", s.name(), e); ok.remove(s); }
        }
        for (ITransformationService s : List.copyOf(ok)) {
            try { s.initialize(env); } catch (Throwable e) { Bridge.LOG.error("Alloy: {}.initialize failed", s.name(), e); ok.remove(s); }
        }
        for (ITransformationService s : ok) {
            try {
                var r1 = s.beginScanning(env);
                var r2 = s.completeScan(BridgeLayers.INSTANCE);
                if ((r1 != null && !r1.isEmpty()) || (r2 != null && !r2.isEmpty()))
                    Bridge.LOG.warn("Alloy: {} returned scan Resources; they are not added to the classpath", s.name());
                if (s.additionalClassesLocator() != null || s.additionalResourcesLocator() != null)
                    Bridge.LOG.warn("Alloy: {} has additional class/resource locators, which are not supported", s.name());
            } catch (Throwable e) { Bridge.LOG.error("Alloy: {} scanning failed", s.name(), e); }
        }
        int n = 0;
        for (ITransformationService s : ok) {
            try {
                n += register(s.name(), s.transformers());
            } catch (Throwable e) { Bridge.LOG.error("Alloy: {}.transformers() failed", s.name(), e); }
        }
        active |= n > 0;
        Bridge.LOG.info("Alloy: {} ModLauncher transformation service(s) {}, {} transformer target(s) over {} class(es)",
                ok.size(), ok.stream().map(ITransformationService::name).toList(), n, CLASS.size() + PRE.size());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int register(String service, Iterable<?> transformers) {
        int n = 0;
        for (Object o0 : transformers) {
            ITransformer t = (ITransformer) o0;
            for (Object o : t.targets()) {
                ITransformer.Target tg = (ITransformer.Target) o;
                String cls = tg.getClassName().replace('/', '.');
                (tg.getTargetType() == ITransformer.TargetType.PRE_CLASS ? PRE : CLASS)
                        .computeIfAbsent(cls, k -> new ArrayList<>()).add(new Holder(t, tg, service));
                n++;
            }
        }
        return n;
    }

    /**
     * Transformers that do not come from a mod's ITransformationService: Forge 47's JS coremods (CoreModProvider). Call before
     * {@link #install()}; -Dalloy.modtransformers=false does not switch these off (they are part of Forge itself).
     */
    static synchronized void addTransformers(String service, java.util.List<?> transformers) {
        int n = register(service, transformers);
        active |= n > 0;
        Bridge.LOG.info("Alloy: {} transformer(s) from {}, {} target(s)", transformers.size(), service, n);
    }

    static final String FORGE_SERVICE = "net.minecraftforge.forge.transformers.ForgeModTransformers";
    private static final Set<String> FORGE_DONE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Forge 58.1.2 and later moved Forge's own class rewrites (field reads to getters on Biome, LiquidBlock, BucketItem,
     * FlowerPotBlock, MobEffectInstance, and the finalizeSpawn redirect to ForgeEventFactory) from JS coremods into the
     * forge-transformers jar, a ModLauncher service named "forge". {@link #install()} skips Forge's own services, so run
     * this one here. Found by its class, since Forge 56, 57 and 58.0 are the same family but still use JS coremods.
     * Forge 64 does not need it: PrepGame applies forge-transformers to the game jar ahead of time.
     * Turn off with -Dalloy.forgeTransformers=false.
     */
    @SuppressWarnings("unchecked")
    static void installForge() {
        if (!Fml.runtimeAT() || "false".equals(System.getProperty("alloy.forgeTransformers"))) return;
        Class<?> c;
        try {
            c = Class.forName(FORGE_SERVICE, true, BridgeLayers.loader());
        } catch (ClassNotFoundException e) {
            return;   // Forge up to 58.0: its rewrites are JS coremods (Bridge.coreMods47)
        }
        try {
            ITransformationService s = (ITransformationService) c.getConstructor().newInstance();
            IEnvironment env = Launcher.INSTANCE.environment();
            s.onLoad(env, Set.of());
            s.initialize(env);
            addTransformers(s.name(), (List<?>) s.transformers());
        } catch (Throwable e) {
            Bridge.LOG.error("Alloy: Forge's own transformers ({}) could not be set up; biome, fluid and spawn hooks will be missing", FORGE_SERVICE, e);
        }
    }

    /** Cheap check, dotted or slashed class name. */
    static boolean handles(String name) {
        if (!active) return false;
        String n = name.replace('/', '.');
        return CLASS.containsKey(n) || PRE.containsKey(n);
    }

    static boolean hasPre(String name) { return active && PRE.containsKey(name.replace('/', '.')); }

    /** PRE_CLASS transformers (ModLauncher runs them before the launch plugins' BEFORE phase). */
    static ClassNode applyPre(String name, ClassNode node) { return run(PRE.get(name.replace('/', '.')), name.replace('/', '.'), node); }

    /** CLASS / METHOD / FIELD transformers (between launch plugin BEFORE and AFTER phases). */
    static ClassNode apply(String name, ClassNode node) { return run(CLASS.get(name.replace('/', '.')), name.replace('/', '.'), node); }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ClassNode run(List<Holder> hs, String name, ClassNode node) {
        if (hs == null) return node;
        boolean forge = hs.stream().anyMatch(h -> h.service().equals("forge")) && FORGE_DONE.add(name);
        long before = forge ? fingerprint(node) : 0;
        for (Holder h : hs) {
            try {
                ITransformer.Target tg = h.target();
                switch (tg.getTargetType()) {
                    case CLASS, PRE_CLASS -> {
                        if (vote(h, name, node)) { Object r = h.t().transform(node, new Ctx(name, node)); if (r instanceof ClassNode c) node = c; }
                    }
                    case METHOD -> {
                        if (!vote(h, name, node)) break;
                        for (int i = 0; i < node.methods.size(); i++) {
                            MethodNode m = node.methods.get(i);
                            if (m.name.equals(tg.getElementName()) && m.desc.equals(tg.getElementDescriptor())) {
                                Object r = h.t().transform(m, new Ctx(name, node, m, null));
                                if (r instanceof MethodNode nm) node.methods.set(i, nm);
                            }
                        }
                    }
                    case FIELD -> {
                        if (!vote(h, name, node)) break;
                        for (int i = 0; i < node.fields.size(); i++) {
                            FieldNode f = node.fields.get(i);
                            if (f.name.equals(tg.getElementName())) {
                                Object r = h.t().transform(f, new Ctx(name, node, null, f));
                                if (r instanceof FieldNode nf) node.fields.set(i, nf);
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                Bridge.LOG.error("Alloy: ModLauncher transformer of service {} failed on {}", h.service(), name, e);
            }
        }
        if (forge) Bridge.LOG.info("Alloy: Forge transformer {} {}", fingerprint(node) != before ? "rewrote" : "left unchanged", name);
        return node;
    }

    /** opcodes and member references of every method, to tell whether a transformer changed the class */
    private static long fingerprint(ClassNode node) {
        long h = 1;
        for (MethodNode m : node.methods)
            for (AbstractInsnNode i : m.instructions) {
                h = h * 31 + i.getOpcode();
                if (i instanceof MethodInsnNode mi) h = h * 31 + (mi.owner + mi.name + mi.desc).hashCode();
                else if (i instanceof FieldInsnNode fi) h = h * 31 + (fi.owner + fi.name).hashCode();
            }
        return h;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean vote(Holder h, String name, ClassNode node) {
        TransformerVoteResult v = h.t().castVote(new Ctx(name, node));
        if (v == TransformerVoteResult.YES) return true;
        if (v == TransformerVoteResult.REJECT) Bridge.LOG.error("Alloy: transformer of {} rejected {}", h.service(), name);
        else Bridge.LOG.debug("Alloy: transformer of {} voted {} on {}", h.service(), v, name);
        return false;
    }

    /** Minimal ITransformerVotingContext over the ClassNode being transformed. */
    private record Ctx(String name, ClassNode node, MethodNode method, FieldNode field) implements ITransformerVotingContext {
        Ctx(String name, ClassNode node) { this(name, node, null, null); }
        @Override public String getClassName() { return name; }
        @Override public boolean doesClassExist() { return true; }
        @Override public byte[] getInitialClassSha256() { return new byte[32]; }
        @Override public List<ITransformerActivity> getAuditActivities() { return List.of(); }
        @Override public String getReason() { return ITransformerActivity.CLASSLOADING_REASON; }
        @Override public boolean applyFieldPredicate(FieldPredicate p) {
            if (field != null) return p.test(field.access, field.name, field.desc, field.signature, field.value);
            for (FieldNode f : node.fields) if (!p.test(f.access, f.name, f.desc, f.signature, f.value)) return false;
            return true;
        }
        @Override public boolean applyMethodPredicate(MethodPredicate p) {
            if (method != null) return p.test(method.access, method.name, method.desc, method.signature, method.exceptions.toArray(new String[0]));
            for (MethodNode m : node.methods) if (!p.test(m.access, m.name, m.desc, m.signature, m.exceptions.toArray(new String[0]))) return false;
            return true;
        }
        @Override public boolean applyClassPredicate(ClassPredicate p) {
            return p.test(node.version, node.access, node.name, node.signature, node.superName, node.interfaces.toArray(new String[0]));
        }
        @Override public boolean applyInstructionPredicate(InsnPredicate p) {
            if (method == null) return false;
            for (AbstractInsnNode i : method.instructions) if (!p.test(i.getOpcode(), -1)) return false;
            return true;
        }
    }
}
