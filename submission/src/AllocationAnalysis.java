import java.util.*;
import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.callgraph.*;
import soot.toolkits.graph.*;
import soot.toolkits.scalar.*;

//Pass 2 Analysis: Takes the method summaries from pass 1 and identifies whihc allocations can be scalar replaced and necessary info.
public class AllocationAnalysis extends ForwardFlowAnalysis<Unit, AllocState> {

    private final Body body;
    private final CallGraph cg;
    private final Map<SootMethod, MethodSummary> summaries;
    private final Map<Unit, ReplaceableAlloc> allocs = new LinkedHashMap<>();
    private final Set<Unit> disqualified = new HashSet<>();

    public AllocationAnalysis(Body body, CallGraph cg,
                              Map<SootMethod, MethodSummary> summaries) {
        super(new ExceptionalUnitGraph(body));
        this.body = body;
        this.cg = cg;
        this.summaries = summaries;

        for (Unit u : body.getUnits()) {
            if (!(u instanceof AssignStmt)) continue;
            Value rhs = ((AssignStmt) u).getRightOp();
            if (!(rhs instanceof NewExpr)) continue;
            SootClass c = ((NewExpr) rhs).getBaseType().getSootClass();
            allocs.put(u, new ReplaceableAlloc(u, c));
        }

        if (!allocs.isEmpty()) doAnalysis();
    }

    public AllocState stateBefore(Unit u) { return getFlowBefore(u); }

    public List<ReplaceableAlloc> getReplaceableAllocs() {
        List<ReplaceableAlloc> r = new ArrayList<>();
        for (Map.Entry<Unit, ReplaceableAlloc> e : allocs.entrySet()) {
            Unit site = e.getKey();
            ReplaceableAlloc ra = e.getValue();
            if (disqualified.contains(site)) continue;
            if (ra.initCall == null || ra.initTarget == null) continue;
            if (!recurseChain(ra)) continue;
            populateChainFields(ra);
            r.add(ra);
        }
        return r;
    }

    private boolean recurseChain(ReplaceableAlloc ra) {
        SootMethod cur = ra.initTarget;
        Set<SootMethod> visited = new HashSet<>();
        while (cur != null) {
            if (!visited.add(cur)) return false;
            if (isObjectInit(cur)) { ra.initChain.add(cur); break; }
            MethodSummary s = summaries.get(cur);
            if (s == null) return false;
            if (!s.goodParams.contains(0)) return false;
            if (!s.nonChainCalleeModified(0).isEmpty()) return false;
            ra.initChain.add(cur);
            cur = s.chainInitTarget;
        }
        return true;
    }

    private boolean isObjectInit(SootMethod m) {
        return m.getDeclaringClass().getName().equals("java.lang.Object")
            && m.getName().equals("<init>");
    }

    private void populateChainFields(ReplaceableAlloc ra) {
        MethodSummary s = summaries.get(ra.initTarget);
        if (s == null) return;
        ra.fieldsUsed.addAll(s.directModified(0));
        ra.fieldsUsed.addAll(s.calleeModified(0));
        ra.fieldsUsed.addAll(s.read(0));
    }

    @Override
    protected AllocState newInitialFlow() { return new AllocState(); }

    @Override
    protected AllocState entryInitialFlow() { return new AllocState(); }

    @Override
    protected void copy(AllocState src, AllocState dst) { dst.copyFrom(src); }

    @Override
    protected void merge(AllocState a, AllocState b, AllocState out) {
        AllocState.merge(a, b, out);
    }

