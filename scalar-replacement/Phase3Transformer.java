import java.util.*;
import soot.*;
import soot.jimple.*;

/**
 * Phase-3 IR transformation. Runs per method, after Phase 2 verdicts.
 *
 * For each scalar-replaceable allocation in a method:
 *   1. Create fresh scalar locals (one per field in fieldsUsed).
 *   2. Emit default-value initializers at the allocation site so reads
 *      before the first write see the Java zero (objects start zero-filled;
 *      bare Jimple locals do not).
 *   3. Inline the constructor chain, rewriting {@code this.f = v} stores
 *      to scalar-local assignments. The super call chain is expanded
 *      recursively; {@code Object.<init>} is dropped.
 *   4. Delete the {@code new X} and the {@code specialinvoke <init>}.
 *   5. Rewrite every direct field access (in the caller body) on any local
 *      aliasing the allocation to the corresponding scalar local.
 *   6. Rewrite helper call sites that receive the allocation into calls of
 *      a specialized (field-taking static) variant of the callee.
 */
public class Phase3Transformer {

    private final Specializer specializer;

    public Phase3Transformer(Specializer specializer) {
        this.specializer = specializer;
    }

    public void transform(SootMethod m, List<ReplaceableAlloc> allocs,
                          AllocationAnalysis analysis) {
        if (allocs.isEmpty()) return;
        Body body = m.getActiveBody();

        // Per-alloc scalar locals, keyed by the allocation and field.
        Map<ReplaceableAlloc, Map<SootField, Local>> scalarsPerAlloc = new LinkedHashMap<>();
        int allocIdx = 0;
        for (ReplaceableAlloc ra : allocs) {
            Map<SootField, Local> sl = new LinkedHashMap<>();
            int line = ra.site.getJavaSourceStartLineNumber();
            for (SootField f : ra.fieldsUsed) {
                String name = "srO" + line + "_" + allocIdx + "_" + f.getName();
                Local l = Jimple.v().newLocal(name, f.getType());
                body.getLocals().add(l);
                sl.put(f, l);
            }
            scalarsPerAlloc.put(ra, sl);
            allocIdx++;
        }

        // Plan edits: units to remove, and lists of units to insert before given anchor units.
        Set<Unit> toRemove = new HashSet<>();
        Map<Unit, List<Unit>> toInsertBefore = new LinkedHashMap<>();

        // Step 1–4: at each alloc site, materialize default-value inits and
        // inlined constructor body; schedule the new & <init> for removal.
        for (ReplaceableAlloc ra : allocs) {
            Map<SootField, Local> scalars = scalarsPerAlloc.get(ra);

            List<Unit> emitted = new ArrayList<>();

            // Default values so unwritten fields read as Java zero.
            for (Map.Entry<SootField, Local> e : scalars.entrySet()) {
                emitted.add(Jimple.v().newAssignStmt(
                    e.getValue(), defaultValue(e.getValue().getType())));
            }

            // Inline the constructor chain (X.<init> outermost).
            InitInliner inliner = new InitInliner(body, scalars, specializer);
            inliner.inlineChain(ra.initChain, (InvokeStmt) toInvokeStmt(ra.initCall));
            emitted.addAll(inliner.getEmitted());

            toInsertBefore.put(ra.site, emitted);
            toRemove.add(ra.site);
            toRemove.add(ra.initCall);
        }

        // Step 5: rewrite direct field accesses in the caller body.
        for (Unit u : new ArrayList<>(body.getUnits())) {
            if (toRemove.contains(u)) continue;
            rewriteDirectFieldAccess(u, allocs, scalarsPerAlloc, analysis);
        }

        // Step 6: rewrite helper call sites.
        // A single call site may have multiple arg positions each aliasing a
        // different replaceable alloc (e.g., o3.foo(o2) — receiver is one
        // alloc, arg is another). We need to rewrite each call site exactly
        // once with the FULL set of scalarized positions, otherwise the
        // second rewrite would clobber the first.
        Map<Unit, Map<Integer, ReplaceableAlloc>> callSiteMap = new LinkedHashMap<>();
        for (ReplaceableAlloc ra : allocs) {
            for (Unit cs : ra.helperCallSites) {
                if (toRemove.contains(cs)) continue;
                for (int pos : positionsAliasing(cs, ra, analysis)) {
                    callSiteMap
                        .computeIfAbsent(cs, k -> new TreeMap<>())
                        .put(pos, ra);
                }
            }
        }
        for (Map.Entry<Unit, Map<Integer, ReplaceableAlloc>> e : callSiteMap.entrySet()) {
            rewriteCallSite(e.getKey(), e.getValue(), scalarsPerAlloc);
        }

        // Apply edits.
        for (Map.Entry<Unit, List<Unit>> e : toInsertBefore.entrySet()) {
            for (Unit n : e.getValue()) body.getUnits().insertBefore(n, e.getKey());
        }
        for (Unit u : toRemove) body.getUnits().remove(u);
    }

