package alloy;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

/**
 * MixinConstraints (Moulberry's library, nested in many Fabric mods: Skyboxify, Polytone, ...) finds its loader by
 * looking for classes, and keeps every one it finds: NeoForge's FMLLoader, Forge's FMLLoader, FabricLoader. Under Alloy
 * Forge's FMLLoader exists, so Fabric mods also asked its Forge implementation, which is built for another Forge and
 * stopped MineDave at start (IncompatibleClassChangeError on LoadingModList.get). Its system property only adds an
 * implementation, it does not remove the others. So Alloy rewrites the two FMLLoader names in the library's
 * Abstractions class (it is loaded once, from the Fabric side), unless a Forge mod ships the library too: then nothing is
 * changed, because the Forge mod needs the Forge implementation.
 */
final class MixinConstraintsSide {
    static final String CLASS = "com.moulberry.mixinconstraints.util.Abstractions";
    private static volatile Boolean forgeShipsIt;

    static byte[] patch(byte[] bytes) {
        if (forgeShipsIt == null) {
            Path game = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
            forgeShipsIt = forgeModsShipIt(game.resolve("forgemods"));
        }
        if (forgeShipsIt) {
            Bridge.LOG.info("Alloy: a Forge mod ships MixinConstraints; its loader detection is left as it is");
            return bytes;
        }
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        int n = 0;
        for (var m : cn.methods)
            for (var i : m.instructions.toArray())
                if (i instanceof LdcInsnNode l && (l.cst.equals("net.minecraftforge.fml.loading.FMLLoader") || l.cst.equals("net.neoforged.fml.loading.FMLLoader"))) {
                    l.cst = "alloy.absent." + l.cst;
                    n++;
                }
        if (n == 0) return bytes;
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        Bridge.LOG.info("Alloy: MixinConstraints (Fabric side) uses its Fabric implementation only ({} Forge/NeoForge check(s) turned off)", n);
        return cw.toByteArray();
    }

    static boolean forgeModsShipIt(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (var s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".jar")).toList()) {
                try (JarFile jf = new JarFile(p.toFile())) {
                    var en = jf.entries();
                    while (en.hasMoreElements()) {
                        String n = en.nextElement().getName().toLowerCase(java.util.Locale.ROOT);
                        if (n.startsWith("com/moulberry/mixinconstraints/") || (n.startsWith("meta-inf/jarjar/") && n.contains("mixinconstraints")))
                            return true;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
