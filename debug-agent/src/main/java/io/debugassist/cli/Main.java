package io.debugassist.cli;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * <pre>
 * java -jar debug-agent.jar list   &lt;recordings-dir&gt;...
 * java -jar debug-agent.jar report &lt;recordings-dir&gt;... [--out report.html]
 * java -jar debug-agent.jar replay &lt;recording.json&gt; &lt;callId&gt; [--project &lt;maven/gradle module dir&gt;]
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] argv) throws Exception {
        if (argv.length == 0) {
            usage();
            System.exit(1);
        }
        List<String> args = new ArrayList<String>(Arrays.asList(argv).subList(1, argv.length));
        try {
            switch (argv[0]) {
                case "list":
                    Recordings.printList(dirs(args));
                    break;
                case "report": {
                    String out = option(args, "--out", "debug-report.html");
                    File html = ReportGenerator.generate(dirs(args), new File(out));
                    System.out.println("Report written to " + html.getAbsolutePath());
                    break;
                }
                case "replay": {
                    String project = option(args, "--project", ".");
                    if (args.size() != 2) {
                        usage();
                        System.exit(1);
                    }
                    ReplayGenerator.generate(new File(args.get(0)), Integer.parseInt(args.get(1)), new File(project));
                    break;
                }
                default:
                    usage();
                    System.exit(1);
            }
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(2);
        }
    }

    private static List<File> dirs(List<String> args) {
        List<File> dirs = new ArrayList<File>();
        for (String a : args) {
            dirs.add(new File(a));
        }
        if (dirs.isEmpty()) {
            dirs.add(new File("debug-recordings"));
        }
        return dirs;
    }

    private static String option(List<String> args, String name, String def) {
        int i = args.indexOf(name);
        if (i < 0) {
            return def;
        }
        if (i + 1 >= args.size()) {
            throw new IllegalArgumentException(name + " needs a value");
        }
        String v = args.get(i + 1);
        args.remove(i + 1);
        args.remove(i);
        return v;
    }

    private static void usage() {
        System.err.println("Debug Assistant\n"
                + "  java -jar debug-agent.jar list   <recordings-dir>...\n"
                + "      one line per trace: time, trace id, apps, summary\n"
                + "  java -jar debug-agent.jar report <recordings-dir>... [--out debug-report.html]\n"
                + "      interactive HTML view of the data flow, merged across applications\n"
                + "  java -jar debug-agent.jar replay <recording.json> <callId> [--project <module-dir>]\n"
                + "      generate a JUnit 5 + Mockito test that re-runs a recorded call with the recorded data");
    }
}
