package io.debugassist.cli;

import io.debugassist.agent.core.Json;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates a JUnit 5 + Mockito test that re-runs one recorded call with exactly the data seen in QA:
 * <ul>
 *   <li>arguments are loaded from JSON fixtures,</li>
 *   <li>every collaborator the call used is a mock that answers what the real one answered,</li>
 *   <li>the result (or exception, or changed arguments) is compared with the recording.</li>
 * </ul>
 * Fixtures are plain JSON under {@code src/test/resources/debug-replay/<TestName>/} so they can be edited to try
 * variations of the scenario.
 */
final class ReplayGenerator {

    private static final String SUPPORT_PKG = "io.debugassist.replay";

    private final Map<String, Object> recording;
    private final Map<String, Object> call;
    private final String pkg;
    private final String targetSimple;
    private final String testName;
    private final File fixtureDir;
    private final List<String> notes = new ArrayList<String>();
    private final Collaborators collaborators;

    private ReplayGenerator(Map<String, Object> recording, Map<String, Object> call, File project) {
        this.recording = recording;
        this.call = call;
        String cls = str(call, "cls");
        int dot = cls.lastIndexOf('.');
        this.pkg = dot < 0 ? "" : cls.substring(0, dot);
        this.targetSimple = cls.substring(dot + 1).replace('$', '_');
        this.testName = targetSimple + "_" + str(call, "m") + "_Replay" + num(call, "id") + "Test";
        this.fixtureDir = new File(project, "src/test/resources/debug-replay/" + testName);
        Collaborators c = null;
        try {
            c = Collaborators.load(project, cls);
        } catch (Exception e) {
            // fall back to mocking every collaborator
        }
        this.collaborators = c;
        if (c == null) {
            notes.add("compiled classes of " + cls + " not found under " + project.getAbsolutePath()
                    + " (build the module first, or pass --project); every called object is mocked");
        }
    }

    static void generate(File recordingFile, int callId, File project) throws IOException {
        Map<String, Object> rec = Recordings.parse(recordingFile);
        Map<String, Object> call = findCall(map(rec.get("root")), callId);
        if (call == null) {
            throw new IllegalArgumentException("call #" + callId + " not found in " + recordingFile);
        }
        if (!"method".equals(call.get("kind"))) {
            throw new IllegalArgumentException("call #" + callId + " is an HTTP call; pick an application method"
                    + " (the first method under it, e.g. the controller)");
        }
        if (Boolean.TRUE.equals(call.get("proxy"))) {
            throw new IllegalArgumentException("call #" + callId + " is on a generated proxy (" + call.get("cls")
                    + "); replay the method that calls it instead");
        }
        new ReplayGenerator(rec, call, project).write(project);
    }

    private void write(File project) throws IOException {
        File testDir = new File(project, "src/test/java/" + pkg.replace('.', '/'));
        File supportDir = new File(project, "src/test/java/" + SUPPORT_PKG.replace('.', '/'));
        testDir.mkdirs();
        supportDir.mkdirs();
        fixtureDir.mkdirs();

        String source = testSource();
        File testFile = new File(testDir, testName + ".java");
        Files.write(testFile.toPath(), source.getBytes(StandardCharsets.UTF_8));

        File support = new File(supportDir, "ReplaySupport.java");
        Files.write(support.toPath(), resource("/io/debugassist/cli/ReplaySupport.java.txt").getBytes(StandardCharsets.UTF_8));

        System.out.println("Generated " + testFile.getPath());
        System.out.println("Fixtures  " + fixtureDir.getPath());
        System.out.println("Support   " + support.getPath());
        System.out.println("Needs test dependencies: junit-jupiter, mockito-junit-jupiter, jackson-databind"
                + " (all included in spring-boot-starter-test + spring-boot-starter-web)");
        for (String n : notes) {
            System.out.println("NOTE: " + n);
        }
    }

    // ------------------------------------------------------------------ source generation

