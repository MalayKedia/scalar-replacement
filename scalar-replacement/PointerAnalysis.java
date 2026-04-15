import java.util.*;
import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.callgraph.*;
import soot.toolkits.graph.*;
import soot.toolkits.scalar.*;

/**
 * Intra-procedural forward-flow pointer and escape analysis.
 *
 * For each method body it computes, at every program point, an
 * {@link AnalysisState} that tracks which abstract objects each local may
 * reference, the abstract heap, and which objects have escaped or been
 * modified.
 *
 * After the fixpoint the analysis can:
 *   - report which allocation sites are scalar-replaceable
 *     ({@link #getScalarReplacementResults}), and
 *   - produce a {@link MethodSummary} for callers
 *     ({@link #computeSummary}).
 */
public class PointerAnalysis extends ForwardFlowAnalysis<Unit, AnalysisState> {

    private final Body body;
    private final CallGraph cg;
    private final Map<SootMethod, MethodSummary> summaries;

    /*
     * Stable maps: the same Jimple Unit always maps to the same AbstractObject
     * across fixpoint iterations, which is required for convergence.
     */
    private final Map<Unit, AllocObject>  allocObjects     = new HashMap<>();
    private final Map<Unit, ParamObject>  unitParamObjects = new HashMap<>(); // return vals & static loads
    private final Map<Integer, ParamObject> paramObjects   = new HashMap<>(); // method parameters
    private final Map<HeapKey, ParamObject> fieldOfParam   = new HashMap<>(); // lazy field loads from params

    public PointerAnalysis(ExceptionalUnitGraph cfg, Body body, CallGraph cg,
                           Map<SootMethod, MethodSummary> summaries) {
        super(cfg);
        this.body      = body;
        this.cg        = cg;
        this.summaries = summaries;
        doAnalysis();
    }

    /* =============================================================
     *  Flow setup
     * ============================================================= */

    @Override
    protected AnalysisState newInitialFlow() {
        return new AnalysisState();
    }

    @Override
    protected AnalysisState entryInitialFlow() {
        AnalysisState s = new AnalysisState();
        int idx = 0;

        // Receiver (instance methods only)
        if (!body.getMethod().isStatic()) {
            Local thisLocal = body.getThisLocal();
            ParamObject obj = new ParamObject();
            s.stack.put(thisLocal, new HashSet<>(Set.of(obj)));
            s.initialized.add(thisLocal);
            paramObjects.put(idx++, obj);
        }

        // Explicit parameters
        for (Local p : body.getParameterLocals()) {
            ParamObject obj = new ParamObject();
            s.stack.put(p, new HashSet<>(Set.of(obj)));
            s.initialized.add(p);
            paramObjects.put(idx++, obj);
        }

        return s;
    }

    @Override
    protected void copy(AnalysisState src, AnalysisState dst) {
        dst.copyFrom(src);
    }

    @Override
    protected void merge(AnalysisState a, AnalysisState b, AnalysisState out) {
        AnalysisState.merge(a, b, out);
    }

    /* =============================================================
     *  Transfer function
     * ============================================================= */

    @Override
    protected void flowThrough(AnalysisState in, Unit unit, AnalysisState out) {
        copy(in, out);
        Stmt stmt = (Stmt) unit;

        // --- invokes (standalone or as part of x = foo()) ---
        if (stmt.containsInvokeExpr()) {
            handleInvoke(stmt, in, out);
            if (!(stmt instanceof AssignStmt)) return;
            // fall through so the assignment part (x = <return-value>) is handled below
        }

        // --- return / throw ---
        if (stmt instanceof ReturnStmt) {
            Value ret = ((ReturnStmt) stmt).getOp();
            if (ret instanceof Local)
                for (AbstractObject o : in.stack.getOrDefault((Local) ret, Collections.emptySet()))
                    markEscaped(o, out);
            return;
        }
        if (stmt instanceof ThrowStmt) {
            Value thrown = ((ThrowStmt) stmt).getOp();
            if (thrown instanceof Local)
                for (AbstractObject o : in.stack.getOrDefault((Local) thrown, Collections.emptySet()))
                    markEscaped(o, out);
            return;
        }

        // --- identity (@this, @parameterN, @caughtexception) ---
        if (stmt instanceof IdentityStmt) {
            Value lhs = ((IdentityStmt) stmt).getLeftOp();
            if (lhs instanceof Local) out.initialized.add((Local) lhs);
            return;
        }

        // --- assignments ---
        if (!(stmt instanceof AssignStmt)) return;

        AssignStmt assign = (AssignStmt) stmt;
        Value lhs = assign.getLeftOp();
        Value rhs = assign.getRightOp();

        if      (lhs instanceof Local)           handleLocalAssign((Local) lhs, rhs, unit, in, out);
        else if (lhs instanceof InstanceFieldRef) handleFieldStore((InstanceFieldRef) lhs, rhs, in, out);
        else if (lhs instanceof ArrayRef)         handleArrayStore((ArrayRef) lhs, rhs, in, out);
        else if (lhs instanceof StaticFieldRef)   handleStaticStore(rhs, in, out);
    }

