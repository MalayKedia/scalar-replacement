import java.util.*;
import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.callgraph.*;
import soot.toolkits.graph.*;
import soot.toolkits.scalar.*;

/**
 * Phase-1 analysis: compute a {@link MethodSummary} for one method.
 *
 * Forward dataflow on per-local param-alias tag sets (see {@link AnalysisState}).
 * While the fixpoint iterates, side-effect accumulators on {@link #result}
 * record escape, identity use, forwarded-bad, and read/modified field facts.
 * All accumulators are monotone (union), so re-observing the same fact across
 * iterations is idempotent.
 *
 * Assumes summaries for every outgoing call target are already installed in
 * {@code summaries}. The driver arranges this by visiting SCCs in reverse
 * topological order and pre-installing a fully-bad summary for every method
 * in a recursive SCC.
 */
public class PointerAnalysis extends ForwardFlowAnalysis<Unit, AnalysisState> {

    private final Body body;
    private final SootMethod method;
    private final CallGraph cg;
    private final Map<SootMethod, MethodSummary> summaries;
    private final MethodSummary result;

    private final int paramCount;
    private final Map<Integer, Local> paramLocals = new HashMap<>();

    public PointerAnalysis(Body body, CallGraph cg,
                           Map<SootMethod, MethodSummary> summaries) {
        super(new ExceptionalUnitGraph(body));
        this.body = body;
        this.method = body.getMethod();
        this.cg = cg;
        this.summaries = summaries;

        int idx = 0;
        if (!method.isStatic()) {
            paramLocals.put(idx++, body.getThisLocal());
        }
        for (int i = 0; i < method.getParameterCount(); i++) {
            paramLocals.put(idx++, body.getParameterLocal(i));
        }
        this.paramCount = idx;
        this.result = new MethodSummary(paramCount);

        doAnalysis();
        result.finalizeGoodParams();
    }

    public MethodSummary getSummary() { return result; }

    /* =============================================================
     *  Flow plumbing
     * ============================================================= */

    @Override
    protected AnalysisState newInitialFlow() { return new AnalysisState(); }

    @Override
    protected AnalysisState entryInitialFlow() {
        AnalysisState s = new AnalysisState();
        for (Map.Entry<Integer, Local> e : paramLocals.entrySet()) {
            s.directAlias.put(e.getValue(), new HashSet<>(Set.of(e.getKey())));
        }
        return s;
    }

    @Override
    protected void copy(AnalysisState src, AnalysisState dst) { dst.copyFrom(src); }

    @Override
    protected void merge(AnalysisState a, AnalysisState b, AnalysisState out) {
        AnalysisState.merge(a, b, out);
    }

    /* =============================================================
     *  Transfer
     * ============================================================= */

    @Override
    protected void flowThrough(AnalysisState in, Unit unit, AnalysisState out) {
        copy(in, out);
        Stmt stmt = (Stmt) unit;

        if (stmt instanceof IdentityStmt) {
            handleIdentity((IdentityStmt) stmt, out);
            return;
        }

        if (stmt.containsInvokeExpr()) {
            handleInvoke(stmt, in);
            // fall through so x = g(...) also records return-value alias
        }

        if (stmt instanceof ReturnStmt) {
            handleReturn((ReturnStmt) stmt, in);
            return;
        }
        if (stmt instanceof ThrowStmt) {
            handleThrow((ThrowStmt) stmt, in);
            return;
        }
        if (stmt instanceof IfStmt) {
            handleIf((IfStmt) stmt, in);
            return;
        }
        if (stmt instanceof EnterMonitorStmt) {
            markIdentity(((EnterMonitorStmt) stmt).getOp(), in);
            return;
        }
        if (stmt instanceof ExitMonitorStmt) {
            markIdentity(((ExitMonitorStmt) stmt).getOp(), in);
            return;
        }

        if (!(stmt instanceof AssignStmt)) return;
        handleAssign((AssignStmt) stmt, in, out);
    }

    /* -------- IdentityStmt (@this, @parameter, @caughtexception) -------- */

    private void handleIdentity(IdentityStmt stmt, AnalysisState out) {
        if (!(stmt.getLeftOp() instanceof Local)) return;
        Local lhs = (Local) stmt.getLeftOp();
        Value rhs = stmt.getRightOp();

        if (rhs instanceof ThisRef) {
            out.directAlias.put(lhs, new HashSet<>(Set.of(0)));
        } else if (rhs instanceof ParameterRef) {
            int p = ((ParameterRef) rhs).getIndex() + (method.isStatic() ? 0 : 1);
            out.directAlias.put(lhs, new HashSet<>(Set.of(p)));
        } else {
            out.directAlias.put(lhs, new HashSet<>());
        }
    }

    /* -------- AssignStmt -------- */

