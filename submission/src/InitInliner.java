import java.util.*;
import soot.*;
import soot.jimple.*;

/**
 * Inlines the body of a constructor chain at a {@code new X(); <init>(...)}
 * allocation site. Produces a flat list of units to splice into the caller,
 * with the scalar-replaced {@code this} and super-call structure fully
 * eliminated.
 *
 * For each level of the chain (outermost first), we:
 *   - drop the {@code @this := ...} identity stmt,
 *   - rewrite {@code @parameterN := ...} into an assignment to a fresh local
 *     bound to the actual argument,
 *   - skip the nested super/delegated {@code <init>} call (the next chain
 *     level's body takes its place),
 *   - rewrite {@code this.f = v} into {@code scalar_f = v} and
 *     {@code v = this.f} into {@code v = scalar_f},
 *   - drop the trailing {@code return} (constructors always return void),
 *   - copy all other units, substituting renamed locals.
 *
 * Object.&lt;init&gt; is treated as a no-op terminator.
 */
public class InitInliner {

    private final Body callerBody;
    private final Map<SootField, Local> scalars;
    private final Specializer specializer;
    private final List<Unit> emitted = new ArrayList<>();

    /**
     * Counter for renaming cloned locals to avoid collision with the caller
     * or with other allocations in the same body. Static so every
     * {@code sr_il<n>_<origName>} synthesized in this JVM session has a
     * unique {@code n} — two allocations in one method body each construct
     * their own {@link InitInliner} instance, so a per-instance counter
     * would reset and cause name collisions (observed as
     * "Register contains wrong type" verifier errors with duplicate locals
     * of different types).
     */
    private static int renameCounter = 0;

    public InitInliner(Body callerBody, Map<SootField, Local> scalars,
                       Specializer specializer) {
        this.callerBody = callerBody;
        this.scalars = scalars;
        this.specializer = specializer;
    }

    public List<Unit> getEmitted() { return emitted; }

    /**
     * Inline the chain starting at index 0 (X.&lt;init&gt;). The outermost
     * constructor's actuals come from {@code initCall}. As we encounter the
     * super/delegated call inside its body, we recurse to the next level
     * and the super's actuals come from that invoke's args at that recursion
     * frame. {@code Object.<init>} is skipped (no body worth inlining).
     */
    public void inlineChain(List<SootMethod> chain, Stmt initCall) {
        if (chain.isEmpty()) return;
        InvokeExpr invoke = initCall.getInvokeExpr();
        List<Value> actuals = new ArrayList<>(invoke.getArgs());
        inlineOne(chain, 0, actuals);
    }

