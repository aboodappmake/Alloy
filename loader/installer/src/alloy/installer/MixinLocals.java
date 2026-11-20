package alloy.installer;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.util.*;

/**
 * What Mixin sees as the locals of a method at one instruction: a port of org.spongepowered.asm.util.Locals from
 * Fabric's sponge-mixin 0.17 (Mixin 0.8.7), the code behind @Local, @ModifyVariable and LocalCapture.
 *
 * Mixin does not read the LocalVariableTable (LVT) as "what is in scope here". It walks the method from the top, and a
 * slot becomes a local when it is stored, loaded or named by a stack map frame; its type is then looked up: the LVT entry
 * in range at that instruction, else (the method has an LVT, but not for this slot) the table Mixin generates by its own
 * type analysis, else any other LVT entry of that slot. A chop frame ends a local (after one instruction). So with an LVT:
 * - javac's helper locals with no LVT entry (for-each iterators, switch temporaries) are locals for Mixin, typed by the
 *   analysis,
 * - a local whose scope ended stays a local until a frame drops it, with the type it had.
 * The vanilla 1.20.1 jar has an LVT ($$0 names) and Forge's patched classes have one too, so this is the normal case.
 *
 * Which variant runs depends on things the fitter cannot know: the Fabric compatibility level of the mod's mixin config
 * (below 0.10 Mixin uses its 0.9.2 walk) and whether Mixin's class metadata for the target was read with expanded frames.
 * Nor whether Mixin already generated its own table for the method (the walk that generates it compares one position
 * before the labels it inserts). {@link #views} gives all eight variants; Recover only trusts an answer that is the same
 * in all of them.
 *
 * Types from the generated table that Mixin works out with the class hierarchy (two different reference types meeting
 * at a branch) are not known here: those locals have the type {@link #UNSURE}.
 */
final class MixinLocals {
    private MixinLocals() { }

    static final String UNSURE = "?";
    private static final String OBJECT = "Ljava/lang/Object;";

    /** an LVT entry (real or generated); start/end are positions: 2*i for the node at list index i, 2*i+1 for a label right after it */
    record Ent(int index, String desc, String name, int start, int end) { }

    /** one local at the instruction, as Mixin returns it */
    record Lv(int slot, String desc, String name) {
        boolean sure() { return !UNSURE.equals(desc); }
    }

    /** the variants: modern walk or the 0.9.2 one, frame metadata from expanded or compressed frames, table cached or not */
    static final String[] VIEWS = {"modern/expanded", "modern/compressed", "0.9.2/expanded", "0.9.2/compressed",
            "modern/expanded/fresh", "modern/compressed/fresh", "0.9.2/expanded/fresh", "0.9.2/compressed/fresh"};

    /** per-method state: the expanded frames, frame metadata of both readings, the real and the generated table */
    static final class Meth {
        final String owner;
        final MethodNode m;
        final AbstractInsnNode[] insns;
        final List<Object>[] expanded;          // per list index: full local list of the frame there (expanded reading), null if no frame
        final int[][] fdExpanded, fdCompressed; // per frame: {type, locals, size, rawSize}
        final List<Ent> lvt;
        List<Ent> gen;
        int[] inserted;   // positions (odd) of the labels Mixin inserts while it generates its table
        final Map<Integer, List<Lv[]>> cache = new HashMap<>();
        final int initial;
        final boolean ok;