    /* =============================================================
     *  Invoke handling
     * ============================================================= */

    private void handleInvoke(Stmt stmt, AnalysisState in, AnalysisState out) {
        InvokeExpr invoke = stmt.getInvokeExpr();
        boolean isInit = invoke.getMethod().getName().equals("<init>");

        // Collect summaries from every resolved call target
        Set<Integer> escParams = new HashSet<>();
        Set<Integer> modParams = new HashSet<>();
        Set<Integer> reachModParams = new HashSet<>();
        Map<Integer, Set<Integer>> pCallSites = new HashMap<>();

        Iterator<Edge> edges = cg.edgesOutOf(stmt);
        while (edges.hasNext()) {
            MethodSummary s = summaries.get(edges.next().tgt());
            if (s == null) continue;
            escParams.addAll(s.escapingParams);
            modParams.addAll(s.modifiedParams);
            reachModParams.addAll(s.reachableModifiedParams);
            s.paramCallSites.forEach((k, v) ->
                pCallSites.computeIfAbsent(k, x -> new HashSet<>()).addAll(v));
        }

        int line = stmt.getJavaSourceStartLineNumber();

        // Constructors: check escapes and record call sites for arguments,
        // but skip modification tracking (field writes in <init> are expected).
        if (isInit) {
            if (invoke instanceof InstanceInvokeExpr) {
                Value base = ((InstanceInvokeExpr) invoke).getBase();
                if (base instanceof Local) {
                    Set<AbstractObject> pts = in.stack.getOrDefault((Local) base, Collections.emptySet());
                    if (escParams.contains(0))
                        for (AbstractObject o : pts) markEscaped(o, out);
                }
            }
            List<Value> initArgs = invoke.getArgs();
            for (int i = 0; i < initArgs.size(); i++) {
                if (!(initArgs.get(i) instanceof Local)) continue;
                int paramIdx = (invoke instanceof InstanceInvokeExpr) ? i + 1 : i;
                Set<AbstractObject> pts = in.stack.getOrDefault(
                    (Local) initArgs.get(i), Collections.emptySet());
                for (AbstractObject o : pts) {
                    addCallSite(o, line, out);
                    addCallSites(o, pCallSites.getOrDefault(paramIdx, Collections.emptySet()), out);
                }
                if (escParams.contains(paramIdx))
                    for (AbstractObject o : pts) markEscaped(o, out);
            }
            return;
        }

        // Receiver (this) for instance invokes — param index 0
        if (invoke instanceof InstanceInvokeExpr) {
            Value base = ((InstanceInvokeExpr) invoke).getBase();
            if (base instanceof Local) {
                Set<AbstractObject> pts = in.stack.getOrDefault((Local) base, Collections.emptySet());

                for (AbstractObject o : pts) {
                    addCallSite(o, line, out);
                    addCallSites(o, pCallSites.getOrDefault(0, Collections.emptySet()), out);
                }
                if (escParams.contains(0))
                    for (AbstractObject o : pts) markEscaped(o, out);
                if (modParams.contains(0)) {
                    out.modified.addAll(pts);
                    out.modifiedInCalls.addAll(pts);
                }
                if (reachModParams.contains(0)) {
                    Set<AbstractObject> descendants = reachableFrom(pts, in.heap);
                    descendants.removeAll(pts);
                    out.modified.addAll(descendants);
                    out.modifiedInCalls.addAll(descendants);
                }
            }
        }

        // Explicit arguments
        List<Value> args = invoke.getArgs();
        for (int i = 0; i < args.size(); i++) {
            if (!(args.get(i) instanceof Local)) continue;
            Local arg = (Local) args.get(i);
            int paramIdx = (invoke instanceof InstanceInvokeExpr) ? i + 1 : i;

            Set<AbstractObject> pts = in.stack.getOrDefault(arg, Collections.emptySet());
            for (AbstractObject o : pts) {
                addCallSite(o, line, out);
                addCallSites(o, pCallSites.getOrDefault(paramIdx, Collections.emptySet()), out);
            }
            if (escParams.contains(paramIdx))
                for (AbstractObject o : pts) markEscaped(o, out);
            if (modParams.contains(paramIdx)) {
                out.modified.addAll(pts);
                out.modifiedInCalls.addAll(pts);
            }
            if (reachModParams.contains(paramIdx)) {
                Set<AbstractObject> descendants = reachableFrom(pts, in.heap);
                descendants.removeAll(pts);
                out.modified.addAll(descendants);
                out.modifiedInCalls.addAll(descendants);
            }
        }
    }

