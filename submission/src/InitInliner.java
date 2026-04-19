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
 *
 * Control-flow handling: every original unit in the constructor body gets
 * mapped to exactly one emitted unit (a {@link NopStmt} placeholder when
 * we'd otherwise drop the statement). After inlining, {@link #fixupUnitBoxes}
 * walks the emitted chain and rewires every branch target via this map so
 * any {@code if}/{@code goto}/{@code switch} inside the constructor points
 * at the correct emitted statement, not the dangling original.
 */
public class InitInliner {

    private final Body callerBody;
    private final Map<SootField, Local> scalars;
    private final Specializer specializer;
    private final List<Unit> emitted = new ArrayList<>();
    /** orig constructor unit → the single emitted unit that represents it. */
    private final Map<Unit, Unit> origToEmitted = new HashMap<>();

    /**
     * Counter for renaming cloned locals to avoid collision with the caller
     * or with other allocations in the same body. Static so every
     * {@code sr_il<n>_<origName>} synthesized in this JVM session has a
     * unique {@code n}.
     */
    private static int renameCounter = 0;

    public InitInliner(Body callerBody, Map<SootField, Local> scalars,
                       Specializer specializer) {
        this.callerBody = callerBody;
        this.scalars = scalars;
        this.specializer = specializer;
    }

    public List<Unit> getEmitted() { return emitted; }

    public void inlineChain(List<SootMethod> chain, Stmt initCall) {
        if (chain.isEmpty()) return;
        InvokeExpr invoke = initCall.getInvokeExpr();
        List<Value> actuals = new ArrayList<>(invoke.getArgs());
        inlineOne(chain, 0, actuals);
        fixupUnitBoxes();
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
        Set<Local> thisAliases = computeThisAliases(cBody, thisLocal);

        for (Unit u : cBody.getUnits()) {
            Stmt s = (Stmt) u;

            if (s instanceof IdentityStmt) {
                IdentityStmt id = (IdentityStmt) s;
                Value rhs = id.getRightOp();
                if (rhs instanceof ThisRef) {
                    // Drop, but leave a nop placeholder for possible branches.
                    emitMapped(u, Jimple.v().newNopStmt());
                    continue;
                }
                if (rhs instanceof ParameterRef) {
                    int pIdx = ((ParameterRef) rhs).getIndex();
                    Local lhs = localMap.get((Local) id.getLeftOp());
                    if (pIdx >= actuals.size()) {
                        emitMapped(u, Jimple.v().newNopStmt());
                    } else {
                        emitMapped(u, Jimple.v().newAssignStmt(
                            lhs, actuals.get(pIdx)));
                    }
                    continue;
                }
                // @caughtexception — carry through (rename lhs)
                Local lhs = localMap.get((Local) id.getLeftOp());
                emitMapped(u, Jimple.v().newIdentityStmt(lhs, rhs));
                continue;
            }

            // super.<init>/this.<init> call — replace with next-level inlining.
            // Emit a nop first so any branch that targeted this call in the
            // orig body has a valid target; the recursion then appends the
            // super body's emitted units after it, and fall-through works.
            if (s.containsInvokeExpr()
                    && s.getInvokeExpr() instanceof SpecialInvokeExpr
                    && s.getInvokeExpr().getMethodRef().getName().equals("<init>")) {
                SpecialInvokeExpr si = (SpecialInvokeExpr) s.getInvokeExpr();
                if (si.getBase() instanceof Local
                        && thisAliases.contains(si.getBase())) {
                    emitMapped(u, Jimple.v().newNopStmt());
                    List<Value> superActuals = new ArrayList<>();
                    for (Value a : si.getArgs())
                        superActuals.add(substitute(a, localMap));
                    inlineOne(chain, idx + 1, superActuals);
                    continue;
                }
            }

            if (s instanceof ReturnVoidStmt || s instanceof ReturnStmt) {
                // Constructor return — we're splicing inline, so this is a
                // no-op; placeholder preserves any branch target.
                emitMapped(u, Jimple.v().newNopStmt());
                continue;
            }

            if (s instanceof AssignStmt) {
                AssignStmt a = (AssignStmt) s;
                Value lhs = a.getLeftOp();
                Value rhs = a.getRightOp();

                // this.f = rhs → scalar_f = substitute(rhs)
                if (lhs instanceof InstanceFieldRef) {
                    InstanceFieldRef ref = (InstanceFieldRef) lhs;
                    if (ref.getBase() instanceof Local
                            && thisAliases.contains(ref.getBase())) {
                        Local sl = scalars.get(ref.getField());
                        if (sl != null) {
                            emitMapped(u, Jimple.v().newAssignStmt(
                                sl, substitute(rhs, localMap)));
                            continue;
                        }
                    }
                }
                // lhs = this.f → lhs = scalar_f
                if (rhs instanceof InstanceFieldRef) {
                    InstanceFieldRef ref = (InstanceFieldRef) rhs;
                    if (ref.getBase() instanceof Local
                            && thisAliases.contains(ref.getBase())) {
                        Local sl = scalars.get(ref.getField());
                        if (sl != null) {
                            emitMapped(u, Jimple.v().newAssignStmt(
                                substitute(lhs, localMap), sl));
                            continue;
                        }
                    }
                }
                // Copy into a this-alias — drop to nop (aliases are meaningless now).
                if (lhs instanceof Local && thisAliases.contains(lhs)) {
                    emitMapped(u, Jimple.v().newNopStmt());
                    continue;
                }
            }

            // Generic fallback: clone with renamed locals.
            Unit clone = (Unit) s.clone();
            substituteInUnit(clone, localMap);
            emitMapped(u, clone);
        }
    }

    /** Append {@code emit} to the output list and record the mapping
     *  {@code orig → emit}. */
    private void emitMapped(Unit orig, Unit emit) {
        emitted.add(emit);
        origToEmitted.put(orig, emit);
    }

    /**
     * After all emission, walk emitted units and retarget every UnitBox
     * (branch / goto / switch target) that still points at an original
     * constructor unit to the corresponding emitted unit.
     */
    private void fixupUnitBoxes() {
        for (Unit u : emitted) {
            for (UnitBox box : u.getUnitBoxes()) {
                Unit target = box.getUnit();
                Unit mapped = origToEmitted.get(target);
                if (mapped != null) box.setUnit(mapped);
            }
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
