package io.debugassist.agent.core;

import io.debugassist.agent.AgentConfig;

/**
 * Called from the code injected into the application. Every public method here must be cheap, must never throw
 * into the application and must tolerate being called before {@link #init}.
 */
public final class Recorder {

    private static final Class<?>[] STRING = {String.class};
    private static final Class<?>[] STRING_STRING = {String.class, String.class};
    private static final Class<?>[] STRING_VARARGS = {String.class, String[].class};

    private static volatile AgentConfig config;
    private static ValueSerializer serializer;
    private static RecordingWriter writer;

    private static final ThreadLocal<TraceContext> CONTEXT = new ThreadLocal<TraceContext>() {
        @Override
        protected TraceContext initialValue() {
            return new TraceContext();
        }
    };

    private Recorder() {
    }

    public static void init(AgentConfig cfg) {
        serializer = new ValueSerializer(cfg);
        writer = new RecordingWriter(cfg);
        config = cfg;
    }

    static AgentConfig config() {
        return config;
    }

    /** The trace id of the request running on this thread, or null. Handy for logging (e.g. put it in the MDC). */
    public static String currentTraceId() {
        TraceContext ctx = CONTEXT.get();
        return ctx.active() ? ctx.traceId : null;
    }

    // ------------------------------------------------------------------ application methods

    public static Object enter(Class<?> type, String signature, Object self, Object[] args) {
        if (config == null) {
            return null;
        }
        TraceContext ctx = CONTEXT.get();
        if (ctx.busy) {
            return null;
        }
        ctx.busy = true;
        try {
            if (!ctx.active()) {
                ctx.begin(null);
            } else if (ctx.calls >= config.maxCallsPerTrace) {
                ctx.truncated = true;
                return null;
            }
            MethodMeta meta = MethodMeta.of(type, signature);
            CallNode node = new CallNode(ctx.nextId(), CallNode.METHOD);
            node.meta = meta;
            node.self = self == null ? 0 : System.identityHashCode(self);
            node.args = serializeArgs(meta, args);
            ctx.push(node);
            ctx.methodCalls++;
            return node;
        } catch (Throwable t) {
            return null;
        } finally {
            ctx.busy = false;
        }
    }

    public static void exit(Object token, Object[] args, Object result, Throwable thrown) {
        if (!(token instanceof CallNode)) {
            return;
        }
        TraceContext ctx = CONTEXT.get();
        ctx.busy = true;
        try {
            CallNode node = (CallNode) token;
            node.finish();
            if (thrown != null) {
                node.failed(thrown, true);
            } else if (!node.meta.isVoid) {
                node.result = serializer.toJson(result);
            }
            if (config.captureArgsAfter && args != null && args.length > 0) {
                String[] after = serializeArgs(node.meta, args);
                for (int i = 0; i < after.length; i++) {
                    if (!after[i].equals(node.args[i])) {
                        node.argsAfter = after; // the method changed one of its arguments
                        break;
                    }
                }
            }
            complete(ctx, node);
        } catch (Throwable ignored) {
            // never break the application
        } finally {
            ctx.busy = false;
        }
    }

