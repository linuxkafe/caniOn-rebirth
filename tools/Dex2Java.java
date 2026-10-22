import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;

import java.io.File;
import java.util.Collections;

/**
 * Minimal jadx driver: decompile an APK to a source tree.
 *
 * Usage: java -cp <jadx-fat-jar> tools/Dex2Java.java <input.apk> <outdir> [package-filter]
 *
 * The jadx CLI is not shipped in the jadx-headless-mcp fat jar, but the
 * jadx.api API is, so this drives the API directly.
 */
public class Dex2Java {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: Dex2Java <input.apk> <outdir> [package-filter]");
            System.exit(2);
        }
        JadxArgs jadxArgs = new JadxArgs();
        jadxArgs.setOutDir(new File(args[1]));
        jadxArgs.setInputFile(new File(args[0]));
        jadxArgs.setSkipResources(true);
        jadxArgs.setShowInconsistentCode(true);
        jadxArgs.setUseImports(true);
        jadxArgs.setThreadsCount(4);
        jadxArgs.setDebugInfo(false);
        if (args.length > 2) {
            final String pkg = args[2];
            jadxArgs.setClassFilter(name -> name.startsWith(pkg));
        }
        try (JadxDecompiler jadx = new JadxDecompiler(jadxArgs)) {
            jadx.load();
            jadx.save();
            System.out.println("classes=" + jadx.getClasses().size()
                    + " errors=" + jadx.getErrorsCount()
                    + " warns=" + jadx.getWarnsCount());
        }
        System.out.println("DONE -> " + args[1]);
    }
}
