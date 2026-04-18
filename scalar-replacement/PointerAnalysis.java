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

        if (baseObjs.size() > 1)
            for (AbstractObject o : baseObjs)
                if (o != AbstractObject.NULL) out.localPointsToMultiple.add(o);

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

    /* =============================================================
     *  Scalar Replacement Transformation (no-callsite objects only)
     * ============================================================= */

    /**
     * Perform scalar replacement for objects that are scalar-replaceable
     * and have NO call sites (i.e., Y[]).
     *
     * For each such object:
     *   - Remove the {@code new} allocation statement.
     *   - Remove the {@code <init>} constructor call.
     *   - Replace field stores ({@code base.field = rhs}) with assignments
     *     to a fresh scalar local named {@code varName_ClassName_fieldName}.
     *   - Replace field loads ({@code lhs = base.field}) with reads from
     *     the corresponding scalar local.
     */
    void performScalarReplacement() {
        AnalysisState fin = getFinalState();

        performNoCallSiteScalarReplacement(fin);
        performReceiverCallScalarReplacement(fin);
    }

    /* =============================================================
     *  Phase 1: Y[] objects (no call sites)
     * ============================================================= */

    private void performNoCallSiteScalarReplacement(AnalysisState fin) {
        // Step 1: Identify scalar-replaceable objects with no call sites
        Map<AllocObject, Unit> noCallSiteObjects = new LinkedHashMap<>();
        for (Unit u : body.getUnits()) {
            if (!(u instanceof AssignStmt)) continue;
            AssignStmt assign = (AssignStmt) u;
            if (!(assign.getRightOp() instanceof AnyNewExpr)) continue;
            if (!(assign.getRightOp() instanceof NewExpr)) continue; // skip arrays

            AllocObject obj = allocObjects.get(u);
            if (obj == null) continue;

            if (fin.escaped.contains(obj)) continue;
            if (fin.modifiedInCalls.contains(obj)) continue;
            if (fin.localPointsToMultiple.contains(obj)) continue;

            Set<Integer> sites = fin.callSites.getOrDefault(obj, Collections.emptySet());
            if (!sites.isEmpty()) continue;

            noCallSiteObjects.put(obj, u);
        }

        if (noCallSiteObjects.isEmpty()) return;

        // Step 2: For each object, record the variable name and class name
        Map<AllocObject, String> varNames = new HashMap<>();
        Map<AllocObject, String> classNames = new HashMap<>();

        for (Map.Entry<AllocObject, Unit> entry : noCallSiteObjects.entrySet()) {
            AllocObject obj = entry.getKey();
            AssignStmt allocStmt = (AssignStmt) entry.getValue();
            Local allocLocal = (Local) allocStmt.getLeftOp();
            NewExpr newExpr = (NewExpr) allocStmt.getRightOp();

            varNames.put(obj, allocLocal.getName());
            classNames.put(obj, newExpr.getBaseType().getSootClass().getShortName());
        }

        // Step 3: First pass — collect all accessed fields and create scalar locals
        Map<AllocObject, Map<SootField, Local>> fieldLocals = new HashMap<>();
        for (AllocObject obj : noCallSiteObjects.keySet())
            fieldLocals.put(obj, new HashMap<>());

        for (Unit u : body.getUnits()) {
            if (!(u instanceof AssignStmt)) continue;
            AssignStmt assign = (AssignStmt) u;

            collectFieldLocal(assign.getLeftOp(), u, noCallSiteObjects, varNames,
                              classNames, fieldLocals);
            collectFieldLocal(assign.getRightOp(), u, noCallSiteObjects, varNames,
                              classNames, fieldLocals);
        }

        // Step 4: Second pass — rewrite field accesses, mark allocations/inits for removal
        List<Unit> toRemove = new ArrayList<>();
        // Deferred inserts: at each allocation site, insert default-value inits.
        // We cannot modify the unit chain while iterating, so collect and apply after.
        Map<Unit, List<Unit>> toInsertBefore = new LinkedHashMap<>();

        for (Unit u : body.getUnits()) {
            Stmt stmt = (Stmt) u;

            // Replace allocation statements with default-value inits for scalar locals.
            // Object fields default to 0/false/null in Java; local variables do not,
            // so we must explicitly initialize each scalar local at the allocation site.
            if (u instanceof AssignStmt) {
                AssignStmt assign = (AssignStmt) u;
                if (assign.getRightOp() instanceof NewExpr) {
                    AllocObject obj = allocObjects.get(u);
                    if (obj != null && noCallSiteObjects.containsKey(obj)) {
                        Map<SootField, Local> locals = fieldLocals.get(obj);
                        List<Unit> inits = new ArrayList<>();
                        for (Local scalarLocal : locals.values()) {
                            inits.add(Jimple.v().newAssignStmt(
                                scalarLocal, getDefaultValue(scalarLocal.getType())));
                        }
                        toInsertBefore.put(u, inits);
                        toRemove.add(u);
                        continue;
                    }
                }

                // Rewrite field stores: base.field = rhs  -->  scalarLocal = rhs
                if (assign.getLeftOp() instanceof InstanceFieldRef) {
                    InstanceFieldRef ref = (InstanceFieldRef) assign.getLeftOp();
                    AllocObject target = resolveUniqueAllocTarget(
                        (Local) ref.getBase(), u, noCallSiteObjects.keySet());
                    if (target != null) {
                        Local scalarLocal = fieldLocals.get(target).get(ref.getField());
                        if (scalarLocal != null) {
                            assign.setLeftOp(scalarLocal);
                        }
                    }
                }

                // Rewrite field loads: lhs = base.field  -->  lhs = scalarLocal
                if (assign.getRightOp() instanceof InstanceFieldRef) {
                    InstanceFieldRef ref = (InstanceFieldRef) assign.getRightOp();
                    AllocObject target = resolveUniqueAllocTarget(
                        (Local) ref.getBase(), u, noCallSiteObjects.keySet());
                    if (target != null) {
                        Local scalarLocal = fieldLocals.get(target).get(ref.getField());
                        if (scalarLocal != null) {
                            assign.setRightOp(scalarLocal);
                        }
                    }
                }
            }

            // Mark <init> calls on scalar-replaced objects for removal
            if (stmt.containsInvokeExpr()
                    && stmt.getInvokeExpr() instanceof SpecialInvokeExpr) {
                SpecialInvokeExpr invoke = (SpecialInvokeExpr) stmt.getInvokeExpr();
                if (invoke.getMethod().getName().equals("<init>")) {
                    Local base = (Local) invoke.getBase();
                    AllocObject target = resolveUniqueAllocTarget(
                        base, u, noCallSiteObjects.keySet());
                    if (target != null) {
                        toRemove.add(u);
                    }
                }
            }
        }

        // Step 5: Insert default-value inits, then remove dead statements
        for (Map.Entry<Unit, List<Unit>> entry : toInsertBefore.entrySet()) {
            for (Unit init : entry.getValue()) {
                body.getUnits().insertBefore(init, entry.getKey());
            }
        }
        for (Unit u : toRemove) {
            body.getUnits().remove(u);
        }
    }

    /* =============================================================
     *  Phase 2: Y[callsites] objects — receiver of method calls
     *
     *  For each scalar-replaceable object used as the receiver of method
     *  calls, create a static variant of each callee that takes the
     *  object's fields as parameters.  Then scalar-replace the object
     *  the same way as Phase 1 and rewrite the invokes to use the
     *  static variants.
     *
     *  Pessimistic safety checks:
     *    - The object is ONLY used as the receiver (never passed as arg)
     *    - In the callee, 'this' is ONLY used in field reads
     *      (no writes, no aliasing, no monitor ops, no identity checks,
     *       no passing to other methods, no returning)
     * ============================================================= */

    private void performReceiverCallScalarReplacement(AnalysisState fin) {
        // Step 1: Identify Y[callsites] candidates
        Map<AllocObject, Unit> candidates = new LinkedHashMap<>();
        for (Unit u : body.getUnits()) {
            if (!(u instanceof AssignStmt)) continue;
            AssignStmt assign = (AssignStmt) u;
            if (!(assign.getRightOp() instanceof NewExpr)) continue;

            AllocObject obj = allocObjects.get(u);
            if (obj == null) continue;
            if (fin.escaped.contains(obj)) continue;
            if (fin.modifiedInCalls.contains(obj)) continue;
            if (fin.localPointsToMultiple.contains(obj)) continue;

            Set<Integer> sites = fin.callSites.getOrDefault(obj, Collections.emptySet());
            if (sites.isEmpty()) continue; // already handled in Phase 1

            candidates.put(obj, u);
        }
        if (candidates.isEmpty()) return;

        // Step 2: Validate each candidate
        //  - Every invoke where the object is the receiver must have a safe callee
        //  - The object must NOT be passed as an argument anywhere
        Map<AllocObject, Unit> eligible = new LinkedHashMap<>();

        for (Map.Entry<AllocObject, Unit> entry : candidates.entrySet()) {
            AllocObject obj = entry.getKey();
            boolean ok = true;

            for (Unit u : body.getUnits()) {
                Stmt stmt = (Stmt) u;
                if (!stmt.containsInvokeExpr()) continue;
                InvokeExpr invoke = stmt.getInvokeExpr();

                // Check: is this object passed as an explicit argument? → disqualify
                for (Value arg : invoke.getArgs()) {
                    if (!(arg instanceof Local)) continue;
                    AllocObject target = resolveUniqueAllocTarget(
                        (Local) arg, u, candidates.keySet());
                    if (target != null && target.equals(obj)) { ok = false; break; }
                }
                if (!ok) break;

                // Check: is this object the receiver?
                if (!(invoke instanceof InstanceInvokeExpr)) continue;
                Local base = (Local) ((InstanceInvokeExpr) invoke).getBase();
                AllocObject target = resolveUniqueAllocTarget(base, u, candidates.keySet());
                if (target == null || !target.equals(obj)) continue;
                if (invoke.getMethod().getName().equals("<init>")) continue;

                // Resolve to the exact callee using the allocation type
                NewExpr newExpr = (NewExpr) ((AssignStmt) entry.getValue()).getRightOp();
                SootClass exactType = newExpr.getBaseType().getSootClass();
                try {
                    SootMethod callee = exactType.getMethod(
                        invoke.getMethod().getSubSignature());
                    if (!isCalleeBodySafeForReceiverReplacement(callee)) {
                        ok = false; break;
                    }
                } catch (Exception e) { ok = false; break; }
            }
            if (ok) eligible.put(obj, entry.getValue());
        }
        if (eligible.isEmpty()) return;

        // Step 3: Record var names / class names
        Map<AllocObject, String> varNames = new HashMap<>();
        Map<AllocObject, String> classNames = new HashMap<>();
        for (Map.Entry<AllocObject, Unit> entry : eligible.entrySet()) {
            AssignStmt alloc = (AssignStmt) entry.getValue();
            varNames.put(entry.getKey(), ((Local) alloc.getLeftOp()).getName());
            classNames.put(entry.getKey(),
                ((NewExpr) alloc.getRightOp()).getBaseType().getSootClass().getShortName());
        }

        // Step 4: Collect fields from caller body AND callee bodies, create scalar locals
        Map<AllocObject, Map<SootField, Local>> fieldLocals = new HashMap<>();
        for (AllocObject obj : eligible.keySet())
            fieldLocals.put(obj, new HashMap<>());

        // From caller field accesses
        for (Unit u : body.getUnits()) {
            if (!(u instanceof AssignStmt)) continue;
            AssignStmt assign = (AssignStmt) u;
            collectFieldLocal(assign.getLeftOp(), u, eligible, varNames, classNames, fieldLocals);
            collectFieldLocal(assign.getRightOp(), u, eligible, varNames, classNames, fieldLocals);
        }

        // From callee bodies (fields read on 'this')
        for (Map.Entry<AllocObject, Unit> entry : eligible.entrySet()) {
            AllocObject obj = entry.getKey();
            NewExpr newExpr = (NewExpr) ((AssignStmt) entry.getValue()).getRightOp();
            SootClass exactType = newExpr.getBaseType().getSootClass();

            for (Unit u : body.getUnits()) {
                Stmt stmt = (Stmt) u;
                if (!stmt.containsInvokeExpr()) continue;
                InvokeExpr invoke = stmt.getInvokeExpr();
                if (!(invoke instanceof InstanceInvokeExpr)) continue;
                if (invoke.getMethod().getName().equals("<init>")) continue;
                Local base = (Local) ((InstanceInvokeExpr) invoke).getBase();
                AllocObject target = resolveUniqueAllocTarget(base, u, eligible.keySet());
                if (target == null || !target.equals(obj)) continue;

                SootMethod callee = exactType.getMethod(invoke.getMethod().getSubSignature());
                for (SootField f : getFieldsAccessedOnThis(callee.getActiveBody())) {
                    fieldLocals.get(obj).computeIfAbsent(f, field -> {
                        String name = varNames.get(obj) + "_" + classNames.get(obj)
                                    + "_" + field.getName();
                        Local sl = Jimple.v().newLocal(name, field.getType());
                        body.getLocals().add(sl);
                        return sl;
                    });
                }
            }
        }

        // Step 5: Create static method variants (one per callee, deduplicated)
        Map<SootMethod, SootMethod> staticVariants = new HashMap<>();
        Map<SootMethod, List<SootField>> methodFieldsUsed = new HashMap<>();

        for (Map.Entry<AllocObject, Unit> entry : eligible.entrySet()) {
            AllocObject obj = entry.getKey();
            NewExpr newExpr = (NewExpr) ((AssignStmt) entry.getValue()).getRightOp();
            SootClass exactType = newExpr.getBaseType().getSootClass();

            for (Unit u : body.getUnits()) {
                Stmt stmt = (Stmt) u;
                if (!stmt.containsInvokeExpr()) continue;
                InvokeExpr invoke = stmt.getInvokeExpr();
                if (!(invoke instanceof InstanceInvokeExpr)) continue;
                if (invoke.getMethod().getName().equals("<init>")) continue;
                Local base = (Local) ((InstanceInvokeExpr) invoke).getBase();
                AllocObject target = resolveUniqueAllocTarget(base, u, eligible.keySet());
                if (target == null || !target.equals(obj)) continue;

                SootMethod callee = exactType.getMethod(invoke.getMethod().getSubSignature());
                if (staticVariants.containsKey(callee)) continue;

                List<SootField> fieldsUsed = getFieldsAccessedOnThis(callee.getActiveBody());
                SootMethod sv = createStaticVariant(callee, fieldsUsed);
                if (sv != null) {
                    staticVariants.put(callee, sv);
                    methodFieldsUsed.put(callee, fieldsUsed);
                }
            }
        }

        // Step 6: Rewrite the caller body
        List<Unit> toRemove = new ArrayList<>();
        Map<Unit, List<Unit>> toInsertBefore = new LinkedHashMap<>();

        for (Unit u : body.getUnits()) {
            Stmt stmt = (Stmt) u;

            if (u instanceof AssignStmt) {
                AssignStmt assign = (AssignStmt) u;

                // Replace new with default-value inits
                if (assign.getRightOp() instanceof NewExpr) {
                    AllocObject obj = allocObjects.get(u);
                    if (obj != null && eligible.containsKey(obj)) {
                        List<Unit> inits = new ArrayList<>();
                        for (Local sl : fieldLocals.get(obj).values())
                            inits.add(Jimple.v().newAssignStmt(sl, getDefaultValue(sl.getType())));
                        toInsertBefore.put(u, inits);
                        toRemove.add(u);
                        continue;
                    }
                }

                // Rewrite field stores
                if (assign.getLeftOp() instanceof InstanceFieldRef) {
                    InstanceFieldRef ref = (InstanceFieldRef) assign.getLeftOp();
                    AllocObject target = resolveUniqueAllocTarget(
                        (Local) ref.getBase(), u, eligible.keySet());
                    if (target != null) {
                        Local sl = fieldLocals.get(target).get(ref.getField());
                        if (sl != null) assign.setLeftOp(sl);
                    }
                }

                // Rewrite field loads
                if (assign.getRightOp() instanceof InstanceFieldRef) {
                    InstanceFieldRef ref = (InstanceFieldRef) assign.getRightOp();
                    AllocObject target = resolveUniqueAllocTarget(
                        (Local) ref.getBase(), u, eligible.keySet());
                    if (target != null) {
                        Local sl = fieldLocals.get(target).get(ref.getField());
                        if (sl != null) assign.setRightOp(sl);
                    }
                }
            }

            // Remove <init> calls
            if (stmt.containsInvokeExpr()
                    && stmt.getInvokeExpr() instanceof SpecialInvokeExpr) {
                SpecialInvokeExpr inv = (SpecialInvokeExpr) stmt.getInvokeExpr();
                if (inv.getMethod().getName().equals("<init>")) {
                    AllocObject target = resolveUniqueAllocTarget(
                        (Local) inv.getBase(), u, eligible.keySet());
                    if (target != null) { toRemove.add(u); continue; }
                }
            }

            // Rewrite receiver calls → static invokes
            if (stmt.containsInvokeExpr()
                    && stmt.getInvokeExpr() instanceof InstanceInvokeExpr) {
                InstanceInvokeExpr instInvoke = (InstanceInvokeExpr) stmt.getInvokeExpr();
                if (instInvoke.getMethod().getName().equals("<init>")) continue;
                Local base = (Local) instInvoke.getBase();

                for (AllocObject obj : eligible.keySet()) {
                    AllocObject target = resolveUniqueAllocTarget(base, u, eligible.keySet());
                    if (target == null || !target.equals(obj)) continue;

                    NewExpr newExpr = (NewExpr) ((AssignStmt) eligible.get(obj)).getRightOp();
                    SootClass exactType = newExpr.getBaseType().getSootClass();
                    SootMethod callee = exactType.getMethod(
                        instInvoke.getMethod().getSubSignature());
                    SootMethod sv = staticVariants.get(callee);
                    if (sv == null) continue;

                    List<SootField> fieldsUsed = methodFieldsUsed.get(callee);
                    List<Value> newArgs = new ArrayList<>();
                    for (SootField f : fieldsUsed)
                        newArgs.add(fieldLocals.get(obj).get(f));
                    newArgs.addAll(instInvoke.getArgs());

                    stmt.getInvokeExprBox().setValue(
                        Jimple.v().newStaticInvokeExpr(sv.makeRef(), newArgs));
                    break;
                }
            }
        }

        // Step 7: Apply deferred inserts and removals
        for (Map.Entry<Unit, List<Unit>> e : toInsertBefore.entrySet())
            for (Unit init : e.getValue())
                body.getUnits().insertBefore(init, e.getKey());
        for (Unit u : toRemove)
            body.getUnits().remove(u);
    }

    /**
     * Pessimistic check: in the callee's body, 'this' must ONLY appear
     * as the base of field reads (InstanceFieldRef on RHS).  Any other
     * use — writes, copies, invokes, monitors, returns, identity checks —
     * disqualifies the method.
     */
    private boolean isCalleeBodySafeForReceiverReplacement(SootMethod method) {
        if (!method.isConcrete() || method.isJavaLibraryMethod()) return false;

        Body b = method.getActiveBody();
        Local thisLocal = b.getThisLocal();

        for (Unit u : b.getUnits()) {
            if (u instanceof IdentityStmt) continue;
            Stmt stmt = (Stmt) u;

            if (stmt instanceof AssignStmt) {
                AssignStmt assign = (AssignStmt) stmt;
                // this on LHS (reassignment or field write on this): reject
                if (assign.getLeftOp().equals(thisLocal)) return false;
                if (assign.getLeftOp() instanceof InstanceFieldRef
                        && ((InstanceFieldRef) assign.getLeftOp()).getBase().equals(thisLocal))
                    return false;
                // this on RHS: only OK inside a field read
                if (assign.getRightOp() instanceof InstanceFieldRef) {
                    if (((InstanceFieldRef) assign.getRightOp()).getBase().equals(thisLocal))
                        continue; // field read on this — allowed
                }
                // this copied to another local or used in any other expression
                if (assign.getRightOp().equals(thisLocal)) return false;
                for (ValueBox vb : assign.getRightOp().getUseBoxes())
                    if (vb.getValue().equals(thisLocal)) return false;
            }

            // this as receiver or argument of any invoke: reject
            if (stmt.containsInvokeExpr()) {
                InvokeExpr inv = stmt.getInvokeExpr();
                if (inv instanceof InstanceInvokeExpr
                        && ((InstanceInvokeExpr) inv).getBase().equals(thisLocal))
                    return false;
                for (Value arg : inv.getArgs())
                    if (arg.equals(thisLocal)) return false;
            }

            if (stmt instanceof ReturnStmt && ((ReturnStmt) stmt).getOp().equals(thisLocal))
                return false;
            if (stmt instanceof ThrowStmt && ((ThrowStmt) stmt).getOp().equals(thisLocal))
                return false;
            if (stmt instanceof EnterMonitorStmt
                    && ((EnterMonitorStmt) stmt).getOp().equals(thisLocal))
                return false;
            if (stmt instanceof ExitMonitorStmt
                    && ((ExitMonitorStmt) stmt).getOp().equals(thisLocal))
                return false;
            if (stmt instanceof IfStmt) {
                Value cond = ((IfStmt) stmt).getCondition();
                if (cond instanceof BinopExpr) {
                    BinopExpr bin = (BinopExpr) cond;
                    if (bin.getOp1().equals(thisLocal) || bin.getOp2().equals(thisLocal))
                        return false;
                }
            }
        }
        return true;
    }

    /** Collect the ordered list of fields read on 'this' in a method body. */
    private List<SootField> getFieldsAccessedOnThis(Body b) {
        Local thisLocal = b.getThisLocal();
        List<SootField> result = new ArrayList<>();
        Set<SootField> seen = new LinkedHashSet<>();
        for (Unit u : b.getUnits()) {
            if (!(u instanceof AssignStmt)) continue;
            Value rhs = ((AssignStmt) u).getRightOp();
            if (rhs instanceof InstanceFieldRef) {
                InstanceFieldRef ref = (InstanceFieldRef) rhs;
                if (ref.getBase().equals(thisLocal) && seen.add(ref.getField()))
                    result.add(ref.getField());
            }
        }
        return result;
    }

    /**
     * Create a static copy of {@code original} whose 'this' parameter is
     * replaced by explicit field parameters at the front of the parameter list.
     *
     * Uses {@link Body#importBodyContentsFrom} to clone the body, then:
     *   - Removes the {@code @this} identity statement
     *   - Shifts existing {@code @parameterN} refs by the field count
     *   - Adds {@code @parameterN} identity stmts for each field parameter
     *   - Replaces field reads on 'this' with the corresponding field parameter
     */
    private SootMethod createStaticVariant(SootMethod original, List<SootField> fieldsUsed) {
        if (!original.isConcrete()) return null;

        SootClass cls = original.getDeclaringClass();
        Body origBody = original.getActiveBody();

        // Build parameter types: field types first, then original params
        List<Type> paramTypes = new ArrayList<>();
        for (SootField f : fieldsUsed) paramTypes.add(f.getType());
        paramTypes.addAll(original.getParameterTypes());

        // Use a distinct name to avoid conflicts with the instance method
        // (same subsignature when no fields are accessed on 'this')
        String name = original.getName() + "_scalar";

        // Check for existing method with this exact signature
        try {
            cls.getMethod(name, paramTypes, original.getReturnType());
            return null; // conflict — skip
        } catch (Exception ignored) {}

        SootMethod staticMethod = new SootMethod(
            name, paramTypes, original.getReturnType(),
            Modifier.STATIC | Modifier.PUBLIC);
        cls.addMethod(staticMethod);

        // Clone the body
        JimpleBody newBody = Jimple.v().newBody(staticMethod);
        staticMethod.setActiveBody(newBody);
        newBody.importBodyContentsFrom(origBody);

        // Find 'this' local and its identity stmt in the cloned body
        Local thisLocal = null;
        Unit thisIdentity = null;
        for (Unit u : newBody.getUnits()) {
            if (u instanceof IdentityStmt) {
                IdentityStmt id = (IdentityStmt) u;
                if (id.getRightOp() instanceof ThisRef) {
                    thisLocal = (Local) id.getLeftOp();
                    thisIdentity = u;
                    break;
                }
            }
        }
        if (thisLocal == null) { cls.removeMethod(staticMethod); return null; }

        // Shift existing @parameterN refs by the number of field params
        int fieldCount = fieldsUsed.size();
        for (Unit u : newBody.getUnits()) {
            if (u instanceof IdentityStmt) {
                IdentityStmt id = (IdentityStmt) u;
                if (id.getRightOp() instanceof ParameterRef) {
                    ParameterRef ref = (ParameterRef) id.getRightOp();
                    id.setRightOp(Jimple.v().newParameterRef(
                        ref.getType(), ref.getIndex() + fieldCount));
                }
            }
        }

        // Create field parameter locals + identity stmts
        Map<SootField, Local> fieldParamLocals = new LinkedHashMap<>();
        List<Unit> fieldIdentities = new ArrayList<>();
        int paramIdx = 0;
        for (SootField f : fieldsUsed) {
            Local pl = Jimple.v().newLocal("param_" + f.getName(), f.getType());
            newBody.getLocals().add(pl);
            fieldParamLocals.put(f, pl);
            fieldIdentities.add(Jimple.v().newIdentityStmt(
                pl, Jimple.v().newParameterRef(f.getType(), paramIdx++)));
        }

        // Insert field param identities where @this was, then remove @this
        for (Unit id : fieldIdentities)
            newBody.getUnits().insertBefore(id, thisIdentity);
        newBody.getUnits().remove(thisIdentity);

        // Replace field reads on 'this' with field parameter locals
        for (Unit u : newBody.getUnits()) {
            for (ValueBox vb : u.getUseBoxes()) {
                if (vb.getValue() instanceof InstanceFieldRef) {
                    InstanceFieldRef ref = (InstanceFieldRef) vb.getValue();
                    if (ref.getBase().equals(thisLocal)) {
                        Local fp = fieldParamLocals.get(ref.getField());
                        if (fp != null) vb.setValue(fp);
                    }
                }
            }
        }

        // Remove the 'this' local
        newBody.getLocals().remove(thisLocal);

        return staticMethod;
    }

    /* =============================================================
     *  Shared helpers for scalar replacement
     * ============================================================= */

    /**
     * If {@code value} is an {@link InstanceFieldRef} whose base local points
     * to exactly one object in {@code candidates}, create (or reuse) a scalar
     * local named {@code varName_className_fieldName} and register it.
     */
    private void collectFieldLocal(Value value, Unit unit,
                                   Map<AllocObject, Unit> candidates,
                                   Map<AllocObject, String> varNames,
                                   Map<AllocObject, String> classNames,
                                   Map<AllocObject, Map<SootField, Local>> fieldLocals) {
        if (!(value instanceof InstanceFieldRef)) return;
        InstanceFieldRef ref = (InstanceFieldRef) value;
        Local base = (Local) ref.getBase();

        AllocObject target = resolveUniqueAllocTarget(base, unit, candidates.keySet());
        if (target == null) return;

        SootField field = ref.getField();
        fieldLocals.get(target).computeIfAbsent(field, f -> {
            String name = varNames.get(target) + "_" + classNames.get(target) + "_" + f.getName();
            Local scalarLocal = Jimple.v().newLocal(name, f.getType());
            body.getLocals().add(scalarLocal);
            return scalarLocal;
        });
    }

    /**
     * Check the analysis state before {@code unit}: if {@code base} points to
     * exactly one {@link AllocObject} that is in {@code candidates}, return it.
     */
    private AllocObject resolveUniqueAllocTarget(Local base, Unit unit,
                                                 Set<AllocObject> candidates) {
        AnalysisState flowBefore = getFlowBefore(unit);
        Set<AbstractObject> pts = flowBefore.stack.getOrDefault(base, Collections.emptySet());
        if (pts.size() != 1) return null;

        AbstractObject single = pts.iterator().next();
        if (single instanceof AllocObject && candidates.contains(single))
            return (AllocObject) single;
        return null;
    }

    /**
     * Return the Jimple default value for a type:
     * 0 for int/byte/short/char/boolean, 0L for long, 0.0f/0.0 for float/double,
     * null for reference types.
     */
    private Value getDefaultValue(Type t) {
        if (t instanceof IntType || t instanceof ByteType
                || t instanceof ShortType || t instanceof CharType
                || t instanceof BooleanType)
            return IntConstant.v(0);
        if (t instanceof LongType)    return LongConstant.v(0);
        if (t instanceof FloatType)   return FloatConstant.v(0);
        if (t instanceof DoubleType)  return DoubleConstant.v(0);
        return NullConstant.v();
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
