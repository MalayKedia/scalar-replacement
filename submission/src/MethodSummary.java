import java.util.*;
import soot.SootField;
import soot.SootMethod;
import soot.Unit;

/**
 * Phase-1 summary of a method, indexed by parameter.
 *
 * Param indices are uniform: for instance methods 0 = this and 1..k = explicit
 * params; for static methods 0..k-1 = explicit params (no receiver).
 *
 * "Good" is the scalar-replacement contract for a single param position:
 * the reference passed at that position does not escape, is never used for
 * identity, and is only forwarded to callees whose corresponding param is
 * also good. A caller that passes a scalar-replaceable object at a good
 * position can safely rewrite the call via specialization.
 */
public class MethodSummary {

    final int paramCount;

    /** Params that satisfy the contract. Derived from the bad sets. */
    final Set<Integer> goodParams          = new HashSet<>();

    /** Param ref exposed beyond this method in a way that *can't* be
     *  recovered by in-method materialization — return, throw, thread
     *  capture. Blocks specialization. */
    final Set<Integer> escapingParams      = new HashSet<>();

    /**
     * Per-param list of units inside this method body where the param's
     * reference escapes in a *recoverable* way (static store, instance
     * field store, array store, invoke-arg to a bad callee). A caller that
     * passes scalars at this position can still do so: the specialized
     * version of this method emits a materialization at each of these
     * units internally. Non-empty does NOT block specialization.
     */
    final Map<Integer, List<Unit>> paramEscapePoints = new HashMap<>();

    /** Param ref used in ==/!=/instanceof/synchronized/... */
    final Set<Integer> identityUsedParams  = new HashSet<>();

    /** Param forwarded to a callee position that isn't good. */
    final Set<Integer> forwardedBadParams  = new HashSet<>();

    /** Params whose reference may be returned by this method. */
    final Set<Integer> returnAliases       = new HashSet<>();

    /** Fields of param i written by direct stores in this method. */
    final Map<Integer, Set<SootField>> directlyModifiedFields = new HashMap<>();

    /** Fields of param i written via a forwarded call to a good callee. */
    final Map<Integer, Set<SootField>> calleeModifiedFields   = new HashMap<>();

    /** Fields of param i read (directly or transitively via good callees). */
    final Map<Integer, Set<SootField>> readFields             = new HashMap<>();

    /**
     * Like {@link #calleeModifiedFields} but excluding contributions from
     * super.&lt;init&gt; / this.&lt;init&gt; delegated-constructor calls (only
     * meaningful for constructors; identical to calleeModifiedFields for
     * non-constructors). Phase 2 uses this to check that a constructor's
     * body has no non-chain helper call that writes fields — such a helper
     * would require writing modified scalars back at the call site, which
     * our specialization doesn't support.
     */
    final Map<Integer, Set<SootField>> nonChainCalleeModifiedFields = new HashMap<>();

    /**
     * For constructors: the target of the super.&lt;init&gt; / this.&lt;init&gt;
     * call in this constructor's body, or null if none. Phase 2 walks this
     * pointer to validate the whole constructor chain.
     */
    SootMethod chainInitTarget = null;

    /**
     * For each param i: source-line numbers of every call in this method's
     * body that receives param i (or a callee's own forwarded param i
     * transitively). Phase 2 unions these into a replaceable allocation's
     * call-site report so {@code Y[...]} lists every line touched by the
     * specialization, not just the immediate call sites.
     */
    final Map<Integer, Set<Integer>> paramCallSites = new HashMap<>();

    public MethodSummary(int paramCount) {
        this.paramCount = paramCount;
    }

    Set<SootField> directModified(int i) {
        return directlyModifiedFields.getOrDefault(i, Collections.emptySet());
    }

    Set<SootField> calleeModified(int i) {
        return calleeModifiedFields.getOrDefault(i, Collections.emptySet());
    }

    /** Union of direct and callee-mediated modifications to param i's fields. */
    Set<SootField> allModified(int i) {
        Set<SootField> r = new HashSet<>(directModified(i));
        r.addAll(calleeModified(i));
        return r;
    }

    Set<SootField> read(int i) {
        return readFields.getOrDefault(i, Collections.emptySet());
    }

    Set<SootField> nonChainCalleeModified(int i) {
        return nonChainCalleeModifiedFields.getOrDefault(i, Collections.emptySet());
    }

    List<Unit> paramEscapes(int i) {
        return paramEscapePoints.getOrDefault(i, Collections.emptyList());
    }

    /**
     * Conservative summary used for unanalyzable callees: library methods,
     * phantoms, and every member of a recursive SCC under the pessimistic
     * fixpoint.
     */
    static MethodSummary allBad(int paramCount) {
        MethodSummary s = new MethodSummary(paramCount);
        for (int i = 0; i < paramCount; i++) {
            s.escapingParams.add(i);
            s.identityUsedParams.add(i);
            s.forwardedBadParams.add(i);
        }
        return s;
    }

    /** After all bad sets are populated, derive goodParams as the complement. */
    void finalizeGoodParams() {
        goodParams.clear();
        for (int i = 0; i < paramCount; i++) {
            if (!escapingParams.contains(i)
                    && !identityUsedParams.contains(i)
                    && !forwardedBadParams.contains(i)) {
                goodParams.add(i);
            }
        }
    }
}