    //<-------------------Main flow logic --------------------------->
    @Override
    protected void flowThrough(AllocState in, Unit unit, AllocState out) {
        copy(in, out);
        Stmt stmt = (Stmt) unit;

        if (stmt instanceof IdentityStmt) {
            Value lhs = ((IdentityStmt) stmt).getLeftOp();
            if (lhs instanceof Local) out.allocTag.put((Local) lhs, new HashSet<>());
            return;
        }

        if (stmt.containsInvokeExpr()) {
            handleInvoke(stmt, in);
        }

        if (stmt instanceof ReturnStmt) {
            disqualifyAll(localTag(((ReturnStmt) stmt).getOp(), in));
            return;
        }

        if (stmt instanceof ThrowStmt) {
            disqualifyAll(localTag(((ThrowStmt) stmt).getOp(), in));
            return;
        }

        if (stmt instanceof IfStmt) {
            Value cond = ((IfStmt) stmt).getCondition();
            if (cond instanceof BinopExpr) {
                BinopExpr b = (BinopExpr) cond;
                if (isRefOp(b.getOp1()) || isRefOp(b.getOp2())) {
                    disqualifyAll(localTag(b.getOp1(), in));
                    disqualifyAll(localTag(b.getOp2(), in));
                }
            }
            return;
        }

        if (stmt instanceof EnterMonitorStmt) {
            disqualifyAll(localTag(((EnterMonitorStmt) stmt).getOp(), in));
            return;
        }

        if (stmt instanceof ExitMonitorStmt) {
            disqualifyAll(localTag(((ExitMonitorStmt) stmt).getOp(), in));
            return;
        }

        if (!(stmt instanceof AssignStmt)) return;
        handleAssign((AssignStmt) stmt, in, out);
    }

    private void handleAssign(AssignStmt stmt, AllocState in, AllocState out) {
        Value lhs = stmt.getLeftOp();
        Value rhs = stmt.getRightOp();

        if (lhs instanceof Local) {
            out.allocTag.put((Local) lhs, resolveAllocTag(rhs, stmt, in));
            return;
        }

        Set<Unit> rhsTag = resolveAllocTag(rhs, stmt, in);

        if (lhs instanceof InstanceFieldRef) {
            InstanceFieldRef ref = (InstanceFieldRef) lhs;
            Set<Unit> baseTag = localTag(ref.getBase(), in);
            disqualifyIfAmbiguous(baseTag); //If the local can point to multiple allocs, the allocs cant be scalar replaced.
            for (Unit u : baseTag) {
                if (allocs.containsKey(u) && !disqualified.contains(u))
                    allocs.get(u).fieldsUsed.add(ref.getField());
            }
            disqualifyAll(rhsTag); //Any value assigned to an instance field cannot be scalar replaced.
        } else if (lhs instanceof ArrayRef) {
            disqualifyAll(rhsTag);  //Any value assigned to an array element cannot be scalar replaced.
        } else if (lhs instanceof StaticFieldRef) {
            disqualifyAll(rhsTag);  //Any value assigned to a static field cannot be scalar replaced.
        }
    }

    private Set<Unit> resolveAllocTag(Value v, Stmt stmt, AllocState in) {
        if (v instanceof Local) {
            return new HashSet<>(in.allocTag.getOrDefault((Local) v, Collections.emptySet()));
        }
        if (v instanceof CastExpr) {
            Value op = ((CastExpr) v).getOp();
            if (op instanceof Local) {
                return new HashSet<>(in.allocTag.getOrDefault((Local) op, Collections.emptySet()));
            }
            return new HashSet<>();
        }
        if (v instanceof NewExpr) {
            if (stmt instanceof AssignStmt && ((AssignStmt) stmt).getRightOp() == v && allocs.containsKey(stmt)) {
                return new HashSet<>(Set.of(stmt));
            }
            return new HashSet<>();
        }
        if (v instanceof InstanceOfExpr) {
            disqualifyAll(localTag(((InstanceOfExpr) v).getOp(), in));
            return new HashSet<>();
        }
        if (v instanceof InstanceFieldRef) {
            InstanceFieldRef ref = (InstanceFieldRef) v;
            Set<Unit> baseTag = localTag(ref.getBase(), in);
            disqualifyIfAmbiguous(baseTag);
            for (Unit u : baseTag) {
                if (allocs.containsKey(u) && !disqualified.contains(u))
                    allocs.get(u).fieldsUsed.add(ref.getField());
            }
            return new HashSet<>();
        }
        return new HashSet<>();
    }

