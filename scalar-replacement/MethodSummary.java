import java.util.*;

/**
 * Inter-procedural summary produced after analysing one method.
 *
 * Parameter indices follow a uniform scheme:
 *   - For instance methods: 0 = this, 1… = explicit parameters.
 *   - For static  methods:  0… = explicit parameters (no receiver).
 *
 * A caller looks up the callee's summary to learn which of the objects it
 * passes might escape or be modified, without re-analysing the callee.
 */
public class MethodSummary {

    /** Indices of parameters that may escape (stored to a static field, returned, etc.). */
    final Set<Integer> escapingParams;

    /** Indices of parameters whose own fields may be written to. */
    final Set<Integer> modifiedParams;

    /** Indices of parameters where a heap-reachable descendant's fields are written to. */
    final Set<Integer> reachableModifiedParams;

    /** For each parameter index, the set of source-line call sites the parameter flowed through. */
    final Map<Integer, Set<Integer>> paramCallSites;

    MethodSummary(Set<Integer> escapingParams,
                  Set<Integer> modifiedParams,
                  Set<Integer> reachableModifiedParams,
                  Map<Integer, Set<Integer>> paramCallSites) {
        this.escapingParams         = escapingParams;
        this.modifiedParams         = modifiedParams;
        this.reachableModifiedParams = reachableModifiedParams;
        this.paramCallSites         = paramCallSites;
    }
}