    /* =============================================================
     *  Local assignment  (x = ...)
     * ============================================================= */

    private void handleLocalAssign(Local lhs, Value rhs, Unit unit,
                                   AnalysisState in, AnalysisState out) {
        out.initialized.add(lhs);

        if (rhs instanceof NullConstant) {
            out.stack.put(lhs, new HashSet<>(Set.of(AbstractObject.NULL)));

        } else if (rhs instanceof Local) {
            out.stack.put(lhs, new HashSet<>(
                out.stack.getOrDefault((Local) rhs, Collections.emptySet())));

        } else if (rhs instanceof AnyNewExpr) {
            AllocObject obj = allocObjects.computeIfAbsent(unit, AllocObject::new);
            out.stack.put(lhs, new HashSet<>(Set.of(obj)));

        } else if (rhs instanceof InstanceFieldRef) {
            out.stack.put(lhs, loadField((InstanceFieldRef) rhs, out));

        } else if (rhs instanceof ArrayRef) {
            out.stack.put(lhs, loadArray((ArrayRef) rhs, out));

        } else if (rhs instanceof StaticFieldRef) {
            // Conservative: static fields can hold any external object
            ParamObject ext = unitParamObjects.computeIfAbsent(unit, u -> new ParamObject());
            markEscaped(ext, out);
            out.stack.put(lhs, new HashSet<>(Set.of(ext)));

        } else if (rhs instanceof CastExpr) {
            Value op = ((CastExpr) rhs).getOp();
            if (op instanceof Local)
                out.stack.put(lhs, new HashSet<>(
                    out.stack.getOrDefault((Local) op, Collections.emptySet())));
            else
                out.stack.put(lhs, new HashSet<>());

        } else if (rhs instanceof InvokeExpr) {
            // Return value of a method call
            Type retType = ((InvokeExpr) rhs).getMethod().getReturnType();
            if (retType instanceof RefLikeType) {
                ParamObject retObj = unitParamObjects.computeIfAbsent(unit, u -> new ParamObject());
                out.stack.put(lhs, new HashSet<>(Set.of(retObj)));
            } else {
                out.stack.put(lhs, new HashSet<>());
            }

        } else {
            // Primitives, arithmetic, instanceof, length, etc.
            out.stack.put(lhs, new HashSet<>());
        }
    }

    /* =============================================================
     *  Field / array loads
     * ============================================================= */

    private Set<AbstractObject> loadField(InstanceFieldRef ref, AnalysisState state) {
        Local base = (Local) ref.getBase();
        SootField field = ref.getField();
        Set<AbstractObject> baseObjs = state.stack.getOrDefault(base, Collections.emptySet());
        Set<AbstractObject> result = new HashSet<>();

        if (baseObjs.size() > 1)
            for (AbstractObject o : baseObjs)
                if (o != AbstractObject.NULL) state.localPointsToMultiple.add(o);

        for (AbstractObject o : baseObjs) {
            if (o == AbstractObject.NULL) continue;
            HeapKey key = new HeapKey(o, field);

            if (state.heap.containsKey(key)) {
                result.addAll(state.heap.get(key));
            } else if (o instanceof ParamObject) {
                // Field of an external object — materialize a stable escaped placeholder
                ParamObject placeholder = fieldOfParam.computeIfAbsent(key, k -> new ParamObject());
                markEscaped(placeholder, state);
                state.heap.put(key, new HashSet<>(Set.of(placeholder)));
                result.add(placeholder);
            } else {
                result.add(AbstractObject.NULL);
            }
        }
        return result;
    }

    private Set<AbstractObject> loadArray(ArrayRef ref, AnalysisState state) {
        if (!(ref.getBase() instanceof Local)) return new HashSet<>();
        Local base = (Local) ref.getBase();
        Set<AbstractObject> result = new HashSet<>();
        for (AbstractObject o : state.stack.getOrDefault(base, Collections.emptySet())) {
            if (o == AbstractObject.NULL) continue;
            HeapKey key = HeapKey.arrayOf(o);

            if (state.heap.containsKey(key)) {
                result.addAll(state.heap.get(key));
            } else if (o instanceof ParamObject) {
                ParamObject placeholder = fieldOfParam.computeIfAbsent(key, k -> new ParamObject());
                markEscaped(placeholder, state);
                state.heap.put(key, new HashSet<>(Set.of(placeholder)));
                result.add(placeholder);
            } else {
                result.add(AbstractObject.NULL);
            }
        }
        return result;
    }