    /* =============================================================
     *  Field access rewriting in the caller body
     * ============================================================= */

    private void rewriteDirectFieldAccess(
            Unit u, List<ReplaceableAlloc> allocs,
            Map<ReplaceableAlloc, Map<SootField, Local>> scalarsPerAlloc,
            AllocationAnalysis analysis) {

        if (!(u instanceof AssignStmt)) return;
        AssignStmt assign = (AssignStmt) u;
        Value lhs = assign.getLeftOp();
        Value rhs = assign.getRightOp();

        // $r.f = v  →  scalar_f = v
        if (lhs instanceof InstanceFieldRef) {
            InstanceFieldRef ref = (InstanceFieldRef) lhs;
            ReplaceableAlloc owner = findAllocForBase(ref.getBase(), u, allocs, analysis);
            if (owner != null) {
                Local sl = scalarsPerAlloc.get(owner).get(ref.getField());
                if (sl != null) assign.setLeftOp(sl);
            }
        }

        // v = $r.f  →  v = scalar_f
        if (rhs instanceof InstanceFieldRef) {
            InstanceFieldRef ref = (InstanceFieldRef) rhs;
            ReplaceableAlloc owner = findAllocForBase(ref.getBase(), u, allocs, analysis);
            if (owner != null) {
                Local sl = scalarsPerAlloc.get(owner).get(ref.getField());
                if (sl != null) assign.setRightOp(sl);
            }
        }
    }

    /**
     * If {@code base} at the program point before {@code u} is unambiguously
     * the allocation for exactly one {@code ReplaceableAlloc}, return it.
     * Ambiguous or no-match → null (we can't rewrite safely).
     */
    private ReplaceableAlloc findAllocForBase(Value base, Unit u,
            List<ReplaceableAlloc> allocs, AllocationAnalysis analysis) {
        if (!(base instanceof Local)) return null;
        AllocState st = analysis.stateBefore(u);
        Set<Unit> tags = st.allocTag.getOrDefault((Local) base, Collections.emptySet());
        if (tags.size() != 1) return null;
        Unit site = tags.iterator().next();
        for (ReplaceableAlloc ra : allocs) {
            if (ra.site.equals(site)) return ra;
        }
        return null;
    }

    /* =============================================================
     *  Helper call-site rewriting (specialization)
     * ============================================================= */

    /** Positions (in the uniform-indexed actuals list) at which {@code cs}'s
     *  actual is unambiguously an alias of {@code ra.site}. */
    private List<Integer> positionsAliasing(Unit cs, ReplaceableAlloc ra,
            AllocationAnalysis analysis) {
        Stmt stmt = (Stmt) cs;
        if (!stmt.containsInvokeExpr()) return Collections.emptyList();
        InvokeExpr invoke = stmt.getInvokeExpr();
        List<Value> actuals = uniformActuals(invoke);

        AllocState st = analysis.stateBefore(cs);
        List<Integer> r = new ArrayList<>();
        for (int i = 0; i < actuals.size(); i++) {
            Value a = actuals.get(i);
            if (!(a instanceof Local)) continue;
            Set<Unit> tags = st.allocTag.getOrDefault((Local) a, Collections.emptySet());
            if (tags.size() == 1 && tags.contains(ra.site)) r.add(i);
        }
        return r;
    }