    private String testSource() throws IOException {
        StringBuilder imports = new StringBuilder();
        StringBuilder fields = new StringBuilder();
        StringBuilder body = new StringBuilder();
        boolean isStatic = Boolean.TRUE.equals(call.get("static"));
        String method = str(call, "m");
        List<Object> params = list(call.get("params"), true);
        List<Object> args = list(call.get("args"), true);

        // 1) collaborators -> mocks answering with the recorded values
        Map<String, String> mockFields = new LinkedHashMap<String, String>();
        Map<String, Stub> stubs = new LinkedHashMap<String, Stub>();
        if (!isStatic) {
            collectStubs(call, num(call, "self"), stubs);
        }
        int stubNo = 0;
        for (Stub s : stubs.values()) {
            String key = s.mockType + " " + s.mockName;
            String field = mockFields.get(key);
            if (field == null) {
                field = uniqueField(s.mockName, mockFields.values());
                mockFields.put(key, field);
                fields.append("    @Mock\n    private ").append(typeRef(s.mockType)).append(' ').append(field).append(";\n");
            }
            String file = "mock-" + (++stubNo) + "-" + simple(s.cls) + "." + s.method + ".json";
            fixture(file, Json.pretty(s.outcomes));
            body.append("        // ").append(simple(s.cls)).append('.').append(s.method)
                    .append(" was called ").append(s.outcomes.size()).append(" time(s) in QA\n");
            body.append("        doAnswer(replay.answers(\"").append(file).append("\")).when(").append(field)
                    .append(").").append(s.method).append('(').append(matchers(s.params)).append(");\n");
        }
        if (!stubs.isEmpty()) {
            body.append('\n');
        }

        // 2) recorded inputs
        List<String> argNames = new ArrayList<String>();
        if (!args.isEmpty()) {
            body.append("        // Inputs captured in QA (edit the JSON files to try other data)\n");
        }
        Set<String> usedNames = new LinkedHashSet<String>(mockFields.values());
        usedNames.add("target");
        usedNames.add("replay");
        usedNames.add("actual");
        for (int i = 0; i < args.size(); i++) {
            Map<String, Object> p = map(params.get(i));
            String raw = str(p, "r");
            String name = uniqueField(javaName(str(p, "n")), usedNames);
            usedNames.add(name);
            argNames.add(name);
            String file = "arg" + i + "-" + name + ".json";
            fixture(file, Json.pretty(args.get(i)));
            String declared = typeRef(raw);
            body.append("        ").append(declared).append(' ').append(name).append(" = replay.load(\"")
                    .append(file).append("\", ").append(declared).append(".class);\n");
        }
        if (!args.isEmpty()) {
            body.append('\n');
        }

        // 3) invoke and compare
        String receiver = isStatic ? typeRef(str(call, "cls")) : "target";
        String invocation;
        if ("private".equals(call.get("vis"))) {
            StringBuilder types = new StringBuilder();
            for (Object p : params) {
                types.append(", ").append(typeRef(str(map(p), "r"))).append(".class");
            }
            invocation = "replay.invokePrivate(" + (isStatic ? "null" : "target") + ", " + typeRef(str(call, "cls"))
                    + ".class, \"" + method + "\", new Class<?>[]{" + (types.length() > 0 ? types.substring(2) : "")
                    + "}" + (argNames.isEmpty() ? "" : ", " + join(argNames)) + ")";
        } else {
            invocation = receiver + "." + method + "(" + join(argNames) + ")";
        }

        Map<String, Object> exc = map(call.get("exc"));
        boolean isVoid = "void".equals(call.get("retRaw"));
        if (exc != null) {
            body.append("        // In QA this call failed with ").append(exc.get("type")).append(": ")
                    .append(oneLine(exc.get("msg"))).append('\n');
            body.append("        Throwable thrown = assertThrows(Throwable.class, () -> ").append(invocation).append(");\n");
            body.append("        assertEquals(\"").append(exc.get("type")).append("\", thrown.getClass().getName());\n");
        } else if (isVoid) {
            body.append("        ").append(invocation).append(";\n");
        } else {
            fixture("expected-result.json", Json.pretty(call.get("result")));
            body.append("        Object actual = ").append(invocation).append(";\n\n");
            body.append("        // Result returned in QA\n");
            body.append("        replay.assertMatches(\"expected-result.json\", actual);\n");
        }
        List<Object> after = list(call.get("argsAfter"));
        if (exc == null && after != null) {
            for (int i = 0; i < after.size() && i < args.size(); i++) {
                if (!Json.write(after.get(i)).equals(Json.write(args.get(i)))) {
                    String file = "expected-arg" + i + "-after.json";
                    fixture(file, Json.pretty(after.get(i)));
                    body.append("        // The method changed this argument in QA\n");
                    body.append("        replay.assertMatches(\"").append(file).append("\", ").append(argNames.get(i)).append(");\n");
                }
            }
        }

        imports.append("import ").append(SUPPORT_PKG).append(".ReplaySupport;\n");
        imports.append("import org.junit.jupiter.api.Test;\n");
        imports.append("import org.junit.jupiter.api.extension.ExtendWith;\n");
        if (!mockFields.isEmpty()) {
            imports.append("import org.mockito.ArgumentMatchers;\n");
            imports.append("import org.mockito.Mock;\n");
        }
        if (!isStatic) {
            imports.append("import org.mockito.InjectMocks;\n");
        }
        imports.append("import org.mockito.junit.jupiter.MockitoExtension;\n");
        imports.append("import org.mockito.junit.jupiter.MockitoSettings;\n");
        imports.append("import org.mockito.quality.Strictness;\n\n");
        if (exc != null) {
            imports.append("import static org.junit.jupiter.api.Assertions.assertEquals;\n");
            imports.append("import static org.junit.jupiter.api.Assertions.assertThrows;\n");
        }
        if (!mockFields.isEmpty()) {
            imports.append("import static org.mockito.Mockito.doAnswer;\n");
        }

        StringBuilder src = new StringBuilder();
        if (!pkg.isEmpty()) {
            src.append("package ").append(pkg).append(";\n\n");
        }
        src.append(imports).append('\n');
        src.append("/**\n");
        src.append(" * Replays a call recorded by debug-agent in ").append(recording.get("app")).append(".\n");
        src.append(" * <pre>\n");
        src.append(" * trace    : ").append(recording.get("traceId")).append('\n');
        src.append(" * recorded : ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
                .format(new Date(((Number) call.get("start")).longValue()))).append(" on ")
                .append(recording.get("host")).append(" [").append(recording.get("thread")).append("]\n");
        src.append(" * request  : ").append(recording.get("summary")).append('\n');
        src.append(" * call     : #").append(call.get("id")).append(' ').append(simple(str(call, "cls"))).append('.')
                .append(method).append('(').append(paramList(params)).append(")\n");
        src.append(" * fixtures : src/test/resources/debug-replay/").append(testName).append("/\n");
        src.append(" * </pre>\n");
        for (String n : notes) {
            src.append(" * NOTE: ").append(n.replace("*/", "* /")).append('\n');
        }
        src.append(" */\n");
        src.append("@ExtendWith(MockitoExtension.class)\n");
        src.append("@MockitoSettings(strictness = Strictness.LENIENT)\n");
        src.append("class ").append(testName).append(" {\n\n");
        src.append("    private final ReplaySupport replay = new ReplaySupport(getClass());\n\n");
        src.append(fields);
        if (!isStatic) {
            if (fields.length() > 0) {
                src.append('\n');
            }
            src.append("    @InjectMocks\n    private ").append(typeRef(str(call, "cls"))).append(" target;\n");
        }
        src.append("\n    @Test\n");
        src.append("    @SuppressWarnings({\"unchecked\", \"rawtypes\"})\n");
        src.append("    void replaysRecordedCall() throws Throwable {\n");
        src.append(body);
        src.append("    }\n}\n");
        return src.toString();
    }