        @SuppressWarnings("unchecked")
        Meth(String owner, MethodNode m) {
            this.owner = owner;
            this.m = m;
            insns = m.instructions.toArray();
            initial = firstNonArg(m);
            lvt = new ArrayList<>();
            if (m.localVariables != null)
                for (LocalVariableNode lv : m.localVariables)
                    lvt.add(new Ent(lv.index, lv.desc, lv.name, 2 * m.instructions.indexOf(lv.start), 2 * m.instructions.indexOf(lv.end)));
            expanded = new List[insns.length];
            List<int[]> fe = new ArrayList<>(), fc = new ArrayList<>();
            boolean good = true;
            List<Object> cur = initialFrame(owner, m);
            for (int i = 0; i < insns.length; i++) {
                if (!(insns[i] instanceof FrameNode fn)) continue;
                List<Object> next = new ArrayList<>();
                switch (fn.type) {
                    case Opcodes.F_NEW, Opcodes.F_FULL -> next.addAll(fn.local == null ? List.of() : fn.local);
                    case Opcodes.F_APPEND -> { next.addAll(cur); next.addAll(fn.local); }
                    case Opcodes.F_CHOP -> {
                        next.addAll(cur);
                        int k = fn.local == null ? 0 : fn.local.size();
                        if (k > next.size()) good = false;
                        for (int c = 0; c < k && !next.isEmpty(); c++) next.remove(next.size() - 1);
                    }
                    case Opcodes.F_SAME, Opcodes.F_SAME1 -> next.addAll(cur);
                    default -> good = false;
                }
                cur = next;
                expanded[i] = next;
                FrameNode ex = new FrameNode(Opcodes.F_NEW, next.size(), next.toArray(), 0, new Object[0]);
                fe.add(frameData(ex, initial));
                fc.add(frameData(fn, initial));
            }
            fdExpanded = fe.toArray(new int[0][]);
            fdCompressed = fc.toArray(new int[0][]);
            ok = good;
        }

        /** Mixin's getGeneratedLocalVariableTable (Locals.generateLocalVariableTable), computed once */
        List<Ent> generated() {
            if (gen == null) {
                gen = generate(owner, m);
                TreeSet<Integer> ins = new TreeSet<>();
                if (gen != null) for (Ent e : gen) { if (e.start() % 2 != 0) ins.add(e.start()); if (e.end() % 2 != 0) ins.add(e.end()); }
                inserted = ins.stream().mapToInt(Integer::intValue).toArray();
            }
            return gen;
        }

        /** list index of a position once Mixin has inserted its labels */
        int mutated(int c) {
            int k = 0;
            while (k < inserted.length && inserted[k] < c) k++;
            if (c >= 2 * insns.length) return insns.length + inserted.length;   // the label added at the end
            return c % 2 == 0 ? c / 2 + k : (c - 1) / 2 + 1 + k;
        }
    }

