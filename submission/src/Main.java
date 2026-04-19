import soot.PackManager;
import soot.Transform;

/**
 * Entry point for the scalar-replacement optimizer.
 *
 * Usage:
 *   java Main &lt;mainClass&gt; &lt;classPath&gt; &lt;outputDir&gt; [--no-transform] [--format c|J]
 *
 * - mainClass    The testcase's public class name (e.g. {@code Test3}).
 * - classPath    Directory containing the testcase's compiled .class files.
 * - outputDir    Directory to write transformed .class or .jimple output into.
 * - --no-transform    Skip Phase 3 (analysis only; leaves IR untouched).
 * - --format c|J      Output format: {@code c} = bytecode .class (default),
 *                     {@code J} = Jimple .jimple.
 *
 * Pipeline (registered on Soot's {@code wjtp} pack):
 *   Phase 1 — per-method parameter summaries (see {@link PointerAnalysis}).
 *   Phase 2 — per-allocation scalar-replaceability + partial-escape
 *             classification (see {@link AllocationAnalysis}).
 *   Phase 3 — IR rewrite: scalar-local materialization, constructor-chain
 *             inlining, callee specialization, on-demand object
 *             reconstruction at escape points
 *             (see {@link Phase3Transformer}, {@link InitInliner},
 *             {@link Specializer}).
 */
public class Main {

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: Main <mainClass> <classPath> <outputDir> "
                + "[--no-transform] [--format c|J]");
            System.exit(1);
        }

        String mainClass = args[0];
        String classPath = args[1];
        String outputDir = args[2];
        boolean transform = true;
        String format = "c";

        for (int i = 3; i < args.length; i++) {
            if (args[i].equals("--no-transform")) {
                transform = false;
            } else if (args[i].equals("--format") && i + 1 < args.length) {
                format = args[++i];
            }
        }

        AnalysisTransformer analysisTransformer = new AnalysisTransformer();
        analysisTransformer.enableTransformation = transform;
        PackManager.v().getPack("wjtp").add(
            new Transform("wjtp.dfa", analysisTransformer));

        String[] sootArgs = {
            "-d", outputDir,
            "-keep-line-number",
            "-cp", classPath,
            "-pp",
            "-w",
            "-app",
            "-allow-phantom-refs",
            "-no-bodies-for-excluded",
            "-exclude", "java.*",
            "-exclude", "javax.*",
            "-exclude", "sun.*",
            "-exclude", "com.sun.*",
            "-exclude", "jdk.*",
            // Disable local packers so each local gets its own slot. Without
            // this, Soot reuses parameter slots for scalar locals of
            // different types, producing StackMapTable conflicts the
            // verifier / decompilers reject.
            "-p", "jb.ulp", "enabled:false",
            "-p", "jb.lp", "enabled:false",
            "-f", format,
            "-t", "1",
            "-main-class", mainClass,
            "-process-dir", classPath
        };

        soot.Main.main(sootArgs);
    }
}
