import java.util.*;
import soot.*;
import soot.jimple.toolkits.callgraph.*;

/**
 * Whole-program driver for Phase-1 summary computation.
 *
 * Order of operations:
 *   1. Collect concrete user methods reachable from the entry point.
 *   2. Compute SCCs on the induced call graph (Tarjan, reverse topological).
 *   3. Visit SCCs in reverse topological order:
 *        - recursive SCC (size > 1, or singleton with self-edge):
 *              install {@link MethodSummary#allBad} for every member. This
 *              is the pessimistic fixpoint — sound, strictly less precise
 *              than the coinductive version.
 *        - singleton non-recursive method:
 *              run {@link PointerAnalysis}, store the resulting summary.
 *
 * All callee summaries are guaranteed to exist before a caller is analysed,
 * so {@link PointerAnalysis} never encounters a missing entry for a reachable
 * target.
 */
public class AnalysisTransformer extends SceneTransformer {

    private final Map<SootMethod, MethodSummary> summaries = new HashMap<>();

    /** Placeholder for Phase 3. Phase 1 does not mutate IR; this flag is unused
     *  until transformation lands, but SootRunner still toggles it. */
    boolean enableTransformation = true;

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        CallGraph cg = Scene.v().getCallGraph();

        // Object.<init> is library code but semantically a no-op — it neither
        // escapes nor modifies this. Without this override, every user
        // constructor's super call would resolve to allBad and poison the
        // whole chain, making goodParams[0] forever false on constructors.
        installObjectInitSummary();

        Set<SootMethod> reachable = collectReachable(cg);
        List<List<SootMethod>> sccs = tarjan(reachable, cg);

        for (List<SootMethod> scc : sccs) {
            boolean recursive = scc.size() > 1 || hasSelfEdge(scc.get(0), cg);
            if (recursive) {
                for (SootMethod m : scc) {
                    summaries.put(m, MethodSummary.allBad(paramCountOf(m)));
                }
                continue;
            }
            SootMethod m = scc.get(0);
            try {
                PointerAnalysis pa = new PointerAnalysis(m.getActiveBody(), cg, summaries);
                summaries.put(m, pa.getSummary());
            } catch (Exception e) {
                summaries.put(m, MethodSummary.allBad(paramCountOf(m)));
            }
        }

        // Phase 2: per-method scalar-replaceable allocation analysis.
        Map<SootMethod, List<ReplaceableAlloc>> perMethodAllocs = new LinkedHashMap<>();
        Map<SootMethod, AllocationAnalysis> perMethodAnalysis = new LinkedHashMap<>();
        for (List<SootMethod> scc : sccs) {
            for (SootMethod m : scc) {
                if (!m.isConcrete()) continue;
                try {
                    AllocationAnalysis aa = new AllocationAnalysis(
                        m.getActiveBody(), cg, summaries);
                    List<ReplaceableAlloc> rs = aa.getReplaceableAllocs();
                    if (!rs.isEmpty()) {
                        perMethodAllocs.put(m, rs);
                        perMethodAnalysis.put(m, aa);
                    }
                } catch (Exception ignored) { }
            }
        }

        printYResults(perMethodAllocs);

