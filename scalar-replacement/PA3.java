import soot.*;
import soot.options.Options;

public class PA3 {
    public static void main(String[] args) {
        String classPath = args[0];

        Options.v().set_keep_line_number(true);

        SceneTransformer sceneTransformer = new AnalysisTransformer();
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.dfa", sceneTransformer));

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
            "-f", "J",
            "-t", "1",
            "-main-class", "Test",
            "-process-dir", classPath
        };

        soot.Main.main(sootArgs);
    }
}
