package alloy.installer;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * FIT-L, FIT-O and FIT-Q in the installer's fitter (Java port of fit_targets.py, same rules and thresholds).
 *
 * FIT-L: a handler targets a synthetic lambda (lambda$load$2) that Forge's recompile renumbered, or whose parameters
 * Forge changed. Candidates are Forge lambdas of the same enclosing method with the same static flag and return type
 * whose parameters relate to the vanilla ones (same, trailing or inserted extra, reordered, or changed types with a
 * close body). They are scored by body similarity; the best wins only when it is good enough and clearly ahead.
 * FIT-O: a non-lambda @Inject target is missing and Forge has exactly one same-named method with the vanilla
 * parameters plus trailing ones (or changed types and a similar body).
 * FIT-Q: a name-only selector whose vanilla method Forge kept, next to same-named overloads Forge added, gets the
 * vanilla descriptor when the handler's target arguments do not fit one of those overloads (Mixin would pick it too).
 *
 * The rewrite changes the handler annotation's `method` string to the full Forge selector, or adds a bridge method
 * (name$alloyBridge) with Forge's descriptor that calls the handler, when the handler takes target arguments that moved.
 * A jar with no rule comes back as the same byte array. Switch: -Dalloy.fit.targets=false or FF_FIT_TARGETS=0.
 */
final class FitTargets {
    static final Pattern LAMBDA = Pattern.compile("^lambda\\$(.+)\\$(\\d+)$");
    static final Pattern LNUM = Pattern.compile("(lambda\\$[^$(.]+\\$)\\d+");
    static final Set<String> CI = Set.of("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;",
            "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;");
    static final String OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
    static final double GOOD = 0.70, MARGIN = 0.15, CHANGED_TYPES = 0.80, SAME_NAME = 0.50;

    static boolean enabled() {
        String p = System.getProperty("alloy.fit.targets"), e = System.getenv("FF_FIT_TARGETS");
        if ("false".equalsIgnoreCase(p) || "0".equals(e)) return false;
        return true;
    }

    record Rule(String handler, String hdesc, String oldSel, String newSel, String bdesc, int[] map) { }

    // ------------------------------------------------------------------ descriptors
    static List<String> params(String desc) {
        List<String> out = new ArrayList<>();
        int i = 1;
        while (desc.charAt(i) != ')') {
            int j = i;
            while (desc.charAt(j) == '[') j++;
            j = desc.charAt(j) == 'L' ? desc.indexOf(';', j) + 1 : j + 1;
            out.add(desc.substring(i, j));
            i = j;
        }
        return out;
    }

    static String ret(String desc) { return desc.substring(desc.indexOf(')') + 1); }

