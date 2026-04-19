import java.util.*;
import soot.SootField;
import soot.SootMethod;

public class MethodSummary {

    final int paramCount;
    final Set<Integer> goodParams = new HashSet<>();
    final Set<Integer> escapingParams = new HashSet<>();
    final Set<Integer> identityUsedParams = new HashSet<>();
    final Set<Integer> forwardedBadParams = new HashSet<>();
    final Set<Integer> returnAliases = new HashSet<>();
    final Map<Integer, Set<SootField>> directlyModifiedFields = new HashMap<>();
    final Map<Integer, Set<SootField>> calleeModifiedFields = new HashMap<>();
    final Map<Integer, Set<SootField>> readFields = new HashMap<>();
    final Map<Integer, Set<SootField>> nonChainCalleeModifiedFields = new HashMap<>();
    SootMethod chainInitTarget = null;//Pointer to init method
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

    //Helper used to create a summary where all params are bad
    static MethodSummary allBad(int paramCount) {
        MethodSummary s = new MethodSummary(paramCount);
        for (int i = 0; i < paramCount; i++) {
            s.escapingParams.add(i);
            s.identityUsedParams.add(i);
            s.forwardedBadParams.add(i);
        }
        return s;
    }

    // Set all non-bad params to good
    void setGoodParams() {
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
