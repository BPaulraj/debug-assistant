package io.debugassist.cli;

import io.debugassist.agent.core.Json;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Builds a single self-contained HTML file (no server, no internet needed) from recordings. */
final class ReportGenerator {

    private ReportGenerator() {
    }

    static File generate(List<File> dirs, File out) throws IOException {
        List<File> files = Recordings.find(dirs);
        StringBuilder data = new StringBuilder("[");
        int n = 0;
        for (File f : files) {
            String text = Recordings.read(f).trim();
            if (!text.startsWith("{\"format\"")) {
                continue;
            }
            if (n++ > 0) {
                data.append(",\n");
            }
            data.append("{\"file\":");
            Json.quote(data, f.getAbsolutePath());
            data.append(",\"rec\":").append(text).append('}');
        }
        data.append(']');
        if (n == 0) {
            throw new IllegalArgumentException("no recordings found in " + dirs);
        }

        String html = template()
                // "</" can only occur inside JSON strings, where "<\/" is an equivalent escape.
                .replace("/*__RECORDINGS__*/[]", data.toString().replace("</", "<\\/"))
                .replace("__AGENT_JAR__", Json.quote(agentJar()).replace("</", "<\\/"));
        File parent = out.getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        Files.write(out.toPath(), html.getBytes(StandardCharsets.UTF_8));
        System.out.println(n + " recording(s) included");
        return out;
    }

    private static String template() throws IOException {
        InputStream in = ReportGenerator.class.getResourceAsStream("/io/debugassist/cli/viewer.html");
        if (in == null) {
            throw new IllegalStateException("viewer.html missing from the agent jar");
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

    private static String agentJar() {
        try {
            return new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
        } catch (Exception e) {
            return "debug-agent.jar";
        }
    }
}
