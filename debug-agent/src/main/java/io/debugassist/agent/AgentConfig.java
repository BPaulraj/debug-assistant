package io.debugassist.agent;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Agent settings. Sources, lowest to highest priority:
 * <ol>
 *   <li>built-in defaults</li>
 *   <li>a properties file: {@code config=path/to/debug-agent.properties}</li>
 *   <li>inline agent args: {@code -javaagent:debug-agent.jar=packages=com.acme,app=order-service}</li>
 *   <li>system properties prefixed with {@code debugagent.} (e.g. {@code -Ddebugagent.enabled=false})</li>
 * </ol>
 * List values are separated by {@code ;} (or {@code |}) because {@code ,} separates agent args.
 */
public final class AgentConfig {

    public static final String TRACE_HEADER = "X-Debug-Trace-Id";

    public final boolean enabled;
    public final List<String> packages;
    public final List<String> excludes;
    public final String app;
    public final File outDir;
    public final int maxDepth;
    public final int maxStringLength;
    public final int maxCollectionSize;
    public final int maxCallsPerTrace;
    public final boolean captureArgsAfter;
    public final boolean skipAccessors;
    public final List<String> maskFields;
    public final boolean verbose;

    private AgentConfig(Properties p) {
        enabled = bool(p, "enabled", true);
        packages = list(p, "packages", "");
        excludes = list(p, "exclude", "");
        app = p.getProperty("app", defaultAppName());
        outDir = new File(p.getProperty("out", "debug-recordings")).getAbsoluteFile();
        maxDepth = integer(p, "maxDepth", 6);
        maxStringLength = integer(p, "maxString", 2000);
        maxCollectionSize = integer(p, "maxCollection", 100);
        maxCallsPerTrace = integer(p, "maxCalls", 5000);
        captureArgsAfter = bool(p, "captureArgsAfter", true);
        skipAccessors = bool(p, "skipAccessors", true);
        maskFields = lower(list(p, "mask",
                "password;passwd;pwd;secret;token;apikey;api_key;authorization;cardnumber;card_number;cvv;ssn"));
        verbose = bool(p, "verbose", false);
    }

    public static AgentConfig parse(String agentArgs) throws Exception {
        Properties inline = new Properties();
        if (agentArgs != null) {
            for (String pair : agentArgs.split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    inline.setProperty(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
        }
        Properties merged = new Properties();
        String configFile = inline.getProperty("config", System.getProperty("debugagent.config"));
        if (configFile != null) {
            try (InputStream in = new FileInputStream(configFile)) {
                merged.load(in);
            }
        }
        merged.putAll(inline);
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith("debugagent.")) {
                merged.setProperty(key.substring("debugagent.".length()), System.getProperty(key));
            }
        }
        return new AgentConfig(merged);
    }

    public boolean isMasked(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        for (String m : maskFields) {
            if (n.contains(m)) {
                return true;
            }
        }
        return false;
    }

    private static String defaultAppName() {
        String cmd = System.getProperty("sun.java.command", "app").trim();
        String first = cmd.split("\\s+")[0];
        first = first.replace('\\', '/');
        first = first.substring(first.lastIndexOf('/') + 1);
        if (first.endsWith(".jar")) {
            first = first.substring(0, first.length() - 4);
        } else if (first.contains(".")) {
            first = first.substring(first.lastIndexOf('.') + 1);
        }
        return first.isEmpty() ? "app" : first;
    }

    private static List<String> list(Properties p, String key, String def) {
        String v = p.getProperty(key, def);
        List<String> out = new ArrayList<String>();
        for (String s : v.split("[;|]")) {
            if (!s.trim().isEmpty()) {
                out.add(s.trim());
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static List<String> lower(List<String> in) {
        List<String> out = new ArrayList<String>();
        for (String s : in) {
            out.add(s.toLowerCase(Locale.ROOT));
        }
        return Collections.unmodifiableList(out);
    }

    private static boolean bool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static int integer(Properties p, String key, int def) {
        String v = p.getProperty(key);
        return v == null ? def : Integer.parseInt(v.trim());
    }

    @Override
    public String toString() {
        return "app=" + app + ", packages=" + packages + ", exclude=" + excludes + ", out=" + outDir
                + ", maxDepth=" + maxDepth + ", maxCalls=" + maxCallsPerTrace;
    }
}
