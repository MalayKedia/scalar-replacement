import java.util.*;
import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.callgraph.*;

/**
 * Transforms a method body by performing scalar replacement on objects
 * identified as scalar-replaceable by {@link PointerAnalysis}.
 *
 * Two phases:
 *   Phase 1 — Y[] objects (no call sites): replace field accesses with
 *             scalar locals, remove the allocation and constructor call.
 *   Phase 2 — Y[callsites] objects used only as receiver: create static
 *             method variants that take field values as parameters, then
 *             scalar-replace the object and rewrite invokes.
 */
public class ScalarReplacementTransformer {

    private final Body body;
    private final Map<Unit, AllocObject> allocObjects;
    private final PointerAnalysis analysis; // for getFlowBefore()
    private final CallGraph cg;

    public ScalarReplacementTransformer(Body body,
                                        Map<Unit, AllocObject> allocObjects,
                                        PointerAnalysis analysis,
                                        CallGraph cg) {
        this.body         = body;
        this.allocObjects = allocObjects;
        this.analysis     = analysis;
        this.cg           = cg;
    }

    /** Run all phases of scalar replacement. */
    void transform() {
        AnalysisState fin = analysis.getFinalState();
        performNoCallSiteScalarReplacement(fin);
        performReceiverCallScalarReplacement(fin);
        performArgumentCallScalarReplacement(fin);
    }

    /* =============================================================
     *  Phase 1: Y[] objects (no call sites)
     * ============================================================= */

    private void performNoCallSiteScalarReplacement(AnalysisState fin) {
        Map<AllocObject, Unit> targets = new LinkedHashMap<>();
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
            if (!sites.isEmpty()) continue;

            targets.put(obj, u);
        }
        if (targets.isEmpty()) return;

        Map<AllocObject, String> varNames = new HashMap<>();
        Map<AllocObject, String> classNames = new HashMap<>();
        for (Map.Entry<AllocObject, Unit> e : targets.entrySet()) {
            AssignStmt alloc = (AssignStmt) e.getValue();
            varNames.put(e.getKey(), ((Local) alloc.getLeftOp()).getName());
            classNames.put(e.getKey(),
                ((NewExpr) alloc.getRightOp()).getBaseType().getSootClass().getShortName());
        }

        Map<AllocObject, Map<SootField, Local>> fieldLocals = new HashMap<>();
        for (AllocObject obj : targets.keySet()) fieldLocals.put(obj, new HashMap<>());

        for (Unit u : body.getUnits()) {
            if (!(u instanceof AssignStmt)) continue;
            AssignStmt assign = (AssignStmt) u;
            collectFieldLocal(assign.getLeftOp(), u, targets, varNames, classNames, fieldLocals);
            collectFieldLocal(assign.getRightOp(), u, targets, varNames, classNames, fieldLocals);
        }