    private void handleAssign(AssignStmt stmt, AnalysisState in, AnalysisState out) {
        Value lhs = stmt.getLeftOp();
        Value rhs = stmt.getRightOp();

        if (lhs instanceof Local) {
            out.directAlias.put((Local) lhs, resolveDirect(rhs, in, stmt));
            return;
        }

        Set<Integer> rhsTag = resolveDirect(rhs, in, stmt);

        if (lhs instanceof InstanceFieldRef) {
            InstanceFieldRef ref = (InstanceFieldRef) lhs;
            Set<Integer> baseTag = localTag(ref.getBase(), in);
            for (int i : baseTag) {
                result.directlyModifiedFields
                    .computeIfAbsent(i, k -> new HashSet<>())
                    .add(ref.getField());
            }
            // Any param-aliased value being stored into a heap location
            // escapes conservatively — Phase 1 doesn't know whether the
            // base outlives this method.
            for (int j : rhsTag) result.escapingParams.add(j);
        } else if (lhs instanceof ArrayRef) {
            // Array element store: we don't track array slots, so any
            // param-aliased rhs escapes.
            for (int j : rhsTag) result.escapingParams.add(j);
        } else if (lhs instanceof StaticFieldRef) {
            for (int j : rhsTag) result.escapingParams.add(j);
        }
    }

    /* -------- RHS resolution -------- */

    /** Tag set for an expression used as a value. Has side effects: field
     *  loads record readFields; instanceof records identity use. */
    private Set<Integer> resolveDirect(Value v, AnalysisState in, Stmt containingStmt) {
        if (v instanceof Local) {
            return new HashSet<>(in.directAlias.getOrDefault(
                (Local) v, Collections.emptySet()));
        }
        if (v instanceof CastExpr) {
            Value op = ((CastExpr) v).getOp();
            if (op instanceof Local) {
                return new HashSet<>(in.directAlias.getOrDefault(
                    (Local) op, Collections.emptySet()));
            }
            return new HashSet<>();
        }
        if (v instanceof InstanceOfExpr) {
            markIdentity(((InstanceOfExpr) v).getOp(), in);
            return new HashSet<>();
        }
        if (v instanceof InstanceFieldRef) {
            InstanceFieldRef ref = (InstanceFieldRef) v;
            Set<Integer> baseTag = localTag(ref.getBase(), in);
            for (int i : baseTag) {
                result.readFields
                    .computeIfAbsent(i, k -> new HashSet<>())
                    .add(ref.getField());
            }
            return new HashSet<>();
        }
        if (v instanceof InvokeExpr) {
            return computeReturnAlias((InvokeExpr) v, in, containingStmt);
        }
        // NewExpr, NewArrayExpr, StaticFieldRef, ArrayRef, LengthExpr,
        // arithmetic, constants — none are direct param aliases.
        return new HashSet<>();
    }

    private Set<Integer> localTag(Value v, AnalysisState in) {
        if (v instanceof Local) {
            return in.directAlias.getOrDefault((Local) v, Collections.emptySet());
        }
        return Collections.emptySet();
    }

    /* -------- Return / Throw / If / Monitor -------- */

    private void handleReturn(ReturnStmt stmt, AnalysisState in) {
        Set<Integer> tag = localTag(stmt.getOp(), in);
        for (int i : tag) {
            result.escapingParams.add(i);
            result.returnAliases.add(i);
        }
    }

    private void handleThrow(ThrowStmt stmt, AnalysisState in) {
        for (int i : localTag(stmt.getOp(), in)) result.escapingParams.add(i);
    }

    private void handleIf(IfStmt stmt, AnalysisState in) {
        Value cond = stmt.getCondition();
        if (!(cond instanceof BinopExpr)) return;
        BinopExpr b = (BinopExpr) cond;
        if (isRefOp(b.getOp1()) || isRefOp(b.getOp2())) {
            markIdentity(b.getOp1(), in);
            markIdentity(b.getOp2(), in);
        }
    }

    private boolean isRefOp(Value v) {
        return v.getType() instanceof RefLikeType;
    }

    private void markIdentity(Value v, AnalysisState in) {
        if (!(v instanceof Local)) return;
        for (int i : in.directAlias.getOrDefault(
                (Local) v, Collections.emptySet())) {
            result.identityUsedParams.add(i);
        }
    }

    /* -------- Invokes -------- */

