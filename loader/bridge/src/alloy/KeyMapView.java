package alloy;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Vanilla's {@code KeyMapping.MAP} field for Fabric mods on Forge.
 *
 * <p>Forge replaces vanilla's {@code static Map MAP} with {@code static KeyMappingLookup MAP}. Fabric mods that read the
 * vanilla field (Amecs and KeybindsGalore Plus through an {@code @Accessor}, VoxelMap through "the third Map field" by
 * reflection) failed. {@link #patch} adds a second static field with the same name and the vanilla type
 * {@code Ljava/util/Map;} right before Forge's field (class file order is the declared order, which VoxelMap counts on),
 * fills it in {@code <clinit>} with {@link #of}, and calls {@link #afterReset} at the end of {@code resetMapping}.
 *
 * <p>The view is the {@code KeyModifier.NONE} bucket of Forge's lookup. Keys bound with Shift/Ctrl/Alt live in other
 * buckets and are not in it: vanilla has no modifiers, so a Fabric mod never expects them.
 * <ul>
 * <li>list mode (1.21.9 and up, vanilla's MAP is {@code Map<Key, List<KeyMapping>>}): the bucket itself, live.</li>
 * <li>single mode (older, vanilla's MAP is {@code Map<Key, KeyMapping>}): a live map over the bucket, one entry per key
 * (the first mapping). {@code put} adds through Forge's lookup (it adds, it does not replace).</li>
 * </ul>
 * Only Forge's {@code put(Key, KeyMapping)} and {@code remove(KeyMapping)} and its private {@code map} are used: Forge 52 and
 * up removed {@code KeyMappingLookup.get(Key)}. No game member is named here, so this compiles in every bridge build.
 */
public final class KeyMapView {
    private KeyMapView() { }

    static final String KEY_MAPPING = "net.minecraft.client.KeyMapping";
    static final String OWNER = "net/minecraft/client/KeyMapping";
    static final String LOOKUP_DESC = "Lnet/minecraftforge/client/settings/KeyMappingLookup;";
    static final String CATEGORY_DESC = "Lnet/minecraft/client/KeyMapping$Category;";
    static final String SIG_SINGLE = "Ljava/util/Map<Lcom/mojang/blaze3d/platform/InputConstants$Key;Lnet/minecraft/client/KeyMapping;>;";
    static final String SIG_LIST = "Ljava/util/Map<Lcom/mojang/blaze3d/platform/InputConstants$Key;Ljava/util/List<Lnet/minecraft/client/KeyMapping;>;>;";

    /** where messages go (the bridge points it at its logger); level is "info" or "warn" */
    static volatile BiConsumer<String, String> LOG = (lvl, msg) -> System.err.println("[Alloy " + lvl + "] " + msg);

    // ================================================================== class transform

    /** what {@link #patch} did, for the log line and the unit test */
    record Patched(byte[] bytes, String field, boolean list, int clinitHooks, int resetHooks) { }

    /** the patched KeyMapping, or the same bytes when there is nothing to do (no Forge lookup field, or patched before) */
    public static byte[] patch(byte[] bytes) {
        Patched p = patchInfo(bytes, System.getProperty("alloy.keyMapView", ""));
        if (p.bytes() != bytes)
            LOG.accept("info", "Alloy: KeyMapping." + p.field() + " vanilla Map view added (" + (p.list() ? "list" : "single")
                    + " mode, before Forge's KeyMappingLookup field)");
        return p.bytes();
    }

    /** @param mode "" (pick from the class), "single", "list" or "off" */
    static Patched patchInfo(byte[] bytes, String mode) {
        Patched none = new Patched(bytes, null, false, 0, 0);
        if ("off".equals(mode)) return none;
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        int at = -1;
        String name = null;
        boolean hasCategory = false;
        for (int i = 0; i < cn.fields.size(); i++) {
            FieldNode f = cn.fields.get(i);
            if (at < 0 && (f.access & Opcodes.ACC_STATIC) != 0 && f.desc.equals(LOOKUP_DESC)) { at = i; name = f.name; }
            if (f.desc.equals(CATEGORY_DESC)) hasCategory = true;
        }
        if (at < 0) return none;
        for (FieldNode f : cn.fields) if (f.name.equals(name) && f.desc.equals("Ljava/util/Map;")) return none;   // done before
        boolean list = "list".equals(mode) || (!"single".equals(mode) && hasCategory);
        cn.fields.add(at, new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, name, "Ljava/util/Map;",
                list ? SIG_LIST : SIG_SINGLE, null));
        int clinit = 0, reset = 0;
        for (MethodNode m : cn.methods) {
            boolean ci = m.name.equals("<clinit>"), rs = isReset(m);
            if (!ci && !rs) continue;
            for (AbstractInsnNode n : m.instructions.toArray()) {
                if (n.getOpcode() != Opcodes.RETURN) continue;
                InsnList l = new InsnList();
                if (ci) {
                    l.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, name, LOOKUP_DESC));
                    l.add(new InsnNode(list ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
                    l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "alloy/KeyMapView", "of", "(Ljava/lang/Object;Z)Ljava/util/Map;", false));
                    l.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, name, "Ljava/util/Map;"));
                    clinit++;
                } else {
                    l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "alloy/KeyMapView", "afterReset", "()V", false));
                    reset++;
                }
                m.instructions.insertBefore(n, l);
            }
        }
        if (clinit == 0) {   // no <clinit> at all: nothing would fill the field, leave the class alone
            LOG.accept("warn", "Alloy: KeyMapping has no static initialiser; vanilla MAP view not added");
            return none;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);   // no new branches: the frames stay valid
        cn.accept(cw);
        return new Patched(cw.toByteArray(), name, list, clinit, reset);
    }

    /** resetMapping()V by name (Mojang) or by shape (SRG m_90854_): the static ()V that calls KeyMappingLookup.clear() */
    static boolean isReset(MethodNode m) {
        if (!m.desc.equals("()V") || (m.access & Opcodes.ACC_STATIC) == 0) return false;
        if (m.name.equals("resetMapping")) return true;
        for (AbstractInsnNode n : m.instructions)
            if (n instanceof MethodInsnNode mi && mi.name.equals("clear") && mi.owner.equals(LOOKUP_DESC.substring(1, LOOKUP_DESC.length() - 1)))
                return true;
        return false;
    }

    // ================================================================== the view

    private static volatile Object lookup;
    private static volatile Method put, getAll;
    private static volatile Map<Object, List<Object>> bucket;

    /** called from KeyMapping.<clinit>: the vanilla-typed MAP over Forge's lookup. Never null. */
    public static Map<Object, Object> of(Object forgeLookup, boolean list) {
        try {
            Map<Object, List<Object>> b = noneBucket(forgeLookup);
            Method p = null, r = null, g = null;
            for (Method m : forgeLookup.getClass().getMethods()) {
                if (m.getName().equals("put") && m.getParameterCount() == 2) p = m;
                else if (m.getName().equals("remove") && m.getParameterCount() == 1) r = m;
                else if (m.getName().equals("getAll") && m.getParameterCount() == 1) g = m;
            }
            if (p == null || r == null) throw new NoSuchMethodException("KeyMappingLookup put/remove");
            lookup = forgeLookup;
            put = p;
            getAll = g;
            bucket = b;
            @SuppressWarnings({"unchecked", "rawtypes"}) Map<Object, Object> m = list ? (Map) b : new Single(b, forgeLookup, p, r);
            return m;
        } catch (Throwable e) {
            LOG.accept("warn", "Alloy: KeyMapping vanilla MAP view not available (" + e + "); mods reading it see an empty map");
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    static Map<Object, List<Object>> noneBucket(Object forgeLookup) throws ReflectiveOperationException {
        Field f = forgeLookup.getClass().getDeclaredField("map");
        f.setAccessible(true);
        Map<?, ?> all = (Map<?, ?>) f.get(java.lang.reflect.Modifier.isStatic(f.getModifiers()) ? null : forgeLookup);
        for (Map.Entry<?, ?> e : all.entrySet())
            if (e.getKey() instanceof Enum<?> k && k.name().equals("NONE")) return (Map<Object, List<Object>>) e.getValue();
        throw new NoSuchFieldException("KeyMappingLookup.map has no NONE bucket");
    }

    /** {@code Map<Key, KeyMapping>} over one {@code Map<Key, List<KeyMapping>>} bucket; first mapping per key */
    static final class Single extends AbstractMap<Object, Object> {
        final Map<Object, List<Object>> b;
        final Object lookup;
        final Method put, remove;

        Single(Map<Object, List<Object>> b, Object lookup, Method put, Method remove) {
            this.b = b; this.lookup = lookup; this.put = put; this.remove = remove;
        }

        private static Object first(List<Object> l) { return l == null || l.isEmpty() ? null : l.get(0); }

        @Override public Object get(Object k) { return first(b.get(k)); }
        @Override public boolean containsKey(Object k) { return first(b.get(k)) != null; }

        @Override public Object put(Object k, Object v) {
            Object prev = get(k);
            call(put, k, v);
            return prev;
        }

        @Override public Object remove(Object k) { return first(b.remove(k)); }

        @Override public boolean remove(Object k, Object v) {
            List<Object> l = b.get(k);
            if (l == null || !l.contains(v)) return false;
            call(remove, v);
            return true;
        }

        @Override public void clear() { b.clear(); }

        private void call(Method m, Object... args) {
            try { m.invoke(lookup, args); }
            catch (java.lang.reflect.InvocationTargetException e) { throw sneaky(e.getCause()); }
            catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }

        @Override public Set<Map.Entry<Object, Object>> entrySet() {
            return new AbstractSet<>() {
                @Override public int size() {
                    int n = 0;
                    for (List<Object> l : b.values()) if (l != null && !l.isEmpty()) n++;
                    return n;
                }

                @Override public Iterator<Map.Entry<Object, Object>> iterator() {
                    Iterator<Map.Entry<Object, List<Object>>> it = b.entrySet().iterator();
                    return new Iterator<>() {
                        Map.Entry<Object, Object> next;
                        boolean removable;

                        @Override public boolean hasNext() {
                            while (next == null && it.hasNext()) {
                                Map.Entry<Object, List<Object>> e = it.next();
                                Object v = first(e.getValue());
                                if (v != null) next = new AbstractMap.SimpleImmutableEntry<>(e.getKey(), v);
                            }
                            return next != null;
                        }

                        @Override public Map.Entry<Object, Object> next() {
                            if (!hasNext()) throw new NoSuchElementException();
                            Map.Entry<Object, Object> r = next;
                            next = null;
                            removable = true;
                            return r;
                        }

                        @Override public void remove() {
                            if (!removable || next != null) throw new IllegalStateException();
                            it.remove();
                            removable = false;
                        }
                    };
                }
            };
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T { throw (T) t; }

    // ================================================================== duplicate key names

    /** mappings that share a name with another mod's mapping: not the ALL owner, so resetMapping skips them */
    private static final Map<Object, Boolean> ORPHANS = Collections.synchronizedMap(new IdentityHashMap<>());

    /** remember a mapping that resetMapping does not put back (its name belongs to another mapping in ALL) */
    public static boolean orphan(Object mapping) {
        return ORPHANS.put(mapping, Boolean.TRUE) == null;
    }

    static int orphans() { return ORPHANS.size(); }

    /** end of KeyMapping.resetMapping(): put the orphans back into Forge's lookup, under their current key */
    public static void afterReset() {
        Object lk = lookup;
        Method p = put;
        if (lk == null || p == null || ORPHANS.isEmpty()) return;
        List<Object> all;
        synchronized (ORPHANS) { all = new ArrayList<>(ORPHANS.keySet()); }
        for (Object m : all) {
            try {
                Object key = m.getClass().getMethod("getKey").invoke(m);
                if (getAll != null && getAll.invoke(lk, key) instanceof List<?> l && containsSame(l, m)) continue;
                p.invoke(lk, key, m);
            } catch (Throwable e) {
                LOG.accept("warn", "Alloy: could not put key binding " + m + " back after resetMapping (" + e + ")");
            }
        }
    }

    private static boolean containsSame(List<?> l, Object m) {
        for (Object o : l) if (o == m) return true;
        return false;
    }

    /** what to do with one entry of options.keyMappings */
    public enum Dedupe { KEEP, DROP, ORPHAN }

    /** one entry of options.keyMappings for {@link #dedupe}: live = {@code ALL.get(name) == this} */
    public record KeyRow(String name, Object category, Object defaultKey, Object defaultModifier, boolean live) {
        boolean sameAs(KeyRow o) {
            return Objects.equals(category, o.category) && Objects.equals(defaultKey, o.defaultKey)
                    && Objects.equals(defaultModifier, o.defaultModifier);
        }
    }

    /**
     * The duplicate key name rule. Vanilla's constructor does {@code ALL.put(name, this)}, so of two mappings with one name
     * only the last one constructed is put into the lookup by resetMapping; the other shows in Controls and never fires.
     * <ul>
     * <li>same mod twice (same name, category, default key and default modifier as the live one): DROP the dead copy
     * (Connector keeps the first in the array, which on Alloy is usually the dead Fabric copy);</li>
     * <li>different mods sharing a name: KEEP the live one, ORPHAN the other (kept, and made live by afterReset).</li>
     * </ul>
     * Rows without a duplicate name are KEEP.
     */
    public static List<Dedupe> dedupe(List<KeyRow> rows) {
        Map<String, List<KeyRow>> byName = new java.util.HashMap<>();
        for (KeyRow r : rows) byName.computeIfAbsent(r.name(), k -> new ArrayList<>()).add(r);
        List<Dedupe> out = new ArrayList<>(rows.size());
        for (KeyRow r : rows) {
            List<KeyRow> g = byName.get(r.name());
            if (g.size() < 2 || r.live()) { out.add(Dedupe.KEEP); continue; }
            boolean copy = false;
            for (KeyRow o : g) if (o.live() && o.sameAs(r)) { copy = true; break; }
            out.add(copy ? Dedupe.DROP : Dedupe.ORPHAN);
        }
        return out;
    }
}
