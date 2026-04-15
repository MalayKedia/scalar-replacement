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

    /** Indices of parameters whose fields may be written to (directly or transitively). */
    final Set<Integer> modifiedParams;

    /** For each parameter index, the set of source-line call sites the parameter flowed through. */
    final Map<Integer, Set<Integer>> paramCallSites;

    MethodSummary(Set<Integer> escapingParams,
                  Set<Integer> modifiedParams,
                  Map<Integer, Set<Integer>> paramCallSites) {
        this.escapingParams  = escapingParams;
        this.modifiedParams  = modifiedParams;
        this.paramCallSites  = paramCallSites;
    }
}
