import java.util.*;
import soot.*;
import soot.jimple.*;

//Generates the methods which now have the changed signatures with scalarized fields instead of object params, and rewrites the method body accordingly.
public class Specializer {

    private final Map<SootMethod, MethodSummary> summaries;
    private final Map<Key, SootMethod> cache = new HashMap<>();
    private final Map<Key, List<SootField>> fieldOrder = new HashMap<>();

    public Specializer(Map<SootMethod, MethodSummary> summaries) {
        this.summaries = summaries;
    }

    public List<SootField> fieldOrderFor(SootMethod m, int scalarizedPos) {

        Key k = new Key(m, Collections.singleton(scalarizedPos));
        List<SootField> cached = fieldOrder.get(k); //If we already generated a specialization, then use that
        if (cached != null) return cached;
        return generateFieldOrder(m, scalarizedPos); //Else compute a new one
    }

    public SootMethod specialize(SootMethod m, SortedSet<Integer> positions) {
        Key key = new Key(m, positions);
        SootMethod existing = cache.get(key);
        if (existing != null) return existing;

        if (!m.isConcrete()) return null;
        MethodSummary s = summaries.get(m);
        if (s == null) return null;

        List<List<SootField>> perPos = new ArrayList<>();
        for (int p : positions) perPos.add(generateFieldOrder(m, p));

        List<Type> newParamTypes = new ArrayList<>();
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

        try {
            SootMethod existingOnClass = declaring.getMethod(name, newParamTypes, m.getReturnType());
            cache.put(key, existingOnClass);
            List<SootField> concat = new ArrayList<>();
            for (List<SootField> p : perPos) concat.addAll(p);
            fieldOrder.put(key, concat);
            for (int p : positions)
                fieldOrder.put(new Key(m, Collections.singleton(p)),generateFieldOrder(m, p));
            return existingOnClass;
        } catch (Exception ignored) { }

        SootMethod spec = new SootMethod(name, newParamTypes, m.getReturnType(),Modifier.STATIC | Modifier.PUBLIC);
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
            fieldOrder.put(new Key(m, Collections.singleton(p)),generateFieldOrder(m, p));
        return spec;
    }

    //rewriting the body to replace field accesses with scalar params, and nested invokes with their specialized versions if needed.
    private void rewriteSpecializedBody(JimpleBody newBody, SootMethod orig,
            SortedSet<Integer> positions, List<List<SootField>> perPos) {

        Map<Integer, Map<SootField, Local>> scalarParamsByPos = new HashMap<>();

        //find all parameter statements
        Local thisLocal = null;
        Map<Integer, Local> paramLocals = new HashMap<>();
        List<IdentityStmt> idStmts = new ArrayList<>();
        for (Unit u : newBody.getUnits()) {
            if (u instanceof IdentityStmt) {
                IdentityStmt id = (IdentityStmt) u;
                if (id.getRightOp() instanceof ThisRef) {
                    thisLocal = (Local) id.getLeftOp(); //@this local
                    idStmts.add(id);
                } else if (id.getRightOp() instanceof ParameterRef) {
                    paramLocals.put(((ParameterRef) id.getRightOp()).getIndex(),(Local) id.getLeftOp()); //parameter locals
                    idStmts.add(id);
                }
            }
        }

        //use the parameter statements to generate new identity statements for scalar params
        List<Unit> newIds = new ArrayList<>();
        int paramCount = orig.getParameterCount() + (orig.isStatic() ? 0 : 1);
        int newParamIdx = 0;
        for (int oldIdx = 0; oldIdx < paramCount; oldIdx++) {
            if (positions.contains(oldIdx)) {
                List<SootField> fields = perPos.get(indexOf(positions, oldIdx));
                Map<SootField, Local> fieldLocals = new LinkedHashMap<>();
                for (SootField f : fields) {
                    Local l = Jimple.v().newLocal("srP" + oldIdx + "_" + f.getName(),f.getType());
                    newBody.getLocals().add(l);
                    newIds.add(Jimple.v().newIdentityStmt(l,Jimple.v().newParameterRef(f.getType(), newParamIdx++)));
                    fieldLocals.put(f, l);
                }
                scalarParamsByPos.put(oldIdx, fieldLocals);
            } else {
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
                newIds.add(Jimple.v().newIdentityStmt(existing,Jimple.v().newParameterRef(tp, newParamIdx++)));
            }
        }

        // Remove old identity stmts, insert new ones at the head.
        for (IdentityStmt id : idStmts) newBody.getUnits().remove(id);
        Unit firstOrig = newBody.getUnits().getFirst();
        for (Unit id : newIds) {
            if (firstOrig == null) newBody.getUnits().add(id);
            else newBody.getUnits().insertBefore(id, firstOrig);
        }

        // Compute alias set for each scalarized position by propagating from the original param local
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

        List<Unit> toRemoveCopies = new ArrayList<>();
        for (Unit u : new ArrayList<>(newBody.getUnits())) {
            Stmt stmt = (Stmt) u;
            if (stmt.containsInvokeExpr()) {
                rewriteNestedInvoke(stmt, aliasesByPos, scalarParamsByPos);
            }

            if (!(u instanceof AssignStmt)) continue;
            AssignStmt a = (AssignStmt) u;
            Value lhs = a.getLeftOp();
            Value rhs = a.getRightOp();

            if (rhs instanceof InstanceFieldRef) {
                InstanceFieldRef ref = (InstanceFieldRef) rhs;
                Integer pos = posOwning(ref.getBase(), aliasesByPos);
                if (pos != null) {
                    Local sl = scalarParamsByPos.get(pos).get(ref.getField());
                    if (sl != null) a.setRightOp(sl);
                }
            }

            if (lhs instanceof Local) {
                for (Set<Local> aliases : aliasesByPos.values()) {
                    if (aliases.contains(lhs)) { toRemoveCopies.add(u); break; }
                }
            }
        }
        for (Unit u : toRemoveCopies) newBody.getUnits().remove(u);
    }