    /** {kind, map}: how the parameter list changed; null if unrelated */
    static Object[] pmap(String old, String nw) {
        List<String> po = params(old), pn = params(nw);
        if (!ret(old).equals(ret(nw)) || pn.size() < po.size()) return null;
        int[] id = new int[po.size()];
        for (int i = 0; i < id.length; i++) id[i] = i;
        if (po.equals(pn)) return new Object[]{"same", id};
        if (pn.subList(0, po.size()).equals(po)) return new Object[]{"trailing", id};
        int[] m = new int[po.size()];
        int j = 0, k = 0;
        for (String t : po) {
            while (j < pn.size() && !pn.get(j).equals(t)) j++;
            if (j == pn.size()) break;
            m[k++] = j++;
        }
        if (k == po.size()) return new Object[]{"added", m};
        Map<String, Integer> used = new HashMap<>();
        m = new int[po.size()];
        k = 0;
        for (String t : po) {
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < pn.size(); i++) if (pn.get(i).equals(t)) idx.add(i);
            int u = used.getOrDefault(t, 0);
            if (u >= idx.size()) break;
            m[k++] = idx.get(u);
            used.put(t, u + 1);
        }
        if (k == po.size()) return new Object[]{"reordered", m};
        if (pn.size() == po.size()) return new Object[]{"changed", id};
        return null;
    }

    /** {"simple"} | {"bridge", bdesc, omap} | {null, reason} */
    static Object[] fitHandler(String anno, String hdesc, int k, String old, String nw, int[] m) {
        List<String> hp = params(hdesc), po = params(old), pn = params(nw);
        String hr = ret(hdesc);
        int base = anno.equals("Inject") ? 0 : hp.size() - k;
        if (k > po.size() || base < 0 || base + k > hp.size() || !hp.subList(base, base + k).equals(po.subList(0, k)))
            return new Object[]{null, "handler parameters are not the target's first " + k};
        boolean simple = true;
        for (int i = 0; i < k; i++) if (m[i] != i || !pn.get(i).equals(po.get(i))) { simple = false; break; }
        if (simple && !(anno.equals("Inject") && k > 0 && pn.size() != k)) return new Object[]{"simple"};
        for (int i = 0; i < k; i++) {
            String a = po.get(i), b = pn.get(m[i]);
            if (!a.equals(b) && !("L[".indexOf(a.charAt(0)) >= 0 && "L[".indexOf(b.charAt(0)) >= 0))
                return new Object[]{null, "argument " + i + " changed from " + a + " to " + b};
        }
        int maxm = -1;
        for (int i = 0; i < k; i++) maxm = Math.max(maxm, m[i]);
        List<String> region = anno.equals("Inject") ? pn : pn.subList(0, maxm + 1);
        StringBuilder bd = new StringBuilder("(");
        for (String s : hp.subList(0, base)) bd.append(s);
        for (String s : region) bd.append(s);
        for (String s : hp.subList(base + k, hp.size())) bd.append(s);
        bd.append(')').append(hr);
        int[] omap = new int[hp.size()];
        for (int i = 0; i < hp.size(); i++) omap[i] = i < base ? i : i < base + k ? base + m[i - base] : i - k + region.size();
        return new Object[]{"bridge", bd.toString(), omap};
    }

    // ------------------------------------------------------------------ similarity
    static List<String> normRefs(Fit.Meth m) {
        List<String> out = new ArrayList<>();
        if (m != null) for (String[] r : m.refs) out.add(r[0] + ":" + LNUM.matcher(r[1]).replaceAll("$1#"));
        return out;
    }

    static double similarity(List<String> a, List<String> b) {
        double s1 = sim(a, b);
        List<String> sa = new ArrayList<>(), sb = new ArrayList<>();
        for (String x : a) sa.add(stripOwner(x));
        for (String x : b) sb.add(stripOwner(x));
        return Math.max(s1, Math.round(0.95 * sim(sa, sb) * 1000) / 1000.0);
    }

    static String stripOwner(String x) {
        if (!x.startsWith("M:")) return x;
        String r = x.substring(2);
        int dot = r.indexOf('.');
        return "M:" + (dot < 0 ? r : r.substring(dot + 1));
    }

    static double sim(List<String> a, List<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        Set<String> sa = new HashSet<>(a), sb = new HashSet<>(b), inter = new HashSet<>(sa), uni = new HashSet<>(sa);
        inter.retainAll(sb);
        uni.addAll(sb);
        double jac = inter.size() / (double) Math.max(1, uni.size());
        Map<String, Integer> ca = new HashMap<>(), cb = new HashMap<>();
        for (String x : a) ca.merge(x, 1, Integer::sum);
        for (String x : b) cb.merge(x, 1, Integer::sum);
        int common = 0;
        for (var e : ca.entrySet()) common += Math.min(e.getValue(), cb.getOrDefault(e.getKey(), 0));
        double cont = common / (double) Math.max(1, a.size());
        double seq = 2.0 * lcs(a, b) / Math.max(1, a.size() + b.size());   // difflib ratio, LCS form
        return Math.round((0.4 * cont + 0.3 * jac + 0.3 * seq) * 1000) / 1000.0;
    }

    static int lcs(List<String> a, List<String> b) {
        if (a.size() > 3000 || b.size() > 3000) return 0;
        int[] prev = new int[b.size() + 1], cur = new int[b.size() + 1];
        for (int i = 1; i <= a.size(); i++) {
            for (int j = 1; j <= b.size(); j++)
                cur[j] = a.get(i - 1).equals(b.get(j - 1)) ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
            int[] t = prev; prev = cur; cur = t;
            Arrays.fill(cur, 0);
        }
        return prev[b.size()];
    }

    /** number of target-method arguments a handler takes; -1 if the shape is not understood */
    static int capturedArgs(String anno, String hdesc, String atTarget) {
        List<String> hp = params(hdesc);
        switch (anno) {
            case "Inject": for (int i = 0; i < hp.size(); i++) if (CI.contains(hp.get(i))) return i; return -1;
            case "ModifyVariable", "ModifyConstant", "ModifyExpressionValue", "ModifyReturnValue", "ModifyArgs": return Math.max(0, hp.size() - 1);
            case "ModifyArg": return 0;
            case "WrapOperation": for (int i = 0; i < hp.size(); i++) if (hp.get(i).equals(OPERATION)) return hp.size() - i - 1; return -1;
            case "Redirect", "WrapWithCondition", "ModifyReceiver": {
                if (atTarget == null || !atTarget.contains("(")) return -1;
                String owner = atTarget.startsWith("L") && atTarget.indexOf(';') > 0 ? atTarget.substring(1, atTarget.indexOf(';')) : null;
                List<String> cp = params(atTarget.substring(atTarget.indexOf('(')));
                int base = cp.size() + (owner != null && !hp.isEmpty() && hp.get(0).equals("L" + owner + ";") ? 1 : 0);
                return Math.max(0, hp.size() - base);
            }
            default: return -1;
        }
    }

    static boolean atOk(Fit.Dump d, String tgt, String n, String ds, Fit.Rec r) {
        if (r.atTgt() == null || r.atTgt().isEmpty()) return true;
        int c = Fit.countRefs(d, tgt, n, ds, r.atVal(), r.atTgt());
        if (c < 0) return true;
        int ord = Integer.parseInt(r.ord());
        return ord >= 0 ? c > ord : c > 0;
    }

    // ------------------------------------------------------------------ decisions
    /** {newNameDesc, fit[]} or {null, reason} */
    static Object[] decideLambda(Fit.Dump v, Fit.Dump p, String tgt, String n, String d, List<Fit.Rec> rows, String anno, String hdesc, int cap) {
        Matcher lm = LAMBDA.matcher(n);
        lm.matches();
        String enc = lm.group(1);
        Fit.Meth vm = v.cls(tgt).methods.get(n + d);
        List<String> vRefs = normRefs(vm);
        Fit.CI pc = p.cls(tgt);
        Fit.Meth same = pc.methods.get(n + d);
        boolean present = same != null;
        double sameSim = -1;
        if (present) {
            sameSim = same.hash.equals(vm.hash) ? 1.0 : similarity(vRefs, normRefs(same));
            if (sameSim >= 0.5) return new Object[]{null, null};
        }
        record Cand(double sim, String name, String desc, String rel, Object[] fit) { }
        List<Cand> scored = new ArrayList<>();
        Map<String, Integer> whyNot = new TreeMap<>();
        for (Fit.Meth pm : pc.methods.values()) {
            Matcher m2 = LAMBDA.matcher(pm.name);
            if (!m2.matches() || !m2.group(1).equals(enc)) continue;
            if (pm.isStatic != vm.isStatic) { whyNot.merge("static flag", 1, Integer::sum); continue; }
            Object[] rel = pmap(d, pm.desc);
            if (rel == null) { whyNot.merge("descriptor", 1, Integer::sum); continue; }
            Object[] fit = fitHandler(anno, hdesc, cap, d, pm.desc, (int[]) rel[1]);
            if (fit[0] == null) { whyNot.merge(pm.name + pm.desc + ": " + fit[1], 1, Integer::sum); continue; }
            boolean ok = true;
            for (Fit.Rec r : rows) if (!atOk(p, tgt, pm.name, pm.desc, r)) { ok = false; break; }
            if (!ok) { whyNot.merge("@At target not in body", 1, Integer::sum); continue; }
            double s = pm.hash.equals(vm.hash) ? 1.0 : similarity(vRefs, normRefs(pm));
            if (rel[0].equals("changed") && s < (pm.name.equals(n) ? SAME_NAME : CHANGED_TYPES)) { whyNot.merge("types changed, body not close", 1, Integer::sum); continue; }
            // a @ModifyVariable picks its variable by type: when Forge changed a parameter type (Architectury's
            // wrapCharTyped on GuiEventListener, Forge's lambda takes Screen) it finds nothing there and fails hard
            if (rel[0].equals("changed") && anno.equals("ModifyVariable")) { whyNot.merge("@ModifyVariable and types changed", 1, Integer::sum); continue; }
            scored.add(new Cand(s, pm.name, pm.desc, (String) rel[0], fit));
        }
        scored.sort(Comparator.comparingDouble((Cand c) -> -c.sim()).thenComparing(Cand::name).thenComparing(Cand::desc));
        StringBuilder top = new StringBuilder();
        for (int i = 0; i < Math.min(3, scored.size()); i++) top.append(i > 0 ? ", " : "").append(scored.get(i).name()).append(scored.get(i).desc()).append(' ').append(scored.get(i).rel()).append(' ').append(scored.get(i).sim());
        if (present) {
            List<Cand> others = new ArrayList<>();
            for (Cand c : scored) if (!(c.name().equals(n) && c.desc().equals(d))) others.add(c);
            if (others.isEmpty() || others.get(0).sim() < 0.85) return new Object[]{null, "kept: " + n + d + " exists on Forge but its body looks unrelated (" + sameSim + ") [" + top + "]"};
            double second = others.size() > 1 ? others.get(1).sim() : 0;
            if (others.get(0).sim() - second < MARGIN) return new Object[]{null, "refused: ambiguous [" + top + "]"};
            Cand b = others.get(0);
            return new Object[]{b.name() + b.desc(), b.fit(), "FIT-L collision: " + n + d + " -> " + b.name() + b.desc() + " (" + b.rel() + ", sim " + b.sim() + ") [" + top + "]"};
        }
        if (scored.isEmpty()) return new Object[]{null, "refused: no usable Forge lambda$" + enc + "$* (rejected: " + whyNot + ")"};
        Cand best = scored.get(0);
        double second = scored.size() > 1 ? scored.get(1).sim() : 0;
        if (best.sim() < GOOD && !(scored.size() == 1 && best.name().equals(n) && best.sim() >= SAME_NAME))
            return new Object[]{null, "refused: best match too weak [" + top + "]"};
        if (scored.size() > 1 && best.sim() - second < MARGIN && !(best.sim() == 1.0 && second < 1.0))
            return new Object[]{null, "refused: ambiguous [" + top + "]"};
        return new Object[]{best.name() + best.desc(), best.fit(), "FIT-L " + n + d + " -> " + best.name() + best.desc() + " (" + best.rel() + ", sim " + best.sim() + ", " + best.fit()[0] + ") [" + top + "]"};
    }

    static Object[] decideOverload(Fit.Dump v, Fit.Dump p, String tgt, String n, String d, List<Fit.Rec> rows, String anno, String hdesc, int cap) {
        Fit.CI pc = p.cls(tgt);
        List<String> alts = new ArrayList<>();
        for (Fit.Meth pm : pc.methods.values()) if (pm.name.equals(n)) alts.add(pm.desc);
        if (alts.isEmpty()) return new Object[]{null, null};
        List<Object[]> rels = new ArrayList<>();
        for (String pd : alts) { Object[] r = pmap(d, pd); if (r != null && (r[0].equals("trailing") || r[0].equals("changed"))) rels.add(new Object[]{pd, r}); }
        if (rels.isEmpty()) return new Object[]{null, "refused: " + n + " on Forge is " + alts + ", not the vanilla one plus trailing parameters"};
        if (rels.size() > 1) return new Object[]{null, "refused: several Forge overloads of " + n};
        String pd = (String) rels.get(0)[0];
        Object[] rel = (Object[]) rels.get(0)[1];
        if (!anno.equals("Inject")) return new Object[]{null, "refused: @" + anno + " on " + n + " (only @Inject is moved to a Forge overload)"};
        for (Fit.Rec r : rows) {
            String a = r.atVal().substring(r.atVal().lastIndexOf(':') + 1).toUpperCase();
            if (!Set.of("HEAD", "RETURN", "TAIL", "INVOKE", "INVOKE_ASSIGN").contains(a)) return new Object[]{null, "refused: @At " + r.atVal()};
        }
        Fit.Meth vm = v.cls(tgt).methods.get(n + d), pm = pc.methods.get(n + pd);
        if (vm.isStatic != pm.isStatic) return new Object[]{null, "refused: static flag differs"};
        Object[] fit = fitHandler(anno, hdesc, cap, d, pd, (int[]) rel[1]);
        if (fit[0] == null) return new Object[]{null, "refused: " + fit[1]};
        for (Fit.Rec r : rows) {
            String a = r.atVal().substring(r.atVal().lastIndexOf(':') + 1).toUpperCase();
            if (a.startsWith("INVOKE")) {
                int cv = Fit.countRefs(v, tgt, n, d, r.atVal(), r.atTgt()), cp = Fit.countRefs(p, tgt, n, pd, r.atVal(), r.atTgt());
                if (cv < 0 || cp <= 0 || cp != cv) return new Object[]{null, "refused: INVOKE count " + cv + " -> " + cp};
            }
        }
        double s = similarity(normRefs(vm), normRefs(pm));
        if (rel[0].equals("changed") && s < 0.6) return new Object[]{null, "refused: changed parameter types and body not similar (" + s + ")"};
        return new Object[]{n + pd, fit, "FIT-O " + n + d + " -> " + n + pd + " (" + rel[0] + ", sim " + s + ", " + fit[0] + ")"};
    }

    /**
     * FIT-Q: a name-only selector (no descriptor, no wildcard) whose one vanilla method is still in Forge's class, next
     * to overloads of the same name that Forge added (ClientLevel.addBreakingBlockEffect(BlockPos, BlockHitResult) on
     * Forge 61 beside vanilla's (BlockPos, Direction)). Mixin then also picks a Forge overload and refuses the handler
     * ("Invalid descriptor"). Only when the handler takes target arguments that do not fit some Forge overload: a handler
     * that fits every overload (no target arguments, or the same leading ones) keeps Mixin's own choice. Only the
     * injectors whose target arguments are known (Inject: all or none; Redirect, WrapOperation, WrapWithCondition,
     * ModifyReceiver: leading ones). Returns the decision text when the selector should become name + vanilla
     * descriptor, else null.
     */
    static String qualify(Fit.CI pc, String name, String dsel, String n, String d, String anno, String hdesc, String atTgt) {
        if (dsel != null || !name.equals(n) || LAMBDA.matcher(n).matches()) return null;
        if (!Set.of("Inject", "Redirect", "WrapOperation", "WrapWithCondition", "ModifyReceiver").contains(anno)) return null;
        int cap = capturedArgs(anno, hdesc, atTgt);
        if (cap <= 0) return null;
        List<String> vp = params(d);
        if (cap > vp.size() || (anno.equals("Inject") && cap != vp.size())) return null;
        int forge = 0;
        String bad = null;
        for (Fit.Meth m : pc.methods.values()) {
            if (!m.name.equals(n)) continue;
            forge++;
            if (m.desc.equals(d)) continue;
            List<String> op = params(m.desc);
            boolean fits = anno.equals("Inject") ? op.equals(vp) : op.size() >= cap && op.subList(0, cap).equals(vp.subList(0, cap));
            if (!fits && bad == null) bad = m.desc;
        }
        if (forge < 2 || bad == null) return null;
        return "FIT-Q " + n + " -> " + n + d + " (Forge has " + forge + " methods named " + n + ", the handler does not fit " + n + bad + ")";
    }

    /** rules for one mixin class */
    static List<Rule> plan(Fit.MixinInfo mi, Fit.Dump v, Fit.Dump p, List<String> decisions) {
        return plan(mi, v, p, decisions, new ArrayList<>(), new ArrayList<>());
    }

    /** rules for one mixin class; FIT-Q rules and their decision lines go to qual / qualWhy (applied after Recover) */
    static List<Rule> plan(Fit.MixinInfo mi, Fit.Dump v, Fit.Dump p, List<String> decisions, List<Rule> qual, List<String> qualWhy) {
        List<Rule> rules = new ArrayList<>();
        List<String> tg = new ArrayList<>();
        for (String t : mi.targets) if (v.has(t)) tg.add(t);
        Map<List<String>, List<Fit.Rec>> bySel = new LinkedHashMap<>();
        for (Fit.Rec r : mi.recs) if (r.type().equals("INJ")) bySel.computeIfAbsent(List.of(r.name(), r.desc(), r.anno(), r.rawSel()), k -> new ArrayList<>()).add(r);
        Map<String, Integer> selsOf = new HashMap<>();
        for (List<String> k : bySel.keySet()) selsOf.merge(k.get(0) + k.get(1), 1, Integer::sum);
        for (var e : bySel.entrySet()) {
            // sel: the string as written (what the rewrite replaces); eff: what Mixin looks for (through the refmap)
            String hname = e.getKey().get(0), hdesc = e.getKey().get(1), anno = e.getKey().get(2), sel = e.getKey().get(3);
            List<Fit.Rec> rs = e.getValue();
            String eff = rs.get(0).sel();
            String[] ps = Fit.parseSel(eff);
            String owner = ps[0], name = ps[1], dsel = ps[2];
            if (name == null || name.contains("*") || name.contains("/") || name.equals("<init>") || name.equals("<clinit>")) continue;
            for (String tgt : tg) {
                if (owner != null && !owner.equals(tgt)) continue;
                List<Fit.Meth> cands = new ArrayList<>();
                for (Fit.Meth m : v.cls(tgt).methods.values()) if (Fit.nameMatch(name, m.name) && Fit.descMatch(dsel, m.desc)) cands.add(m);
                if (cands.size() != 1 || !p.has(tgt)) continue;
                String n = cands.get(0).name, d = cands.get(0).desc;
                boolean isLambda = LAMBDA.matcher(n).matches();
                if (p.cls(tgt).methods.containsKey(n + d) && !isLambda) {
                    String q = qualify(p.cls(tgt), name, dsel, n, d, anno, hdesc, rs.isEmpty() ? "" : rs.get(0).atTgt());
                    if (q != null) {
                        String where = mi.cls + "." + hname + hdesc + " [" + anno + " " + sel + "] on " + tgt;
                        if (tg.size() != 1) decisions.add(where + ": " + q + " (not applied: the mixin has several targets)");
                        else {
                            // only when Recover finds nothing better (FIT-D moves a handler off a method Forge turned
                            // into a delegate of its overload; pinning the delegate would lose the @At there)
                            qual.add(new Rule(hname, hdesc, sel, n + d, null, null));
                            qualWhy.add(where + ": " + q);
                        }
                    }
                    continue;
                }
                int cap = capturedArgs(anno, hdesc, rs.isEmpty() ? "" : rs.get(0).atTgt());
                String where = mi.cls + "." + hname + hdesc + " [" + anno + " " + sel + "] on " + tgt;
                boolean mv = !rs.isEmpty() && rs.get(0).val().startsWith("LV");
                if (!rs.isEmpty() && rs.get(0).val().startsWith("L") && !mv) {
                    // locals of the other method differ (Architectury explodePost: @Local List became ambiguous on
                    // ServerExplosion.hurtEntities(List)); leave it to the normal local checks
                    if (!p.cls(tgt).methods.containsKey(n + d)) decisions.add(where + ": refused: handler captures locals");
                    continue;
                }
                if (cap < 0) {
                    if (!p.cls(tgt).methods.containsKey(n + d)) decisions.add(where + ": refused: handler shape of @" + anno + " not understood");
                    continue;
                }
                Object[] dec = isLambda ? decideLambda(v, p, tgt, n, d, rs, anno, hdesc, cap) : decideOverload(v, p, tgt, n, d, rs, anno, hdesc, cap);
                String why = dec.length > 2 ? (String) dec[2] : (String) dec[1];
                if (dec[0] == null) { if (why != null) decisions.add(where + ": " + why); continue; }
                Object[] fit = (Object[]) dec[1];
                if (tg.size() != 1) { decisions.add(where + ": " + why + " (not applied: the mixin has several targets)"); continue; }
                if (mv) {
                    // a @ModifyVariable picks its variable by type, ordinal, index or name; the Forge method has other
                    // parameters (and maybe other locals), so the pick must provably hold the same value there
                    Fit.Meth om = p.cls(tgt).methods.get((String) dec[0]);
                    List<String> no = new ArrayList<>();
                    boolean same = om != null;
                    for (Fit.Rec r : rs) if (same && !Recover.localsSame(v, p, tgt, cands.get(0), om, r, no)) same = false;
                    if (!same) { decisions.add(where + ": " + why + " (not applied: @ModifyVariable: " + (no.isEmpty() ? "Forge method not found" : no.get(0)) + ")"); continue; }
                }
                if (fit[0].equals("bridge")) {
                    if (selsOf.get(hname + hdesc) > 1) { decisions.add(where + ": " + why + " (not applied: bridge needed and several selectors)"); continue; }
                    rules.add(new Rule(hname, hdesc, sel, "L" + tgt + ";" + dec[0], (String) fit[1], (int[]) fit[2]));
                    decisions.add(where + ": " + why + " bridge " + hname + "$alloyBridge" + fit[1]);
                } else {
                    rules.add(new Rule(hname, hdesc, sel, "L" + tgt + ";" + dec[0], null, null));
                    decisions.add(where + ": " + why);
                }
            }
        }
        return rules;
    }

    // ------------------------------------------------------------------ recover: moved calls, shifted ordinals, delegates
    /**
     * Handlers whose target method is the same on both sides but whose @At call Forge moved (Recover). Adds delegate
     * moves to `rules` and @At edits to `fixes`; every decision goes to `decisions`.
     */
    static void planRecover(Fit.MixinInfo mi, byte[] b, Fit.Dump v, Fit.Dump p, List<String> decisions, List<Rule> rules, List<Recover.AtFix> fixes) {
        List<String> tg = new ArrayList<>();
        for (String t : mi.targets) if (v.has(t)) tg.add(t);
        if (tg.size() != 1 || !p.has(tg.get(0))) return;
        String tgt = tg.get(0);
        ClassNode cn = new ClassNode();
        try { new ClassReader(b).accept(cn, ClassReader.SKIP_CODE); } catch (RuntimeException e) { return; }
        Map<String, MethodNode> hm = new HashMap<>();
        for (MethodNode m : cn.methods) hm.put(m.name + m.desc, m);
        Map<List<String>, List<Fit.Rec>> bySel = new LinkedHashMap<>();
        for (Fit.Rec r : mi.recs) if (r.type().equals("INJ")) bySel.computeIfAbsent(List.of(r.name(), r.desc(), r.anno(), r.rawSel()), k -> new ArrayList<>()).add(r);
        Map<String, Integer> selsOf = new HashMap<>();
        for (List<String> k : bySel.keySet()) selsOf.merge(k.get(0) + k.get(1), 1, Integer::sum);
        Set<String> moved = new HashSet<>();
        for (Rule r : rules) moved.add(r.handler() + r.hdesc());
        for (var e : bySel.entrySet()) {
            String hname = e.getKey().get(0), hdesc = e.getKey().get(1), anno = e.getKey().get(2), sel = e.getKey().get(3);
            if (moved.contains(hname + hdesc)) continue;
            List<Fit.Rec> rs = e.getValue();
            MethodNode h = hm.get(hname + hdesc);
            AnnotationNode an = null;
            if (h != null) for (AnnotationNode a : Fit.annos(h.visibleAnnotations, h.invisibleAnnotations))
                if (Fit.simpleName(a.desc).equals(anno) && Fit.get(a, "method") != null) an = a;
            if (an == null) continue;
            String[] ps = Fit.parseSel(rs.get(0).sel());
            if (ps[1] == null || ps[1].contains("*") || ps[1].contains("/") || (ps[0] != null && !ps[0].equals(tgt))) continue;
            List<Fit.Meth> cands = new ArrayList<>();
            for (Fit.Meth m : v.cls(tgt).methods.values()) if (Fit.nameMatch(ps[1], m.name) && Fit.descMatch(ps[2], m.desc)) cands.add(m);
            if (cands.size() != 1) continue;
            Fit.Meth vm = cands.get(0), pm = p.cls(tgt).methods.get(vm.name + vm.desc);
            if (pm == null || LAMBDA.matcher(vm.name).matches()) continue;
            String where = mi.cls + "." + hname + hdesc + " [" + anno + " " + sel + "] on " + tgt;
            boolean lc = false, ls = false;
            for (Fit.Rec r : rs) { if (r.val().equals("L")) lc = true; if (r.val().startsWith("LS") || r.val().startsWith("LV")) ls = true; }
            // 1. Forge kept the method as a delegate to an overload that now holds the call
            int cap = capturedArgs(anno, hdesc, rs.get(0).atTgt());
            Object[] dec = cap < 0 ? new Object[]{null, null} : Recover.decideDelegate(v, p, tgt, vm, pm, rs, anno, hdesc, cap);
            if (dec[0] != null) {
                Object[] fit = (Object[]) dec[1];
                String why = (String) dec[2];
                Fit.Meth om = p.cls(tgt).methods.get((String) dec[0]);
                String bad = lc ? "handler captures locals by position" : null;
                if (bad == null && ls) for (Fit.Rec r : rs) {
                    String x = Recover.localsSame(tgt, Recover.method(v, tgt, vm.name + vm.desc), Recover.method(p, tgt, om.name + om.desc),
                            Fit.injectionPoints(vm, r.atVal(), r.atTgt(), r.ord()), Fit.injectionPoints(om, r.atVal(), r.atTgt(), r.ord()), Fit.parseSpecs(r.val()), Recover.assignAt(r));
                    if (x != null) { bad = "@Local: " + x; break; }
                }
                if (bad == null && Set.of("ModifyVariable", "ModifyConstant").contains(anno)) bad = "@" + anno + " is not moved";
                if (bad == null && fit[0].equals("bridge") && selsOf.get(hname + hdesc) > 1) bad = "bridge needed and several selectors";
                if (bad != null) { decisions.add(where + ": " + why + " (not applied: " + bad + ")"); continue; }
                if (fit[0].equals("bridge")) {
                    rules.add(new Rule(hname, hdesc, sel, "L" + tgt + ";" + dec[0], (String) fit[1], (int[]) fit[2]));
                    decisions.add(where + ": " + why + " bridge " + hname + "$alloyBridge" + fit[1]);
                } else {
                    rules.add(new Rule(hname, hdesc, sel, "L" + tgt + ";" + dec[0], null, null));
                    decisions.add(where + ": " + why);
                }
                continue;
            }
            if (dec[1] != null) decisions.add(where + ": " + dec[1]);
            // 2. the call moved to another owner / gained trailing parameters, or its ordinal shifted
            if (selsOf.get(hname + hdesc) > 1) continue;
            for (Fit.Rec r : rs) {
                Recover.AtFix f = Recover.planAt(v, p, tgt, vm, pm, r, an, h);
                if (f == null) continue;
                if (f.newTgt() == null && f.newOrd() == null) { decisions.add(where + ": " + f.why()); continue; }
                if (r.val().equals("L")) { decisions.add(where + ": " + f.why() + " (not applied: handler captures locals by position)"); continue; }
                String nt = f.newTgt() == null ? r.atTgt() : f.newTgt(), no = f.newOrd() == null ? r.ord() : f.newOrd();
                if (r.val().startsWith("LS") || r.val().startsWith("LV")) {
                    // the call moved or its ordinal shifted: the @Local / @ModifyVariable pick at the new point must hold
                    // the same value as at the old point on vanilla
                    String x = Recover.localsSame(tgt, Recover.method(v, tgt, vm.name + vm.desc), Recover.method(p, tgt, pm.name + pm.desc),
                            Fit.injectionPoints(vm, r.atVal(), r.atTgt(), r.ord()), Fit.injectionPoints(pm, r.atVal(), nt, no), Fit.parseSpecs(r.val()), Recover.assignAt(r));
                    if (x != null) { decisions.add(where + ": " + f.why() + " (not applied: " + (r.val().startsWith("LV") ? "@ModifyVariable: " : "@Local: ") + x + ")"); continue; }
                }
                // the rewrite looks for the @At target as written; the refmap key stays for the other handlers
                fixes.add(new Recover.AtFix(f.handler(), f.hdesc(), r.rawAt(), f.oldOrd(), f.newTgt(), f.newOrd(), f.why()));
                Recover.PROVEN.get().add(Recover.key(mi.cls, hname + hdesc, nt, no));
                decisions.add(where + ": " + f.why());
            }
        }
    }

    static byte[] rewriteAt(byte[] cls, List<Recover.AtFix> fixes) {
        if (fixes.isEmpty()) return cls;
        ClassNode cn = new ClassNode();
        new ClassReader(cls).accept(cn, 0);
        int n = 0;
        for (MethodNode m : cn.methods)
            for (Recover.AtFix f : fixes) {
                if (!(m.name.equals(f.handler()) && m.desc.equals(f.hdesc()))) continue;
                for (AnnotationNode an : injectors(m.visibleAnnotations)) n += Recover.applyAt(an, f);
                for (AnnotationNode an : injectors(m.invisibleAnnotations)) n += Recover.applyAt(an, f);
            }
        if (n == 0) return cls;
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    // ------------------------------------------------------------------ rewrite (same as tools/RetargetSelectors.java)
    static boolean injector(String desc) {
        return desc.startsWith("Lorg/spongepowered/asm/mixin/injection/") || desc.startsWith("Lcom/llamalad7/mixinextras/");
    }

    static int retarget(AnnotationNode an, String from, String to) {
        int n = 0;
        if (an.values == null) return 0;
        for (int i = 0; i + 1 < an.values.size(); i += 2) {
            if (!"method".equals(an.values.get(i))) continue;
            Object v = an.values.get(i + 1);
            if (v instanceof String s && s.equals(from)) { an.values.set(i + 1, to); n++; }
            else if (v instanceof List<?> lv) {
                @SuppressWarnings("unchecked") List<Object> mut = (List<Object>) lv;
                for (int j = 0; j < mut.size(); j++) if (from.equals(mut.get(j))) { mut.set(j, to); n++; }
            }
        }
        return n;
    }

    static List<AnnotationNode> injectors(List<AnnotationNode> l) {
        List<AnnotationNode> r = new ArrayList<>();
        if (l != null) for (AnnotationNode an : l) if (injector(an.desc)) r.add(an);
        return r;
    }

    @SuppressWarnings("unchecked")
    static List<AnnotationNode>[] moveParamAnnos(List<AnnotationNode>[] src, int[] map, int bc) {
        if (src == null) return null;
        List<AnnotationNode>[] out = new List[bc];
        for (int i = 0; i < src.length && i < map.length; i++) {
            if (src[i] == null || src[i].isEmpty()) continue;
            List<AnnotationNode> moved = new ArrayList<>();
            for (Iterator<AnnotationNode> it = src[i].iterator(); it.hasNext(); ) {
                AnnotationNode an = it.next();
                if (an.desc.startsWith("Lcom/llamalad7/mixinextras/")) { moved.add(an); it.remove(); }
            }
            if (!moved.isEmpty()) out[map[i]] = moved;
        }
        return out;
    }

    static MethodNode bridge(ClassNode cn, MethodNode m, String bdesc, int[] map) {
        MethodNode b = new MethodNode(m.access & ~Opcodes.ACC_SYNTHETIC, m.name + "$alloyBridge", bdesc, null,
                m.exceptions == null ? null : m.exceptions.toArray(new String[0]));
        boolean stat = (m.access & Opcodes.ACC_STATIC) != 0;
        Type[] bt = Type.getArgumentTypes(bdesc), ot = Type.getArgumentTypes(m.desc);
        int[] slot = new int[bt.length];
        int s = stat ? 0 : 1;
        for (int i = 0; i < bt.length; i++) { slot[i] = s; s += bt[i].getSize(); }
        InsnList in = b.instructions;
        if (!stat) in.add(new VarInsnNode(Opcodes.ALOAD, 0));
        for (int i = 0; i < ot.length; i++) {
            Type t = bt[map[i]];
            in.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot[map[i]]));
            if (!t.equals(ot[i])) in.add(new TypeInsnNode(Opcodes.CHECKCAST, ot[i].getInternalName()));
        }
        boolean priv = (m.access & Opcodes.ACC_PRIVATE) != 0, itf = (cn.access & Opcodes.ACC_INTERFACE) != 0;
        int op = stat ? Opcodes.INVOKESTATIC : priv ? Opcodes.INVOKESPECIAL : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
        in.add(new MethodInsnNode(op, cn.name, m.name, m.desc, itf));
        in.add(new InsnNode(Type.getReturnType(m.desc).getOpcode(Opcodes.IRETURN)));
        b.maxLocals = s;
        b.maxStack = Math.max(1, s + 2);
        return b;
    }

    static byte[] rewrite(byte[] cls, List<Rule> rules) {
        ClassNode cn = new ClassNode();
        new ClassReader(cls).accept(cn, 0);
        int n = 0;
        List<MethodNode> added = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            for (Rule r : rules) {
                if (!(m.name.equals(r.handler()) && m.desc.equals(r.hdesc()))) continue;
                if (r.bdesc() == null) {
                    for (AnnotationNode an : injectors(m.visibleAnnotations)) n += retarget(an, r.oldSel(), r.newSel());
                    for (AnnotationNode an : injectors(m.invisibleAnnotations)) n += retarget(an, r.oldSel(), r.newSel());
                    continue;
                }
                if (r.map().length != Type.getArgumentTypes(m.desc).length) continue;
                MethodNode b = bridge(cn, m, r.bdesc(), r.map());
                int bc = Type.getArgumentTypes(r.bdesc()).length;
                List<AnnotationNode> vis = injectors(m.visibleAnnotations), inv = injectors(m.invisibleAnnotations);
                int k = 0;
                for (AnnotationNode an : vis) k += retarget(an, r.oldSel(), r.newSel());
                for (AnnotationNode an : inv) k += retarget(an, r.oldSel(), r.newSel());
                if (k == 0) continue;
                if (m.visibleAnnotations != null) m.visibleAnnotations.removeAll(vis);
                if (m.invisibleAnnotations != null) m.invisibleAnnotations.removeAll(inv);
                b.visibleAnnotations = vis.isEmpty() ? null : vis;
                b.invisibleAnnotations = inv.isEmpty() ? null : inv;
                b.visibleParameterAnnotations = moveParamAnnos(m.visibleParameterAnnotations, r.map(), bc);
                b.invisibleParameterAnnotations = moveParamAnnos(m.invisibleParameterAnnotations, r.map(), bc);
                if (b.visibleParameterAnnotations != null) b.visibleAnnotableParameterCount = bc;
                if (b.invisibleParameterAnnotations != null) b.invisibleAnnotableParameterCount = bc;
                added.add(b);
                n += k;
            }
        }
        cn.methods.addAll(added);
        if (n == 0) return cls;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    // ------------------------------------------------------------------ accessor whose field type Forge changed
    static final String CHUNK_GEN = "net/minecraft/world/level/chunk/ChunkGenerator";

    /**
     * ChunkGenerator.featuresPerStep is a Supplier on vanilla and a ClearableLazy on Forge. An @Accessor setter
     * setFeaturesPerStep(Supplier) cannot apply there, and Mixin then skips the whole accessor interface. The setter loses
     * its @Accessor/@Mutable and becomes an @Invoker of ChunkGenerator.alloy$setFeaturesPerStep(Supplier), which Alloy's
     * compat mixin alloy.compat.worldgen.ChunkGeneratorFeaturesPerStep adds; it wraps the Supplier in a ClearableLazy. (An
     * interface mixin may only hold accessors and invokers, so a plain abstract method does not work.) Only when the Forge-patched ChunkGenerator really has the ClearableLazy field.
     */
    static byte[] featuresPerStepAccessor(byte[] b, Fit.Games g, List<String> decisions) {
        boolean forgeLazy = false;
        for (Fit.Dump[] vp : g.sides.values()) {
            Fit.CI pc = vp[1].cls(CHUNK_GEN);
            if (pc != null && pc.fields.contains("featuresPerStep:Lnet/minecraftforge/common/util/ClearableLazy;")) forgeLazy = true;
        }
        if (!forgeLazy) return b;
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, 0);
        if ((cn.access & Opcodes.ACC_INTERFACE) == 0) return shadowRetype(cn, b, decisions);
        int n = 0;
        for (MethodNode m : cn.methods) {
            if (!m.desc.equals("(Ljava/util/function/Supplier;)V") || m.visibleAnnotations == null) continue;
            AnnotationNode acc = null;
            for (AnnotationNode an : m.visibleAnnotations) if (an.desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")) acc = an;
            if (acc == null) continue;
            Object v = Fit.get(acc, "value");
            String field = v != null ? String.valueOf(v) : m.name.startsWith("set") ? Character.toLowerCase(m.name.charAt(3)) + m.name.substring(4) : m.name;
            if (!field.equals("featuresPerStep") || !m.name.equals("setFeaturesPerStep")) continue;
            // an interface mixin may only hold accessors and invokers: make it an invoker of Alloy's compat method
            m.visibleAnnotations.removeIf(an -> an.desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;") || an.desc.equals("Lorg/spongepowered/asm/mixin/Mutable;"));
            AnnotationNode inv = new AnnotationNode("Lorg/spongepowered/asm/mixin/gen/Invoker;");
            inv.values = new ArrayList<>(List.of("value", "alloy$setFeaturesPerStep"));
            m.visibleAnnotations.add(inv);
            n++;
            decisions.add(cn.name + "." + m.name + m.desc + ": ACCESSOR-BRIDGE featuresPerStep is a ClearableLazy on Forge; now an @Invoker of alloy.compat.worldgen.ChunkGeneratorFeaturesPerStep.alloy$setFeaturesPerStep");
        }
        if (n == 0) return b;
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    static final String SUPPLIER = "Ljava/util/function/Supplier;", LAZY = "Lnet/minecraftforge/common/util/ClearableLazy;";

    /**
     * A class mixin on ChunkGenerator that shadows featuresPerStep as a Supplier (All The Plants) fails to apply on Forge,
     * and the failed apply also took the other mixins of that class with it (Lithostitched's accessor). The shadow field
     * becomes a ClearableLazy (it is a Supplier, so reads work as before) and every write wraps the value with
     * ClearableLazy.concurrentOf.
     */
    static byte[] shadowRetype(ClassNode cn, byte[] b, List<String> decisions) {
        FieldNode f = null;
        for (FieldNode x : cn.fields)
            if (x.name.equals("featuresPerStep") && x.desc.equals(SUPPLIER) && x.visibleAnnotations != null
                    && x.visibleAnnotations.stream().anyMatch(an -> an.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;"))) f = x;
        if (f == null) return b;
        f.desc = LAZY;
        f.signature = null;
        int w = 0;
        for (MethodNode m : cn.methods)
            for (AbstractInsnNode i : m.instructions.toArray())
                if (i instanceof FieldInsnNode fi && fi.owner.equals(cn.name) && fi.name.equals("featuresPerStep") && fi.desc.equals(SUPPLIER)) {
                    fi.desc = LAZY;
                    if (fi.getOpcode() == Opcodes.PUTFIELD) {
                        m.instructions.insertBefore(fi, new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraftforge/common/util/ClearableLazy",
                                "concurrentOf", "(Ljava/util/function/Supplier;)" + LAZY, true));
                        w++;
                    }
                }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        decisions.add(cn.name + ".featuresPerStep: ACCESSOR-BRIDGE @Shadow Supplier is a ClearableLazy on Forge; field retyped, " + w + " write(s) wrapped");
        return cw.toByteArray();
    }

    // ------------------------------------------------------------------ jar pass
    /** the jar with retargeted mixin classes (nested jars too), or the same array when nothing changed */
    static byte[] apply(byte[] jar, String name, Fit.Games g, List<String> decisions, int depth) throws IOException {
        return apply(jar, name, g, decisions, depth, null);
    }

    /**
     * The mixins and handlers that compat_replaces / the hand strip list (compat) turns off are not retargeted, as in
     * fit_targets.py: a handler moved to a bridge method would no longer be found by name and would escape the list.
     * Returns {"cls" -> whole mixin, "cls#handler" -> one handler} as internal names.
     */
    static Set<String> listed(Fit.Comp c, String compat) {
        Set<String> out = new HashSet<>();
        if (compat == null) return out;
        for (String line : compat.split("\\R")) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#") || !line.contains(":") || line.contains("@")) continue;
            int colon = line.indexOf(':');
            Fit.Cfg cf = c.configs.get(line.substring(0, colon));
            if (cf == null) continue;
            String mixin = line.substring(colon + 1), handler = null;
            if (mixin.contains("#")) { handler = mixin.substring(mixin.indexOf('#') + 1); mixin = mixin.substring(0, mixin.indexOf('#')); }
            String fqn = mixin.startsWith(cf.pkg + ".") || cf.pkg.isEmpty() ? mixin : cf.pkg + "." + mixin;
            out.add(fqn.replace('.', '/') + (handler == null ? "" : "#" + handler));
        }
        return out;
    }

    /**
     * Mixins that are never retargeted, whatever matches. C2ME's chunk IO mixin redirects the save(chunk) call in ChunkMap's
     * unload lambda to its async save, which skips Forge's ChunkDataEvent.Save and runs chunk capability code on a worker
     * thread (lost per-chunk data). Forge renamed that lambda, so the redirect does not apply
     * and chunks unload through Forge's own save. Retargeting it would switch the unsafe path on. Same list in fit_targets.py.
     */
    static final Set<String> NEVER_RETARGET = Set.of("com/ishland/c2me/threading/chunkio/mixin/MixinThreadedAnvilChunkStorage");

    static byte[] apply(byte[] jar, String name, Fit.Games g, List<String> decisions, int depth, String compat) throws IOException {
        if (!enabled() || g.sides.isEmpty()) return jar;
        List<Fit.Comp> comps = Fit.components(jar, name, null, 0);
        Fit.Comp c = comps.get(0);
        Set<String> listed = listed(c, compat);
        listed.addAll(NEVER_RETARGET);
        Map<String, byte[]> replace = new HashMap<>();
        for (String[] m : c.mixins) {
            byte[] b = c.classes.get(m[2]);
            if (b == null || listed.contains(m[2])) continue;
            Fit.MixinInfo mi = Fit.scan(m[0], m[1], m[2], b, c.refmapOf(m[2]));
            if (mi == null) continue;
            mi.recs.removeIf(r -> listed.contains(m[2] + "#" + r.name()));
            if (mi.targets.contains(CHUNK_GEN)) {
                byte[] nb = featuresPerStepAccessor(b, g, decisions);
                if (nb != b) { replace.put(m[2] + ".class", nb); b = nb; }
            }
            for (Fit.Dump[] vp : Fit.pairs(g, mi.side)) {
                List<Rule> qual = new ArrayList<>();
                List<String> qualWhy = new ArrayList<>();
                List<Rule> rules = plan(mi, vp[0], vp[1], decisions, qual, qualWhy);
                List<Recover.AtFix> fixes = new ArrayList<>();
                if (!Recover.off()) planRecover(mi, b, vp[0], vp[1], decisions, rules, fixes);
                Set<String> taken = new HashSet<>();
                for (Rule r : rules) taken.add(r.handler() + r.hdesc());
                for (int i = 0; i < qual.size(); i++) {
                    Rule q = qual.get(i);
                    if (taken.contains(q.handler() + q.hdesc())) { decisions.add(qualWhy.get(i) + " (not applied: Recover moved the handler)"); continue; }
                    rules.add(q);
                    decisions.add(qualWhy.get(i));
                }
                if (rules.isEmpty() && fixes.isEmpty()) continue;
                byte[] nb = rewriteAt(rewrite(b, rules), fixes);
                if (nb != b) replace.put(m[2] + ".class", nb);
                break;
            }
        }
        if (depth < 3) {
            try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
                for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                    String n = e.getName();
                    if (!n.startsWith("META-INF/jars/") || !n.endsWith(".jar")) continue;
                    byte[] nested = z.readAllBytes();
                    byte[] out = apply(nested, n.substring(n.lastIndexOf('/') + 1), g, decisions, depth + 1, compat);
                    if (out != nested) replace.put(n, out);
                }
            }
        }
        if (replace.isEmpty()) return jar;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar)); ZipOutputStream zo = new ZipOutputStream(bo)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                byte[] data = z.readAllBytes();
                byte[] r = replace.getOrDefault(e.getName(), data);
                ZipEntry ne = new ZipEntry(e.getName());
                ne.setTime(e.getTime());
                if (e.getMethod() == ZipEntry.STORED) {
                    CRC32 crc = new CRC32();
                    crc.update(r);
                    ne.setMethod(ZipEntry.STORED);
                    ne.setSize(r.length);
                    ne.setCompressedSize(r.length);
                    ne.setCrc(crc.getValue());
                }
                zo.putNextEntry(ne);
                zo.write(r);
                zo.closeEntry();
            }
        }
        return bo.toByteArray();
    }
}
