import java.util.*;
import soot.*;
import soot.jimple.*;

/**
 * Synthesizes specialized static variants of methods whose selected reference
 * parameters are replaced by one scalar parameter per field the callee reads.
 *
 * Keyed on {@code (method, scalarized-param-positions)}. Two call sites that
 * scalarize the same position(s) of the same method share one specialization —
 * the set of fields passed is determined by the callee's body
 * ({@code readFields}), not by the caller's alloc.
 *
 * Because Phase 2 guarantees every scalarized position has zero modified
 * fields transitively, the specialized method never needs to write back:
 * signature only widens inputs, return type is unchanged.
 */
public class Specializer {

    private final Map<SootMethod, MethodSummary> summaries;
    private final Map<Key, SootMethod> cache = new HashMap<>();
    private final Map<Key, List<SootField>> fieldOrder = new HashMap<>();

    public Specializer(Map<SootMethod, MethodSummary> summaries) {
        this.summaries = summaries;
    }

    /** Public ordering lookup — callers need this to build the call-site arg list
     *  in exactly the order the synthesized signature expects. */
    public List<SootField> fieldOrderFor(SootMethod m, int scalarizedPos) {
        // For the call-site builder, we only need the ordering *per position*.
        // The per-key ordering concatenates the per-position orderings.
        Key k = new Key(m, Collections.singleton(scalarizedPos));
        // If we already synthesized a specialization for exactly this single
        // position, reuse its ordering; otherwise compute from the summary.
        List<SootField> cached = fieldOrder.get(k);
        if (cached != null) return cached;
        return canonicalFieldOrder(m, scalarizedPos);
    }

    /**
     * Get or create the specialized method for {@code (m, positions)}.
     * Returns null if synthesis fails (phantom, missing summary, etc.).
     */
    public SootMethod specialize(SootMethod m, SortedSet<Integer> positions) {
        Key key = new Key(m, positions);
        SootMethod existing = cache.get(key);
        if (existing != null) return existing;

        if (!m.isConcrete()) return null;
        MethodSummary s = summaries.get(m);
        if (s == null) return null;

        // Compute field order per scalarized position, concatenated.
        List<List<SootField>> perPos = new ArrayList<>();
        for (int p : positions) perPos.add(canonicalFieldOrder(m, p));

        // Build new signature.
        List<Type> newParamTypes = new ArrayList<>();
        // Walk the original params in order; replace scalarized positions
        // with their scalar field types, leave others as-is.
        // Note: callee param indexing matches our uniform scheme (0 = this
        // for instance, 0... for static).
        int paramCount = m.getParameterCount() + (m.isStatic() ? 0 : 1);
        int scalarPosIdx = 0;
        Iterator<Integer> posIt = positions.iterator();
        int nextScalarized = posIt.hasNext() ? posIt.next() : -1;
        List<Type> origParamTypes = new ArrayList<>();
        if (!m.isStatic()) origParamTypes.add(m.getDeclaringClass().getType());
        origParamTypes.addAll(m.getParameterTypes());

        for (int i = 0; i < paramCount; i++) {
            if (i == nextScalarized) {
                for (SootField f : perPos.get(scalarPosIdx)) {
                    newParamTypes.add(f.getType());
                }
                scalarPosIdx++;
                nextScalarized = posIt.hasNext() ? posIt.next() : -1;
            } else {
                newParamTypes.add(origParamTypes.get(i));
            }
        }

        String name = m.getName() + "$scalar_" + joinPositions(positions);
        SootClass declaring = m.getDeclaringClass();

        // If we've already created a method with this exact name (e.g., from
        // a previous analysis run on the same Scene), reuse.
        try {
            SootMethod existingOnClass =
                declaring.getMethod(name, newParamTypes, m.getReturnType());
            cache.put(key, existingOnClass);
            // Record concatenated field order.
            List<SootField> concat = new ArrayList<>();
            for (List<SootField> p : perPos) concat.addAll(p);
            fieldOrder.put(key, concat);
            for (int p : positions)
                fieldOrder.put(new Key(m, Collections.singleton(p)),
                               canonicalFieldOrder(m, p));
            return existingOnClass;
        } catch (Exception ignored) { /* need to create */ }

        SootMethod spec = new SootMethod(name, newParamTypes, m.getReturnType(),
            Modifier.STATIC | Modifier.PUBLIC);
        declaring.addMethod(spec);

        JimpleBody newBody = Jimple.v().newBody(spec);
        spec.setActiveBody(newBody);
        newBody.importBodyContentsFrom(m.getActiveBody());

        rewriteSpecializedBody(newBody, m, positions, perPos);

        cache.put(key, spec);
        List<SootField> concat = new ArrayList<>();
        for (List<SootField> p : perPos) concat.addAll(p);
        fieldOrder.put(key, concat);
        for (int p : positions)
            fieldOrder.put(new Key(m, Collections.singleton(p)),
                           canonicalFieldOrder(m, p));
        return spec;
    }

