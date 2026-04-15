import soot.*;
import soot.options.Options;

public class PA3 {
    public static void main(String[] args) {
        String classPath = "./testcases/" + args[0]; 

        // 1. Setting global options
        Options.v().set_keep_line_number(true);
        
        // 2. Add your transformer to the "wjtp" pack
        SceneTransformer sceneTransformer = new AnalysisTransformer();
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.dfa", sceneTransformer));

        // 3. Prepare arguments
        String[] sootArgs = {
            "-cp", classPath,
            "-pp", // sets the class path for Soot
            "-w",
            "-app",
            "-allow-phantom-refs",
            "-no-bodies-for-excluded",
            "-exclude", "java.*",
            "-exclude", "javax.*",
            "-exclude", "sun.*",
            "-exclude", "com.sun.*",
            "-exclude", "jdk.*",
            "-f", "J",
            "-t", "1",
            "-main-class", "Test", // specify the main class
            "-process-dir", classPath
        };

        // 4. Just call main. It will parse args, load classes, and run the packs.
        soot.Main.main(sootArgs);
    }
}