    private void inlineOne(List<SootMethod> chain, int idx, List<Value> actuals) {
        if (idx >= chain.size()) return;
        SootMethod c = chain.get(idx);
        if (c.getDeclaringClass().getName().equals("java.lang.Object")
                && c.getName().equals("<init>")) {
            return;  // terminator
        }
        if (!c.isConcrete()) return;
        Body cBody = c.getActiveBody();

        // Rename cloned locals; keep a mapping from orig → new.
        Map<Local, Local> localMap = new HashMap<>();
        for (Local orig : cBody.getLocals()) {
            Local fresh = Jimple.v().newLocal(
                "sr_il" + (renameCounter++) + "_" + orig.getName(),
                orig.getType());
            callerBody.getLocals().add(fresh);
            localMap.put(orig, fresh);
        }
        Local thisLocal = cBody.getThisLocal();
        // Compute which locals may alias thisLocal in this constructor's body
        // — a trivial copy/cast closure — so we can rewrite field accesses
        // through copies, not just the thisLocal itself.
        Set<Local> thisAliases = computeThisAliases(cBody, thisLocal);

        // Walk units and emit transformed versions.
        for (Unit u : cBody.getUnits()) {
            Stmt s = (Stmt) u;

            if (s instanceof IdentityStmt) {
                IdentityStmt id = (IdentityStmt) s;
                Value rhs = id.getRightOp();
                if (rhs instanceof ThisRef) continue;
                if (rhs instanceof ParameterRef) {
                    int pIdx = ((ParameterRef) rhs).getIndex();
                    if (pIdx >= actuals.size()) continue;
                    Local lhs = localMap.get((Local) id.getLeftOp());
                    emitted.add(Jimple.v().newAssignStmt(lhs, actuals.get(pIdx)));
                    continue;
                }
                // @caughtexception — carry through (rename lhs)
                Local lhs = localMap.get((Local) id.getLeftOp());
                emitted.add(Jimple.v().newIdentityStmt(lhs, rhs));
                continue;
            }

            // super.<init>/this.<init> call — replace with next-level inlining.
            if (s.containsInvokeExpr()
                    && s.getInvokeExpr() instanceof SpecialInvokeExpr
                    && s.getInvokeExpr().getMethodRef().getName().equals("<init>")) {
                SpecialInvokeExpr si = (SpecialInvokeExpr) s.getInvokeExpr();
                if (si.getBase() instanceof Local
                        && thisAliases.contains(si.getBase())) {
                    List<Value> superActuals = new ArrayList<>();
                    for (Value a : si.getArgs()) superActuals.add(substitute(a, localMap));
                    inlineOne(chain, idx + 1, superActuals);
                    continue;
                }
            }

            if (s instanceof ReturnVoidStmt || s instanceof ReturnStmt) {
                continue;  // constructor's implicit return — don't propagate
            }

            // AssignStmt — check for field accesses on aliased-this.
            if (s instanceof AssignStmt) {
                AssignStmt a = (AssignStmt) s;
                Value lhs = a.getLeftOp();
                Value rhs = a.getRightOp();

                // this.f = rhs  →  scalar_f = substitute(rhs)
                if (lhs instanceof InstanceFieldRef) {
                    InstanceFieldRef ref = (InstanceFieldRef) lhs;
                    if (ref.getBase() instanceof Local
                            && thisAliases.contains(ref.getBase())) {
                        Local sl = scalars.get(ref.getField());
                        if (sl != null) {
                            emitted.add(Jimple.v().newAssignStmt(
                                sl, substitute(rhs, localMap)));
                            continue;
                        }
                    }
                }
                // lhs = this.f  →  lhs = scalar_f
                if (rhs instanceof InstanceFieldRef) {
                    InstanceFieldRef ref = (InstanceFieldRef) rhs;
                    if (ref.getBase() instanceof Local
                            && thisAliases.contains(ref.getBase())) {
                        Local sl = scalars.get(ref.getField());
                        if (sl != null) {
                            emitted.add(Jimple.v().newAssignStmt(
                                substitute(lhs, localMap), sl));
                            continue;
                        }
                    }
                }
                // If lhs is a plain local that's in thisAliases (a copy of
                // this), drop — aliases no longer matter once rewritten.
                if (lhs instanceof Local && thisAliases.contains(lhs)) continue;
            }

            // Generic fallback: clone with renamed locals.
            Unit clone = (Unit) s.clone();
            substituteInUnit(clone, localMap);
            emitted.add(clone);
        }
    }

    /* =============================================================
     *  Substitution and alias helpers
     * ============================================================= */

    private static Set<Local> computeThisAliases(Body body, Local thisLocal) {
        Set<Local> aliases = new HashSet<>();
        aliases.add(thisLocal);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Unit u : body.getUnits()) {
                if (!(u instanceof AssignStmt)) continue;
                AssignStmt a = (AssignStmt) u;
                if (!(a.getLeftOp() instanceof Local)) continue;
                Local lhs = (Local) a.getLeftOp();
                Value rhs = a.getRightOp();
                Value src = null;
                if (rhs instanceof Local) src = rhs;
                else if (rhs instanceof CastExpr
                        && ((CastExpr) rhs).getOp() instanceof Local)
                    src = ((CastExpr) rhs).getOp();
                if (src != null && aliases.contains(src)) {
                    if (aliases.add(lhs)) changed = true;
                }
            }
        }
        return aliases;
    }

    private Value substitute(Value v, Map<Local, Local> localMap) {
        if (v instanceof Local) {
            Local mapped = localMap.get((Local) v);
            return mapped != null ? mapped : v;
        }
        return v;
    }

    private void substituteInUnit(Unit u, Map<Local, Local> localMap) {
        for (ValueBox vb : u.getUseAndDefBoxes()) {
            Value val = vb.getValue();
            if (val instanceof Local) {
                Local mapped = localMap.get((Local) val);
                if (mapped != null) vb.setValue(mapped);
            }
        }
    }
}