    /* =============================================================
     *  Body rewriting inside the specialized method
     * ============================================================= */

    private void rewriteSpecializedBody(JimpleBody newBody, SootMethod orig,
            SortedSet<Integer> positions, List<List<SootField>> perPos) {
        // Map from scalarized position → map of field → fresh param local.
        Map<Integer, Map<SootField, Local>> scalarParamsByPos = new HashMap<>();

        // Locate the existing @this / @parameterN identity stmts.
        Local thisLocal = null;
        Map<Integer, Local> paramLocals = new HashMap<>();
        List<IdentityStmt> idStmts = new ArrayList<>();
        for (Unit u : newBody.getUnits()) {
            if (u instanceof IdentityStmt) {
                IdentityStmt id = (IdentityStmt) u;
                if (id.getRightOp() instanceof ThisRef) {
                    thisLocal = (Local) id.getLeftOp();
                    idStmts.add(id);
                } else if (id.getRightOp() instanceof ParameterRef) {
                    paramLocals.put(((ParameterRef) id.getRightOp()).getIndex(),
                                    (Local) id.getLeftOp());
                    idStmts.add(id);
                }
            }
        }

        // Rewrite identity stmts into the new signature: build a fresh
        // identity block with the new parameter order.
        List<Unit> newIds = new ArrayList<>();
        int paramCount = orig.getParameterCount() + (orig.isStatic() ? 0 : 1);
        int newParamIdx = 0;
        for (int oldIdx = 0; oldIdx < paramCount; oldIdx++) {
            if (positions.contains(oldIdx)) {
                // Introduce one fresh local per field, identity-bound to
                // @parameterN at the widened indices.
                List<SootField> fields = perPos.get(indexOf(positions, oldIdx));
                Map<SootField, Local> fieldLocals = new LinkedHashMap<>();
                for (SootField f : fields) {
                    Local l = Jimple.v().newLocal("srP" + oldIdx + "_" + f.getName(),
                                                   f.getType());
                    newBody.getLocals().add(l);
                    newIds.add(Jimple.v().newIdentityStmt(l,
                        Jimple.v().newParameterRef(f.getType(), newParamIdx++)));
                    fieldLocals.put(f, l);
                }
                scalarParamsByPos.put(oldIdx, fieldLocals);
            } else {
                // Re-index existing @parameter / @this to new position.
                Local existing;
                Type tp;
                if (oldIdx == 0 && !orig.isStatic()) {
                    existing = thisLocal;
                    tp = orig.getDeclaringClass().getType();
                } else {
                    int origParamIdx = orig.isStatic() ? oldIdx : oldIdx - 1;
                    existing = paramLocals.get(origParamIdx);
                    tp = orig.getParameterType(origParamIdx);
                }
                if (existing == null) { newParamIdx++; continue; }
                newIds.add(Jimple.v().newIdentityStmt(existing,
                    Jimple.v().newParameterRef(tp, newParamIdx++)));
            }
        }

        // Remove old identity stmts, insert new ones at the head.
        for (IdentityStmt id : idStmts) newBody.getUnits().remove(id);
        Unit firstOrig = newBody.getUnits().getFirst();
        for (Unit id : newIds) {
            if (firstOrig == null) newBody.getUnits().add(id);
            else newBody.getUnits().insertBefore(id, firstOrig);
        }

        // Compute alias set for each scalarized position's original "holder" local.
        Map<Integer, Set<Local>> aliasesByPos = new HashMap<>();
        for (int p : positions) {
            Local holder;
            if (p == 0 && !orig.isStatic()) holder = thisLocal;
            else {
                int origParamIdx = orig.isStatic() ? p : p - 1;
                holder = paramLocals.get(origParamIdx);
            }
            if (holder != null) aliasesByPos.put(p, computeAliases(newBody, holder));
        }

        // Rewrite: field accesses → scalar params; nested invokes that
        // touch scalarized holders → specialized static invokes.
        List<Unit> toRemoveCopies = new ArrayList<>();
        for (Unit u : new ArrayList<>(newBody.getUnits())) {
            Stmt stmt = (Stmt) u;

            // Rewrite nested invokes first (they may appear as RHS of assign).
            if (stmt.containsInvokeExpr()) {
                rewriteNestedInvoke(stmt, aliasesByPos, scalarParamsByPos);
            }

            if (!(u instanceof AssignStmt)) continue;
            AssignStmt a = (AssignStmt) u;
            Value lhs = a.getLeftOp();
            Value rhs = a.getRightOp();

            // v = this.f  →  v = scalar_f
            if (rhs instanceof InstanceFieldRef) {
                InstanceFieldRef ref = (InstanceFieldRef) rhs;
                Integer pos = posOwning(ref.getBase(), aliasesByPos);
                if (pos != null) {
                    Local sl = scalarParamsByPos.get(pos).get(ref.getField());
                    if (sl != null) a.setRightOp(sl);
                }
            }

            // Drop plain copies whose lhs aliases a scalarized holder.
            // They carry a reference that no longer exists.
            if (lhs instanceof Local) {
                for (Set<Local> aliases : aliasesByPos.values()) {
                    if (aliases.contains(lhs)) { toRemoveCopies.add(u); break; }
                }
            }
        }
        for (Unit u : toRemoveCopies) newBody.getUnits().remove(u);
    }