    //Rewriting nested invokes to call the specialized versions if they call the same method with aliases of our scalarized params.
    private void rewriteNestedInvoke(Stmt stmt,
            Map<Integer, Set<Local>> aliasesByPos,
            Map<Integer, Map<SootField, Local>> scalarParamsByPos) {
        InvokeExpr invoke = stmt.getInvokeExpr();

        List<Value> actuals = new ArrayList<>();
        boolean isInstance = invoke instanceof InstanceInvokeExpr;
        if (isInstance) actuals.add(((InstanceInvokeExpr) invoke).getBase());
        actuals.addAll(invoke.getArgs());

        Map<Integer, Integer> innerToOuter = new TreeMap<>();
        for (int i = 0; i < actuals.size(); i++) {
            Value a = actuals.get(i);
            if (!(a instanceof Local)) continue;
            Integer outer = posOwning(a, aliasesByPos);
            if (outer != null) innerToOuter.put(i, outer);
        }
        if (innerToOuter.isEmpty()) return;
        //get callee method
        SootMethod target;
        try { target = invoke.getMethod(); }
        catch (Exception e) { return; }
        //specialize callee on the positions corresponding to the aliases we found
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
                for (SootField f : generateFieldOrder(target, i)) {
                    Local sl = myScalars != null ? myScalars.get(f) : null;
                    if (sl == null) {
                        sl = Jimple.v().newLocal("sr_missing_" + f.getName(), f.getType()); //error , shouldn't happen
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

    //Generate a stable field order for a given method + scalarized position
    private List<SootField> generateFieldOrder(SootMethod m, int pos) {
        MethodSummary s = summaries.get(m);
        Set<SootField> wanted = (s == null)
            ? Collections.emptySet() : s.read(pos);
        if (wanted.isEmpty()) return Collections.emptyList();
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
        SootClass cur = typeClass;
        while (cur != null) {
            for (SootField f : cur.getFields()) {
                if (wanted.contains(f)) ordered.add(f);
            }
            cur = cur.hasSuperclass() ? cur.getSuperclass() : null;
        }
        for (SootField f : wanted) if (!ordered.contains(f)) ordered.add(f);
        return ordered;
    }

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
