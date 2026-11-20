package alloy.installer;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.util.*;

/**
 * Proofs that let the fitter keep a handler it used to strip. Each one says yes only when the Forge-patched method holds
 * the same call or the same variable as vanilla; anything it cannot prove stays stripped.
 *
 * Locals (MixinExtras @Local, @ModifyVariable, LocalCapture): at every injection point, the variable Mixin picks on Forge
 * must hold a value with the same origin as the one it picks on vanilla. Which variable Mixin picks comes from
 * {@link MixinLocals}, a port of Mixin's own local finder: it reads the LocalVariableTable the way Mixin does (helper
 * locals without an entry count, locals past their scope stay until a frame drops them), and an answer is only used when
 * all four Mixin variants agree. The origin of a local is the instruction that stored it, described with its operands
 * (a call with its receiver and arguments, a field, a method argument), up to a few levels deep.
 *
 * Moved calls (@At INVOKE): the vanilla call is gone from the Forge method, but the method has a call of the same name
 * whose owner is the vanilla owner's subclass or superclass, or whose descriptor is the vanilla one plus parameters
 * Forge added at the end. It must be the only such call shape, appear as many times as the vanilla call did, and sit
 * at the same place: an alignment of the two methods' call/field sequences pairs each vanilla call with it, with a
 * matching neighbour on at least one side.
 *
 * Shifted ordinals: Forge added calls of the same target, so ordinal N points elsewhere. The same alignment finds the
 * Forge occurrence paired with vanilla's N-th one (with a matching neighbour); the ordinal is rewritten to it.
 *
 * Delegates: Forge kept the vanilla method as a short delegate to a new overload with trailing parameters
 * (setRotation(FF) -> setRotation(FFF)) and the call moved there. The handler moves to the overload when the overload
 * holds the call as many times as vanilla did and its body is close to vanilla's.
 */
final class Recover {
    private Recover() { }

    /** handlers that a rewrite here proved: "mixin#handler+desc|atTarget|ordinal" (the values after the rewrite) */
    static final ThreadLocal<Set<String>> PROVEN = ThreadLocal.withInitial(HashSet::new);

    static String key(String mixin, String handler, String atTgt, String ord) { return mixin + "#" + handler + "|" + atTgt + "|" + ord; }

    static boolean off() { return "false".equalsIgnoreCase(System.getProperty("alloy.fit.recover")); }

    // ================================================================== raw methods
    static MethodNode method(Fit.Dump d, String cls, String nameDesc) {
        ClassNode cn = d.raw(cls);
        if (cn == null) return null;
        for (MethodNode m : cn.methods) if ((m.name + m.desc).equals(nameDesc)) return m;
        return null;
    }

