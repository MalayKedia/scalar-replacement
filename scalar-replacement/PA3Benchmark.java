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
public class PA3Benchmark {
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
            // Disable Soot's own Jimple optimizations so our scalar
            // replacement locals are preserved in the output.
            "-p", "jop", "enabled:false",
            "-p", "jb.cp", "enabled:false",
            "-p", "jb.dae", "enabled:false",
            "-p", "jb.cp-ule", "enabled:false",
            "-f", format,
            "-t", "1",
            "-main-class", "Test",
            "-process-dir", classPath
        };

        soot.Main.main(sootArgs);
    }
}