        rewriteBody(targets, fieldLocals);
    }

    /* =============================================================
     *  Phase 2: Y[callsites] objects — receiver of method calls
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
            if (sites.isEmpty()) continue;

            candidates.put(obj, u);
        }
        if (candidates.isEmpty()) return;

        // Step 2: Validate — every invoke where the object is the receiver must
        //         have a safe callee; the object must NOT be passed as an argument.
        Map<AllocObject, Unit> eligible = new LinkedHashMap<>();
        for (Map.Entry<AllocObject, Unit> entry : candidates.entrySet()) {
            AllocObject obj = entry.getKey();
            boolean ok = true;

            for (Unit u : body.getUnits()) {
                Stmt stmt = (Stmt) u;
                if (!stmt.containsInvokeExpr()) continue;
                InvokeExpr invoke = stmt.getInvokeExpr();

                // Disqualify if passed as an explicit argument
                for (Value arg : invoke.getArgs()) {
                    if (!(arg instanceof Local)) continue;
                    AllocObject target = resolveUniqueAllocTarget(
                        (Local) arg, u, candidates.keySet());
                    if (target != null && target.equals(obj)) { ok = false; break; }
                }
                if (!ok) break;

                // Check receiver invokes
                if (!(invoke instanceof InstanceInvokeExpr)) continue;
                Local base = (Local) ((InstanceInvokeExpr) invoke).getBase();
                AllocObject target = resolveUniqueAllocTarget(base, u, candidates.keySet());
                if (target == null || !target.equals(obj)) continue;
                if (invoke.getMethod().getName().equals("<init>")) continue;

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

        // Step 3: Record var / class names
        Map<AllocObject, String> varNames = new HashMap<>();
        Map<AllocObject, String> classNames = new HashMap<>();
        for (Map.Entry<AllocObject, Unit> e : eligible.entrySet()) {
            AssignStmt alloc = (AssignStmt) e.getValue();
            varNames.put(e.getKey(), ((Local) alloc.getLeftOp()).getName());
            classNames.put(e.getKey(),
                ((NewExpr) alloc.getRightOp()).getBaseType().getSootClass().getShortName());
        }

        // Step 4: Collect fields from caller AND callee bodies
        Map<AllocObject, Map<SootField, Local>> fieldLocals = new HashMap<>();
        for (AllocObject obj : eligible.keySet()) fieldLocals.put(obj, new HashMap<>());

        for (Unit u : body.getUnits()) {
            if (!(u instanceof AssignStmt)) continue;
            AssignStmt assign = (AssignStmt) u;
            collectFieldLocal(assign.getLeftOp(), u, eligible, varNames, classNames, fieldLocals);
            collectFieldLocal(assign.getRightOp(), u, eligible, varNames, classNames, fieldLocals);
        }

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

        // Step 5: Create static method variants (deduplicated)
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

        // Step 6: Rewrite body (field accesses + new/init removal + invoke rewriting)
        List<Unit> toRemove = new ArrayList<>();
        Map<Unit, List<Unit>> toInsertBefore = new LinkedHashMap<>();

        for (Unit u : body.getUnits()) {
            Stmt stmt = (Stmt) u;

            if (u instanceof AssignStmt) {
                AssignStmt assign = (AssignStmt) u;

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

                if (assign.getLeftOp() instanceof InstanceFieldRef) {
                    InstanceFieldRef ref = (InstanceFieldRef) assign.getLeftOp();
                    AllocObject target = resolveUniqueAllocTarget(
                        (Local) ref.getBase(), u, eligible.keySet());
                    if (target != null) {
                        Local sl = fieldLocals.get(target).get(ref.getField());
                        if (sl != null) assign.setLeftOp(sl);
                    }
                }

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

        for (Map.Entry<Unit, List<Unit>> e : toInsertBefore.entrySet())
            for (Unit init : e.getValue())
                body.getUnits().insertBefore(init, e.getKey());
        for (Unit u : toRemove)
            body.getUnits().remove(u);
    }

    /* =============================================================
     *  Shared rewrite logic for Phase 1
     * ============================================================= */

    private void rewriteBody(Map<AllocObject, Unit> targets,
                             Map<AllocObject, Map<SootField, Local>> fieldLocals) {
        List<Unit> toRemove = new ArrayList<>();
        Map<Unit, List<Unit>> toInsertBefore = new LinkedHashMap<>();

        for (Unit u : body.getUnits()) {
            Stmt stmt = (Stmt) u;

            if (u instanceof AssignStmt) {
                AssignStmt assign = (AssignStmt) u;

                if (assign.getRightOp() instanceof NewExpr) {
                    AllocObject obj = allocObjects.get(u);
                    if (obj != null && targets.containsKey(obj)) {
                        Map<SootField, Local> locals = fieldLocals.get(obj);
                        List<Unit> inits = new ArrayList<>();
                        for (Local sl : locals.values())
                            inits.add(Jimple.v().newAssignStmt(sl, getDefaultValue(sl.getType())));
                        toInsertBefore.put(u, inits);
                        toRemove.add(u);
                        continue;
                    }
                }

                if (assign.getLeftOp() instanceof InstanceFieldRef) {
                    InstanceFieldRef ref = (InstanceFieldRef) assign.getLeftOp();
                    AllocObject target = resolveUniqueAllocTarget(
                        (Local) ref.getBase(), u, targets.keySet());
                    if (target != null) {
                        Local sl = fieldLocals.get(target).get(ref.getField());
                        if (sl != null) assign.setLeftOp(sl);
                    }
                }

                if (assign.getRightOp() instanceof InstanceFieldRef) {
                    InstanceFieldRef ref = (InstanceFieldRef) assign.getRightOp();
                    AllocObject target = resolveUniqueAllocTarget(
                        (Local) ref.getBase(), u, targets.keySet());
                    if (target != null) {
                        Local sl = fieldLocals.get(target).get(ref.getField());
                        if (sl != null) assign.setRightOp(sl);
                    }
                }
            }

            if (stmt.containsInvokeExpr()
                    && stmt.getInvokeExpr() instanceof SpecialInvokeExpr) {
                SpecialInvokeExpr inv = (SpecialInvokeExpr) stmt.getInvokeExpr();
                if (inv.getMethod().getName().equals("<init>")) {
                    AllocObject target = resolveUniqueAllocTarget(
                        (Local) inv.getBase(), u, targets.keySet());
                    if (target != null) toRemove.add(u);
                }
            }
        }

        for (Map.Entry<Unit, List<Unit>> e : toInsertBefore.entrySet())
            for (Unit init : e.getValue())
                body.getUnits().insertBefore(init, e.getKey());
        for (Unit u : toRemove)
            body.getUnits().remove(u);
    }

    /* =============================================================
     *  Phase 3: Y[callsites] objects passed as arguments
     *
     *  For each scalar-replaceable object passed as an argument (not
     *  receiver) to method calls, create a static variant of the callee
     *  that takes the object's fields instead of the object reference.
     *
     *  One argument is replaced per pass.  Re-running analysis on the
     *  modified Jimple can replace further arguments.
     *
     *  Naming: originalName_paramIdx_field1_field2_...
     * ============================================================= */

    private void performArgumentCallScalarReplacement(AnalysisState fin) {
        // Step 1: Find Y[callsites] objects still in the body (not handled by Phase 1/2)
        Map<AllocObject, Unit> candidates = new LinkedHashMap<>();
        for (Unit u : body.getUnits()) {
            if (!(u instanceof AssignStmt)) continue;
            if (!(((AssignStmt) u).getRightOp() instanceof NewExpr)) continue;

            AllocObject obj = allocObjects.get(u);
            if (obj == null) continue;
            if (fin.escaped.contains(obj)) continue;
            if (fin.modifiedInCalls.contains(obj)) continue;
            if (fin.localPointsToMultiple.contains(obj)) continue;

            Set<Integer> sites = fin.callSites.getOrDefault(obj, Collections.emptySet());
            if (sites.isEmpty()) continue;

            candidates.put(obj, u);
        }
        if (candidates.isEmpty()) return;

        // Step 2: Validate each candidate
        //  - Object must only appear as an argument (never as receiver except <init>)
        //  - At each resolvable call site, the callee must be safe for the param position
        //  - Call sites already rewritten by Phase 2 (no call graph edges) are skipped
        Map<AllocObject, Unit> eligible = new LinkedHashMap<>();

        for (Map.Entry<AllocObject, Unit> entry : candidates.entrySet()) {
            AllocObject obj = entry.getKey();
            boolean ok = true;
            boolean hasArgCallSite = false;

            for (Unit u : body.getUnits()) {
                Stmt stmt = (Stmt) u;
                if (!stmt.containsInvokeExpr()) continue;
                InvokeExpr invoke = stmt.getInvokeExpr();

                // Reject if used as receiver (non-<init>)
                if (invoke instanceof InstanceInvokeExpr) {
                    Local base = (Local) ((InstanceInvokeExpr) invoke).getBase();
                    AllocObject target = resolveUniqueAllocTarget(base, u, candidates.keySet());
                    if (target != null && target.equals(obj)
                            && !invoke.getMethod().getName().equals("<init>")) {
                        ok = false; break;
                    }
                }

                // Check each argument position
                for (int i = 0; i < invoke.getArgs().size(); i++) {
                    Value arg = invoke.getArgs().get(i);
                    if (!(arg instanceof Local)) continue;
                    AllocObject target = resolveUniqueAllocTarget(
                        (Local) arg, u, candidates.keySet());
                    if (target == null || !target.equals(obj)) continue;

                    // Skip call sites already rewritten by Phase 2
                    SootMethod callee = resolveUniqueCallee(stmt);
                    if (callee == null) continue;

                    if (!isParamSafeForScalarReplacement(callee, i)) {
                        ok = false; break;
                    }
                    hasArgCallSite = true;
                }
                if (!ok) break;
            }
            if (ok && hasArgCallSite) eligible.put(obj, entry.getValue());
        }
        if (eligible.isEmpty()) return;

        // Step 3: Record var / class names
        Map<AllocObject, String> varNames = new HashMap<>();
        Map<AllocObject, String> classNames = new HashMap<>();
        for (Map.Entry<AllocObject, Unit> e : eligible.entrySet()) {
            AssignStmt alloc = (AssignStmt) e.getValue();
            varNames.put(e.getKey(), ((Local) alloc.getLeftOp()).getName());
            classNames.put(e.getKey(),
                ((NewExpr) alloc.getRightOp()).getBaseType().getSootClass().getShortName());
        }

        // Step 4: Collect fields from caller + callee bodies
        Map<AllocObject, Map<SootField, Local>> fieldLocals = new HashMap<>();
        for (AllocObject obj : eligible.keySet()) fieldLocals.put(obj, new HashMap<>());

        // From caller field accesses
        for (Unit u : body.getUnits()) {
            if (!(u instanceof AssignStmt)) continue;
            AssignStmt assign = (AssignStmt) u;
            collectFieldLocal(assign.getLeftOp(), u, eligible, varNames, classNames, fieldLocals);
            collectFieldLocal(assign.getRightOp(), u, eligible, varNames, classNames, fieldLocals);
        }

        // From callee bodies (fields read on the parameter)
        for (AllocObject obj : eligible.keySet()) {
            for (Unit u : body.getUnits()) {
                Stmt stmt = (Stmt) u;
                if (!stmt.containsInvokeExpr()) continue;
                InvokeExpr invoke = stmt.getInvokeExpr();

                for (int i = 0; i < invoke.getArgs().size(); i++) {
                    Value arg = invoke.getArgs().get(i);
                    if (!(arg instanceof Local)) continue;
                    AllocObject target = resolveUniqueAllocTarget(
                        (Local) arg, u, eligible.keySet());
                    if (target == null || !target.equals(obj)) continue;

                    SootMethod callee = resolveUniqueCallee(stmt);
                    if (callee == null) continue;

                    for (SootField f : getFieldsAccessedOnParam(callee.getActiveBody(), i)) {
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
        }

        // Step 5: Create argument method variants (deduplicated by callee+paramIdx)
        //  Key: "className.methodSubSig@paramIdx"
        Map<String, SootMethod> argVariants = new HashMap<>();
        Map<String, List<SootField>> argVariantFields = new HashMap<>();

        for (AllocObject obj : eligible.keySet()) {
            for (Unit u : body.getUnits()) {
                Stmt stmt = (Stmt) u;
                if (!stmt.containsInvokeExpr()) continue;
                InvokeExpr invoke = stmt.getInvokeExpr();

                for (int i = 0; i < invoke.getArgs().size(); i++) {
                    Value arg = invoke.getArgs().get(i);
                    if (!(arg instanceof Local)) continue;
                    AllocObject target = resolveUniqueAllocTarget(
                        (Local) arg, u, eligible.keySet());
                    if (target == null || !target.equals(obj)) continue;

                    SootMethod callee = resolveUniqueCallee(stmt);
                    if (callee == null) continue;

                    String key = callee.getSignature() + "@" + i;
                    if (argVariants.containsKey(key)) continue;

                    List<SootField> fieldsUsed = getFieldsAccessedOnParam(
                        callee.getActiveBody(), i);
                    SootMethod sv = createArgumentVariant(callee, i, fieldsUsed);
                    if (sv != null) {
                        argVariants.put(key, sv);
                        argVariantFields.put(key, fieldsUsed);
                    }
                }
            }
        }

        // Step 6: Rewrite body
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

            // Rewrite argument calls → static invoke with fields replacing the arg
            if (stmt.containsInvokeExpr()) {
                InvokeExpr invoke = stmt.getInvokeExpr();
                if (invoke.getMethod().getName().equals("<init>")) continue;

                // Find the first eligible argument in this invoke
                for (int i = 0; i < invoke.getArgs().size(); i++) {
                    Value arg = invoke.getArgs().get(i);
                    if (!(arg instanceof Local)) continue;
                    AllocObject target = resolveUniqueAllocTarget(
                        (Local) arg, u, eligible.keySet());
                    if (target == null) continue;

                    SootMethod callee = resolveUniqueCallee(stmt);
                    if (callee == null) continue;

                    String key = callee.getSignature() + "@" + i;
                    SootMethod sv = argVariants.get(key);
                    if (sv == null) continue;

                    List<SootField> fieldsUsed = argVariantFields.get(key);

                    // Build new args: keep all original args, but replace
                    // the one at position i with the field scalar locals
                    List<Value> newArgs = new ArrayList<>();

                    // If original was instance method, pass receiver as first arg
                    if (!callee.isStatic() && invoke instanceof InstanceInvokeExpr) {
                        newArgs.add(((InstanceInvokeExpr) invoke).getBase());
                    }

                    for (int j = 0; j < invoke.getArgs().size(); j++) {
                        if (j == i) {
                            for (SootField f : fieldsUsed)
                                newArgs.add(fieldLocals.get(target).get(f));
                        } else {
                            newArgs.add(invoke.getArgs().get(j));
                        }
                    }

                    stmt.getInvokeExprBox().setValue(
                        Jimple.v().newStaticInvokeExpr(sv.makeRef(), newArgs));
                    break; // one argument at a time per invoke
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

    /* =============================================================
     *  Callee safety check + static variant creation
     * ============================================================= */

    /** Pessimistic check: 'this' must ONLY appear as the base of field reads. */
    private boolean isCalleeBodySafeForReceiverReplacement(SootMethod method) {
        if (!method.isConcrete() || method.isJavaLibraryMethod()) return false;
        return isLocalSafeForFieldReadOnly(method.getActiveBody(), method.getActiveBody().getThisLocal());
    }

    /** Collect the ordered list of fields read on 'this' in a method body. */
    private List<SootField> getFieldsAccessedOnThis(Body b) {
        return getFieldsAccessedOnLocal(b, b.getThisLocal());
    }

    /**
     * Create a static copy of {@code original} whose 'this' is replaced by
     * explicit field parameters at the front of the parameter list.
     */
    private SootMethod createStaticVariant(SootMethod original, List<SootField> fieldsUsed) {
        if (!original.isConcrete()) return null;

        SootClass cls = original.getDeclaringClass();
        Body origBody = original.getActiveBody();

        List<Type> paramTypes = new ArrayList<>();
        for (SootField f : fieldsUsed) paramTypes.add(f.getType());
        paramTypes.addAll(original.getParameterTypes());

        String name = original.getName() + "_scalar";
        try {
            cls.getMethod(name, paramTypes, original.getReturnType());
            return null;
        } catch (Exception ignored) {}

        SootMethod staticMethod = new SootMethod(
            name, paramTypes, original.getReturnType(),
            Modifier.STATIC | Modifier.PUBLIC);
        cls.addMethod(staticMethod);

        JimpleBody newBody = Jimple.v().newBody(staticMethod);
        staticMethod.setActiveBody(newBody);
        newBody.importBodyContentsFrom(origBody);

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

        for (Unit id : fieldIdentities)
            newBody.getUnits().insertBefore(id, thisIdentity);
        newBody.getUnits().remove(thisIdentity);

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

        newBody.getLocals().remove(thisLocal);
        return staticMethod;
    }

    /**
     * Pessimistic check: parameter at {@code paramIdx} (0-based in the
     * explicit parameter list) must ONLY appear as the base of field reads.
     */
    private boolean isParamSafeForScalarReplacement(SootMethod method, int paramIdx) {
        if (!method.isConcrete() || method.isJavaLibraryMethod()) return false;

        Body b = method.getActiveBody();
        if (paramIdx >= method.getParameterCount()) return false;
        Local paramLocal = b.getParameterLocal(paramIdx);

        return isLocalSafeForFieldReadOnly(b, paramLocal);
    }

    /** Collect fields read on an explicit parameter in a method body. */
    private List<SootField> getFieldsAccessedOnParam(Body b, int paramIdx) {
        Local paramLocal = b.getParameterLocal(paramIdx);
        return getFieldsAccessedOnLocal(b, paramLocal);
    }

    /**
     * Create a static variant of {@code original} where parameter at
     * {@code paramIdx} is replaced by its field values.
     *
     * If the original is an instance method, the receiver becomes an
     * explicit first parameter (same as Phase 2).
     *
     * Naming: {@code originalName_paramIdx_field1_field2_...}
     */
    private SootMethod createArgumentVariant(SootMethod original, int paramIdx,
                                             List<SootField> fieldsUsed) {
        if (!original.isConcrete()) return null;

        SootClass cls = original.getDeclaringClass();
        Body origBody = original.getActiveBody();
        boolean isInstance = !original.isStatic();

        // Build new parameter list:
        //   [receiver (if instance)] + orig params with paramIdx replaced by fields
        List<Type> paramTypes = new ArrayList<>();
        if (isInstance) paramTypes.add(cls.getType());
        for (int i = 0; i < original.getParameterCount(); i++) {
            if (i == paramIdx) {
                for (SootField f : fieldsUsed) paramTypes.add(f.getType());
            } else {
                paramTypes.add(original.getParameterType(i));
            }
        }

        // Name: originalName_paramIdx_field1_field2
        StringBuilder sb = new StringBuilder(original.getName());
        sb.append("_").append(paramIdx);
        for (SootField f : fieldsUsed) sb.append("_").append(f.getName());
        String name = sb.toString();

        try {
            cls.getMethod(name, paramTypes, original.getReturnType());
            return null; // conflict
        } catch (Exception ignored) {}

        SootMethod variant = new SootMethod(
            name, paramTypes, original.getReturnType(),
            Modifier.STATIC | Modifier.PUBLIC);
        cls.addMethod(variant);

        JimpleBody newBody = Jimple.v().newBody(variant);
        variant.setActiveBody(newBody);
        newBody.importBodyContentsFrom(origBody);

        // Find the parameter local and @this in the cloned body
        Local targetParamLocal = null;
        Unit targetParamIdentity = null;
        Local thisLocal = null;
        Unit thisIdentity = null;

        for (Unit u : newBody.getUnits()) {
            if (!(u instanceof IdentityStmt)) continue;
            IdentityStmt id = (IdentityStmt) u;
            if (id.getRightOp() instanceof ThisRef) {
                thisLocal = (Local) id.getLeftOp();
                thisIdentity = u;
            }
            if (id.getRightOp() instanceof ParameterRef) {
                ParameterRef ref = (ParameterRef) id.getRightOp();
                if (ref.getIndex() == paramIdx) {
                    targetParamLocal = (Local) id.getLeftOp();
                    targetParamIdentity = u;
                }
            }
        }
        if (targetParamLocal == null) { cls.removeMethod(variant); return null; }

        // Compute new parameter indices.
        // Instance method layout: @this → @param0, then each orig @paramI shifts.
        //   orig @paramI (I < paramIdx)  → @param(I + 1)
        //   orig @paramIdx               → replaced by field params @param(paramIdx+1) .. @param(paramIdx+K)
        //   orig @paramI (I > paramIdx)  → @param(I + K)   where K = fieldsUsed.size()
        // Static method layout: no @this shift.
        //   orig @paramI (I < paramIdx)  → @paramI
        //   orig @paramIdx               → replaced by field params
        //   orig @paramI (I > paramIdx)  → @param(I + K - 1)

        int fieldCount = fieldsUsed.size();
        int recvShift = isInstance ? 1 : 0;

        // Rewrite all existing @parameterN identity stmts (skip the one being replaced)
        for (Unit u : newBody.getUnits()) {
            if (!(u instanceof IdentityStmt)) continue;
            IdentityStmt id = (IdentityStmt) u;
            if (id.getRightOp() instanceof ParameterRef) {
                ParameterRef ref = (ParameterRef) id.getRightOp();
                int oldIdx = ref.getIndex();
                int newIdx;
                if (oldIdx < paramIdx)      newIdx = oldIdx + recvShift;
                else if (oldIdx == paramIdx) continue; // handled below
                else                        newIdx = oldIdx + recvShift + fieldCount - 1;
                id.setRightOp(Jimple.v().newParameterRef(ref.getType(), newIdx));
            }
        }

        // If instance, convert @this → @parameter0
        if (isInstance && thisIdentity != null) {
            ((IdentityStmt) thisIdentity).setRightOp(
                Jimple.v().newParameterRef(cls.getType(), 0));
        }

        // Create field parameter locals + identity stmts, insert at replaced param's spot
        Map<SootField, Local> fieldParamLocals = new LinkedHashMap<>();
        List<Unit> fieldIdentities = new ArrayList<>();
        int fpIdx = paramIdx + recvShift;
        for (SootField f : fieldsUsed) {
            Local pl = Jimple.v().newLocal("param_" + f.getName(), f.getType());
            newBody.getLocals().add(pl);
            fieldParamLocals.put(f, pl);
            fieldIdentities.add(Jimple.v().newIdentityStmt(
                pl, Jimple.v().newParameterRef(f.getType(), fpIdx++)));
        }

        for (Unit id : fieldIdentities)
            newBody.getUnits().insertBefore(id, targetParamIdentity);
        newBody.getUnits().remove(targetParamIdentity);

        // Replace field reads on the old parameter local with field param locals
        for (Unit u : newBody.getUnits()) {
            for (ValueBox vb : u.getUseBoxes()) {
                if (vb.getValue() instanceof InstanceFieldRef) {
                    InstanceFieldRef ref = (InstanceFieldRef) vb.getValue();
                    if (ref.getBase().equals(targetParamLocal)) {
                        Local fp = fieldParamLocals.get(ref.getField());
                        if (fp != null) vb.setValue(fp);
                    }
                }
            }
        }

        // Remove the old parameter local
        newBody.getLocals().remove(targetParamLocal);

        return variant;
    }

    /**
     * Resolve the invoke at {@code stmt} to exactly one callee via the
     * call graph.  Returns null if there are zero or multiple targets.
     */
    private SootMethod resolveUniqueCallee(Stmt stmt) {
        Iterator<Edge> edges = cg.edgesOutOf(stmt);
        if (!edges.hasNext()) return null;
        SootMethod callee = edges.next().tgt();
        if (edges.hasNext()) return null; // multiple targets
        if (!callee.isConcrete() || callee.isJavaLibraryMethod()) return null;
        return callee;
    }

    /* =============================================================
     *  Shared safety / field-collection helpers
     * ============================================================= */

    /**
     * Pessimistic check: {@code local} must ONLY appear as the base of
     * field reads ({@code InstanceFieldRef} on RHS of assignments).
     * Any other use — writes, copies, invokes, monitors, returns,
     * identity comparisons — returns false.
     */
    private boolean isLocalSafeForFieldReadOnly(Body b, Local local) {
        for (Unit u : b.getUnits()) {
            if (u instanceof IdentityStmt) continue;
            Stmt stmt = (Stmt) u;

            if (stmt instanceof AssignStmt) {
                AssignStmt assign = (AssignStmt) stmt;
                if (assign.getLeftOp().equals(local)) return false;
                if (assign.getLeftOp() instanceof InstanceFieldRef
                        && ((InstanceFieldRef) assign.getLeftOp()).getBase().equals(local))
                    return false;
                if (assign.getRightOp() instanceof InstanceFieldRef
                        && ((InstanceFieldRef) assign.getRightOp()).getBase().equals(local))
                    continue; // field read — allowed
                if (assign.getRightOp().equals(local)) return false;
                for (ValueBox vb : assign.getRightOp().getUseBoxes())
                    if (vb.getValue().equals(local)) return false;
            }

            if (stmt.containsInvokeExpr()) {
                InvokeExpr inv = stmt.getInvokeExpr();
                if (inv instanceof InstanceInvokeExpr
                        && ((InstanceInvokeExpr) inv).getBase().equals(local))
                    return false;
                for (Value arg : inv.getArgs())
                    if (arg.equals(local)) return false;
            }

            if (stmt instanceof ReturnStmt && ((ReturnStmt) stmt).getOp().equals(local))
                return false;
            if (stmt instanceof ThrowStmt && ((ThrowStmt) stmt).getOp().equals(local))
                return false;
            if (stmt instanceof EnterMonitorStmt
                    && ((EnterMonitorStmt) stmt).getOp().equals(local))
                return false;
            if (stmt instanceof ExitMonitorStmt
                    && ((ExitMonitorStmt) stmt).getOp().equals(local))
                return false;
            if (stmt instanceof IfStmt) {
                Value cond = ((IfStmt) stmt).getCondition();
                if (cond instanceof BinopExpr) {
                    BinopExpr bin = (BinopExpr) cond;
                    if (bin.getOp1().equals(local) || bin.getOp2().equals(local))
                        return false;
                }
            }
        }
        return true;
    }

    /** Collect the ordered list of fields read on a given local in a body. */
    private List<SootField> getFieldsAccessedOnLocal(Body b, Local local) {
        List<SootField> result = new ArrayList<>();
        Set<SootField> seen = new LinkedHashSet<>();
        for (Unit u : b.getUnits()) {
            if (!(u instanceof AssignStmt)) continue;
            Value rhs = ((AssignStmt) u).getRightOp();
            if (rhs instanceof InstanceFieldRef) {
                InstanceFieldRef ref = (InstanceFieldRef) rhs;
                if (ref.getBase().equals(local) && seen.add(ref.getField()))
                    result.add(ref.getField());
            }
        }
        return result;
    }

    /* =============================================================
     *  Helpers
     * ============================================================= */

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

    private AllocObject resolveUniqueAllocTarget(Local base, Unit unit,
                                                 Set<AllocObject> candidates) {
        AnalysisState flowBefore = analysis.getFlowBefore(unit);
        Set<AbstractObject> pts = flowBefore.stack.getOrDefault(base, Collections.emptySet());
        if (pts.size() != 1) return null;

        AbstractObject single = pts.iterator().next();
        if (single instanceof AllocObject && candidates.contains(single))
            return (AllocObject) single;
        return null;
    }

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
}
