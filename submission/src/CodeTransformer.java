import java.util.*;
import soot.*;
import soot.jimple.*;

//Pass 3: Here we actually make the changes in the jimple IR
public class CodeTransformer {

    private final Specializer specializer;

    public CodeTransformer(Specializer specializer) {
        this.specializer = specializer;
    }

    public void transform(SootMethod m, List<ReplaceableAlloc> allocs,
                          AllocationAnalysis analysis) {
        if (allocs.isEmpty()) return;
        Body body = m.getActiveBody();

        //map containing the scalar local for each field of each replaceable alloc
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

        //we remove and add units only in the end.
        Set<Unit> toRemove = new HashSet<>();
        Map<Unit, List<Unit>> toInsertBefore = new LinkedHashMap<>();

        // Recurse the constructor chain and convert to scalar fields.
        for (ReplaceableAlloc ra : allocs) {
            Map<SootField, Local> scalars = scalarsPerAlloc.get(ra);

            List<Unit> emitted = new ArrayList<>();

            // Default value in java is 0
            for (Map.Entry<SootField, Local> e : scalars.entrySet()) {
                emitted.add(Jimple.v().newAssignStmt(
                    e.getValue(), defaultValue(e.getValue().getType())));
            }

            InitInliner inliner = new InitInliner(body, scalars, specializer);
            inliner.inlineChain(ra.initChain, (InvokeStmt) toInvokeStmt(ra.initCall));
            emitted.addAll(inliner.getEmitted());
            toInsertBefore.put(ra.initCall, emitted); //inserting the code between the old new statement and the init statement
            toRemove.add(ra.site);
            toRemove.add(ra.initCall);
        }

        //Rewriting Field Accesses
        for (Unit u : new ArrayList<>(body.getUnits())) {
            if (toRemove.contains(u)) continue;
            rewriteDirectFieldAccess(u, allocs, scalarsPerAlloc, analysis);
        }

        //Rewriting call sites of methods (This has to be done only once per call site, so we collect the relevant call sites for all allocs and rewrite them together to avoid conflicts)
        Map<Unit, Map<Integer, ReplaceableAlloc>> callSiteMap = new LinkedHashMap<>();
        for (ReplaceableAlloc ra : allocs) {
            for (Unit cs : ra.CalleeCallSites) {
                if (toRemove.contains(cs)) continue;
                for (int pos : positionsAliasing(cs, ra, analysis)) {
                    callSiteMap.computeIfAbsent(cs, k -> new TreeMap<>()).put(pos, ra);
                }
            }
        }
        for (Map.Entry<Unit, Map<Integer, ReplaceableAlloc>> e : callSiteMap.entrySet()) {
            rewriteCallSite(e.getKey(), e.getValue(), scalarsPerAlloc);
        }

        //Make the changes in the body
        for (Map.Entry<Unit, List<Unit>> e : toInsertBefore.entrySet()) {
            for (Unit n : e.getValue()) body.getUnits().insertBefore(n, e.getKey());
        }
        for (Unit u : toRemove) body.getUnits().remove(u);
    }

    //only cases are (IFR on left) and (local on left and IFR on right) 
    private void rewriteDirectFieldAccess(
            Unit u, List<ReplaceableAlloc> allocs,
            Map<ReplaceableAlloc, Map<SootField, Local>> scalarsPerAlloc,
            AllocationAnalysis analysis) {

        if (!(u instanceof AssignStmt)) return;
        AssignStmt assign = (AssignStmt) u;
        Value lhs = assign.getLeftOp();
        Value rhs = assign.getRightOp();

        if (lhs instanceof InstanceFieldRef) {
            InstanceFieldRef ref = (InstanceFieldRef) lhs;
            ReplaceableAlloc owner = findAllocForBase(ref.getBase(), u, allocs, analysis);
            if (owner != null) {
                Local sl = scalarsPerAlloc.get(owner).get(ref.getField());
                if (sl != null) assign.setLeftOp(sl);
            }
        }

        if (rhs instanceof InstanceFieldRef) {
            InstanceFieldRef ref = (InstanceFieldRef) rhs;
            ReplaceableAlloc owner = findAllocForBase(ref.getBase(), u, allocs, analysis);
            if (owner != null) {
                Local sl = scalarsPerAlloc.get(owner).get(ref.getField());
                if (sl != null) assign.setRightOp(sl);
            }
        }
    }

    //Scalar replacement only applies when locals are unambiguously aliases of the replaceable alloc
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

    //Have to rewrite all calls as static invokes to the specialized version
    private void rewriteCallSite(Unit cs, Map<Integer, ReplaceableAlloc> posToAlloc,
            Map<ReplaceableAlloc, Map<SootField, Local>> scalarsPerAlloc) {
        Stmt stmt = (Stmt) cs;
        if (!stmt.containsInvokeExpr()) return;
        InvokeExpr invoke = stmt.getInvokeExpr();

        SortedSet<Integer> scalarized = new TreeSet<>(posToAlloc.keySet());
        if (scalarized.isEmpty()) return;

        //If reciever is scalarized, we have to make the method StaticInvoke and pass the reciever as an explicit argument
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
            } catch (Exception ignored) { }
        }
        try {
            return invoke.getMethod();
        } catch (Exception e) { return null; }
    }

    //Just in case smth goes wrong, Shouldnt be called.
    private static Local defaultLocalForField(SootField f) {
        return Jimple.v().newLocal("sr_missing_" + f.getName(), f.getType());
    }

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