    static int firstNonArg(MethodNode m) {
        int s = (m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (Type t : Type.getArgumentTypes(m.desc)) s += t.getSize();
        return s;
    }

    /** the frame a method starts with, in ClassReader's expanded form (long and double take one entry) */
    static List<Object> initialFrame(String owner, MethodNode m) {
        List<Object> l = new ArrayList<>();
        if ((m.access & Opcodes.ACC_STATIC) == 0) l.add(m.name.equals("<init>") ? Opcodes.UNINITIALIZED_THIS : owner);
        for (Type t : Type.getArgumentTypes(m.desc)) {
            switch (t.getSort()) {
                case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> l.add(Opcodes.INTEGER);
                case Type.FLOAT -> l.add(Opcodes.FLOAT);
                case Type.LONG -> l.add(Opcodes.LONG);
                case Type.DOUBLE -> l.add(Opcodes.DOUBLE);
                case Type.ARRAY -> l.add(t.getDescriptor());
                default -> l.add(t.getInternalName());
            }
        }
        return l;
    }

    /** Locals.computeFrameSize */
    static int computeFrameSize(List<Object> local, int initialFrameSize) {
        if (local == null) return initialFrameSize;
        int size = 0;
        for (Object o : local) size += o instanceof Integer && (o == Opcodes.DOUBLE || o == Opcodes.LONG) ? 2 : 1;
        return Math.max(initialFrameSize, size);
    }

    /** ClassInfo.FrameData: {type, locals, size, rawSize} */
    static int[] frameData(FrameNode fn, int initialFrameSize) {
        int raw = computeFrameSize(fn.local, 0);
        return new int[]{fn.type, fn.local != null ? fn.local.size() : 0, Math.max(raw, initialFrameSize), raw};
    }

    static int adjusted(int currentSize, int type, int size, int initialFrameSize) {
        return switch (type) {
            case -1, 0 -> Math.max(initialFrameSize, size);
            case 1 -> currentSize + size;
            case 2 -> Math.max(initialFrameSize, currentSize - size);
            default -> currentSize;
        };
    }

    // ================================================================== generated table
    static List<Ent> generate(String owner, MethodNode m) {
        Frame<BasicValue>[] frames;
        try {
            frames = new Analyzer<>(new Recover.Typed()).analyze(owner, m);
        } catch (AnalyzerException | RuntimeException e) {
            return null;
        }
        int n = m.instructions.size();
        AbstractInsnNode[] in = m.instructions.toArray();
        List<Ent> out = new ArrayList<>();
        int[] open = new int[m.maxLocals];
        String[] openDesc = new String[m.maxLocals];
        BasicValue[] locals = new BasicValue[m.maxLocals];
        String[] lastKnown = new String[m.maxLocals];
        Arrays.fill(open, Integer.MIN_VALUE);
        for (int i = 0; i < n; i++) {
            Frame<BasicValue> f = frames[i];
            if (f == null) continue;
            int label = Integer.MIN_VALUE;
            for (int j = 0; j < f.getLocals(); j++) {
                BasicValue local = f.getLocal(j);
                if (local == null && locals[j] == null || local != null && local.equals(locals[j])) continue;
                if (label == Integer.MIN_VALUE) label = in[i] instanceof LabelNode ? 2 * i : 2 * i + 1;
                if (local == null) {
                    out.add(new Ent(j, openDesc[j], "var" + j, open[j], label));
                    open[j] = Integer.MIN_VALUE;
                } else {
                    if (locals[j] != null) {
                        out.add(new Ent(j, openDesc[j], "var" + j, open[j], label));
                        open[j] = Integer.MIN_VALUE;
                    }
                    String desc = lastKnown[j];
                    Type t = local.getType();
                    if (t != null) {
                        if (t.equals(Recover.NULLT)) desc = OBJECT;
                        else if (t.equals(Recover.MIXED)) desc = UNSURE;
                        else desc = t.getDescriptor();
                    }
                    open[j] = label;
                    openDesc[j] = desc;
                    if (desc != null) lastKnown[j] = desc;
                }
                locals[j] = local;
            }
        }
        int end = 2 * n + 2;
        for (int k = 0; k < open.length; k++) if (open[k] != Integer.MIN_VALUE) out.add(new Ent(k, openDesc[k], "var" + k, open[k], end));
        return out;
    }

    // ================================================================== lookups
    static boolean inRange(Ent e, int pos) { return e.start() <= pos && e.end() > pos; }

    /**
     * Locals.getLocalVariableAt; pos is a list index. walk[0] == 0 means Mixin has not generated its table for this
     * method yet: the lookup that generates it compares the position it took before the generation inserted labels with
     * the generated entries (which are after the insertion). A failed type analysis gives an UNSURE local.
     */
    static Ent lookup(Meth mm, int pos, int var, int[] walk) {
        int p = 2 * pos;
        boolean noLvt = mm.lvt.isEmpty();
        boolean stale = false;
        if (noLvt) { stale = walk[0] == 0; walk[0] = 1; }
        List<Ent> table = noLvt ? mm.generated() : mm.lvt;
        if (table == null) return new Ent(var, UNSURE, "var" + var, -1, -1);
        Ent node = null, fallback = null;
        for (Ent e : table) {
            if (e.index() != var) continue;
            if (stale ? inStale(mm, e, pos) : inRange(e, p)) { node = e; continue; }
            if (node != null) continue;
            fallback = e;
        }
        if (node == null && !noLvt) {
            stale = walk[0] == 0;
            walk[0] = 1;
            List<Ent> g = mm.generated();
            if (g == null) return new Ent(var, UNSURE, "var" + var, -1, -1);
            for (Ent e : g) if (e.index() == var && (stale ? inStale(mm, e, pos) : inRange(e, p))) node = e;
        }
        return node != null ? node : fallback;
    }

    static boolean inStale(Meth mm, Ent e, int pos) {
        if (e.start() < 0) return false;
        return mm.mutated(e.start()) <= pos && mm.mutated(e.end()) > pos;
    }

    static int size(String desc) {
        if (desc == null || UNSURE.equals(desc)) return 1;
        return Type.getType(desc).getSize();
    }

    // ================================================================== the walks
    private static final class Zombie {
        final Ent ancestor;
        final char type;
        int lifetime, frames;

        Zombie(Ent a, char t) { ancestor = a; type = t; }

        boolean checkResurrect() {   // Settings.DEFAULT: chopped frame threshold 1, everything else unlimited
            return type != 'C' || frames <= 1;
        }

        static Object of(Object a, char t) {
            if (a instanceof Zombie) return a;
            return a != null ? new Zombie((Ent) a, t) : null;
        }
    }

    static Ent[] initialLocals(Meth mm, boolean modern) {
        MethodNode m = mm.m;
        boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
        Type[] args = Type.getArgumentTypes(m.desc);
        Ent[] f = new Ent[mm.initial];
        String[] names = new String[args.length];
        if (modern) {   // fabricCompatibility >= 0.17: parameter names from the LVT
            Map<Integer, Integer> idx = new HashMap<>();
            int li = isStatic ? 0 : 1;
            for (int a = 0; a < args.length; a++) { idx.put(li, a); li += args[a].getSize(); }
            for (Ent e : mm.lvt) { Integer a = idx.get(e.index()); if (a != null) names[a] = e.name(); }
        }
        int local = 0;
        if (!isStatic) f[local++] = new Ent(0, Type.getObjectType(mm.owner).getDescriptor(), "this", -1, -1);
        for (int a = 0; a < args.length; a++) {
            f[local] = new Ent(local, args[a].getDescriptor(), names[a] != null ? names[a] : "arg" + a, -1, -1);
            local += args[a].getSize();
        }
        return f;
    }

    /** Locals.getLocalsAt with Settings.DEFAULT (fabricCompatibility >= 0.10) */
    static Ent[] modern(Meth mm, int node, int[][] fds, boolean fresh) {
        MethodNode m = mm.m;
        int[] walk = {fresh ? 0 : 1};
        Ent[] init = initialLocals(mm, true);
        Object[] frame = new Object[Math.max(m.maxLocals, init.length)];
        System.arraycopy(init, 0, frame, 0, init.length);
        int initialFrameSize = init.length, frameSize = initialFrameSize, frameIndex = -1;
        int lastFrameSize = initialFrameSize, knownFrameSize = initialFrameSize;
        VarInsnNode storeInsn = null;
        for (int i = 0; i < mm.insns.length; i++) {
            AbstractInsnNode insn = mm.insns[i];
            for (Object o : frame) if (o instanceof Zombie z) { z.lifetime++; if (insn instanceof FrameNode) z.frames++; }
            if (storeInsn != null) {
                Ent stored = lookup(mm, i, storeInsn.var, walk);
                frame[storeInsn.var] = stored;
                knownFrameSize = Math.max(knownFrameSize, storeInsn.var + 1);
                if (stored != null && storeInsn.var < m.maxLocals - 1 && stored.desc() != null && size(stored.desc()) == 2) {
                    frame[storeInsn.var + 1] = null;
                    knownFrameSize = Math.max(knownFrameSize, storeInsn.var + 2);
                    resurrect(frame, knownFrameSize);
                }
                storeInsn = null;
            }
            if (insn instanceof FrameNode) {
                frameIndex++;
                List<Object> local = mm.expanded[i];   // the target class is read with expanded frames
                int frameNodeSize = computeFrameSize(local, initialFrameSize);
                int[] fd = frameIndex < fds.length ? fds[frameIndex] : null;
                if (fd != null) {
                    if (fd[0] == Opcodes.F_FULL) { frameSize = lastFrameSize = Math.max(initialFrameSize, Math.min(frameNodeSize, fd[2])); knownFrameSize = frameSize; }
                    else frameSize = adjusted(frameSize, fd[0], fd[2], initialFrameSize);
                } else frameSize = adjusted(frameSize, Opcodes.F_NEW, computeFrameSize(local, initialFrameSize), initialFrameSize);
                if (frameSize < initialFrameSize) return null;   // Mixin throws: the handler fails to apply
                if (fd == null || fd[0] == Opcodes.F_CHOP) {   // node type is F_NEW (expanded): chop when there is no metadata
                    for (int p = frameSize; p < frame.length; p++) frame[p] = Zombie.of(frame[p], 'C');
                    knownFrameSize = lastFrameSize = frameSize;
                } else {
                    int framePos = 0;   // node type F_NEW is never F_APPEND
                    lastFrameSize = frameSize;
                    for (int localPos = 0; framePos < frame.length; framePos++, localPos++) {
                        Object lt = localPos < local.size() ? local.get(localPos) : null;
                        if (lt instanceof String) frame[framePos] = lookup(mm, i, framePos, walk);
                        else if (lt instanceof Integer t) {
                            if (t.equals(Opcodes.TOP)) {
                                if (frame[framePos] instanceof Zombie z && z.type == 'X') frame[framePos] = z.ancestor;
                            } else if (t.equals(Opcodes.UNINITIALIZED_THIS) || t.equals(Opcodes.NULL)) frame[framePos] = null;
                            else {
                                frame[framePos] = lookup(mm, i, framePos, walk);
                                if (t.equals(Opcodes.DOUBLE) || t.equals(Opcodes.LONG)) { if (framePos + 1 < frame.length) frame[framePos + 1] = null; framePos++; }
                            }
                        } else if (lt == null) {
                            if (framePos >= initialFrameSize && framePos >= frameSize && frameSize > 0)
                                frame[framePos] = framePos < knownFrameSize ? lookup(mm, i, framePos, walk) : Zombie.of(frame[framePos], 'X');
                        }
                    }
                }
            } else if (insn instanceof VarInsnNode vi) {
                if (insn.getOpcode() >= Opcodes.ILOAD && insn.getOpcode() <= Opcodes.ALOAD) {
                    Ent toLoad = lookup(mm, i, vi.var, walk);
                    frame[vi.var] = toLoad;
                    int varSize = toLoad != null && toLoad.desc() != null ? size(toLoad.desc()) : 1;
                    knownFrameSize = Math.max(knownFrameSize, vi.var + varSize);
                    resurrect(frame, knownFrameSize);
                } else storeInsn = vi;
            }
            if (i == node) break;
        }
        Ent[] out = new Ent[frame.length];
        for (int l = 0; l < frame.length; l++) {
            Object o = frame[l];
            if (o instanceof Zombie z) o = z.lifetime > 1 ? null : z.ancestor;
            Ent e = (Ent) o;
            out[l] = e == null || e.desc() == null ? null : e;
        }
        return out;
    }

    private static void resurrect(Object[] frame, int knownFrameSize) {
        for (int l = 0; l < knownFrameSize && l < frame.length; l++)
            if (frame[l] instanceof Zombie z && z.checkResurrect()) frame[l] = z.ancestor;
    }

    /** Locals.getLocalsAt092 (fabricCompatibility below 0.10) */
    static Ent[] v092(Meth mm, int node, int[][] fds, boolean fresh) {
        MethodNode m = mm.m;
        int[] walk = {fresh ? 0 : 1};
        Ent[] init = initialLocals(mm, false);
        Ent[] frame = new Ent[Math.max(m.maxLocals, init.length)];
        System.arraycopy(init, 0, frame, 0, init.length);
        int initialFrameSize = init.length, frameSize = initialFrameSize, frameIndex = -1, lastFrameSize = initialFrameSize;
        VarInsnNode storeInsn = null;
        for (int i = 0; i < mm.insns.length; i++) {
            AbstractInsnNode insn = mm.insns[i];
            if (storeInsn != null) { frame[storeInsn.var] = lookup(mm, i, storeInsn.var, walk); storeInsn = null; }
            if (insn instanceof FrameNode) {
                frameIndex++;
                List<Object> local = mm.expanded[i];
                int[] fd = frameIndex < fds.length ? fds[frameIndex] : null;
                if (fd != null) {
                    if (fd[0] == Opcodes.F_FULL) frameSize = lastFrameSize = Math.min(frameSize, fd[1]);
                    else frameSize = adjusted(frameSize, fd[0], fd[3], 0);
                } else frameSize = adjusted(frameSize, Opcodes.F_NEW, computeFrameSize(local, 0), 0);
                int framePos = 0;   // the node is F_NEW: never a chop or an append here
                lastFrameSize = frameSize;
                for (int localPos = 0; framePos < frame.length; framePos++, localPos++) {
                    Object lt = localPos < local.size() ? local.get(localPos) : null;
                    if (lt instanceof String) frame[framePos] = lookup(mm, i, framePos, walk);
                    else if (lt instanceof Integer t) {
                        if (t.equals(Opcodes.TOP)) continue;
                        if (t.equals(Opcodes.UNINITIALIZED_THIS) || t.equals(Opcodes.NULL)) frame[framePos] = null;
                        else {
                            frame[framePos] = lookup(mm, i, framePos, walk);
                            if (t.equals(Opcodes.DOUBLE) || t.equals(Opcodes.LONG)) { if (framePos + 1 < frame.length) frame[framePos + 1] = null; framePos++; }
                        }
                    } else if (lt == null) {
                        if (framePos >= initialFrameSize && framePos >= frameSize && frameSize > 0) frame[framePos] = null;
                    }
                }
            } else if (insn instanceof VarInsnNode vi) {
                if (insn.getOpcode() >= Opcodes.ILOAD && insn.getOpcode() <= Opcodes.ALOAD) frame[vi.var] = lookup(mm, i, vi.var, walk);
                else storeInsn = vi;
            }
            if (i == node) break;
        }
        for (int l = 0; l < frame.length; l++) if (frame[l] != null && frame[l].desc() == null) frame[l] = null;
        return frame;
    }

    /** Mixin moves a label or line number point forward to the next node (up to 3, stopping before a frame) */
    static int adjustNode(Meth mm, int node) {
        for (int k = 0; k < 3 && node >= 0 && node < mm.insns.length
                && (mm.insns[node] instanceof LabelNode || mm.insns[node] instanceof LineNumberNode); k++) {
            int next = node + 1 < mm.insns.length ? node + 1 : node;
            if (mm.insns[next] instanceof FrameNode) break;
            node = next;
        }
        return node;
    }

    /**
     * the locals at list index li in each variant: {@link #VIEWS}, each once as the walk that makes Mixin generate its
     * table for the method and once with the table already there (eight in all); a variant is null where Mixin itself
     * would fail. null when the method's frames cannot be read.
     */
    static List<Lv[]> views(Meth mm, int li) {
        if (!mm.ok || li < 0 || li >= mm.insns.length) return null;
        return mm.cache.computeIfAbsent(li, k -> compute(mm, k));
    }

    private static List<Lv[]> compute(Meth mm, int li) {
        int node = adjustNode(mm, li);
        List<Lv[]> out = new ArrayList<>();
        for (boolean fresh : new boolean[]{false, true}) {
            out.add(lv(modern(mm, node, mm.fdExpanded, fresh)));
            out.add(lv(modern(mm, node, mm.fdCompressed, fresh)));
            out.add(lv(v092(mm, node, mm.fdExpanded, fresh)));
            out.add(lv(v092(mm, node, mm.fdCompressed, fresh)));
        }
        return out;
    }

    static Lv[] lv(Ent[] f) {
        if (f == null) return null;
        Lv[] out = new Lv[f.length];
        for (int i = 0; i < f.length; i++) if (f[i] != null) out[i] = new Lv(i, f[i].desc(), f[i].name());
        return out;
    }
}
