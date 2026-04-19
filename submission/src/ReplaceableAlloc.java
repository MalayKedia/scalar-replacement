import java.util.*;
import soot.SootClass;
import soot.SootField;
import soot.SootMethod;
import soot.Unit;

/**
 * Verdict record for one allocation site that Phase 2 judges
 * scalar-replaceable. Holds everything Phase 3 will need: the allocation
 * site, the constructor chain to inline, the set of fields to materialize
 * as scalars, and the list of non-&lt;init&gt; call sites that will need
 * specialized callees.
 */
public class ReplaceableAlloc {

    /** The {@code x = new X()} AssignStmt. */
    final Unit site;

    /** The class being allocated (X). */
    final SootClass allocClass;

    /** The specialinvoke {@code x.<init>(...)} statement paired with the new. */
    Unit initCall;

    /** {@code X.<init>(...)} — the top of the constructor chain. */
    SootMethod initTarget;

    /** Ordered chain {@code X.<init> -> super.<init> -> ... -> Object.<init>}. */
    final List<SootMethod> initChain = new ArrayList<>();

    /** All fields that need scalar locals (read or written, in caller body,
     *  constructor chain, or specialized helper calls). */
    final Set<SootField> fieldsUsed = new LinkedHashSet<>();

    /** Non-&lt;init&gt; invokes that receive this allocation as an argument
     *  or receiver — Phase 3 rewrites these into specialized-callee calls. */
    final List<Unit> helperCallSites = new ArrayList<>();

    /** Source-line numbers of every call site touched by the specialization,
     *  including transitive forwarded calls inside specialized callees.
     *  Informational: fed to the {@code Y[...]} output. */
    final Set<Integer> callSiteLines = new TreeSet<>();

    public ReplaceableAlloc(Unit site, SootClass allocClass) {
        this.site = site;
        this.allocClass = allocClass;
    }
}
