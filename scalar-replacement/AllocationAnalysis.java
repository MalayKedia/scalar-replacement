import java.util.*;
import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.callgraph.*;
import soot.toolkits.graph.*;
import soot.toolkits.scalar.*;

/**
 * Phase-2 analysis: decide, per method, which {@code new} allocations are
 * scalar-replaceable under the rules:
 *
 *   - ref is never returned, thrown, stored to a static or to another
 *     object's heap slot, captured by a thread/lambda, used in identity
 *     checks (==, !=, instanceof, synchronized),
 *   - every non-&lt;init&gt; call that receives the ref has the corresponding
 *     callee param both "good" (per Phase 1) and with zero modified fields,
 *   - the object's &lt;init&gt; chain is entirely good on its {@code this} and
 *     contains no non-chain helper call that writes fields.
 *
 * Array allocations ({@code new T[n]}) are not tracked — they are never
 * scalar-replaced.
 *
 * Running this class produces a list of {@link ReplaceableAlloc} for the
 * method. The list is what Phase 3 will consume.
 */
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

        // Collect NewExpr allocation sites (arrays excluded).
        for (Unit u : body.getUnits()) {
            if (!(u instanceof AssignStmt)) continue;
            Value rhs = ((AssignStmt) u).getRightOp();
            if (!(rhs instanceof NewExpr)) continue;
            SootClass c = ((NewExpr) rhs).getBaseType().getSootClass();
            allocs.put(u, new ReplaceableAlloc(u, c));
        }

        if (!allocs.isEmpty()) doAnalysis();
    }

    /**
     * Filter the tracked allocations to those that survived the dataflow
     * disqualification pass and have a valid {@code <init>} chain, and
     * finalize their fieldsUsed from chain summaries.
     */
    public List<ReplaceableAlloc> getReplaceableAllocs() {
        List<ReplaceableAlloc> r = new ArrayList<>();
        for (Map.Entry<Unit, ReplaceableAlloc> e : allocs.entrySet()) {
            Unit site = e.getKey();
            ReplaceableAlloc ra = e.getValue();
            if (disqualified.contains(site)) continue;
            if (ra.initCall == null || ra.initTarget == null) continue;
            if (!validateAndWalkChain(ra)) continue;
            populateChainFields(ra);
            r.add(ra);
        }
        return r;
    }

    private boolean validateAndWalkChain(ReplaceableAlloc ra) {
        SootMethod cur = ra.initTarget;
        Set<SootMethod> visited = new HashSet<>();
        while (cur != null) {
            if (!visited.add(cur)) return false;  // unexpected cycle
            // Stop successfully at Object.<init> — its summary is a
            // pre-installed good-only stub; no fields to walk past.
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
        // The top-of-chain summary already holds transitive read/mod
        // contributions from super chain and from specialized helpers,
        // because Phase 1 propagates them. So union directly.
        MethodSummary s = summaries.get(ra.initTarget);
        if (s == null) return;
        ra.fieldsUsed.addAll(s.directModified(0));
        ra.fieldsUsed.addAll(s.calleeModified(0));
        ra.fieldsUsed.addAll(s.read(0));
    }

    /* =============================================================
     *  Dataflow plumbing
     * ============================================================= */

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

    /* =============================================================
     *  Transfer
     * ============================================================= */

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
            // fall through so x = g(...) clears allocTag on x
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
            for (Unit u : baseTag) {
                if (allocs.containsKey(u) && !disqualified.contains(u))
                    allocs.get(u).fieldsUsed.add(ref.getField());
            }
            // Any param/alloc ref stored into a heap location escapes from
            // our scalar-replacement perspective — disqualify.
            disqualifyAll(rhsTag);
        } else if (lhs instanceof ArrayRef) {
            disqualifyAll(rhsTag);
        } else if (lhs instanceof StaticFieldRef) {
            disqualifyAll(rhsTag);
        }
    }

    private Set<Unit> resolveAllocTag(Value v, Stmt stmt, AllocState in) {
        if (v instanceof Local) {
            return new HashSet<>(in.allocTag.getOrDefault(
                (Local) v, Collections.emptySet()));
        }
        if (v instanceof CastExpr) {
            Value op = ((CastExpr) v).getOp();
            if (op instanceof Local) {
                return new HashSet<>(in.allocTag.getOrDefault(
                    (Local) op, Collections.emptySet()));
            }
            return new HashSet<>();
        }
        if (v instanceof NewExpr) {
            // A new-expression always appears as the RHS of its own AssignStmt;
            // that stmt is the allocation's identifying Unit.
            if (stmt instanceof AssignStmt && ((AssignStmt) stmt).getRightOp() == v
                    && allocs.containsKey(stmt)) {
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
            for (Unit u : baseTag) {
                if (allocs.containsKey(u) && !disqualified.contains(u))
                    allocs.get(u).fieldsUsed.add(ref.getField());
            }
            return new HashSet<>();
        }
        // NewArrayExpr, StaticFieldRef, ArrayRef, LengthExpr, arithmetic,
        // constants, invoke return values — none are direct alloc aliases.
        return new HashSet<>();
    }

    /* -------- Invokes -------- */

    private void handleInvoke(Stmt stmt, AllocState in) {
        InvokeExpr invoke = stmt.getInvokeExpr();
        boolean isInit = invoke instanceof SpecialInvokeExpr
                      && invoke.getMethodRef().name().equals("<init>");

        List<Value> actuals = new ArrayList<>();
        boolean isInstance = invoke instanceof InstanceInvokeExpr;
        if (isInstance) actuals.add(((InstanceInvokeExpr) invoke).getBase());
        actuals.addAll(invoke.getArgs());

        if (isInit && isInstance) {
            // Receiver = the object being initialized. Pair it with the alloc.
            Set<Unit> receiverTag = localTag(actuals.get(0), in);
            for (Unit u : receiverTag) {
                if (!allocs.containsKey(u)) continue;
                ReplaceableAlloc ra = allocs.get(u);
                if (ra.initCall != null) {
                    // Multiple <init>s on the same alloc — shouldn't happen
                    // in legal Jimple; be conservative.
                    disqualify(u);
                } else {
                    ra.initCall = stmt;
                    try {
                        ra.initTarget = invoke.getMethod();
                    } catch (Exception e) { disqualify(u); }
                }
            }
            // Explicit args to <init> (positions 1+): treat as regular args.
            for (int i = 1; i < actuals.size(); i++) {
                Set<Unit> argTag = localTag(actuals.get(i), in);
                if (!argTag.isEmpty()) checkArgAgainstCallee(argTag, i, stmt);
            }
            return;
        }

        // Regular call: strict rule on every arg carrying an alloc tag.
        int curLine = stmt.getJavaSourceStartLineNumber();
        List<MethodSummary> callees = getCalleeSummaries(stmt);

        for (int i = 0; i < actuals.size(); i++) {
            Set<Unit> argTag = localTag(actuals.get(i), in);
            if (argTag.isEmpty()) continue;
            boolean passed = checkArgAgainstCallee(argTag, i, stmt);
            if (!passed) continue;

            // Accumulate call-site lines: current call's line + each
            // callee's transitive forwarded-call lines at this position.
            Set<Integer> transitive = new HashSet<>();
            for (MethodSummary s : callees) {
                transitive.addAll(
                    s.paramCallSites.getOrDefault(i, Collections.emptySet()));
            }

            for (Unit u : argTag) {
                if (!allocs.containsKey(u) || disqualified.contains(u)) continue;
                ReplaceableAlloc ra = allocs.get(u);
                if (!ra.helperCallSites.contains(stmt))
                    ra.helperCallSites.add(stmt);
                if (curLine > 0) ra.callSiteLines.add(curLine);
                ra.callSiteLines.addAll(transitive);
            }
        }
    }

    /**
     * Validate that every CG target of {@code stmt} accepts an alloc at
     * position {@code paramIdx}: the callee's param must be good AND have
     * zero modified fields. On failure, disqualify every tag in argTag.
     * Returns true iff the check passed for every tag.
     */
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
        // alloc's fieldsUsed (these become scalar locals in Phase 3).
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
            SootMethod tgt = it.next().tgt();
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

    /* -------- Helpers -------- */

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
}