    private void handleInvoke(Stmt stmt, AllocState in) {
        InvokeExpr invoke = stmt.getInvokeExpr();
        boolean isInit = invoke instanceof SpecialInvokeExpr && invoke.getMethodRef().getName().equals("<init>");

        List<Value> actuals = new ArrayList<>();
        boolean isInstance = invoke instanceof InstanceInvokeExpr;
        if (isInstance) actuals.add(((InstanceInvokeExpr) invoke).getBase());
        actuals.addAll(invoke.getArgs());

        if (isInit && isInstance) {
            Set<Unit> receiverTag = localTag(actuals.get(0), in);
            disqualifyIfAmbiguous(receiverTag);
            for (Unit u : receiverTag) {
                if (!allocs.containsKey(u)) continue;
                ReplaceableAlloc ra = allocs.get(u);
                if (ra.initCall != null && !ra.initCall.equals(stmt)) {
                    disqualify(u);
                } else if (ra.initCall == null) {
                    ra.initCall = stmt;
                    try {
                        ra.initTarget = invoke.getMethod();
                    } catch (Exception e) { disqualify(u); }
                }
            }
            for (int i = 1; i < actuals.size(); i++) {
                Set<Unit> argTag = localTag(actuals.get(i), in);
                if (!argTag.isEmpty()) checkArgAgainstCallee(argTag, i, stmt);
            }
            return;
        }

        int curLine = stmt.getJavaSourceStartLineNumber();
        List<MethodSummary> callees = getCalleeSummaries(stmt);

        for (int i = 0; i < actuals.size(); i++) {
            Set<Unit> argTag = localTag(actuals.get(i), in);
            if (argTag.isEmpty()) continue;
            disqualifyIfAmbiguous(argTag);
            boolean passed = checkArgAgainstCallee(argTag, i, stmt);
            if (!passed) continue;
            Set<Integer> transitive = new HashSet<>();
            for (MethodSummary s : callees) {
                transitive.addAll(s.paramCallSites.getOrDefault(i, Collections.emptySet()));
            }

            for (Unit u : argTag) {
                if (!allocs.containsKey(u) || disqualified.contains(u)) continue;
                ReplaceableAlloc ra = allocs.get(u);
                if (!ra.CalleeCallSites.contains(stmt))
                    ra.CalleeCallSites.add(stmt);
                if (curLine > 0) ra.callSiteLines.add(curLine);
                ra.callSiteLines.addAll(transitive);
            }
        }
    }

    // Check that all callees accept the arg: if any callee doesn't accept, the arg can't be replaced. 
    // If all accept, accumulate read-field info from callees into the alloc's fieldsUsed
    private boolean checkArgAgainstCallee(Set<Unit> argTag, int paramIdx, Stmt stmt) {
        List<MethodSummary> callees = getCalleeSummaries(stmt);
        if (callees.isEmpty()) {
            disqualifyAll(argTag);
            return false;
        }
        for (MethodSummary s : callees) {
            if (paramIdx >= s.paramCount
                    || !s.goodParams.contains(paramIdx)
                    || !s.allModified(paramIdx).isEmpty()) {
                disqualifyAll(argTag);
                return false;
            }
        }
        // All targets accept: accumulate read-field contributions into the
        // alloc's fieldsUsed (these become scalar locals in Pass 3).
        for (MethodSummary s : callees) {
            Set<SootField> reads = s.read(paramIdx);
            if (reads.isEmpty()) continue;
            for (Unit u : argTag) {
                if (allocs.containsKey(u) && !disqualified.contains(u))
                    allocs.get(u).fieldsUsed.addAll(reads);
            }
        }
        return true;
    }

    private List<MethodSummary> getCalleeSummaries(Unit stmt) {
        List<MethodSummary> r = new ArrayList<>();
        Iterator<Edge> it = cg.edgesOutOf(stmt);
        while (it.hasNext()) {
            Edge e = it.next();
            SootMethod tgt = e.tgt();
            if (tgt.getName().equals("<clinit>")) continue;
            MethodSummary s = summaries.get(tgt);
            if (s == null) {
                r.add(MethodSummary.allBad(paramCountOf(tgt)));
            } else {
                r.add(s);
            }
        }
        return r;
    }

    private int paramCountOf(SootMethod m) {
        return m.getParameterCount() + (m.isStatic() ? 0 : 1);
    }

    private Set<Unit> localTag(Value v, AllocState in) {
        if (v instanceof Local) {
            return in.allocTag.getOrDefault((Local) v, Collections.emptySet());
        }
        return Collections.emptySet();
    }

    private boolean isRefOp(Value v) {
        return v.getType() instanceof RefLikeType;
    }

    private void disqualify(Unit site) {
        if (allocs.containsKey(site)) disqualified.add(site);
    }

    private void disqualifyAll(Collection<Unit> sites) {
        for (Unit u : sites) disqualify(u);
    }

    private void disqualifyIfAmbiguous(Set<Unit> tag) {
        if (tag.size() > 1) disqualifyAll(tag);
    }
}
