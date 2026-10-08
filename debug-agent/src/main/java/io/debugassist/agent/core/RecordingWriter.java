package io.debugassist.agent.core;

import io.debugassist.agent.AgentConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes one JSON file per completed trace on a background thread, so request threads only pay for building the
 * string. Layout: {@code <out>/traces/<traceId>/<app>-<startMs>-<seq>.json}
 */
final class RecordingWriter {

    private final AgentConfig config;
    private final AtomicLong seq = new AtomicLong();
    private final ExecutorService executor;
    private volatile String host;
    private final String pid;

    RecordingWriter(AgentConfig config) {
        this.config = config;
        this.pid = pid();
        // Bounded queue: under extreme load recordings are dropped rather than exhausting memory.
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(10000), new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "debug-agent-writer");
                t.setDaemon(true);
                return t;
            }
        }, new ThreadPoolExecutor.DiscardPolicy());
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                executor.shutdown();
                try {
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "debug-agent-flush"));
    }

    void submit(TraceContext ctx) {
        CallNode root = ctx.root;
        root.release();
        final String traceId = ctx.traceId;
        final long start = ctx.startMs;
        final String thread = Thread.currentThread().getName();

        StringBuilder body = new StringBuilder(1024);
        root.toJson(body);
        final String rootJson = body.toString();
        final String summary = summary(root);
        final boolean error = root.excType != null || root.httpStatus >= 500;
        final int calls = ctx.calls;
        final boolean truncated = ctx.truncated;
        final long durUs = root.durationMicros;

        executor.execute(new Runnable() {
            @Override
            public void run() {
                write(traceId, start, thread, summary, error, calls, truncated, durUs, rootJson);
            }
        });
    }

    private void write(String traceId, long start, String thread, String summary, boolean error, int calls,
                       boolean truncated, long durUs, String rootJson) {
        try {
            StringBuilder sb = new StringBuilder(rootJson.length() + 512);
            sb.append("{\"format\":1,\"traceId\":");
            Json.quote(sb, traceId);
            sb.append(",\"app\":");
            Json.quote(sb, config.app);
            sb.append(",\"host\":");
            Json.quote(sb, host());
            sb.append(",\"pid\":");
            Json.quote(sb, pid);
            sb.append(",\"thread\":");
            Json.quote(sb, thread);
            sb.append(",\"start\":").append(start);
            sb.append(",\"durUs\":").append(durUs);
            sb.append(",\"summary\":");
            Json.quote(sb, summary);
            sb.append(",\"error\":").append(error);
            sb.append(",\"calls\":").append(calls);
            sb.append(",\"truncated\":").append(truncated);
            sb.append(",\"root\":").append(rootJson).append("}\n");

            File dir = new File(new File(config.outDir, "traces"), safe(traceId));
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                return;
            }
            String name = safe(config.app) + "-" + start + "-" + seq.incrementAndGet();
            File tmp = new File(dir, name + ".tmp");
            OutputStream out = new FileOutputStream(tmp);
            try {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            File target = new File(dir, name + ".json");
            if (!tmp.renameTo(target)) {
                tmp.delete();
            }
        } catch (Throwable t) {
            if (config.verbose) {
                System.err.println("[debug-agent] could not write recording " + traceId + ": " + t);
            }
        }
    }

    private static String summary(CallNode root) {
        if (root.meta == null) {
            String s = root.httpMethod + " " + root.httpUrl;
            return root.httpStatus > 0 ? s + " -> " + root.httpStatus : s;
        }
        String cls = root.meta.className;
        String s = cls.substring(cls.lastIndexOf('.') + 1) + "." + root.meta.methodName + "()";
        return root.excType != null ? s + " -> " + root.excType.substring(root.excType.lastIndexOf('.') + 1) : s;
    }

    private String host() {
        String h = host;
        if (h == null) {
            try {
                h = InetAddress.getLocalHost().getHostName();
            } catch (Throwable t) {
                h = "unknown";
            }
            host = h;
        }
        return h;
    }

    private static String pid() {
        try {
            String n = ManagementFactory.getRuntimeMXBean().getName();
            return n.substring(0, n.indexOf('@'));
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