    /* =============================================================
     *  Field / array / static stores
     * ============================================================= */

    /**
     * {@code base.field = rhs}
     *
     * Uses a <em>strong</em> update (overwrite) when the base variable points
     * to exactly one object and is definitely initialized.  Otherwise falls
     * back to a <em>weak</em> update (union) to stay sound.
     */
    private void handleFieldStore(InstanceFieldRef ref, Value rhs,
                                  AnalysisState in, AnalysisState out) {
        Local base = (Local) ref.getBase();
        SootField field = ref.getField();
        Set<AbstractObject> baseObjs = in.stack.getOrDefault(base, Collections.emptySet());
        Set<AbstractObject> rhsObjs  = resolve(rhs, in);

        out.modified.addAll(baseObjs);
        boolean strong = baseObjs.size() == 1 && in.initialized.contains(base);

        for (AbstractObject o : baseObjs) {
            if (o == AbstractObject.NULL) continue;
            HeapKey key = new HeapKey(o, field);

            if (strong) out.heap.put(key, new HashSet<>(rhsObjs));
            else        out.heap.computeIfAbsent(key, k -> new HashSet<>()).addAll(rhsObjs);

            if (in.escaped.contains(o) || o instanceof ParamObject)
                for (AbstractObject r : rhsObjs) markEscaped(r, out);
        }
    }

    /**
     * {@code base[index] = rhs}
     *
     * Always a weak update because we do not track array indices.
     */
    private void handleArrayStore(ArrayRef ref, Value rhs,
                                  AnalysisState in, AnalysisState out) {
        if (!(ref.getBase() instanceof Local)) return;
        Local base = (Local) ref.getBase();
        Set<AbstractObject> baseObjs = in.stack.getOrDefault(base, Collections.emptySet());
        Set<AbstractObject> rhsObjs  = resolve(rhs, in);

        out.modified.addAll(baseObjs);

        for (AbstractObject o : baseObjs) {
            if (o == AbstractObject.NULL) continue;
            HeapKey key = HeapKey.arrayOf(o);
            out.heap.computeIfAbsent(key, k -> new HashSet<>()).addAll(rhsObjs);

            if (in.escaped.contains(o) || o instanceof ParamObject)
                for (AbstractObject r : rhsObjs) markEscaped(r, out);
        }
    }

    /** {@code SomeClass.field = rhs} — stored objects escape globally. */
    private void handleStaticStore(Value rhs, AnalysisState in, AnalysisState out) {
        for (AbstractObject o : resolve(rhs, in)) markEscaped(o, out);
    }

    /* =============================================================
     *  Helpers
     * ============================================================= */

    /** Resolve a Jimple Value to the set of abstract objects it may denote. */
    private Set<AbstractObject> resolve(Value v, AnalysisState state) {
        if (v instanceof Local)        return state.stack.getOrDefault((Local) v, Collections.emptySet());
        if (v instanceof NullConstant) return Set.of(AbstractObject.NULL);
        return Collections.emptySet();
    }

    /** All objects reachable from {@code roots} via heap edges (includes roots). */
    private Set<AbstractObject> reachableFrom(Set<AbstractObject> roots,
                                              Map<HeapKey, Set<AbstractObject>> heap) {
        Set<AbstractObject> visited = new HashSet<>(roots);
        Deque<AbstractObject> work = new ArrayDeque<>(roots);
        while (!work.isEmpty()) {
            AbstractObject cur = work.poll();
            for (var entry : heap.entrySet())
                if (entry.getKey().base.equals(cur))
                    for (AbstractObject child : entry.getValue())
                        if (visited.add(child)) work.add(child);
        }
        return visited;
    }

    /** Transitively mark {@code obj} and everything reachable from it as escaped. */
    private void markEscaped(AbstractObject obj, AnalysisState state) {
        if (!state.escaped.add(obj)) return;
        for (var entry : state.heap.entrySet())
            if (entry.getKey().base.equals(obj))
                for (AbstractObject child : entry.getValue())
                    markEscaped(child, state);
    }

    private void addCallSite(AbstractObject obj, int line, AnalysisState state) {
        if (line > 0)
            state.callSites.computeIfAbsent(obj, k -> new HashSet<>()).add(line);
    }

