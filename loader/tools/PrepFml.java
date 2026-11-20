import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.BufferedOutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * Alloy loader-jar prep: writes copies of Forge's loader jars (fmlloader, fmlcore, javafmllanguage, mclanguage,
 * modlauncher, ...) with Alloy's changes baked in, calling static hooks in alloy.BridgeHooks (bridge mod).
 * Baked in rather than mixed in at runtime, because some Forge mods' mixin plugins touch FML during Mixin startup,
 * before any runtime mixin could apply.
 *
 * Rewrites (call sites keep their stack shape; receiver becomes the first argument):
 *   ServiceLoader.load(ModuleLayer, Class)      -> BridgeHooks.load            (services live on Knot's classpath)
 *   ModuleLayer.findModule(String)              -> BridgeHooks.findModule      (everything is in Knot's unnamed module)
 *   Class.forName(Module, String)               -> BridgeHooks.forName
 *   FMLModContainer.openModules(...)            -> BridgeHooks.noOpenModules
 *   ModsFolderLocator(): FMLPaths.get()         -> BridgeHooks.forgeModsDir   (FML reads forgemods/, Fabric reads mods/)
 *   ClasspathLocator: ClassLoader.getResources  -> BridgeHooks.classpathResources (Fabric mod jars stay Fabric-only)
 * Bodies replaced:
 *   Launcher.findLayerManager()                 -> BridgeHooks.layers()
 *   FMLLoader.addAccessTransformer(Path, ModFile) -> no-op (tools/PrepGame applies ATs ahead of time)
 *   JarInJarDependencyLocator.anyMixinsLoaded   -> false (Fabric Loader already provides MixinExtras)
 *   JarVersionLookupHandler.getInfo(Class)      -> BridgeHooks.versionInfo (Knot does not fill Package versions)
 *
 * Forge 47 (MC 1.20.1, -Dalloy.fml=fml47; default fml64 = Forge 64, MC 26.1.x) differs in these rules:
 *   Launcher.findNameMapping(String)            -> BridgeHooks.nameMapping   (srg -> runtime names, -Dalloy.srgNames)
 *   FMLLoader.addAccessTransformer              -> kept (the bridge gives FML Forge's AccessTransformerService, which
 *                                                  then runs as a launch plugin at class load)
 *   JarVersionLookupHandler.get*(Class)         -> BridgeHooks.implVersion/implTitle/specVersion (47 has no getInfo)
 *   anyMixinsLoaded and openModules do not exist there.
 *   module-info "provides" without a META-INF/services file -> the services file is added (Knot reads only those)
 * Forge 49 (MC 1.20.4, -Dalloy.fml=fml49): the Forge 47 rules plus the getInfo(Class) body rule (49 has both the old
 *   get*(Class) methods and getInfo). fml48 (1.20.2) = the Forge 47 rules. fml56 (1.21.6 to 1.21.10) and fml61 (1.21.11) =
 *   the fml49 rules; fml61 (and Forge 60.1) also turn anyMixinsLoaded off (familyBody).
 * At the end PrepFml prints the hit count of every rule (0 hits on an expected rule is a port problem).
 *
 * usage: java [-Dalloy.fml=fml47] PrepFml <outDir> <libRoot> <relative/path/to.jar> ...   (output keeps the relative path)
 */
public class PrepFml {
    static final String H = "alloy/BridgeHooks";
    static int calls, bodies;
    static final String FAMILY = System.getProperty("alloy.fml", "fml64");
    /** getInfo(Class) exists: Forge 49 to 61 (fml49, fml56, fml61) */
    static final boolean F49 = FAMILY.equals("fml49") || FAMILY.equals("fml56") || FAMILY.equals("fml61");
    /** Forge 47 rule set, runtime ATs (also the base of fml48, fml49, fml56 and fml61) */
    static final boolean F47 = FAMILY.equals("fml47") || FAMILY.equals("fml48") || F49;
    /** JarInJarDependencyLocator.anyMixinsLoaded exists: Forge 60.1 and up (fml61; fml56 only from 60.1, so not expected there) */
    static final boolean F61 = FAMILY.equals("fml61");
    /** rule name -> hits, in rule order; every rule of the family is listed, so a rule that found nothing shows 0 */
    static final Map<String, Integer> HITS = new LinkedHashMap<>();

    static {
        for (String r : F47
                ? List.of("findLayerManager", "findNameMapping", "findLaunchPlugin/Handler", "versionLookup(Class)", "ServiceLoader.load(Layer)",
                          "ModuleLayer.findModule", "Class.forName(Module)", "ModsFolderLocator.modsDir", "ModDiscoverer.scanMods", "ClasspathLocator.getResources",
                          "module provides -> META-INF/services", F49 ? "getInfo(Class)" : "", F61 ? "anyMixinsLoaded" : "")
                : List.of("findLayerManager", "findNameMapping/LaunchPlugin/LaunchHandler", "addAccessTransformer", "anyMixinsLoaded", "getInfo(Class)",
                          "ServiceLoader.load(Layer)", "ModuleLayer.findModule", "Class.forName(Module)", "FMLModContainer.openModules",
                          "ModsFolderLocator.modsDir", "ModDiscoverer.scanMods", "ClasspathLocator.getResources"))
            if (!r.isEmpty()) HITS.put(r, 0);
    }

    static void hit(String rule) { HITS.merge(rule, 1, Integer::sum); }

    public static void main(String[] a) throws Exception {
        Path out = Path.of(a[0]), root = Path.of(a[1]);
        for (int i = 2; i < a.length; i++) {
            Path src = root.resolve(a[i]), dst = out.resolve(a[i]);
            Files.createDirectories(dst.getParent());
            int c0 = calls, b0 = bodies;
            Path tmp = dst.resolveSibling(dst.getFileName() + ".tmp");
            try (JarFile jf = new JarFile(src.toFile()); JarOutputStream jo = new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20))) {
                for (JarEntry e : Collections.list(jf.entries())) {
                    String n = e.getName();
                    if (n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))) continue;
                    byte[] b = jf.getInputStream(e).readAllBytes();
                    if (n.equals("META-INF/MANIFEST.MF")) b = stripDigests(b);
                    else if (n.endsWith(".class") && !n.endsWith("module-info.class")) b = patch(b);
                    JarEntry ne = new JarEntry(n);
                    ne.setTime(e.getTime());
                    jo.putNextEntry(ne);
                    jo.write(b);
                    jo.closeEntry();
                }
                if (F47) addModuleServices(jf, jo);
            }
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
            System.out.println(a[i].replace('\\', '/').replaceAll(".*/", "") + ": " + (calls - c0) + " call site(s), " + (bodies - b0) + " body(ies)");
        }
        System.out.println("rules (" + FAMILY + "):");
        HITS.forEach((r, n) -> System.out.println("  " + r + ": " + n + (n == 0 ? "   <- no hit" : "")));
    }

    /**
     * Forge 47 libraries (eventbus 6, coremods 5, modlauncher 10.0) declare some services only in module-info "provides", which
     * the JPMS boot layer reads. Knot is a class path loader: write the missing META-INF/services files (existing ones are kept).
     */
    static void addModuleServices(JarFile jf, JarOutputStream jo) throws Exception {
        JarEntry mi = jf.getJarEntry("module-info.class");
        if (mi == null) return;
        ClassNode cn = new ClassNode();
        new ClassReader(jf.getInputStream(mi).readAllBytes()).accept(cn, 0);
        if (cn.module == null || cn.module.provides == null) return;
        for (ModuleProvideNode pn : cn.module.provides) {
            String f = "META-INF/services/" + pn.service.replace('/', '.');
            if (jf.getJarEntry(f) != null) continue;
            StringBuilder sb = new StringBuilder();
            for (String impl : pn.providers) sb.append(impl.replace('/', '.')).append("\n");
            jo.putNextEntry(new JarEntry(f));
            jo.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jo.closeEntry();
            hit("module provides -> META-INF/services");
        }
    }

    /** Signature digests would no longer match the patched classes; keep the main section and per-package attributes. */
    static byte[] stripDigests(byte[] mf) throws Exception {
        Manifest m = new Manifest(new java.io.ByteArrayInputStream(mf));
        for (var it = m.getEntries().entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            en.getValue().keySet().removeIf(k -> k.toString().endsWith("-Digest"));
            if (en.getValue().isEmpty()) it.remove();
        }
        var bo = new java.io.ByteArrayOutputStream();
        m.write(bo);
        return bo.toByteArray();
    }

    static byte[] patch(byte[] b) {
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, 0);
        boolean changed = false;
        for (MethodNode m : cn.methods) {
            // bodies
            if (cn.name.equals("cpw/mods/modlauncher/Launcher") && m.name.equals("findLayerManager") && m.desc.equals("()Ljava/util/Optional;")) {
                body(m, new MethodInsnNode(Opcodes.INVOKESTATIC, H, "layers", "()Ljava/util/Optional;"), new InsnNode(Opcodes.ARETURN)); changed = true; hit("findLayerManager"); continue;
            }
            if (F47) {
                // 1.20.1 runs with SRG member names: FML asks ModLauncher for a srg -> runtime name function (ObfuscationReflectionHelper,
                // JS coremods' ASMAPI.mapMethod/mapField, the AT name handler). BridgeHooks answers it (identity unless -Dalloy.srgNames).
                if (cn.name.equals("cpw/mods/modlauncher/Launcher") && m.name.equals("findNameMapping") && m.desc.equals("(Ljava/lang/String;)Ljava/util/Optional;")) {
                    body(m, new VarInsnNode(Opcodes.ALOAD, 0), new VarInsnNode(Opcodes.ALOAD, 1),
                            new MethodInsnNode(Opcodes.INVOKESTATIC, H, "nameMapping", "(Lcpw/mods/modlauncher/Launcher;Ljava/lang/String;)Ljava/util/Optional;"),
                            new InsnNode(Opcodes.ARETURN)); changed = true; hit("findNameMapping"); continue;
                }
                if (cn.name.equals("cpw/mods/modlauncher/Launcher") && m.desc.endsWith(")Ljava/util/Optional;")
                        && (m.name.equals("findLaunchPlugin") || m.name.equals("findLaunchHandler"))) {
                    body(m, new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "empty", "()Ljava/util/Optional;"), new InsnNode(Opcodes.ARETURN)); changed = true; hit("findLaunchPlugin/Handler"); continue;
                }
                // Knot does not fill java.lang.Package from per-package manifest sections: read the manifest instead
                if (cn.name.equals("net/minecraftforge/fml/loading/JarVersionLookupHandler") && m.desc.equals("(Ljava/lang/Class;)Ljava/util/Optional;")
                        && (m.name.equals("getImplementationVersion") || m.name.equals("getImplementationTitle") || m.name.equals("getSpecificationVersion"))) {
                    String hook = m.name.equals("getImplementationVersion") ? "implVersion" : m.name.equals("getImplementationTitle") ? "implTitle" : "specVersion";
                    body(m, new VarInsnNode(Opcodes.ALOAD, 0), new MethodInsnNode(Opcodes.INVOKESTATIC, H, hook, m.desc), new InsnNode(Opcodes.ARETURN)); changed = true; hit("versionLookup(Class)"); continue;
                }
                if (familyBody(cn, m)) { changed = true; continue; }
                if (F49 && cn.name.equals("net/minecraftforge/fml/loading/JarVersionLookupHandler") && m.name.equals("getInfo") && m.desc.startsWith("(Ljava/lang/Class;)")) {
                    body(m, new VarInsnNode(Opcodes.ALOAD, 0), new MethodInsnNode(Opcodes.INVOKESTATIC, H, "versionInfo", m.desc), new InsnNode(Opcodes.ARETURN)); changed = true; hit("getInfo(Class)"); continue;
                }
            } else {
            // ModLauncher services that do not exist under Fabric: report "none". 26.1 is unobfuscated, so an empty
            // name mapping makes ObfuscationReflectionHelper use names as-is, exactly as on real Forge 26.1.
            if (cn.name.equals("cpw/mods/modlauncher/Launcher") && m.desc.endsWith(")Ljava/util/Optional;")
                    && (m.name.equals("findNameMapping") || m.name.equals("findLaunchPlugin") || m.name.equals("findLaunchHandler"))) {
                body(m, new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "empty", "()Ljava/util/Optional;"), new InsnNode(Opcodes.ARETURN)); changed = true; hit("findNameMapping/LaunchPlugin/LaunchHandler"); continue;
            }
            if (cn.name.equals("net/minecraftforge/fml/loading/FMLLoader") && m.name.equals("addAccessTransformer")) {
                body(m, new InsnNode(Opcodes.RETURN)); changed = true; hit("addAccessTransformer"); continue;
            }
            if (cn.name.equals("net/minecraftforge/fml/loading/moddiscovery/JarInJarDependencyLocator") && m.name.equals("anyMixinsLoaded")) {
                body(m, new InsnNode(Opcodes.ICONST_0), new InsnNode(Opcodes.IRETURN)); changed = true; hit("anyMixinsLoaded"); continue;
            }
            if (cn.name.equals("net/minecraftforge/fml/loading/JarVersionLookupHandler") && m.name.equals("getInfo") && m.desc.startsWith("(Ljava/lang/Class;)")) {
                body(m, new VarInsnNode(Opcodes.ALOAD, 0), new MethodInsnNode(Opcodes.INVOKESTATIC, H, "versionInfo", m.desc), new InsnNode(Opcodes.ARETURN)); changed = true; hit("getInfo(Class)"); continue;
            }
            }
            // call sites
            for (AbstractInsnNode n : m.instructions.toArray()) {
                if (!(n instanceof MethodInsnNode mi)) continue;
                MethodInsnNode r = null;
                String rule = null;
                if (mi.owner.equals("java/util/ServiceLoader") && mi.name.equals("load") && mi.desc.equals("(Ljava/lang/ModuleLayer;Ljava/lang/Class;)Ljava/util/ServiceLoader;"))
                    { r = new MethodInsnNode(Opcodes.INVOKESTATIC, H, "load", mi.desc); rule = "ServiceLoader.load(Layer)"; }
                else if (mi.owner.equals("java/lang/ModuleLayer") && mi.name.equals("findModule"))
                    { r = new MethodInsnNode(Opcodes.INVOKESTATIC, H, "findModule", "(Ljava/lang/ModuleLayer;Ljava/lang/String;)Ljava/util/Optional;"); rule = "ModuleLayer.findModule"; }
                else if (mi.owner.equals("java/lang/Class") && mi.name.equals("forName") && mi.desc.equals("(Ljava/lang/Module;Ljava/lang/String;)Ljava/lang/Class;"))
                    { r = new MethodInsnNode(Opcodes.INVOKESTATIC, H, "forName", mi.desc); rule = "Class.forName(Module)"; }
                else if (mi.owner.equals("net/minecraftforge/fml/javafmlmod/FMLModContainer") && mi.name.equals("openModules"))
                    { r = new MethodInsnNode(Opcodes.INVOKESTATIC, H, "noOpenModules", mi.desc); rule = "FMLModContainer.openModules"; }
                else if (cn.name.equals("net/minecraftforge/fml/loading/moddiscovery/ModsFolderLocator") && m.name.equals("<init>") && m.desc.equals("()V")
                        && mi.owner.equals("net/minecraftforge/fml/loading/FMLPaths") && mi.name.equals("get"))
                    { r = new MethodInsnNode(Opcodes.INVOKESTATIC, H, "forgeModsDir", "(Lnet/minecraftforge/fml/loading/FMLPaths;)Ljava/nio/file/Path;"); rule = "ModsFolderLocator.modsDir"; }
                else if (cn.name.equals("net/minecraftforge/fml/loading/moddiscovery/ClasspathLocator") && m.name.equals("scanMods")
                        && mi.owner.equals("net/minecraftforge/fml/loading/ModDirTransformerDiscoverer") && mi.name.equals("isServiceProvider"))
                    { r = new MethodInsnNode(Opcodes.INVOKESTATIC, H, "classpathSkip", mi.desc); rule = "ClasspathLocator.classpathSkip"; }
                else if (cn.name.equals("net/minecraftforge/fml/loading/moddiscovery/ModDiscoverer")
                        && mi.owner.equals("net/minecraftforge/forgespi/locating/IDependencyLocator") && mi.name.equals("scanMods"))
                    { r = new MethodInsnNode(Opcodes.INVOKESTATIC, H, "scanDependencies",
                            "(Lnet/minecraftforge/forgespi/locating/IDependencyLocator;" + mi.desc.substring(1)); rule = "ModDiscoverer.scanMods"; }
                else if (cn.name.equals("net/minecraftforge/fml/loading/moddiscovery/ClasspathLocator")
                        && mi.owner.equals("java/lang/ClassLoader") && mi.name.equals("getResources") && mi.desc.equals("(Ljava/lang/String;)Ljava/util/Enumeration;"))
                    { r = new MethodInsnNode(Opcodes.INVOKESTATIC, H, "classpathResources", "(Ljava/lang/ClassLoader;Ljava/lang/String;)Ljava/util/Enumeration;"); rule = "ClasspathLocator.getResources"; }
                if (r != null) { m.instructions.set(mi, r); calls++; changed = true; hit(rule); }
            }
        }
        if (!changed) return b;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS); // stack shapes unchanged; existing frames kept
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Body rules of the families after 49 that run on the Forge 47 rule set (runtime ATs). Forge 60.1+ adds MixinExtras
     * itself when a mod has mixins (JarInJarDependencyLocator.anyMixinsLoaded); Fabric Loader already provides it, as on 64.
     * Present on some fml56 builds (60.1) too, so it is applied wherever the method exists.
     */
    static boolean familyBody(ClassNode cn, MethodNode m) {
        if (cn.name.equals("net/minecraftforge/fml/loading/moddiscovery/JarInJarDependencyLocator") && m.name.equals("anyMixinsLoaded") && m.desc.endsWith(")Z")) {
            body(m, new InsnNode(Opcodes.ICONST_0), new InsnNode(Opcodes.IRETURN)); hit("anyMixinsLoaded"); return true;
        }
        return false;
    }

    static void body(MethodNode m, AbstractInsnNode... insns) {
        m.instructions.clear();
        m.tryCatchBlocks.clear();
        m.localVariables = null;
        m.visibleLocalVariableAnnotations = null;
        m.invisibleLocalVariableAnnotations = null;
        for (AbstractInsnNode n : insns) m.instructions.add(n);
        bodies++;
    }
}
