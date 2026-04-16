import soot.*;
import soot.options.Options;

/**
 * Variant of PA3 that outputs class files (-f c) instead of Jimple (-f J),
 * so the transformed code can actually be executed.
 *
 * Usage: java PA3Benchmark <classPath> <outputDir> [--no-transform]
 */
public class PA3Benchmark {
    public static void main(String[] args) {
        String classPath = args[0];
        String outputDir = args[1];
        boolean transform = true;
        if (args.length > 2 && args[2].equals("--no-transform")) {
            transform = false;
        }

        Options.v().set_keep_line_number(true);
        Options.v().set_output_dir(outputDir);

        AnalysisTransformer analysisTransformer = new AnalysisTransformer();
        analysisTransformer.enableTransformation = transform;
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.dfa", analysisTransformer));

        String[] sootArgs = {
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
            "-f", "c",
            "-t", "1",
            "-main-class", "Test",
            "-process-dir", classPath
        };

        soot.Main.main(sootArgs);
    }
}
