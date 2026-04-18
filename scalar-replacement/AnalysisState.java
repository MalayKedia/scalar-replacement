import java.util.*;
import soot.Local;

/**
 * Phase-1 dataflow lattice element.
 *
 * For each local: the set of parameter indices whose *reference* this local
 * may directly alias. Sub-objects reachable through field chains are not
 * tracked — under specialization, sub-objects are real refs and their uses
 * are unrestricted, so only direct aliases of the parameter itself matter.
 *
 * Join is pointwise union. Bottom = empty map.
 */
public class AnalysisState {

    final Map<Local, Set<Integer>> directAlias = new HashMap<>();

    public AnalysisState() {}

    void copyFrom(AnalysisState src) {
        directAlias.clear();
        src.directAlias.forEach((k, v) -> directAlias.put(k, new HashSet<>(v)));
    }

    static void merge(AnalysisState a, AnalysisState b, AnalysisState out) {
        out.directAlias.clear();
        Set<Local> keys = new HashSet<>(a.directAlias.keySet());
        keys.addAll(b.directAlias.keySet());
        for (Local l : keys) {
            Set<Integer> u = new HashSet<>(a.directAlias.getOrDefault(l, Collections.emptySet()));
            u.addAll(b.directAlias.getOrDefault(l, Collections.emptySet()));
            out.directAlias.put(l, u);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AnalysisState
            && directAlias.equals(((AnalysisState) o).directAlias);
    }

    @Override
    public int hashCode() {
        return directAlias.hashCode();
    }
}