        // Phase 3: IR transformation.
        if (enableTransformation) {
            Specializer specializer = new Specializer(summaries);
            Phase3Transformer p3 = new Phase3Transformer(specializer);
            for (Map.Entry<SootMethod, List<ReplaceableAlloc>> e
                    : perMethodAllocs.entrySet()) {
                try {
                    p3.transform(e.getKey(), e.getValue(),
                                 perMethodAnalysis.get(e.getKey()));
                } catch (Exception ex) {
                    System.err.println("Phase 3 failed for " + e.getKey()
                        + ": " + ex);
                }
            }
        }
    }

    private void installObjectInitSummary() {
        try {
            SootClass objectClass = Scene.v().getSootClass("java.lang.Object");
            SootMethod objectInit = objectClass.getMethod(
                "<init>", Collections.emptyList(), VoidType.v());
            MethodSummary s = new MethodSummary(1);
            s.goodParams.add(0);
            summaries.put(objectInit, s);
        } catch (Exception ignored) { }
    }

    /* =============================================================
     *  Reachability
     * ============================================================= */

    private Set<SootMethod> collectReachable(CallGraph cg) {
        Set<SootMethod> visited = new HashSet<>();
        Deque<SootMethod> work = new ArrayDeque<>(Scene.v().getEntryPoints());
        while (!work.isEmpty()) {
            SootMethod m = work.poll();
            if (!visited.add(m)) continue;
            Iterator<Edge> it = cg.edgesOutOf(m);
            while (it.hasNext()) {
                SootMethod tgt = it.next().tgt();
                if (!visited.contains(tgt)) work.add(tgt);
            }
        }
        visited.removeIf(m -> !isAnalyzable(m));
        return visited;
    }

    private boolean isAnalyzable(SootMethod m) {
        return m.isConcrete() && !m.isJavaLibraryMethod();
    }

    private boolean hasSelfEdge(SootMethod m, CallGraph cg) {
        Iterator<Edge> it = cg.edgesOutOf(m);
        while (it.hasNext()) if (it.next().tgt().equals(m)) return true;
        return false;
    }

    private int paramCountOf(SootMethod m) {
        return m.getParameterCount() + (m.isStatic() ? 0 : 1);
    }

    /* =============================================================
     *  Tarjan's SCC algorithm
     *
     *  The SCCs appear in reverse topological order in the returned list
     *  (a property of Tarjan), which is exactly what Phase 1 needs.
     * ============================================================= */

    private List<List<SootMethod>> tarjan(Set<SootMethod> nodes, CallGraph cg) {
        TarjanState st = new TarjanState();
        for (SootMethod m : nodes) {
            if (!st.index.containsKey(m)) tarjanVisit(m, nodes, cg, st);
        }
        return st.result;
    }

    private void tarjanVisit(SootMethod v, Set<SootMethod> nodes, CallGraph cg,
                             TarjanState st) {
        st.index.put(v, st.counter);
        st.lowlink.put(v, st.counter);
        st.counter++;
        st.stack.push(v);
        st.onStack.add(v);

        Iterator<Edge> it = cg.edgesOutOf(v);
        while (it.hasNext()) {
            SootMethod w = it.next().tgt();
            if (!nodes.contains(w)) continue;
            if (!st.index.containsKey(w)) {
                tarjanVisit(w, nodes, cg, st);
                st.lowlink.put(v, Math.min(st.lowlink.get(v), st.lowlink.get(w)));
            } else if (st.onStack.contains(w)) {
                st.lowlink.put(v, Math.min(st.lowlink.get(v), st.index.get(w)));
            }
        }

        if (st.lowlink.get(v).equals(st.index.get(v))) {
            List<SootMethod> scc = new ArrayList<>();
            SootMethod w;
            do {
                w = st.stack.pop();
                st.onStack.remove(w);
                scc.add(w);
            } while (!w.equals(v));
            st.result.add(scc);
        }
    }

    private static class TarjanState {
        final Map<SootMethod, Integer> index   = new HashMap<>();
        final Map<SootMethod, Integer> lowlink = new HashMap<>();
        final Set<SootMethod> onStack          = new HashSet<>();
        final Deque<SootMethod> stack          = new ArrayDeque<>();
        final List<List<SootMethod>> result    = new ArrayList<>();
        int counter = 0;
    }

    /**
     * Print one {@code O<line> = Y[callsite_lines]} line per
     * scalar-replaceable allocation across the whole program, sorted by
     * allocation line. Disqualified allocations are suppressed.
     */
    private void printYResults(Map<SootMethod, List<ReplaceableAlloc>> perMethodAllocs) {
        Map<Integer, String> byLine = new TreeMap<>();
        for (List<ReplaceableAlloc> ras : perMethodAllocs.values()) {
            for (ReplaceableAlloc ra : ras) {
                int line = ra.site.getJavaSourceStartLineNumber();
                StringJoiner sj = new StringJoiner(",", "[", "]");
                for (int l : ra.callSiteLines) sj.add(String.valueOf(l));
                byLine.put(line, "O" + line + " = Y" + sj);
            }
        }
        for (String s : byLine.values()) System.out.println(s);
    }
}
