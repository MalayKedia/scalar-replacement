import java.util.*;
import soot.Local;


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
        return (o instanceof AnalysisState)&& directAlias.equals(((AnalysisState) o).directAlias);
    }

    @Override
    public int hashCode() {
        return directAlias.hashCode();
    }
}
