import java.util.*;
import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.callgraph.*;
import soot.toolkits.graph.*;

/**
 * Whole-program scene transformer that drives the interprocedural analysis.
 *
 * Starting from the program entry point, methods are visited in reverse
 * topological order of the call graph (callees before callers).  Each method
 * is analysed exactly once; the resulting {@link MethodSummary} is stored so
 * that callers can query it without re-analysing the callee.
 *
 * Assumes a non-recursive program (no cycles in the call graph).
 */
public class AnalysisTransformer extends SceneTransformer {

    private final Map<SootMethod, MethodSummary> summaries = new HashMap<>();
    private final Map<Integer, String> results = new TreeMap<>();

    /** When false, skip the scalar replacement transformation (used for baseline benchmarks). */
    boolean enableTransformation = true;

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        CallGraph cg = Scene.v().getCallGraph();

        List<SootMethod> entries = Scene.v().getEntryPoints();
        assert entries.size() == 1;

        analyzeMethod(entries.get(0), cg);

        results.values().forEach(System.out::println);
    }

    /**
     * Recursively analyse {@code method} and every method it calls.
     *
     * Callees are analysed first (via recursion), so by the time we build the
     * {@link PointerAnalysis} for this method every callee summary is already
     * available.
     */
    private void analyzeMethod(SootMethod method, CallGraph cg) {
        if (!method.isConcrete() || method.isJavaLibraryMethod()) return;
        if (summaries.containsKey(method)) return;

        Body body = method.getActiveBody();

        // Analyse callees first (reverse topological order)
        for (Unit u : body.getUnits()) {
            if (!((Stmt) u).containsInvokeExpr()) continue;
            Iterator<Edge> edges = cg.edgesOutOf(u);
            while (edges.hasNext()) analyzeMethod(edges.next().tgt(), cg);
        }

        // Run the intra-procedural analysis
        ExceptionalUnitGraph cfg = new ExceptionalUnitGraph(body);
        PointerAnalysis analysis = new PointerAnalysis(cfg, body, cg, summaries);

        // Store summary for callers, collect scalar-replacement results
        summaries.put(method, analysis.computeSummary());
        results.putAll(analysis.getScalarReplacementResults());

        // Transform: scalar-replace objects with no call sites
        if (enableTransformation)
            analysis.performScalarReplacement();
    }
}
