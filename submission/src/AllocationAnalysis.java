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
    /** Expose the per-unit IN state for Phase 3 rewriting. */
    public AllocState stateBefore(Unit u) { return getFlowBefore(u); }

    public List<ReplaceableAlloc> getReplaceableAllocs() {
        List<ReplaceableAlloc> r = new ArrayList<>();
        for (Map.Entry<Unit, ReplaceableAlloc> e : allocs.entrySet()) {
            Unit site = e.getKey();
            ReplaceableAlloc ra = e.getValue();
            if (disqualified.contains(site)) continue;
            if (ra.initCall == null || ra.initTarget == null) continue;
            if (!validateAndWalkChain(ra)) continue;
            // Partial-escape soundness: if the alloc has any escape points,
            // we emit materialization there and keep using scalars elsewhere.
            // That's only sound if no field-write to the alloc is reachable
            // from any escape point — otherwise post-escape writes would
            // diverge from the materialized heap snapshot.
            if (!ra.escapePoints.isEmpty() && hasPostEscapeWrite(ra)) continue;
            // Any P or L case would run <init> twice: once inlined at the
            // alloc site and once at a materialization site. That's fine
            // iff <init> only writes this's fields — any other side effect
            // (static updates, non-super method calls, new expressions,
            // throws) would double-execute. Restrict P/L to simple inits.
            boolean wouldBePartial =
                !ra.escapePoints.isEmpty() || ra.hasCalleeMaterialization;
            if (wouldBePartial && !isSimpleInitChain(ra.initChain)) continue;
            populateChainFields(ra);
            r.add(ra);
        }
        return r;
    }

    /**
     * True iff every constructor in the chain only performs direct
     * {@code this.f = ...} stores, super/delegated {@code <init>} calls,
     * local arithmetic/copies, and control flow over simple values. Any
     * static-field access, non-super invoke, {@code new} expression, or
     * throw in the chain makes it unsafe for P/L transformation because
     * we'd double-execute those side effects.
     */
    private boolean isSimpleInitChain(List<SootMethod> chain) {
        for (SootMethod c : chain) {
            if (c.getDeclaringClass().getName().equals("java.lang.Object")
                    && c.getName().equals("<init>")) continue;
            if (!c.isConcrete()) return false;
            if (!isSimpleInitBody(c)) return false;
        }
        return true;
    }

    private boolean isSimpleInitBody(SootMethod ctor) {
        Body b = ctor.getActiveBody();
        Local thisLocal = b.getThisLocal();
        for (Unit u : b.getUnits()) {
            Stmt s = (Stmt) u;
            if (s instanceof IdentityStmt) continue;
            if (s instanceof ReturnVoidStmt) continue;
            if (s instanceof GotoStmt) continue;
            if (s instanceof IfStmt) {
                if (!isSimpleValue(((IfStmt) s).getCondition(), thisLocal)) return false;
                continue;
            }
            if (s instanceof InvokeStmt) {
                InvokeExpr inv = ((InvokeStmt) s).getInvokeExpr();
                // Allow super/delegated <init> on this.
                if (inv instanceof SpecialInvokeExpr
                        && inv.getMethodRef().getName().equals("<init>")
                        && ((SpecialInvokeExpr) inv).getBase().equals(thisLocal)) continue;
                return false;
            }
            if (s instanceof AssignStmt) {
                AssignStmt a = (AssignStmt) s;
                if (!isSimpleLhs(a.getLeftOp(), thisLocal)) return false;
                if (!isSimpleValue(a.getRightOp(), thisLocal)) return false;
                continue;
            }
            return false;
        }
        return true;
    }

    private boolean isSimpleLhs(Value v, Local thisLocal) {
        if (v instanceof Local) return true;
        if (v instanceof InstanceFieldRef)
            return ((InstanceFieldRef) v).getBase().equals(thisLocal);
        return false;  // static/array writes disqualify
    }

    private boolean isSimpleValue(Value v, Local thisLocal) {
        if (v instanceof Constant) return true;
        if (v instanceof Local) return true;
        if (v instanceof InstanceFieldRef)
            return ((InstanceFieldRef) v).getBase().equals(thisLocal);
        if (v instanceof BinopExpr) {
            BinopExpr b = (BinopExpr) v;
            return isSimpleValue(b.getOp1(), thisLocal)
                && isSimpleValue(b.getOp2(), thisLocal);
        }
        if (v instanceof UnopExpr)
            return isSimpleValue(((UnopExpr) v).getOp(), thisLocal);
        if (v instanceof CastExpr)
            return isSimpleValue(((CastExpr) v).getOp(), thisLocal);
        return false;  // static refs, new, invoke, array ref → unsafe
    }

    /**
     * True iff any unit in {@code ra.fieldWrites} is CFG-reachable from any
     * unit in {@code ra.escapePoints} (i.e., a post-escape field write could
     * happen). BFS over the method's CFG.
     */
    private boolean hasPostEscapeWrite(ReplaceableAlloc ra) {
        if (ra.fieldWrites.isEmpty()) return false;
        DirectedGraph<Unit> cfg = graph;  // ExceptionalUnitGraph from ForwardFlowAnalysis
        Set<Unit> writes = new HashSet<>(ra.fieldWrites);
        Set<Unit> visited = new HashSet<>();
        Deque<Unit> work = new ArrayDeque<>();
        for (Unit e : ra.escapePoints) {
            for (Unit s : cfg.getSuccsOf(e)) {
                if (visited.add(s)) work.add(s);
            }
        }
        while (!work.isEmpty()) {
            Unit u = work.poll();
            if (writes.contains(u)) return true;
            for (Unit s : cfg.getSuccsOf(u)) {
                if (visited.add(s)) work.add(s);
            }
        }
        return false;
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
            // Return is a terminal escape — ref outlives our method.
            recordEscape(localTag(((ReturnStmt) stmt).getOp(), in), (Unit) stmt);
            return;
        }
        if (stmt instanceof ThrowStmt) {
            // Throw is a terminal escape — ref flows to the exception handler.
            recordEscape(localTag(((ThrowStmt) stmt).getOp(), in), (Unit) stmt);
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
            // If the base may alias more than one tracked allocation, we
            // cannot pick which set of scalar locals to write to — disqualify
            // all of them.
            disqualifyIfAmbiguous(baseTag);
            for (Unit u : baseTag) {
                if (allocs.containsKey(u) && !disqualified.contains(u)) {
                    ReplaceableAlloc ra = allocs.get(u);
                    ra.fieldsUsed.add(ref.getField());
                    if (!ra.fieldWrites.contains(stmt)) ra.fieldWrites.add(stmt);
                }
            }
            // A ref being stored into a heap slot is an escape.
            recordEscape(rhsTag, (Unit) stmt);
        } else if (lhs instanceof ArrayRef) {
            recordEscape(rhsTag, (Unit) stmt);
        } else if (lhs instanceof StaticFieldRef) {
            recordEscape(rhsTag, (Unit) stmt);
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
            disqualifyIfAmbiguous(baseTag);
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
                      && invoke.getMethodRef().getName().equals("<init>");

        List<Value> actuals = new ArrayList<>();
        boolean isInstance = invoke instanceof InstanceInvokeExpr;
        if (isInstance) actuals.add(((InstanceInvokeExpr) invoke).getBase());
        actuals.addAll(invoke.getArgs());

        if (isInit && isInstance) {
            // Receiver = the object being initialized. Pair it with the alloc.
            Set<Unit> receiverTag = localTag(actuals.get(0), in);
            disqualifyIfAmbiguous(receiverTag);
            for (Unit u : receiverTag) {
                if (!allocs.containsKey(u)) continue;
                ReplaceableAlloc ra = allocs.get(u);
                if (ra.initCall != null && !ra.initCall.equals(stmt)) {
                    // Two distinct <init> statements target the same alloc —
                    // can't happen in legal Jimple, but be conservative.
                    // (Re-visiting the same stmt during fixpoint iteration
                    //  is fine and must not trip this check.)
                    disqualify(u);
                } else if (ra.initCall == null) {
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
            disqualifyIfAmbiguous(argTag);
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
            // Unknown target — be conservative and hard-disqualify. We can't
            // partial-escape because we don't know whether the callee mutates.
            disqualifyAll(argTag);
            return false;
        }
        for (MethodSummary s : callees) {
            if (paramIdx >= s.paramCount) {
                disqualifyAll(argTag);
                return false;
            }
            // A callee that *mutates* the alloc's fields breaks our "scalars
            // stay valid through escape" invariant — hard-disqualify.
            if (!s.allModified(paramIdx).isEmpty()) {
                disqualifyAll(argTag);
                return false;
            }
            // Callee's param isn't good (escapes/identity/forwarded) but
            // doesn't mutate — treat as an escape point (partial candidate).
            if (!s.goodParams.contains(paramIdx)) {
                recordEscape(argTag, (Unit) stmt);
                return false;
            }
        }
        // All targets accept: accumulate read-field contributions into the
        // alloc's fieldsUsed (these become scalar locals in Phase 3). Also
        // note if any target has a non-empty paramEscapePoints — that means
        // materialization happens in the specialized callee body ("L" case).
        for (MethodSummary s : callees) {
            Set<SootField> reads = s.read(paramIdx);
            boolean calleeHasEscape = !s.paramEscapes(paramIdx).isEmpty();
            for (Unit u : argTag) {
                if (!allocs.containsKey(u) || disqualified.contains(u)) continue;
                ReplaceableAlloc ra = allocs.get(u);
                if (!reads.isEmpty()) ra.fieldsUsed.addAll(reads);
                if (calleeHasEscape) ra.hasCalleeMaterialization = true;
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
            // Skip synthetic <clinit> edges — Soot adds them on any static-
            // member access, but they don't take the call-site's args.
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

    /**
     * Record {@code escapeUnit} as an escape point for every alloc in
     * {@code allocSites}. The alloc stays a candidate (the dataflow keeps
     * tracking it); later we'll verify that no post-escape writes occur and
     * then either scalar-replace with transient materialization or hard-
     * disqualify.
     */
    private void recordEscape(Collection<Unit> allocSites, Unit escapeUnit) {
        for (Unit site : allocSites) {
            if (!allocs.containsKey(site) || disqualified.contains(site)) continue;
            ReplaceableAlloc ra = allocs.get(site);
            if (!ra.escapePoints.contains(escapeUnit))
                ra.escapePoints.add(escapeUnit);
        }
    }

    /**
     * A local whose alloc-tag set has more than one member cannot be scalar-
     * replaced through, because any use of the local (field access, invoke
     * arg) would need to pick which allocation's scalar locals to read/write.
     * Disqualify every candidate in such a set.
     */
    private void disqualifyIfAmbiguous(Set<Unit> tag) {
        if (tag.size() > 1) disqualifyAll(tag);
    }
}
