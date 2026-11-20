package alloy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Which Fabric API classes an Alloy compat mixin uses. Many Fabric mods carry only a few Fabric API modules inside their own
 * jar (Mod Menu: api-base, screen, key binding, lifecycle, resource loader), so "fabric-api-base is loaded" does not mean
 * the module a compat mixin calls is there. A compat mixin whose Fabric API classes are missing is skipped: the Fabric API
 * mixin it stands in for is not there either, and applying it crashed world loading with NoClassDefFoundError
 * (registry.WorldLoaderCompat120 and DynamicRegistries, Mod Menu without Fabric API on 1.21.1).
 */
public final class FabricApiRefs {
    private FabricApiRefs() { }

    static final Pattern FAPI = Pattern.compile("net/fabricmc/fabric/[\\w/$]+");
    static final Pattern OWN = Pattern.compile("alloy/[\\w/$]+");

    /** Fabric API classes (internal names) used by the class and by the Alloy classes of the same package root it uses. */
    public static Set<String> of(String className, Function<String, ClassNode> nodes) {
        String start = className.replace('.', '/');
        int a = start.indexOf('/'), b = a < 0 ? -1 : start.indexOf('/', a + 1);
        String root = b < 0 ? start : start.substring(0, b + 1);   // e.g. alloy/compat120/
        Set<String> out = new TreeSet<>(), seen = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        todo.add(start);
        while (!todo.isEmpty()) {
            String c = todo.poll();
            if (!seen.add(c) || seen.size() > 64) continue;
            ClassNode cn;
            try { cn = nodes.apply(c); } catch (Throwable t) { cn = null; }
            if (cn == null) continue;
            StringBuilder sb = new StringBuilder();
            add(sb, cn.superName);
            if (cn.interfaces != null) cn.interfaces.forEach(i -> add(sb, i));
            if (cn.fields != null) for (FieldNode f : cn.fields) add(sb, f.desc);
            if (cn.methods != null) for (MethodNode m : cn.methods) {
                add(sb, m.desc);
                if (m.instructions == null) continue;
                for (AbstractInsnNode in : m.instructions) {
                    if (in instanceof MethodInsnNode mi) { add(sb, mi.owner); add(sb, mi.desc); }
                    else if (in instanceof FieldInsnNode fi) { add(sb, fi.owner); add(sb, fi.desc); }
                    else if (in instanceof TypeInsnNode ti) add(sb, ti.desc);
                    else if (in instanceof MultiANewArrayInsnNode mn) add(sb, mn.desc);
                    else if (in instanceof LdcInsnNode ld && ld.cst instanceof Type t) add(sb, t.getDescriptor());
                    else if (in instanceof InvokeDynamicInsnNode id) {
                        add(sb, id.desc);
                        if (id.bsmArgs != null) for (Object o : id.bsmArgs) {
                            if (o instanceof Handle h) { add(sb, h.getOwner()); add(sb, h.getDesc()); }
                            else if (o instanceof Type t) add(sb, t.getDescriptor());
                        }
                    }
                }
            }
            String s = sb.toString();
            Matcher m = FAPI.matcher(s);
            while (m.find()) out.add(m.group());
            m = OWN.matcher(s);
            while (m.find()) if (m.group().startsWith(root)) todo.add(m.group());
        }
        return out;
    }

    /** The first Fabric API class the mixin needs that is not installed, or null when all are there. */
    public static String firstMissing(String className, Function<String, ClassNode> nodes, Predicate<String> exists) {
        for (String c : of(className, nodes)) if (!exists.test(c.replace('/', '.'))) return c.replace('/', '.');
        return null;
    }

    private static void add(StringBuilder sb, String s) {
        if (s != null) sb.append(s).append(' ');
    }
}
