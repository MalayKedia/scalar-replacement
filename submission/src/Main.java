import soot.PackManager;
import soot.Transform;


public class Main {

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: Main <mainClass> <classPath> <outputDir> "
                + "[--no-transform] [--jimple-base <dir>] [--jimple-opt <dir>]");
            System.exit(1);
        }

        String mainClass = args[0];
        String classPath = args[1];
        String outputDir = args[2];
        boolean transform = true;
        String jimpleBaseDir = null;
        String jimpleOptDir = null;

        for (int i = 3; i < args.length; i++) {
            if (args[i].equals("--no-transform")) {
                transform = false;
            } else if (args[i].equals("--jimple-base") && i + 1 < args.length) {
                jimpleBaseDir = args[++i];
            } else if (args[i].equals("--jimple-opt") && i + 1 < args.length) {
                jimpleOptDir = args[++i];
            }
        }

        AnalysisTransformer analysisTransformer = new AnalysisTransformer();
        analysisTransformer.enableTransformation = transform;
        analysisTransformer.baseJimpleDir = jimpleBaseDir;
        analysisTransformer.optJimpleDir = jimpleOptDir;
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
            "-p", "jb.ulp", "enabled:false",
            "-p", "jb.lp", "enabled:false",
            "-f", "c",
            "-t", "1",
            "-main-class", mainClass,
            "-process-dir", classPath
        };

        soot.Main.main(sootArgs);
    }
}