    /**
     * Rewrite an invoke inside a specialized body: if any of its receiver/args
     * aliases a scalarized holder in this method, the callee itself needs a
     * specialization for those positions, and the invoke becomes a
     * {@code staticinvoke} of it with our scalar params threaded through.
     */
    private void rewriteNestedInvoke(Stmt stmt,
            Map<Integer, Set<Local>> aliasesByPos,
            Map<Integer, Map<SootField, Local>> scalarParamsByPos) {
        InvokeExpr invoke = stmt.getInvokeExpr();

        List<Value> actuals = new ArrayList<>();
        boolean isInstance = invoke instanceof InstanceInvokeExpr;
        if (isInstance) actuals.add(((InstanceInvokeExpr) invoke).getBase());
        actuals.addAll(invoke.getArgs());

        // inner position → outer position (the position in *our* signature
        // that the inner's actual aliases).
        Map<Integer, Integer> innerToOuter = new TreeMap<>();
        for (int i = 0; i < actuals.size(); i++) {
            Value a = actuals.get(i);
            if (!(a instanceof Local)) continue;
            Integer outer = posOwning(a, aliasesByPos);
            if (outer != null) innerToOuter.put(i, outer);
        }
        if (innerToOuter.isEmpty()) return;

        // Resolve the concrete callee. For scalarized receivers we
        // devirtualize to the declared target (Phase 1 ensured all CHA
        // targets are equally good for our purposes).
        SootMethod target;
        try { target = invoke.getMethod(); }
        catch (Exception e) { return; }

        SortedSet<Integer> innerPositions = new TreeSet<>(innerToOuter.keySet());
        SootMethod innerSpec = specialize(target, innerPositions);
        if (innerSpec == null) return;

        // Build new args using inner's field order per scalarized position,
        // pulling our own scalar params at the matching outer position.
        List<Value> newArgs = new ArrayList<>();
        for (int i = 0; i < actuals.size(); i++) {
            if (innerToOuter.containsKey(i)) {
                int outerPos = innerToOuter.get(i);
                Map<SootField, Local> myScalars = scalarParamsByPos.get(outerPos);
                for (SootField f : canonicalFieldOrder(target, i)) {
                    Local sl = myScalars != null ? myScalars.get(f) : null;
                    if (sl == null) {
                        // Fallback: synthesize a temp local (shouldn't happen
                        // if readFields propagation is consistent).
                        sl = Jimple.v().newLocal("sr_missing_" + f.getName(), f.getType());
                    }
                    newArgs.add(sl);
                }
            } else {
                newArgs.add(actuals.get(i));
            }
        }

        stmt.getInvokeExprBox().setValue(
            Jimple.v().newStaticInvokeExpr(innerSpec.makeRef(), newArgs));
    }

