import java.util.*; 
import soot.*;
import soot.jimple.*;

//Used for inlining the init chain of an allocation site. Called from CodeTransformer.
//all the @this and @parameterN identity statements have to be converted to locals during the recursive inlining
public class InitInliner {

    private final Body callerBody;
    private final Map<SootField, Local> scalars;
    private final Specializer specializer;
    private final List<Unit> emitted = new ArrayList<>();
    private final Map<Unit, Unit> origToEmitted = new HashMap<>();

    private static int renameCounter = 0; //used to give unique names to the cloned locals

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

        //giving unique names to locals of inlined body
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
                Local lhs = localMap.get((Local) id.getLeftOp());
                emitMapped(u, Jimple.v().newIdentityStmt(lhs, rhs));
                continue;
            }

            //super.<init> being handled recursively
            if (s.containsInvokeExpr()
                    && s.getInvokeExpr() instanceof SpecialInvokeExpr
                    && s.getInvokeExpr().getMethodRef().getName().equals("<init>")) {
                SpecialInvokeExpr si = (SpecialInvokeExpr) s.getInvokeExpr();
                if (si.getBase() instanceof Local
                        && thisAliases.contains(si.getBase())) {
                    emitMapped(u, Jimple.v().newNopStmt()); // we have to keep Nops just in case body has no other statements
                    List<Value> superActuals = new ArrayList<>();
                    for (Value a : si.getArgs())
                        superActuals.add(substitute(a, localMap));
                    inlineOne(chain, idx + 1, superActuals);
                    continue;
                }
            }

            if (s instanceof ReturnVoidStmt || s instanceof ReturnStmt) {
                emitMapped(u, Jimple.v().newNopStmt()); //Nop to keep the body alive
                continue;
            }

            //assigns can be only of (IFR on left) or (local on left and IFR on right).
            if (s instanceof AssignStmt) {
                AssignStmt a = (AssignStmt) s;
                Value lhs = a.getLeftOp();
                Value rhs = a.getRightOp();

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

                if (lhs instanceof Local && thisAliases.contains(lhs)) {
                    emitMapped(u, Jimple.v().newNopStmt()); //Nop to keep the body alive
                    continue;
                }
            }

            Unit clone = (Unit) s.clone();
            substituteInUnit(clone, localMap);
            emitMapped(u, clone);
        }
    }

    private void emitMapped(Unit orig, Unit emit) {
        emitted.add(emit);
        origToEmitted.put(orig, emit);
    }

   //Remake the graph from the flatlist of units 
    private void fixupUnitBoxes() {
        for (Unit u : emitted) {
            for (UnitBox box : u.getUnitBoxes()) {
                Unit target = box.getUnit();
                Unit mapped = origToEmitted.get(target);
                if (mapped != null) box.setUnit(mapped);
            }
        }
    }

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