    private void handleInvoke(Stmt stmt, AnalysisState in) {
        InvokeExpr invoke = stmt.getInvokeExpr();
        List<MethodSummary> callees = getCalleeSummaries(stmt);
        boolean isChainInit = isChainInitCall(stmt);

        List<Value> actuals = new ArrayList<>();
        boolean isInstance = invoke instanceof InstanceInvokeExpr;
        if (isInstance) actuals.add(((InstanceInvokeExpr) invoke).getBase());
        actuals.addAll(invoke.getArgs());

        int curLine = stmt.getJavaSourceStartLineNumber();

        for (int i = 0; i < actuals.size(); i++) {
            Value a = actuals.get(i);
            if (!(a instanceof Local)) continue;
            Set<Integer> tags = in.directAlias.getOrDefault(
                (Local) a, Collections.emptySet());
            if (tags.isEmpty()) continue;

            boolean goodInAllTargets = true;
            boolean escapingInAny = false;
            boolean identityInAny = false;
            Set<SootField> modAt = new HashSet<>();
            Set<SootField> readAt = new HashSet<>();
            Set<Integer> transitiveCallSites = new HashSet<>();

            if (callees.isEmpty()) {
                goodInAllTargets = false;
                escapingInAny = true;
                identityInAny = true;
            } else {
                for (MethodSummary s : callees) {
                    if (i >= s.paramCount) { goodInAllTargets = false; continue; }
                    if (!s.goodParams.contains(i)) goodInAllTargets = false;
                    if (s.escapingParams.contains(i)) escapingInAny = true;
                    if (s.identityUsedParams.contains(i)) identityInAny = true;
                    modAt.addAll(s.allModified(i));
                    readAt.addAll(s.read(i));
                    transitiveCallSites.addAll(
                        s.paramCallSites.getOrDefault(i, Collections.emptySet()));
                }
            }

            // Chain init calls contribute to calleeModifiedFields on position 0
            // (this's receiver) but NOT to nonChainCalleeModifiedFields. Other
            // positions are treated as regular forwarded args even on chain-init.
            boolean isChainContribution = isChainInit && i == 0;

            for (int p : tags) {
                if (escapingInAny) result.escapingParams.add(p);
                if (identityInAny) result.identityUsedParams.add(p);
                if (!goodInAllTargets) result.forwardedBadParams.add(p);
                if (!modAt.isEmpty()) {
                    result.calleeModifiedFields
                        .computeIfAbsent(p, k -> new HashSet<>())
                        .addAll(modAt);
                    if (!isChainContribution) {
                        result.nonChainCalleeModifiedFields
                            .computeIfAbsent(p, k -> new HashSet<>())
                            .addAll(modAt);
                    }
                }
                if (!readAt.isEmpty())
                    result.readFields
                        .computeIfAbsent(p, k -> new HashSet<>())
                        .addAll(readAt);

                // Track call-site lines for this param: current call's line
                // plus callees' own transitive forwarded-call lines.
                if (curLine > 0)
                    result.paramCallSites
                        .computeIfAbsent(p, k -> new HashSet<>())
                        .add(curLine);
                if (!transitiveCallSites.isEmpty())
                    result.paramCallSites
                        .computeIfAbsent(p, k -> new HashSet<>())
                        .addAll(transitiveCallSites);
            }
        }

        if (isChainInit && result.chainInitTarget == null) {
            try {
                result.chainInitTarget = invoke.getMethod();
            } catch (Exception ignored) { /* phantom */ }
        }
    }

    /**
     * True if {@code stmt} is a super.&lt;init&gt; / this.&lt;init&gt; call
     * within a constructor body — i.e., a specialinvoke with name &lt;init&gt;
     * whose receiver is the enclosing method's {@code this} local.
     */
    private boolean isChainInitCall(Stmt stmt) {
        if (method.isStatic()) return false;
        if (!method.getName().equals("<init>")) return false;
        InvokeExpr invoke = stmt.getInvokeExpr();
        if (!(invoke instanceof SpecialInvokeExpr)) return false;
        if (!invoke.getMethodRef().getName().equals("<init>")) return false;
        Value base = ((SpecialInvokeExpr) invoke).getBase();
        return base instanceof Local && base.equals(body.getThisLocal());
    }

    private Set<Integer> computeReturnAlias(InvokeExpr invoke, AnalysisState in,
                                            Stmt containingStmt) {
        List<MethodSummary> callees = getCalleeSummaries(containingStmt);
        if (callees.isEmpty()) return new HashSet<>();

        List<Value> actuals = new ArrayList<>();
        boolean isInstance = invoke instanceof InstanceInvokeExpr;
        if (isInstance) actuals.add(((InstanceInvokeExpr) invoke).getBase());
        actuals.addAll(invoke.getArgs());

        Set<Integer> r = new HashSet<>();
        for (MethodSummary s : callees) {
            for (int idx : s.returnAliases) {
                if (idx >= actuals.size()) continue;
                Value a = actuals.get(idx);
                if (!(a instanceof Local)) continue;
                r.addAll(in.directAlias.getOrDefault(
                    (Local) a, Collections.emptySet()));
            }
        }
        return r;
    }

    private List<MethodSummary> getCalleeSummaries(Unit stmt) {
        List<MethodSummary> r = new ArrayList<>();
        Iterator<Edge> it = cg.edgesOutOf(stmt);
        while (it.hasNext()) {
            SootMethod tgt = it.next().tgt();
            // Skip synthetic <clinit> edges — they model class-initialization
            // side effects, not argument-passing calls.
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
}