    private void addCallSites(AbstractObject obj, Set<Integer> sites, AnalysisState state) {
        if (!sites.isEmpty())
            state.callSites.computeIfAbsent(obj, k -> new HashSet<>()).addAll(sites);
    }

    /* =============================================================
     *  Results  (queried by AnalysisTransformer after the fixpoint)
     * ============================================================= */

    /** Merge the flow-after states of every exit point. */
    AnalysisState getFinalState() {
        List<Unit> tails = new ExceptionalUnitGraph(body).getTails();
        if (tails.isEmpty()) return new AnalysisState();

        AnalysisState result = new AnalysisState();
        result.copyFrom(getFlowAfter(tails.get(0)));
        for (int i = 1; i < tails.size(); i++) {
            AnalysisState merged = new AnalysisState();
            AnalysisState.merge(result, getFlowAfter(tails.get(i)), merged);
            result = merged;
        }
        return result;
    }

    /**
     * Return a map from source line number to a result string for every
     * allocation site in this method.
     *
     * Format:  {@code O<line> = Y[callsites]}  if scalar-replaceable,
     *          {@code O<line> = N}              otherwise.
     */
    Map<Integer, String> getScalarReplacementResults() {
        AnalysisState fin = getFinalState();
        Map<Integer, String> results = new HashMap<>();

        for (Unit u : body.getUnits()) {
            if (!(u instanceof AssignStmt)) continue;
            if (!(((AssignStmt) u).getRightOp() instanceof AnyNewExpr)) continue;

            AllocObject obj = allocObjects.get(u);
            if (obj == null) continue;

            boolean escaped   = fin.escaped.contains(obj);
            boolean modInCall = fin.modifiedInCalls.contains(obj);
            int line = u.getJavaSourceStartLineNumber();

            String out = "O" + line + " = ";
            if (!escaped && !modInCall && !fin.localPointsToMultiple.contains(obj)) {
                Set<Integer> sites = new TreeSet<>(
                    fin.callSites.getOrDefault(obj, Collections.emptySet()));
                StringJoiner sj = new StringJoiner(",", "[", "]");
                for (int s : sites) sj.add(String.valueOf(s));
                out += "Y" + sj;
            } else {
                out += "N";
            }
            results.put(line, out);
        }
        return results;
    }

    /**
     * Produce the inter-procedural summary for this method.
     *
     * Reports which parameter indices escape, which are modified, and the
     * transitive call-site set for each parameter.
     */
    MethodSummary computeSummary() {
        AnalysisState fin = getFinalState();
        SootMethod method = body.getMethod();

        Set<Integer> esc = new HashSet<>();
        Set<Integer> mod = new HashSet<>();
        Set<Integer> reachMod = new HashSet<>();
        Map<Integer, Set<Integer>> cs = new HashMap<>();
        int idx = 0;

        // Receiver
        if (!method.isStatic()) {
            Local thisLocal = body.getThisLocal();
            Set<AbstractObject> pts = fin.stack.getOrDefault(thisLocal, Collections.emptySet());
            if (!Collections.disjoint(fin.escaped, pts))  esc.add(idx);
            if (!Collections.disjoint(fin.modified, pts)) mod.add(idx);
            Set<AbstractObject> descendants = reachableFrom(pts, fin.heap);
            descendants.removeAll(pts);
            if (!Collections.disjoint(fin.modified, descendants)) reachMod.add(idx);
            ParamObject p = paramObjects.get(idx);
            if (p != null)
                cs.put(idx, new HashSet<>(fin.callSites.getOrDefault(p, Collections.emptySet())));
            idx++;
        }

        // Explicit parameters
        for (int i = 0; i < method.getParameterCount(); i++) {
            Local param = body.getParameterLocal(i);
            Set<AbstractObject> pts = fin.stack.getOrDefault(param, Collections.emptySet());
            if (!Collections.disjoint(fin.escaped, pts))  esc.add(idx);
            if (!Collections.disjoint(fin.modified, pts)) mod.add(idx);
            Set<AbstractObject> descendants = reachableFrom(pts, fin.heap);
            descendants.removeAll(pts);
            if (!Collections.disjoint(fin.modified, descendants)) reachMod.add(idx);
            ParamObject p = paramObjects.get(idx);
            if (p != null)
                cs.put(idx, new HashSet<>(fin.callSites.getOrDefault(p, Collections.emptySet())));
            idx++;
        }

        return new MethodSummary(esc, mod, reachMod, cs);
    }
}