    /** One mocked collaborator method with the outcomes it produced, in call order. */
    private static final class Stub {
        String cls;
        String mockType;
        String mockName;
        String method;
        List<Object> params;
        final List<Object> outcomes = new ArrayList<Object>();
    }

    /**
     * Direct collaborators of {@code node} become stubs. Calls on the same object (private helpers, this.foo())
     * run for real in the test, so their collaborators are collected too.
     */
    private void collectStubs(Map<String, Object> node, long self, Map<String, Stub> stubs) {
        for (Object o : list(node.get("children"), true)) {
            Map<String, Object> c = map(o);
            String kind = str(c, "kind");
            if ("http-out".equals(kind)) {
                Map<String, Object> http = map(c.get("http"));
                notes.add("the call made an outgoing HTTP request (" + http.get("method") + " " + http.get("url")
                        + ") from inside " + simple(str(node, "cls")) + "; its HTTP client is not mocked in this test."
                        + " Replay the caller instead, so this class is mocked.");
                continue;
            }
            if (!"method".equals(kind)) {
                continue;
            }
            if (num(c, "self") == self && self != 0) {
                collectStubs(c, self, stubs);
                continue;
            }
            String cls = str(c, "cls");
            String vis = str(c, "vis");
            if (Boolean.TRUE.equals(c.get("static"))) {
                notes.add("static call " + simple(cls) + "." + c.get("m") + " runs for real (static methods are not mocked)");
                continue;
            }
            if ("private".equals(vis) || (!"public".equals(vis) && !pkg.equals(packageOf(cls)))) {
                notes.add(simple(cls) + "." + c.get("m") + " is not visible from the test package and runs for real");
                continue;
            }
            String mockType = cls;
            String mockName = decapitalize(simple(cls));
            if (collaborators != null) {
                Collaborators.Field f = collaborators.fieldFor(cls);
                if (f == null) {
                    continue; // not a dependency of the target (data object, helper created inside): runs for real
                }
                mockType = f.type;
                mockName = f.name;
            }
            String key = cls + "#" + c.get("m") + c.get("desc");
            Stub s = stubs.get(key);
            if (s == null) {
                s = new Stub();
                s.cls = cls;
                s.mockType = mockType;
                s.mockName = mockName;
                s.method = str(c, "m");
                s.params = list(c.get("params"));
                stubs.put(key, s);
            }
            Map<String, Object> outcome = new LinkedHashMap<String, Object>();
            Map<String, Object> exc = map(c.get("exc"));
            if (exc != null) {
                outcome.put("throws", exc.get("type"));
                outcome.put("message", exc.get("msg"));
            } else if ("void".equals(c.get("retRaw"))) {
                outcome.put("void", Boolean.TRUE);
            } else {
                outcome.put("returns", c.get("result"));
            }
            outcome.put("recordedArgs", c.get("args"));
            s.outcomes.add(outcome);
        }
    }