    private Integer posOwning(Value base, Map<Integer, Set<Local>> aliasesByPos) {
        if (!(base instanceof Local)) return null;
        for (Map.Entry<Integer, Set<Local>> e : aliasesByPos.entrySet()) {
            if (e.getValue().contains(base)) return e.getKey();
        }
        return null;
    }

    private static Set<Local> computeAliases(Body body, Local root) {
        Set<Local> aliases = new HashSet<>();
        aliases.add(root);
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

    /* =============================================================
     *  Canonical field ordering
     * ============================================================= */

    /**
     * Canonical ordering of the fields the callee reads at position {@code pos}:
     * iterate the declaring class's field list in declaration order and keep
     * those present in the summary's readFields.
     */
    private List<SootField> canonicalFieldOrder(SootMethod m, int pos) {
        MethodSummary s = summaries.get(m);
        Set<SootField> wanted = (s == null)
            ? Collections.emptySet() : s.read(pos);
        if (wanted.isEmpty()) return Collections.emptyList();
        // Walk the receiver's declared class (for pos 0 of instance methods,
        // that's the declaring class of m; for other positions, the param type).
        SootClass typeClass;
        if (pos == 0 && !m.isStatic()) {
            typeClass = m.getDeclaringClass();
        } else {
            int origParamIdx = m.isStatic() ? pos : pos - 1;
            Type pt = m.getParameterType(origParamIdx);
            if (!(pt instanceof RefType)) return new ArrayList<>(wanted);
            typeClass = ((RefType) pt).getSootClass();
        }
        List<SootField> ordered = new ArrayList<>();
        // Walk class chain from declaring class up to Object for stable order.
        SootClass cur = typeClass;
        while (cur != null) {
            for (SootField f : cur.getFields()) {
                if (wanted.contains(f)) ordered.add(f);
            }
            cur = cur.hasSuperclass() ? cur.getSuperclass() : null;
        }
        // Any wanted field not found on the type (shouldn't happen) — append.
        for (SootField f : wanted) if (!ordered.contains(f)) ordered.add(f);
        return ordered;
    }

    /* =============================================================
     *  Helpers
     * ============================================================= */

    private static int indexOf(SortedSet<Integer> positions, int p) {
        int i = 0;
        for (int q : positions) { if (q == p) return i; i++; }
        return -1;
    }

    private static String joinPositions(SortedSet<Integer> positions) {
        StringJoiner sj = new StringJoiner("_");
        for (int p : positions) sj.add(String.valueOf(p));
        return sj.toString();
    }

    private static final class Key {
        final SootMethod m;
        final SortedSet<Integer> positions;

        Key(SootMethod m, Collection<Integer> positions) {
            this.m = m;
            this.positions = new TreeSet<>(positions);
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return m.equals(k.m) && positions.equals(k.positions);
        }
        @Override public int hashCode() { return m.hashCode() * 31 + positions.hashCode(); }
    }
}