    private static String[] serializeArgs(MethodMeta meta, Object[] args) {
        if (args == null) {
            return new String[0];
        }
        String[] out = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            boolean masked = i < meta.paramNames.length && config.isMasked(meta.paramNames[i]);
            out[i] = masked ? "\"****\"" : serializer.toJson(args[i]);
        }
        return out;
    }

    private static void complete(TraceContext ctx, CallNode node) {
        if (ctx.pop(node)) {
            if (ctx.methodCalls > 0) {
                writer.submit(ctx); // requests that never reached application code (health checks, static files) are skipped
            }
            ctx.root = null;
        }
    }

    // ------------------------------------------------------------------ incoming HTTP (javax / jakarta servlet)

    public static Object httpServerEnter(Object request, Object response) {
        if (config == null) {
            return null;
        }
        TraceContext ctx = CONTEXT.get();
        if (ctx.busy) {
            return null;
        }
        ctx.busy = true;
        try {
            String incoming = incomingTraceId(request);
            if (incoming == null) {
                // Error/forward dispatch of a request we already tagged (e.g. Spring Boot's /error): keep the id the
                // caller sees in the response header.
                Object tagged = Reflect.call(response, "getHeader", STRING, AgentConfig.TRACE_HEADER);
                incoming = tagged == null ? null : tagged.toString();
            }
            if (!ctx.active()) {
                ctx.begin(incoming);
            } else if (incoming != null && ctx.root != null && ctx.root.kind.equals(CallNode.METHOD)) {
                ctx.traceId = incoming; // an application filter started recording before the servlet: adopt caller's id
            }
            CallNode node = new CallNode(ctx.nextId(), CallNode.HTTP_IN);
            node.httpMethod = Reflect.callString(request, "getMethod");
            String uri = Reflect.callString(request, "getRequestURI");
            String query = Reflect.callString(request, "getQueryString");
            node.httpUrl = query == null ? uri : uri + "?" + query;
            Reflect.call(response, "setHeader", STRING_STRING, AgentConfig.TRACE_HEADER, ctx.traceId);
            ctx.push(node);
            return node;
        } catch (Throwable t) {
            return null;
        } finally {
            ctx.busy = false;
        }
    }

    public static void httpServerExit(Object token, Object response, Throwable thrown) {
        if (!(token instanceof CallNode)) {
            return;
        }
        TraceContext ctx = CONTEXT.get();
        ctx.busy = true;
        try {
            CallNode node = (CallNode) token;
            node.finish();
            Object status = Reflect.call(response, "getStatus");
            node.httpStatus = status instanceof Integer ? (Integer) status : 0;
            if (thrown != null) {
                node.failed(thrown, true);
                if (node.httpStatus < 400) {
                    node.httpStatus = 500;
                }
            }
            complete(ctx, node);
        } catch (Throwable ignored) {
            // never break the application
        } finally {
            ctx.busy = false;
        }
    }

    private static String incomingTraceId(Object request) {
        Object header = Reflect.call(request, "getHeader", STRING, AgentConfig.TRACE_HEADER);
        String id = header == null ? null : header.toString().trim();
        if (id != null && id.matches("[A-Za-z0-9._-]{1,64}")) {
            return id;
        }
        // W3C trace context (OpenTelemetry, Spring Cloud Sleuth/Micrometer): 00-<32 hex trace id>-<span>-<flags>
        String tp = (String) Reflect.call(request, "getHeader", STRING, "traceparent");
        if (tp != null) {
            String[] parts = tp.trim().split("-");
            if (parts.length >= 2 && parts[1].matches("[0-9a-f]{32}")) {
                return parts[1];
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ outgoing HTTP

    /** Spring {@code ClientHttpRequest.execute()}: forwards the trace id and records the call in the tree. */
    public static Object springClientEnter(Object request) {
        if (config == null) {
            return null;
        }
        TraceContext ctx = CONTEXT.get();
        if (ctx.busy || !ctx.active()) {
            return null;
        }
        ctx.busy = true;
        try {
            Object headers = Reflect.call(request, "getHeaders");
            Reflect.call(headers, "set", STRING_STRING, AgentConfig.TRACE_HEADER, ctx.traceId);
            String url = Reflect.callString(request, "getURI");
            CallNode top = ctx.stack.peek();
            if (top != null && CallNode.HTTP_OUT.equals(top.kind) && String.valueOf(url).equals(top.httpUrl)) {
                return null; // wrapping request (e.g. interceptors) around the same call
            }
            CallNode node = new CallNode(ctx.nextId(), CallNode.HTTP_OUT);
            node.httpMethod = Reflect.callString(request, "getMethod");
            node.httpUrl = url;
            ctx.push(node);
            return node;
        } catch (Throwable t) {
            return null;
        } finally {
            ctx.busy = false;
        }
    }

    public static void springClientExit(Object token, Object response, Throwable thrown) {
        if (!(token instanceof CallNode)) {
            return;
        }
        TraceContext ctx = CONTEXT.get();
        ctx.busy = true;
        try {
            CallNode node = (CallNode) token;
            node.finish();
            Object code = Reflect.call(response, "getStatusCode");
            Object value = Reflect.call(code, "value");
            if (value == null) {
                value = Reflect.call(response, "getRawStatusCode");
            }
            node.httpStatus = value instanceof Integer ? (Integer) value : 0;
            if (thrown != null) {
                node.failed(thrown, false);
            }
            complete(ctx, node);
        } catch (Throwable ignored) {
            // never break the application
        } finally {
            ctx.busy = false;
        }
    }

    /** Feign {@code RequestTemplate.header(name, values...)} or Apache HttpClient {@code setHeader(name, value)}. */
    public static void injectHeader(Object target, String method) {
        if (config == null) {
            return;
        }
        TraceContext ctx = CONTEXT.get();
        if (!ctx.active()) {
            return;
        }
        if ("header".equals(method)) {
            Reflect.call(target, method, STRING_VARARGS, AgentConfig.TRACE_HEADER, new String[]{ctx.traceId});
        } else {
            Reflect.call(target, method, STRING_STRING, AgentConfig.TRACE_HEADER, ctx.traceId);
        }
    }
}