    private String matchers(List<Object> params) {
        StringBuilder sb = new StringBuilder();
        for (Object p : params) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            String raw = str(map(p), "r");
            if ("int".equals(raw)) sb.append("ArgumentMatchers.anyInt()");
            else if ("long".equals(raw)) sb.append("ArgumentMatchers.anyLong()");
            else if ("double".equals(raw)) sb.append("ArgumentMatchers.anyDouble()");
            else if ("float".equals(raw)) sb.append("ArgumentMatchers.anyFloat()");
            else if ("boolean".equals(raw)) sb.append("ArgumentMatchers.anyBoolean()");
            else if ("short".equals(raw)) sb.append("ArgumentMatchers.anyShort()");
            else if ("byte".equals(raw)) sb.append("ArgumentMatchers.anyByte()");
            else if ("char".equals(raw)) sb.append("ArgumentMatchers.anyChar()");
            else sb.append("ArgumentMatchers.<").append(typeRef(raw)).append(">any()");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    private void fixture(String name, String json) throws IOException {
        Files.write(new File(fixtureDir, name).toPath(), (json + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** Binary class name ({@code a.B$C}, {@code [La.B;}, {@code [I}) to a source type reference. */
    private String typeRef(String binary) {
        int dims = 0;
        while (binary.startsWith("[")) {
            dims++;
            binary = binary.substring(1);
        }
        if (dims > 0) {
            if (binary.startsWith("L") && binary.endsWith(";")) {
                binary = binary.substring(1, binary.length() - 1);
            } else {
                binary = primitive(binary.charAt(0));
            }
        }
        String ref;
        if (!binary.contains(".")) {
            ref = binary;
        } else {
            String p = packageOf(binary);
            String simpleName = binary.substring(p.length() + 1).replace('$', '.');
            ref = p.equals(pkg) || p.equals("java.lang") ? simpleName : binary.replace('$', '.');
        }
        StringBuilder sb = new StringBuilder(ref);
        for (int i = 0; i < dims; i++) {
            sb.append("[]");
        }
        return sb.toString();
    }

    private static String primitive(char c) {
        switch (c) {
            case 'Z': return "boolean";
            case 'B': return "byte";
            case 'C': return "char";
            case 'S': return "short";
            case 'I': return "int";
            case 'J': return "long";
            case 'F': return "float";
            default: return "double";
        }
    }

    private static String paramList(List<Object> params) {
        StringBuilder sb = new StringBuilder();
        for (Object p : params) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            String t = str(map(p), "t");
            sb.append(t.replaceAll("([\\w$]+\\.)+", "")).append(' ').append(str(map(p), "n"));
        }
        return sb.toString();
    }

    private static String packageOf(String cls) {
        int dot = cls.lastIndexOf('.');
        return dot < 0 ? "" : cls.substring(0, dot);
    }

    private static String simple(String cls) {
        return cls.substring(cls.lastIndexOf('.') + 1).replace('$', '.');
    }

    private static String decapitalize(String s) {
        s = s.replace(".", "");
        return s.isEmpty() ? "dependency" : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String javaName(String s) {
        String n = s.replaceAll("[^A-Za-z0-9_]", "_");
        return n.isEmpty() || Character.isDigit(n.charAt(0)) ? "arg" + n : n;
    }

    private static String uniqueField(String base, java.util.Collection<String> used) {
        String n = base;
        for (int i = 2; used.contains(n); i++) {
            n = base + i;
        }
        return n;
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static String oneLine(Object o) {
        return String.valueOf(o).replaceAll("\\s+", " ").replace("*/", "* /");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findCall(Map<String, Object> node, int id) {
        if (node == null) {
            return null;
        }
        if (num(node, "id") == id) {
            return node;
        }
        for (Object c : list(node.get("children"), true)) {
            Map<String, Object> found = findCall((Map<String, Object>) c, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    private static List<Object> list(Object o) {
        return list(o, false);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o, boolean emptyIfNull) {
        if (o == null) {
            return emptyIfNull ? new ArrayList<Object>() : null;
        }
        return (List<Object>) o;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString();
    }

    private static long num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).longValue() : 0;
    }

    private static String resource(String path) throws IOException {
        InputStream in = ReplayGenerator.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException(path + " missing from the agent jar");
        }
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int r;
            while ((r = in.read(chunk)) > 0) {
                buf.write(chunk, 0, r);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }
}
