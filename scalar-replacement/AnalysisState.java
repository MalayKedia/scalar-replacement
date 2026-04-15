import java.util.*;
import soot.Local;

/**
 * Dataflow lattice element for the pointer / escape analysis.
 *
 * Tracks five things at every program point:
 *
 *   stack          — which abstract objects each local variable may point to.
 *   heap           — which abstract objects each (object, field) pair may hold.
 *   escaped        — objects reachable from outside the allocating method.
 *   modified       — objects whose fields have been written (directly or via calls).
 *   modifiedInCalls — subset of {@code modified}: only writes that happened
 *                     through function calls (not direct stores in the current
 *                     method).  Direct stores are fine for scalar replacement;
 *                     only call-mediated writes prevent it.
 *MethodSummary
 * Additionally:
 *   initialized    — locals that have been definitely assigned on every path
 *                     reaching this point.  Used to decide strong vs. weak
 *                     updates for field stores.
 *   callSites      — maps each object to the source-line numbers of call sites
 *                     it has been passed through (informational, printed in the
 *                     output for scalar-replaceable objects).
 */
public class AnalysisState {

    final Map<Local, Set<AbstractObject>> stack          = new HashMap<>();
    final Map<HeapKey, Set<AbstractObject>> heap         = new HashMap<>();
    final Set<AbstractObject> escaped                    = new HashSet<>();
    final Set<AbstractObject> modified                   = new HashSet<>();
    final Set<AbstractObject> modifiedInCalls            = new HashSet<>();
    final Set<Local> initialized                         = new HashSet<>();
    final Map<AbstractObject, Set<Integer>> callSites    = new HashMap<>();
    final Set<AbstractObject> localPointsToMultiple      = new HashSet<>();

    /* ---- Deep copy ---- */

    void copyFrom(AnalysisState src) {
        stack.clear();
        heap.clear();
        escaped.clear();
        modified.clear();
        modifiedInCalls.clear();
        initialized.clear();
        callSites.clear();
        localPointsToMultiple.clear();

        src.stack.forEach((k, v) -> stack.put(k, new HashSet<>(v)));
        src.heap.forEach((k, v)  -> heap.put(k, new HashSet<>(v)));
        escaped.addAll(src.escaped);
        modified.addAll(src.modified);
        modifiedInCalls.addAll(src.modifiedInCalls);
        initialized.addAll(src.initialized);
        localPointsToMultiple.addAll(src.localPointsToMultiple);
        src.callSites.forEach((k, v) -> callSites.put(k, new HashSet<>(v)));
    }

    /* ---- Lattice merge (called at control-flow join points) ---- */

    /**
     * Writes into {@code out} the join of {@code a} and {@code b}.
     *
     * Most sets use union; {@code initialized} uses intersection (a local is
     * definitely initialized only if it was initialized on <em>every</em>
     * incoming path).  For heap keys present on one side but not the other,
     * the missing side contributes {null} (Java default for reference fields).
     */
    static void merge(AnalysisState a, AnalysisState b, AnalysisState out) {
        out.stack.clear();
        out.heap.clear();
        out.escaped.clear();
        out.modified.clear();
        out.modifiedInCalls.clear();
        out.initialized.clear();
        out.callSites.clear();
        out.localPointsToMultiple.clear();

        // stack: union of points-to sets
        Set<Local> allLocals = new HashSet<>(a.stack.keySet());
        allLocals.addAll(b.stack.keySet());
        for (Local l : allLocals) {
            Set<AbstractObject> u = new HashSet<>(a.stack.getOrDefault(l, Collections.emptySet()));
            u.addAll(b.stack.getOrDefault(l, Collections.emptySet()));
            out.stack.put(l, u);
        }

        // heap: union, defaulting missing side to {null}
        Set<HeapKey> allKeys = new HashSet<>(a.heap.keySet());
        allKeys.addAll(b.heap.keySet());
        for (HeapKey k : allKeys) {
            Set<AbstractObject> u = new HashSet<>();
            u.addAll(a.heap.containsKey(k) ? a.heap.get(k) : Set.of(AbstractObject.NULL));
            u.addAll(b.heap.containsKey(k) ? b.heap.get(k) : Set.of(AbstractObject.NULL));
            out.heap.put(k, u);
        }

        // escaped, modified, modifiedInCalls: union
        out.escaped.addAll(a.escaped);               out.escaped.addAll(b.escaped);
        out.modified.addAll(a.modified);              out.modified.addAll(b.modified);
        out.modifiedInCalls.addAll(a.modifiedInCalls); out.modifiedInCalls.addAll(b.modifiedInCalls);
        out.localPointsToMultiple.addAll(a.localPointsToMultiple);
        out.localPointsToMultiple.addAll(b.localPointsToMultiple);

        // initialized: intersection
        out.initialized.addAll(a.initialized);
        out.initialized.retainAll(b.initialized);

        // callSites: union
        Set<AbstractObject> allObjs = new HashSet<>(a.callSites.keySet());
        allObjs.addAll(b.callSites.keySet());
        for (AbstractObject obj : allObjs) {
            Set<Integer> u = new HashSet<>(a.callSites.getOrDefault(obj, Collections.emptySet()));
            u.addAll(b.callSites.getOrDefault(obj, Collections.emptySet()));
            out.callSites.put(obj, u);
        }
    }

    /* ---- Fixpoint convergence ---- */

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof AnalysisState)) return false;
        AnalysisState s = (AnalysisState) o;
        return stack.equals(s.stack)
            && heap.equals(s.heap)
            && escaped.equals(s.escaped)
            && modified.equals(s.modified)
            && modifiedInCalls.equals(s.modifiedInCalls)
            && initialized.equals(s.initialized)
            && callSites.equals(s.callSites)
            && localPointsToMultiple.equals(s.localPointsToMultiple);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stack, heap, escaped, modified,
                            modifiedInCalls, initialized, callSites, localPointsToMultiple);
    }
}
