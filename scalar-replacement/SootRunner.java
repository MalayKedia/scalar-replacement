import soot.*;
import soot.options.Options;

/**
 * Variant of PA3 that outputs to a specified directory.
 *
 * Usage: java PA3Benchmark <classPath> <outputDir> [--no-transform] [--format c|J]
 *
 *   --format c   output class files (default)
 *   --format J   output Jimple files
 */
public class SootRunner {
   public static void main(String[] args) {
        String classPath = args[0];
        String outputDir = args[1];
        boolean transform = true;
        String format = "c";

        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--no-transform")) {
                transform = false;
            } else if (args[i].equals("--format") && i + 1 < args.length) {
                format = args[++i];
            }
        }

        AnalysisTransformer analysisTransformer = new AnalysisTransformer();
        analysisTransformer.enableTransformation = transform;
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.dfa", analysisTransformer));

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
            // Disable local packers so each local gets its own slot.
            // Without this, Soot reuses the parameter slot for scalar
            // locals of a different type, producing StackMap conflicts
            // that decompilers cannot handle.
            "-p", "jb.ulp", "enabled:false",
            "-p", "jb.lp", "enabled:false",
            "-f", format,
            "-t", "1",
            "-main-class", "Test",
            "-process-dir", classPath
        };

        soot.Main.main(sootArgs);
    }
}
