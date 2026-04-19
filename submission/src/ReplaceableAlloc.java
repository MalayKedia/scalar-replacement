import java.util.*;
import soot.SootClass;
import soot.SootField;
import soot.SootMethod;
import soot.Unit;

//Contains information about whether an alloc is replacable or not
public class ReplaceableAlloc {

    final Unit site;
    final SootClass allocClass;
    Unit initCall;
    SootMethod initTarget;
    final List<SootMethod> initChain = new ArrayList<>();
    final Set<SootField> fieldsUsed = new LinkedHashSet<>();
    final List<Unit> CalleeCallSites = new ArrayList<>();
    final Set<Integer> callSiteLines = new TreeSet<>();

    public ReplaceableAlloc(Unit site, SootClass allocClass) {
        this.site = site;
        this.allocClass = allocClass;
    }
}