    /** Rewrite a call site into a staticinvoke of the specialization that
     *  handles every replaceable-alloc position at once. */
    private void rewriteCallSite(Unit cs, Map<Integer, ReplaceableAlloc> posToAlloc,
            Map<ReplaceableAlloc, Map<SootField, Local>> scalarsPerAlloc) {
        Stmt stmt = (Stmt) cs;
        if (!stmt.containsInvokeExpr()) return;
        InvokeExpr invoke = stmt.getInvokeExpr();

        SortedSet<Integer> scalarized = new TreeSet<>(posToAlloc.keySet());
        if (scalarized.isEmpty()) return;

        // Concrete callee: if the receiver (position 0) is scalarized on an
        // instance call, devirtualize to that alloc's exact class.
        SootMethod target = resolveConcreteTarget(invoke, posToAlloc, scalarized);
        if (target == null) return;

        SootMethod specialized = specializer.specialize(target, scalarized);
        if (specialized == null) return;

        // Build new argument list in the order the specialized signature expects.
        List<Value> actuals = uniformActuals(invoke);
        List<Value> newArgs = new ArrayList<>();
        for (int i = 0; i < actuals.size(); i++) {
            if (scalarized.contains(i)) {
                ReplaceableAlloc ra = posToAlloc.get(i);
                Map<SootField, Local> scalars = scalarsPerAlloc.get(ra);
                for (SootField f : specializer.fieldOrderFor(target, i)) {
                    Local sl = scalars.get(f);
                    newArgs.add(sl != null ? sl : defaultLocalForField(f));
                }
            } else {
                newArgs.add(actuals.get(i));
            }
        }

        stmt.getInvokeExprBox().setValue(
            Jimple.v().newStaticInvokeExpr(specialized.makeRef(), newArgs));
    }

    private static List<Value> uniformActuals(InvokeExpr invoke) {
        List<Value> r = new ArrayList<>();
        if (invoke instanceof InstanceInvokeExpr)
            r.add(((InstanceInvokeExpr) invoke).getBase());
        r.addAll(invoke.getArgs());
        return r;
    }

    private SootMethod resolveConcreteTarget(InvokeExpr invoke,
            Map<Integer, ReplaceableAlloc> posToAlloc, SortedSet<Integer> scalarized) {
        if (invoke instanceof InstanceInvokeExpr && scalarized.contains(0)) {
            ReplaceableAlloc receiverAlloc = posToAlloc.get(0);
            try {
                return receiverAlloc.allocClass.getMethod(
                    invoke.getMethodRef().getSubSignature());
            } catch (Exception ignored) { /* fall through */ }
        }
        try {
            return invoke.getMethod();
        } catch (Exception e) { return null; }
    }

    /** Placeholder used if a scalar-local was unexpectedly not created for a
     *  field the specialized signature wants. Shouldn't fire if Phase 2 and
     *  the canonical field order agree. */
    private static Local defaultLocalForField(SootField f) {
        return Jimple.v().newLocal("sr_missing_" + f.getName(), f.getType());
    }

    /* =============================================================
     *  Utilities
     * ============================================================= */

    private static Stmt toInvokeStmt(Unit u) { return (Stmt) u; }

    static Value defaultValue(Type t) {
        if (t instanceof IntType || t instanceof ByteType
                || t instanceof ShortType || t instanceof CharType
                || t instanceof BooleanType) return IntConstant.v(0);
        if (t instanceof LongType)   return LongConstant.v(0);
        if (t instanceof FloatType)  return FloatConstant.v(0);
        if (t instanceof DoubleType) return DoubleConstant.v(0);
        return NullConstant.v();
    }
}