    /** real instruction index (opcode >= 0, as Fit.Meth counts them) -> instruction list index */
    static int[] realToList(MethodNode m) {
        List<Integer> out = new ArrayList<>();
        int i = 0;
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext(), i++) if (n.getOpcode() >= 0) out.add(i);
        int[] r = new int[out.size()];
        for (int k = 0; k < r.length; k++) r[k] = out.get(k);
        return r;
    }

    // ================================================================== type analysis (no class loading)
    static final Type MIXED = Type.getObjectType("?mixed"), NULLT = Type.getObjectType("?null");

    /** BasicInterpreter that keeps reference types; unequal reference types merge to MIXED */
    static final class Typed extends BasicInterpreter {
        Typed() { super(Opcodes.ASM9); }

        @Override public BasicValue newValue(Type t) {
            if (t != null && (t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY)) return new BasicValue(t);
            return super.newValue(t);
        }

        @Override public BasicValue newOperation(AbstractInsnNode insn) throws AnalyzerException {
            if (insn.getOpcode() == Opcodes.ACONST_NULL) return new BasicValue(NULLT);
            return super.newOperation(insn);
        }

        @Override public BasicValue binaryOperation(AbstractInsnNode insn, BasicValue a, BasicValue b) throws AnalyzerException {
            if (insn.getOpcode() == Opcodes.AALOAD) {
                Type t = a.getType();
                if (t != null && t.getSort() == Type.ARRAY) return newValue(Type.getType(t.getDescriptor().substring(1)));
                return new BasicValue(MIXED);
            }
            return super.binaryOperation(insn, a, b);
        }

        @Override public BasicValue merge(BasicValue a, BasicValue b) {
            if (a.equals(b)) return a;
            Type ta = a.getType(), tb = b.getType();
            if (ta != null && tb != null && a.isReference() && b.isReference()) {
                if (ta.equals(NULLT)) return b;
                if (tb.equals(NULLT)) return a;
                return new BasicValue(MIXED);
            }
            return BasicValue.UNINITIALIZED_VALUE;
        }
    }

    record Frames(Frame<BasicValue>[] types, Frame<SourceValue>[] src, int[] real, MethodNode m, MixinLocals.Meth ml) { }

    /** dev check (-Dalloy.fit.lvtlog): "owner name+desc" of every method whose locals were judged, read by a dev check */
    static final Set<String> SEEN = System.getProperty("alloy.fit.lvtlog") != null ? java.util.concurrent.ConcurrentHashMap.newKeySet() : null;

    static Frames frames(String owner, MethodNode m) {
        if (m == null) return null;
        if (SEEN != null) SEEN.add(owner + " " + m.name + m.desc);
        try {
            Frame<BasicValue>[] t = new Analyzer<>(new Typed()).analyze(owner, m);
            Frame<SourceValue>[] s = new Analyzer<>(new SourceInterpreter()).analyze(owner, m);
            return new Frames(t, s, realToList(m), m, new MixinLocals.Meth(owner, m));
        } catch (AnalyzerException | RuntimeException e) {
            return null;
        }
    }

    /** one local visible at an instruction: slot, type descriptor, name (from the LVT, or Mixin's "varN" / "argN") */
    record Loc(int slot, String type, String name) { }

    static int argSlots(MethodNode m) {
        int s = (m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (Type t : Type.getArgumentTypes(m.desc)) s += t.getSize();
        return s;
    }

    static boolean isRef(String desc) { return desc.startsWith("L") || desc.startsWith("["); }

    static final int NONE = -1, UNCLEAR = -2;

    /** the method's parameter names as Mixin reads them (Locals.getParameterNames: the LVT, then the Parameters attribute) */
    static String[] paramNames(MethodNode m) {
        Type[] args = Type.getArgumentTypes(m.desc);
        String[] names = new String[args.length];
        Map<Integer, Integer> idx = new HashMap<>();
        int li = (m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (int a = 0; a < args.length; a++) { idx.put(li, a); li += args[a].getSize(); }
        if (m.localVariables != null) for (LocalVariableNode lv : m.localVariables) { Integer a = idx.get(lv.index); if (a != null) names[a] = lv.name; }
        if (m.parameters != null) for (int i = 0; i < Math.min(args.length, m.parameters.size()); i++) if (names[i] == null) names[i] = m.parameters.get(i).name;
        return names;
    }

    /**
     * Mixin's LocalVariableDiscriminator in one variant of the locals: the slot it picks, NONE when Mixin finds none or
     * fails (the handler does not apply), UNCLEAR when a type this port does not know could change the answer.
     */
    static int pickIn(MixinLocals.Lv[] ls, MixinLocals.Meth ml, Fit.LocalSpec s) {
        MethodNode m = ml.m;
        if (ls == null || s.type().equals("?")) return UNCLEAR;
        boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
        int base = isStatic ? 0 : 1;
        if (s.argsOnly()) {
            // Mixin takes this and the arguments from the descriptor. Names: from the LVT at Fabric compat 0.17 and up,
            // argN below; a handler that names an argument only works on Fabric with the former, so that is the one used
            MixinLocals.Lv[] a = new MixinLocals.Lv[argSlots(m)];
            String[] names = paramNames(m);
            Type[] args = Type.getArgumentTypes(m.desc);
            int k = 0;
            if (!isStatic) { a[0] = new MixinLocals.Lv(0, Type.getObjectType(ml.owner).getDescriptor(), "this"); k = 1; }
            for (int i = 0; i < args.length; i++) { a[k] = new MixinLocals.Lv(k, args[i].getDescriptor(), names[i] == null ? "arg" + i : names[i]); k += args[i].getSize(); }
            ls = a;
        }
        boolean unsure = false;
        for (MixinLocals.Lv l : ls) if (l != null && !l.sure()) unsure = true;
        if (unsure && isRef(s.type())) return UNCLEAR;
        boolean implicit = s.ordinal() < 0 && s.index() < base && s.name().isEmpty();
        Map<String, Integer> ord = new HashMap<>();
        int[] ordinal = new int[ls.length];
        for (int l = 0; l < ls.length; l++) {
            if (ls[l] == null) continue;
            ordinal[l] = ord.merge(ls[l].desc(), 0, (x, y) -> x + 1);
        }
        if (implicit) {
            int found = NONE, count = 0;
            for (int l = base; l < ls.length; l++) if (ls[l] != null && ls[l].desc().equals(s.type())) { count++; found = l; }
            return count == 1 ? found : NONE;
        }
        for (int l = base; l < ls.length; l++) {
            if (ls[l] == null || !ls[l].desc().equals(s.type())) continue;
            boolean hit = s.ordinal() > -1 ? s.ordinal() == ordinal[l] : s.index() >= base ? s.index() == l : s.name().equals(ls[l].name());
            if (hit) return l;
        }
        return NONE;
    }

    /** the slot Mixin picks at list index li in each variant (MixinLocals.VIEWS); all UNCLEAR when the frames cannot be read */
    static int[] picks(Frames f, int li, Fit.LocalSpec s) {
        List<MixinLocals.Lv[]> vs = MixinLocals.views(f.ml(), li);
        int[] out = new int[MixinLocals.VIEWS.length];
        Arrays.fill(out, UNCLEAR);
        if (vs != null) for (int k = 0; k < out.length; k++) out[k] = pickIn(vs.get(k), f.ml(), s);
        return out;
    }

    /** the slot Mixin picks at list index li, the same in every variant; NONE or UNCLEAR otherwise */
    static int pickSlot(Frames f, int li, Fit.LocalSpec s) {
        int r = Integer.MIN_VALUE;
        for (int x : picks(f, li, s)) {
            if (x == UNCLEAR) return UNCLEAR;
            if (r != Integer.MIN_VALUE && r != x) return UNCLEAR;   // the variants disagree: not proven either way
            r = x;
        }
        return r;
    }

    /** the local a @Local / @ModifyVariable picks at list index li in every variant, or null when none, unclear or they differ */
    static Loc pick(Frames f, int li, Fit.LocalSpec s) {
        int slot = pickSlot(f, li, s);
        if (slot < 0) return null;
        MixinLocals.Lv l = MixinLocals.views(f.ml(), li).get(0)[slot];
        return new Loc(slot, l.desc(), l.name());
    }

    /**
     * the locals a LocalCapture handler receives at list index li in variant k (after the arguments, CallbackInfo locals
     * left out, in slot order), at most n; null when a type is not known or Mixin fails there.
     */
    static List<Loc> captured(Frames f, int li, int k, int n) {
        List<MixinLocals.Lv[]> vs = MixinLocals.views(f.ml(), li);
        if (vs == null || vs.get(k) == null) return null;
        MixinLocals.Lv[] v = vs.get(k);
        List<Loc> out = new ArrayList<>();
        for (int l = argSlots(f.m()); l < v.length && out.size() < n; l++) {
            if (v[l] == null || v[l].desc().startsWith("Lorg/spongepowered/asm/mixin/injection/callback/")) continue;
            if (!v[l].sure()) return null;
            out.add(new Loc(l, v[l].desc(), v[l].name()));
        }
        return out;
    }

    /** the locals at list index li in the first variant (for messages) */
    static List<Loc> shown(Frames f, int li) {
        List<MixinLocals.Lv[]> vs = MixinLocals.views(f.ml(), li);
        List<Loc> out = new ArrayList<>();
        if (vs == null || vs.get(0) == null) return out;
        for (MixinLocals.Lv l : vs.get(0)) if (l != null && l.slot() >= argSlots(f.m())) out.add(new Loc(l.slot(), l.desc(), l.name()));
        return out;
    }

    /**
     * list index of an injection point given as a real instruction index. INVOKE_ASSIGN (Fit.injectionPoints: the
     * instruction after the call): Mixin's AfterInvoke steps over a store right after the call, so its node is the one
     * after that store.
     */
    static int listIndex(Frames f, int real, boolean assign) {
        int li = f.real()[real];
        if (assign && li > 0 && f.m().instructions.get(li) instanceof VarInsnNode vi && vi.getOpcode() >= Opcodes.ISTORE
                && f.m().instructions.get(li - 1) instanceof MethodInsnNode && li + 1 < f.m().instructions.size()) return li + 1;
        return li;
    }

    // ================================================================== origins
    static String norm(String s) { return FitTargets.LNUM.matcher(s).replaceAll("$1#"); }

    static String describe(Frames f, AbstractInsnNode p, int depth) {
        if (depth > 4) return "~";
        int op = p.getOpcode();
        String head;
        if (p instanceof MethodInsnNode mi) head = "M" + op + " " + norm(mi.owner + "." + mi.name + mi.desc);
        else if (p instanceof FieldInsnNode fi) head = "G" + op + " " + fi.owner + "." + fi.name + ":" + fi.desc;
        else if (p instanceof TypeInsnNode ti) head = "N" + op + " " + ti.desc;
        else if (p instanceof LdcInsnNode li) head = "C " + li.cst;
        else if (p instanceof IntInsnNode ii) head = "K" + op + " " + ii.operand;
        else if (p instanceof InvokeDynamicInsnNode di) {
            StringBuilder sb = new StringBuilder("D " + di.name + di.desc);
            for (Object o : di.bsmArgs) if (o instanceof Handle h) sb.append(' ').append(norm(h.getOwner() + "." + h.getName() + h.getDesc()));
            head = sb.toString();
        } else if (p instanceof VarInsnNode vi && op >= Opcodes.ILOAD && op <= Opcodes.ALOAD) {
            return "L(" + localOrigin(f, f.m().instructions.indexOf(p), vi.var, depth + 1) + ")";
        } else if (p instanceof MultiANewArrayInsnNode mn) head = "A " + mn.desc + mn.dims;
        else head = "O" + op;
        // operands: what this instruction took from the stack
        int li = f.m().instructions.indexOf(p);
        Frame<SourceValue> before = f.src()[li], after = li + 1 < f.src().length ? f.src()[li + 1] : null;
        if (before == null) return head;
        int pushed = 1, consumed;
        if (after != null) consumed = before.getStackSize() - after.getStackSize() + pushed;
        else consumed = 0;
        if (consumed <= 0) return head;
        StringBuilder sb = new StringBuilder(head).append('(');
        for (int k = before.getStackSize() - consumed; k < before.getStackSize(); k++) {
            if (k < 0) continue;
            sb.append(value(f, before.getStack(k), depth + 1)).append(',');
        }
        return sb.append(')').toString();
    }

    static String value(Frames f, SourceValue v, int depth) {
        if (v == null) return "?";
        if (v.insns.isEmpty()) return "?";
        TreeSet<String> parts = new TreeSet<>();
        for (AbstractInsnNode p : v.insns) parts.add(describe(f, p, depth));
        return String.join("|", parts);
    }

    /** what the local in `slot` holds just before list index li */
    static String localOrigin(Frames f, int li, int slot, int depth) {
        if (depth > 4) return "~";
        Frame<SourceValue> fr = f.src()[li];
        if (fr == null || slot >= fr.getLocals()) return "?";
        SourceValue v = fr.getLocal(slot);
        if (v.insns.isEmpty()) return slot < argSlots(f.m()) ? "ARG" + slot : "?";
        TreeSet<String> parts = new TreeSet<>();
        for (AbstractInsnNode p : v.insns) {
            int pi = f.m().instructions.indexOf(p);
            if (p instanceof VarInsnNode && p.getOpcode() >= Opcodes.ISTORE && p.getOpcode() <= Opcodes.ASTORE) {
                Frame<SourceValue> b = f.src()[pi];
                parts.add(b == null || b.getStackSize() == 0 ? "?" : "S(" + value(f, b.getStack(b.getStackSize() - 1), depth + 1) + ")");
            } else if (p instanceof IincInsnNode ii) {
                parts.add("I" + ii.incr + "(" + localOrigin(f, pi, slot, depth + 1) + ")");
            } else parts.add("?" + p.getOpcode());
        }
        return String.join("|", parts);
    }

    // ================================================================== @Local check
    /**
     * every @Local (or the @ModifyVariable variable) picks, at every injection point, a Forge variable with the same
     * origin as on vanilla. vPts / pPts are real instruction indexes (Fit.injectionPoints) of the same points in the same
     * order. Judged per variant of Mixin's locals: a variant where Mixin finds no variable on vanilla is one the mod
     * cannot be running with (it would fail on Fabric too) and is left out; in every other variant the Forge pick must
     * hold the same value. A variant this port cannot read on vanilla refuses.
     */
    static String localsSame(String owner, MethodNode vm, MethodNode pm, List<Integer> vPts, List<Integer> pPts, List<Fit.LocalSpec> specs) {
        return localsSame(owner, vm, pm, vPts, pPts, specs, false);
    }

    static String localsSame(String owner, MethodNode vm, MethodNode pm, List<Integer> vPts, List<Integer> pPts, List<Fit.LocalSpec> specs, boolean assign) {
        if (vm == null || pm == null) return "no method bytes";
        if (specs.isEmpty()) return "no @Local specs";
        if (vPts == null || pPts == null || vPts.isEmpty() || pPts.isEmpty()) return "injection points unknown";
        Frames fv = frames(owner, vm), fp = frames(owner, pm);
        if (fv == null || fp == null) return "analysis failed";
        int nv = MixinLocals.VIEWS.length;
        boolean params = vm.desc.equals(pm.desc) && ((vm.access ^ pm.access) & Opcodes.ACC_STATIC) == 0, names = realNames(vm, pm);
        for (Fit.LocalSpec s : specs) {
            boolean live = false;
            if (vPts.size() != pPts.size()) {
                // Forge added or removed points (an extra return): in each variant every vanilla point must agree on one
                // origin, and every Forge point must pick that origin
                for (int k = 0; k < nv; k++) {
                    Set<String> vo = new TreeSet<>();
                    boolean dead = false;
                    for (int x : vPts) {
                        int li = listIndex(fv, x, assign), a = picks(fv, li, s)[k];
                        if (a == UNCLEAR) return "vanilla pick unclear for " + s + " (" + MixinLocals.VIEWS[k] + ")";
                        if (a == NONE) { dead = true; break; }
                        vo.add(ident(fv, li, a, params, names, k));
                    }
                    if (dead) continue;
                    if (vo.size() != 1) return "vanilla points disagree " + vo;
                    String oa = vo.iterator().next();
                    if (oa.contains("?") && !oa.startsWith("ARG")) return "vanilla origin unknown " + oa;
                    for (int x : pPts) {
                        int li = listIndex(fp, x, assign), b = picks(fp, li, s)[k];
                        if (b < 0) return "Forge pick " + (b == NONE ? "missing" : "unclear") + " for " + s + " (" + MixinLocals.VIEWS[k] + ")";
                        String ob = ident(fp, li, b, params, names, k);
                        if (!oa.equals(ob)) return "origin differs: " + oa + " vs " + ob;
                    }
                    live = true;
                }
            } else {
                for (int k = 0; k < nv; k++) {
                    boolean dead = false;
                    for (int j = 0; j < vPts.size(); j++) {
                        int lv = listIndex(fv, vPts.get(j), assign), lp = listIndex(fp, pPts.get(j), assign);
                        int a = picks(fv, lv, s)[k];
                        if (a == UNCLEAR) return "vanilla pick unclear for " + s + " (" + MixinLocals.VIEWS[k] + ")";
                        if (a == NONE) { dead = true; break; }
                        int b = picks(fp, lp, s)[k];
                        if (b < 0) return "Forge pick " + (b == NONE ? "missing" : "unclear") + " for " + s + " (" + MixinLocals.VIEWS[k] + ")";
                        String oa = ident(fv, lv, a, params, names, k), ob = ident(fp, lp, b, params, names, k);
                        if (oa.contains("?") && !oa.startsWith("ARG")) return "vanilla origin unknown " + oa;
                        if (!oa.equals(ob)) return "origin differs: " + oa + " vs " + ob;
                    }
                    if (!dead) live = true;
                }
            }
            if (!live) return "vanilla pick missing for " + s + " in every variant";
        }
        return null;
    }

    /**
     * what a picked slot is, for comparing vanilla with Forge: a method parameter is the same variable on both sides when
     * the descriptors are the same (even when the code stores into it), so it is named by its slot; any other local by
     * the origin of its value
     */
    static String ident(Frames f, int li, int slot, boolean params, boolean names, int k) {
        if (params && slot < argSlots(f.m())) return "PARAM" + slot;
        if (names) {
            String n = MixinLocals.views(f.ml(), li).get(k)[slot].name();
            if (!n.matches("(var|arg)\\d+")) return "NAME " + n;
        }
        return localOrigin(f, li, slot, 0);
    }

    /**
     * both methods have a LocalVariableTable with real names (26.x: Mojang's names, and Forge recompiled from them): a
     * local with the same name at the same point is the same variable even when Forge computes its value differently.
     * Not on 1.20.x / 1.21.x, whose vanilla names are $$N.
     */
    static boolean realNames(MethodNode a, MethodNode b) {
        for (MethodNode m : new MethodNode[]{a, b}) {
            if (m.localVariables == null || m.localVariables.isEmpty()) return false;
            for (LocalVariableNode lv : m.localVariables) if (lv.name.startsWith("$$") || lv.name.matches("var\\d+|p_\\w+_|\u2603")) return false;
        }
        return true;
    }

    static boolean assignAt(Fit.Rec rec) {
        String[] sp = rec.atVal().split(":");
        return sp[sp.length - 1].equalsIgnoreCase("INVOKE_ASSIGN");
    }

    /** Fit.evalInj hook: true when the handler's @Locals provably pick the same values on Forge */
    static boolean localsSame(Fit.Dump v, Fit.Dump p, String tgt, Fit.Meth vm, Fit.Meth pm, Fit.Rec rec, List<String> why) {
        if (off()) return false;
        // @ModifyArgs with a @Local: FrozenLib's AbstractMinecartMixin#useGravity passes this proof on Forge 1.20.1 (the
        // method is byte-identical up to the call) but real Mixin fails it there (offline check). Not understood, so these
        // stay stripped.
        if (rec.anno().equals("ModifyArgs")) {
            if (why != null) why.add("@ModifyArgs is not proven");
            return false;
        }
        List<Fit.LocalSpec> specs = Fit.parseSpecs(rec.val());
        String[] sp = rec.atVal().split(":");
        String kind = sp[sp.length - 1].toUpperCase();
        String r;
        MethodNode a = method(v, tgt, vm.name + vm.desc), b = method(p, tgt, pm.name + pm.desc);
        if (rec.val().startsWith("LV") && (kind.equals("LOAD") || kind.equals("STORE"))) {
            r = loadStoreSame(tgt, a, b, kind.equals("STORE"), Integer.parseInt(rec.ord()), specs.get(0));
            if (r == null && specs.size() > 1) r = "@Local on a @ModifyVariable at " + kind + " is not proven";
        } else {
            List<Integer> pv = Fit.injectionPoints(vm, rec.atVal(), rec.atTgt(), rec.ord());
            List<Integer> pp = Fit.injectionPoints(pm, rec.atVal(), rec.atTgt(), rec.ord());
            r = localsSame(tgt, a, b, pv, pp, specs, kind.equals("INVOKE_ASSIGN"));
        }
        if (r != null && why != null) why.add(r);
        if (r != null && System.getProperty("alloy.fit.decisions") != null) System.out.println("LOCALS " + rec.cls() + "#" + rec.name() + " on " + tgt + "." + vm.name + ": " + r);
        return r == null;
    }

    /**
     * @ModifyVariable at LOAD / STORE: Mixin visits every load (store) of the variable's type and keeps those where its
     * discriminator, asked at that load (at the node after that store), picks the slot the instruction uses; the @At
     * ordinal counts those. Vanilla and Forge must give the same number of such points (or both have the ordinal-th one),
     * each with the same origin of the value; every variant of Mixin's locals must agree on every candidate.
     */
    static String loadStoreSame(String owner, MethodNode vm, MethodNode pm, boolean store, int ord, Fit.LocalSpec s) {
        if (vm == null || pm == null) return "no method bytes";
        Frames fv = frames(owner, vm), fp = frames(owner, pm);
        if (fv == null || fp == null) return "analysis failed";
        String what = store ? "STORE" : "LOAD";
        boolean live = false;
        boolean params = vm.desc.equals(pm.desc) && ((vm.access ^ pm.access) & Opcodes.ACC_STATIC) == 0, names = realNames(vm, pm);
        for (int k = 0; k < MixinLocals.VIEWS.length; k++) {
            List<String> a = loadStoreOrigins(fv, store, ord, s, k, params, names), b = loadStoreOrigins(fp, store, ord, s, k, params, names);
            if (a == null) return "vanilla " + what + " points unclear for " + s + " (" + MixinLocals.VIEWS[k] + ")";
            if (a.isEmpty()) continue;   // Mixin finds no point on vanilla in this variant
            if (b == null) return "Forge " + what + " points unclear for " + s + " (" + MixinLocals.VIEWS[k] + ")";
            if (!a.equals(b) && !(ord < 0 && extraOnly(a, b))) return what + " points differ: vanilla " + a + " Forge " + b;
            for (String o : a) if (o.contains("?") && !o.startsWith("ARG")) return "vanilla origin unknown " + o;
            live = true;
        }
        return live ? null : "no vanilla " + what + " point for " + s;
    }

    /** origins of the variable at each point Mixin finds in variant k (only the ord-th when ord >= 0); null when unclear */
    /**
     * Forge only repeats what vanilla does: vanilla's points are, in order, among Forge's, and every extra Forge point
     * holds a value of an origin vanilla has too (Forge assigns the same flag again). Without an ordinal the handler then
     * runs at every vanilla point and at the copies, as it would at every such store on vanilla.
     */
    static boolean extraOnly(List<String> a, List<String> b) {
        int i = 0;
        for (String x : b) {
            if (i < a.size() && a.get(i).equals(x)) i++;
            else if (!a.contains(x)) return false;
        }
        return i == a.size();
    }

    static List<String> loadStoreOrigins(Frames f, boolean store, int ord, Fit.LocalSpec s, int k, boolean params, boolean names) {
        if (s.type().equals("?")) return null;
        int opcode = Type.getType(s.type()).getOpcode(store ? Opcodes.ISTORE : Opcodes.ILOAD);
        AbstractInsnNode[] in = f.m().instructions.toArray();
        List<String> out = new ArrayList<>();
        int count = 0;
        for (int i = 0; i < in.length; i++) {
            if (!(in[i] instanceof VarInsnNode vi) || vi.getOpcode() != opcode) continue;
            if (ord >= 0 && count > ord) break;   // Mixin stops looking once the ordinal-th point is found
            int at = store ? i + 1 : i;   // AfterStoreLocal asks at the next node
            if (at >= in.length) continue;
            int slot = picks(f, at, s)[k];
            if (slot == UNCLEAR) return null;
            if (store) i++;   // the node after the store was the pending check, Mixin does not look at it as an instruction
            if (slot != vi.var) continue;
            if (ord < 0 || ord == count) out.add(ident(f, at, vi.var, params, names, k));
            count++;
        }
        return out;
    }

    /**
     * the older name proof (Fit.sugarSame, 26.x where both sides have real names): every @Local picks, in Mixin's view, a
     * variable of the same LVT name on both sides at every point. Names Mixin makes up (varN, argN) do not count.
     */
    static boolean namesSame(Fit.Dump v, Fit.Dump p, String tgt, Fit.Meth vm, Fit.Meth pm, Fit.Rec rec) {
        if (off()) return false;
        List<Fit.LocalSpec> specs = Fit.parseSpecs(rec.val());
        List<Integer> pv = Fit.injectionPoints(vm, rec.atVal(), rec.atTgt(), rec.ord()), pp = Fit.injectionPoints(pm, rec.atVal(), rec.atTgt(), rec.ord());
        if (specs.isEmpty() || pv == null || pp == null || pv.isEmpty() || pv.size() != pp.size()) return false;
        Frames fv = frames(tgt, method(v, tgt, vm.name + vm.desc)), fp = frames(tgt, method(p, tgt, pm.name + pm.desc));
        if (fv == null || fp == null) return false;
        boolean assign = assignAt(rec);
        for (Fit.LocalSpec s : specs) {
            boolean live = false;
            for (int k = 0; k < MixinLocals.VIEWS.length; k++) {
                boolean dead = false;
                for (int j = 0; j < pv.size(); j++) {
                    int lv = listIndex(fv, pv.get(j), assign), lp = listIndex(fp, pp.get(j), assign);
                    int a = picks(fv, lv, s)[k];
                    if (a == UNCLEAR) return false;
                    if (a == NONE) { dead = true; break; }
                    int b = picks(fp, lp, s)[k];
                    if (b < 0) return false;
                    String na = name(fv, lv, k, a, s), nb = name(fp, lp, k, b, s);
                    if (!na.equals(nb) || na.matches("(var|arg)\\d+|this")) return false;
                }
                if (!dead) live = true;
            }
            if (!live) return false;
        }
        return true;
    }

    /** the name of the variable picked in slot `slot` (argsOnly: the parameter name Mixin reads) */
    static String name(Frames f, int li, int k, int slot, Fit.LocalSpec s) {
        if (s.argsOnly()) {
            Type[] args = Type.getArgumentTypes(f.m().desc);
            String[] names = paramNames(f.m());
            int at = (f.m().access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
            for (int i = 0; i < args.length; i++) { if (at == slot) return names[i] == null ? "arg" + i : names[i]; at += args[i].getSize(); }
            return "this";
        }
        return MixinLocals.views(f.ml(), li).get(k)[slot].name();
    }

    /**
     * LocalCapture (@Inject with locals after the CallbackInfo): Mixin hands the handler the target's locals after its
     * arguments, in slot order, and the types must match. On Forge the first N such locals must have the handler's types
     * and the same origins as on vanilla, at every injection point.
     */
    static String captureSame(String owner, MethodNode vm, MethodNode pm, List<Integer> vPts, List<Integer> pPts, String hdesc) {
        return captureSame(owner, vm, pm, vPts, pPts, hdesc, false);
    }

    static String captureSame(String owner, MethodNode vm, MethodNode pm, List<Integer> vPts, List<Integer> pPts, String hdesc, boolean assign) {
        if (vm == null || pm == null) return "no method bytes";
        if (vPts == null || pPts == null || vPts.isEmpty() || vPts.size() != pPts.size()) return "injection points differ";
        List<String> want = capturedTypes(hdesc);
        if (want.isEmpty()) return "no captured locals";
        Frames fv = frames(owner, vm), fp = frames(owner, pm);
        if (fv == null || fp == null) return "analysis failed";
        boolean live = false;
        String first = null;
        for (int k = 0; k < MixinLocals.VIEWS.length; k++) {
            boolean dead = false;
            for (int j = 0; j < vPts.size(); j++) {
                int lv = listIndex(fv, vPts.get(j), assign), lp = listIndex(fp, pPts.get(j), assign);
                List<Loc> a = captured(fv, lv, k, want.size());
                if (a == null) return "vanilla captured locals unclear (" + MixinLocals.VIEWS[k] + "): want " + want + " Mixin sees " + shown(fv, lv);
                if (!types(a).equals(want)) {   // the handler does not fit vanilla in this variant
                    if (first == null) first = "want " + want + " vanilla " + a;
                    dead = true;
                    break;
                }
                List<Loc> b = captured(fp, lp, k, want.size());
                if (b == null) return "Forge captured locals unclear (" + MixinLocals.VIEWS[k] + "): want " + want + " Mixin sees " + shown(fp, lp);
                if (!types(b).equals(want)) return "type of captured locals differs (" + MixinLocals.VIEWS[k] + "): want " + want + " vanilla " + a + " Forge " + b;
                for (int i = 0; i < want.size(); i++) {
                    String oa = localOrigin(fv, lv, a.get(i).slot(), 0), ob = localOrigin(fp, lp, b.get(i).slot(), 0);
                    if (oa.contains("?") && !oa.startsWith("ARG")) return "vanilla origin unknown " + oa;
                    if (!oa.equals(ob)) return "origin of captured local " + i + " differs: " + oa + " vs " + ob;
                }
            }
            if (!dead) live = true;
        }
        return live ? null : "captured locals do not fit vanilla in any variant: " + first;
    }

    static List<String> types(List<Loc> l) {
        List<String> out = new ArrayList<>();
        for (Loc x : l) out.add(x.type());
        return out;
    }

    /**
     * the locals a LocalCapture handler takes: its parameters after the CallbackInfo, without the MixinExtras sugar at the
     * end (@Share LocalRefs, @Cancellable CallbackInfo), which MixinExtras hands over itself
     */
    static List<String> capturedTypes(String hdesc) {
        Type[] h = Type.getArgumentTypes(hdesc);
        int ci = -1;
        for (int i = 0; i < h.length; i++) if (h[i].getInternalName().startsWith("org/spongepowered/asm/mixin/injection/callback/CallbackInfo")) { ci = i; break; }
        List<String> want = new ArrayList<>();
        if (ci < 0) return want;
        int end = h.length;
        while (end > ci + 1 && (h[end - 1].getDescriptor().startsWith("Lcom/llamalad7/mixinextras/sugar/ref/")
                || h[end - 1].getInternalName().startsWith("org/spongepowered/asm/mixin/injection/callback/CallbackInfo"))) end--;
        for (int i = ci + 1; i < end; i++) want.add(h[i].getDescriptor());
        return want;
    }

    static boolean captureSame(Fit.Dump v, Fit.Dump p, String tgt, Fit.Meth vm, Fit.Meth pm, Fit.Rec rec) {
        if (off()) return false;
        String r = captureSame(tgt, method(v, tgt, vm.name + vm.desc), method(p, tgt, pm.name + pm.desc),
                Fit.injectionPoints(vm, rec.atVal(), rec.atTgt(), rec.ord()), Fit.injectionPoints(pm, rec.atVal(), rec.atTgt(), rec.ord()), rec.desc(), assignAt(rec));
        if (r != null && System.getProperty("alloy.fit.decisions") != null) System.out.println("CAPTURE " + rec.cls() + "#" + rec.name() + " on " + tgt + "." + vm.name + ": " + r);
        return r == null;
    }

    // ================================================================== alignment
    static List<String> seq(Fit.Meth m) {
        List<String> out = new ArrayList<>();
        for (String[] r : m.refs) out.add(r[0] + ":" + norm(r[1]));
        return out;
    }

    /** LCS pairs a[i] -> b[j] (-1 when unpaired); eq decides equality */
    static int[] align(List<String> a, List<String> b, java.util.function.BiPredicate<String, String> eq) {
        int n = a.size(), m = b.size();
        if ((long) n * m > 9_000_000L) return null;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--)
            for (int j = m - 1; j >= 0; j--)
                dp[i][j] = eq.test(a.get(i), b.get(j)) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
        int[] pair = new int[n];
        Arrays.fill(pair, -1);
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (eq.test(a.get(i), b.get(j)) && dp[i][j] == dp[i + 1][j + 1] + 1) { pair[i] = j; i++; j++; }
            else if (dp[i + 1][j] >= dp[i][j + 1]) i++;
            else j++;
        }
        return pair;
    }

    /** a paired element whose neighbour on at least one side is paired with the matching neighbour */
    static boolean anchored(int[] pair, int i) {
        int j = pair[i];
        if (j < 0) return false;
        boolean prev = i > 0 && pair[i - 1] == j - 1, next = i + 1 < pair.length && pair[i + 1] == j + 1;
        return prev || next;
    }

    static String mref(String owner, String name, String desc) { return "M:" + norm(owner + "." + name + desc); }

    // ================================================================== @At rewrites
    /** one change to a handler's @At: new target string and/or new ordinal (null = unchanged) */
    record AtFix(String handler, String hdesc, String oldTgt, String oldOrd, String newTgt, String newOrd, String why) { }

    static final Set<String> CALL_SHAPE_FREE = Set.of("Inject", "ModifyExpressionValue", "ModifyArg");

    /** is b in a's superclass chain, or a in b's? (Forge jar) */
    static boolean related(Fit.Dump p, Fit.Dump v, String a, String b) {
        for (Fit.Dump d : new Fit.Dump[]{p, v}) {
            for (Fit.CI c : d.chain(a)) if (c.name.equals(b)) return true;
            for (Fit.CI c : d.chain(b)) if (c.name.equals(a)) return true;
        }
        return false;
    }

    /**
     * For one INVOKE rec whose target method exists on both sides: a moved call (owner or trailing parameters) or a
     * shifted ordinal, proven by alignment. null when nothing applies; an AtFix with newTgt == null and why starting with
     * "refused" when it was looked at and refused.
     */
    static AtFix planAt(Fit.Dump v, Fit.Dump p, String tgt, Fit.Meth vm, Fit.Meth pm, Fit.Rec r, AnnotationNode an, MethodNode handler) {
        String[] sp = r.atVal().split(":");
        String kind = sp[sp.length - 1].toUpperCase();
        if (!kind.equals("INVOKE") && !kind.equals("INVOKE_ASSIGN")) return null;
        if (r.atTgt().isEmpty() || Fit.get(an, "slice") != null) return null;
        int cv = Fit.countRefs(v, tgt, vm.name, vm.desc, r.atVal(), r.atTgt()), cp = Fit.countRefs(p, tgt, pm.name, pm.desc, r.atVal(), r.atTgt());
        if (cv <= 0) return null;
        int ord = Integer.parseInt(r.ord());
        String[] s = Fit.parseSel(r.atTgt());
        if (s[0] == null || s[2] == null || !s[2].contains(")")) return null;   // loose selectors: not rewritten
        String vref = mref(s[0], s[1], s[2]);
        List<String> a = seq(vm), b = seq(pm);
        String anno = Fit.simpleName(an.desc);
        if (cp == 0) {
            // moved call: same name, related owner or trailing parameters, one shape only
            Set<String> shapes = new TreeSet<>();
            for (String[] ref : pm.refs) {
                if (!ref[0].equals("M")) continue;
                String[] q = Fit.refParts("M", ref[1]);
                if (!q[1].equals(s[1])) continue;
                boolean ownerOk = q[0].equals(s[0]) || related(p, v, q[0], s[0]);
                boolean descOk = q[2].equals(s[2]);
                if (!descOk) {
                    Object[] rel = FitTargets.pmap(s[2], q[2]);
                    descOk = rel != null && rel[0].equals("trailing");
                }
                if (ownerOk && descOk) shapes.add(q[0] + "." + q[1] + q[2]);
            }
            if (shapes.isEmpty()) return null;
            if (shapes.size() > 1) return new AtFix(r.name(), r.desc(), r.atTgt(), r.ord(), null, null, "refused: several calls could be it " + shapes);
            String shape = shapes.iterator().next();
            String[] q = Fit.refParts("M", shape);
            boolean ownerChanged = !q[0].equals(s[0]), descChanged = !q[2].equals(s[2]);
            int cnew = 0;
            for (String[] ref : pm.refs) if (ref[0].equals("M") && ref[1].equals(shape)) cnew++;
            if (cnew != cv) return new AtFix(r.name(), r.desc(), r.atTgt(), r.ord(), null, null, "refused: " + shape + " appears " + cnew + "x, vanilla call " + cv + "x");
            if (!CALL_SHAPE_FREE.contains(anno)) return new AtFix(r.name(), r.desc(), r.atTgt(), r.ord(), null, null, "refused: @" + anno + " sees the call's arguments or receiver");
            if (anno.equals("ModifyArg") && descChanged && plainParams(handler) > 1)
                return new AtFix(r.name(), r.desc(), r.atTgt(), r.ord(), null, null, "refused: @ModifyArg takes all the call's arguments and the call gained parameters");
            if (anno.equals("ModifyArg") && descChanged && Fit.get(an, "index") == null) {
                Type want = Type.getReturnType(handler.desc);
                int same = 0;
                for (Type t : Type.getArgumentTypes(q[2])) if (t.equals(want)) same++;
                if (same != 1) return new AtFix(r.name(), r.desc(), r.atTgt(), r.ord(), null, null, "refused: @ModifyArg without index, argument type not unique in " + q[2]);
            }
            String nref = mref(q[0], q[1], q[2]);
            int[] pair = align(a, b, (x, y) -> x.equals(y) || (x.equals(vref) && y.equals(nref)));
            if (pair == null) return new AtFix(r.name(), r.desc(), r.atTgt(), r.ord(), null, null, "refused: method too large to align");
            int k = 0;
            for (int i = 0; i < a.size(); i++) {
                if (!a.get(i).equals(vref)) continue;
                if (ord < 0 || k == ord) {
                    if (pair[i] < 0 || !b.get(pair[i]).equals(nref) || !anchored(pair, i))
                        return new AtFix(r.name(), r.desc(), r.atTgt(), r.ord(), null, null, "refused: vanilla call #" + k + " has no matching place on Forge");
                }
                k++;
            }
            String nt = "L" + q[0] + ";" + q[1] + q[2];
            return new AtFix(r.name(), r.desc(), r.atTgt(), r.ord(), nt, null,
                    "AT-MOVED " + r.atTgt() + " -> " + nt + " (" + (ownerChanged ? "owner" : "") + (ownerChanged && descChanged ? " and " : "") + (descChanged ? "trailing parameters" : "") + ", " + cv + "x, aligned)");
        }
        if (ord >= 0 && cp != cv) {
            // shifted ordinal: find the Forge occurrence paired with vanilla's ord-th one
            int[] pair = align(a, b, String::equals);
            if (pair == null) return new AtFix(r.name(), r.desc(), r.atTgt(), r.ord(), null, null, "refused: method too large to align");
            int k = 0, vi = -1;
            for (int i = 0; i < a.size(); i++) if (a.get(i).equals(vref)) { if (k == ord) vi = i; k++; }
            if (vi < 0) return null;
            if (pair[vi] < 0 || !anchored(pair, vi)) return new AtFix(r.name(), r.desc(), r.atTgt(), r.ord(), null, null, "refused: vanilla call #" + ord + " has no matching place on Forge");
            int j = 0;
            for (int i = 0; i < pair[vi]; i++) if (b.get(i).equals(vref)) j++;
            // same position also for ref kinds the counter sees (INVOKE only: the ordinal counts calls of this target)
            if (j == ord) return null;
            return new AtFix(r.name(), r.desc(), r.atTgt(), r.ord(), null, String.valueOf(j), "AT-ORDINAL " + r.atTgt() + " ordinal " + ord + " -> " + j + " (count " + cv + "->" + cp + ", aligned)");
        }
        return null;
    }

    /** handler parameters that are not MixinExtras sugar (@Local, @Share, @Cancellable) */
    static int plainParams(MethodNode h) {
        int n = Type.getArgumentTypes(h.desc).length, plain = 0;
        for (int i = 0; i < n; i++) {
            boolean sugar = false;
            for (List<AnnotationNode>[] pa : List.of(h.visibleParameterAnnotations == null ? new List[0] : h.visibleParameterAnnotations,
                    h.invisibleParameterAnnotations == null ? new List[0] : h.invisibleParameterAnnotations))
                if (i < pa.length && pa[i] != null) for (AnnotationNode a : pa[i]) if (a.desc.startsWith("Lcom/llamalad7/mixinextras/sugar/")) sugar = true;
            if (!sugar) plain++;
        }
        return plain;
    }

    /** sets target / ordinal on the @At nodes of an injector annotation that match the fix; returns how many changed */
    static int applyAt(AnnotationNode an, AtFix f) {
        int n = 0;
        for (int i = 0; an.values != null && i + 1 < an.values.size(); i += 2) {
            if (!"at".equals(an.values.get(i))) continue;
            for (Object o : Fit.list(an.values.get(i + 1))) {
                if (!(o instanceof AnnotationNode at)) continue;
                Object t = Fit.get(at, "target"), od = Fit.get(at, "ordinal");
                if (t == null || !String.valueOf(t).equals(f.oldTgt())) continue;
                if (!String.valueOf(od == null ? -1 : od).equals(f.oldOrd())) continue;
                if (f.newTgt() != null) set(at, "target", f.newTgt());
                if (f.newOrd() != null) set(at, "ordinal", Integer.valueOf(f.newOrd()));
                n++;
            }
        }
        return n;
    }

    static void set(AnnotationNode an, String k, Object val) {
        if (an.values == null) an.values = new ArrayList<>();
        for (int i = 0; i + 1 < an.values.size(); i += 2) if (k.equals(an.values.get(i))) { an.values.set(i + 1, val); return; }
        an.values.add(k);
        an.values.add(val);
    }

    // ================================================================== delegates
    /**
     * The vanilla method is still there on Forge but only hands over to an overload with trailing parameters, and the
     * call the handler wants moved into that overload. {newNameDesc, fit[], why} or {null, why} or {null, null}.
     */
    static Object[] decideDelegate(Fit.Dump v, Fit.Dump p, String tgt, Fit.Meth vm, Fit.Meth pm, List<Fit.Rec> rows, String anno, String hdesc, int cap) {
        for (Fit.Rec r : rows) {
            String[] sp = r.atVal().split(":");
            String k = sp[sp.length - 1].toUpperCase();
            if (!Set.of("INVOKE", "INVOKE_ASSIGN", "FIELD", "NEW").contains(k) || r.atTgt().isEmpty()) return new Object[]{null, null};
            int cv = Fit.countRefs(v, tgt, vm.name, vm.desc, r.atVal(), r.atTgt()), cp = Fit.countRefs(p, tgt, pm.name, pm.desc, r.atVal(), r.atTgt());
            if (cv <= 0 || cp != 0) return new Object[]{null, null};
        }
        // same-named overload first (ejectItems); else a renamed one (Forge 47: FriendlyByteBuf.writeItem(ItemStack) only
        // calls writeItemStack(ItemStack, boolean), which holds the vanilla body). The checks below are the same for both.
        Set<String> over = new TreeSet<>();
        for (boolean anyName : new boolean[]{false, true}) {
            for (String[] ref : pm.refs) {
                if (!ref[0].equals("M")) continue;
                String[] q = Fit.refParts("M", ref[1]);
                if (anyName ? q[1].equals(vm.name) || q[1].startsWith("lambda$") : !q[1].equals(vm.name) || q[2].equals(vm.desc)) continue;
                if (!q[0].equals(tgt) && !related(p, v, q[0], tgt)) continue;
                Object[] rel = FitTargets.pmap(vm.desc, q[2]);
                if (rel != null && rel[0].equals("trailing")) over.add(q[1] + q[2]);
            }
            if (!over.isEmpty()) break;
        }
        if (over.isEmpty()) return new Object[]{null, null};
        if (over.size() > 1) return new Object[]{null, "refused: delegates to several overloads " + over};
        String oSel = over.iterator().next();
        String oName = oSel.substring(0, oSel.indexOf('(')), od = oSel.substring(oSel.indexOf('('));
        Fit.Meth om = p.cls(tgt).methods.get(oSel);
        if (om == null) return new Object[]{null, "refused: overload " + oSel + " is not declared in " + tgt};
        if (!oName.equals(vm.name) && v.cls(tgt).methods.containsKey(oSel)) return new Object[]{null, "refused: " + oSel + " is also in vanilla"};
        if (om.isStatic != vm.isStatic) return new Object[]{null, "refused: static flag differs"};
        if (pm.n > 40) return new Object[]{null, "refused: " + vm.name + vm.desc + " is not a short delegate on Forge (" + pm.n + " insns)"};
        for (Fit.Rec r : rows) {
            int cv = Fit.countRefs(v, tgt, vm.name, vm.desc, r.atVal(), r.atTgt()), co = Fit.countRefs(p, tgt, om.name, om.desc, r.atVal(), r.atTgt());
            if (co != cv) return new Object[]{null, "refused: overload has the @At target " + co + "x, vanilla " + cv + "x"};
        }
        double sim = FitTargets.similarity(FitTargets.normRefs(vm), FitTargets.normRefs(om));
        if (sim < 0.7) return new Object[]{null, "refused: overload body not close to vanilla (" + sim + ")"};
        int[] id = new int[FitTargets.params(vm.desc).size()];
        for (int i = 0; i < id.length; i++) id[i] = i;
        Object[] fit = FitTargets.fitHandler(anno, hdesc, cap, vm.desc, od, id);
        if (fit[0] == null) return new Object[]{null, "refused: " + fit[1]};
        return new Object[]{oSel, fit, "FIT-D " + vm.name + vm.desc + " -> " + oSel + " (Forge kept a delegate, sim " + sim + ", " + fit[0] + ")"};
    }
}
