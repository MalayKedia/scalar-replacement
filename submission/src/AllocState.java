import java.util.*;
import soot.Local;
import soot.Unit;

/**
 * Phase-2 dataflow state: for each local, the set of allocation-site units
 * it may directly alias in the current method.
 *
 * Allocations are identified by the {@link Unit} that holds the
 * {@code x = new X()} AssignStmt. Copy propagation (local-to-local, cast)
 * propagates the tag set; field loads, method returns, etc. yield the empty
 * set (those refs aren't direct aliases of a tracked allocation).
 *
 * Join is pointwise union.
 */
public class AllocState {

    final Map<Local, Set<Unit>> allocTag = new HashMap<>();

    public AllocState() {}

    void copyFrom(AllocState src) {
        allocTag.clear();
        src.allocTag.forEach((k, v) -> allocTag.put(k, new HashSet<>(v)));
    }

    static void merge(AllocState a, AllocState b, AllocState out) {
        out.allocTag.clear();
        Set<Local> keys = new HashSet<>(a.allocTag.keySet());
        keys.addAll(b.allocTag.keySet());
        for (Local l : keys) {
            Set<Unit> u = new HashSet<>(a.allocTag.getOrDefault(l, Collections.emptySet()));
            u.addAll(b.allocTag.getOrDefault(l, Collections.emptySet()));
            out.allocTag.put(l, u);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AllocState && allocTag.equals(((AllocState) o).allocTag);
    }

    @Override
    public int hashCode() { return allocTag.hashCode(); }
}
