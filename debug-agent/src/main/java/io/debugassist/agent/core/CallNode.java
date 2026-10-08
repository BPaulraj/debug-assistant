package io.debugassist.agent.core;

import java.util.ArrayList;
import java.util.List;

/** One recorded call: an application method, an incoming HTTP request or an outgoing HTTP call. */
final class CallNode {

    static final String METHOD = "method";
    static final String HTTP_IN = "http-in";
    static final String HTTP_OUT = "http-out";

    final int id;
    final String kind;
    final long startMs = System.currentTimeMillis();
    final long startNanos = System.nanoTime();
    long durationMicros = -1;
    List<CallNode> children;

    // kind == method
    MethodMeta meta;
    int self;
    String[] args;
    String[] argsAfter;
    String result;

    // kind == http-*
    String httpMethod;
    String httpUrl;
    int httpStatus;

    // failure
    Throwable thrown;
    String excType;
    String excMessage;
    boolean excOrigin;
    List<String> excStack;

    CallNode(int id, String kind) {
        this.id = id;
        this.kind = kind;
    }

    void add(CallNode child) {
        if (children == null) {
            children = new ArrayList<CallNode>(4);
        }
        children.add(child);
    }

    void finish() {
        durationMicros = (System.nanoTime() - startNanos) / 1000;
    }

    void failed(Throwable t, boolean withStack) {
        thrown = t;
        excType = t.getClass().getName();
        excMessage = t.getMessage();
        excOrigin = true;
        if (children != null) {
            for (CallNode c : children) {
                if (c.thrown != null && isSameOrCause(t, c.thrown)) {
                    excOrigin = false; // it was thrown (or wrapped) from a child call
                    break;
                }
            }
        }
        if (withStack && excOrigin) {
            excStack = new ArrayList<String>();
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < st.length && i < 12; i++) {
                excStack.add(st[i].toString());
            }
        }
    }

    private static boolean isSameOrCause(Throwable t, Throwable candidate) {
        for (int i = 0; t != null && i < 20; i++, t = t.getCause()) {
            if (t == candidate) {
                return true;
            }
        }
        return false;
    }

    /** Releases references to live application objects once the trace is complete. */
    void release() {
        thrown = null;
        if (children != null) {
            for (CallNode c : children) {
                c.release();
            }
        }
    }

    void toJson(StringBuilder sb) {
        sb.append("{\"id\":").append(id).append(",\"kind\":\"").append(kind).append('"');
        if (meta != null) {
            sb.append(",\"cls\":");
            Json.quote(sb, meta.className);
            sb.append(",\"m\":");
            Json.quote(sb, meta.methodName);
            sb.append(",\"desc\":");
            Json.quote(sb, meta.descriptor);
            sb.append(",\"static\":").append(meta.isStatic);
            sb.append(",\"vis\":\"").append(meta.visibility).append('"');
            if (meta.proxy) {
                sb.append(",\"proxy\":true");
            }
            sb.append(",\"self\":").append(self);
            sb.append(",\"params\":[");
            for (int i = 0; i < meta.paramTypes.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"n\":");
                Json.quote(sb, meta.paramNames[i]);
                sb.append(",\"t\":");
                Json.quote(sb, meta.paramTypes[i]);
                sb.append(",\"r\":");
                Json.quote(sb, meta.paramRawTypes[i]);
                sb.append('}');
            }
            sb.append("],\"ret\":");
            Json.quote(sb, meta.returnType);
            sb.append(",\"retRaw\":");
            Json.quote(sb, meta.returnRawType);
            appendArray(sb, "args", args);
            appendArray(sb, "argsAfter", argsAfter);
            if (result != null) {
                sb.append(",\"result\":").append(result);
            }
        } else {
            sb.append(",\"http\":{\"method\":");
            Json.quote(sb, String.valueOf(httpMethod));
            sb.append(",\"url\":");
            Json.quote(sb, String.valueOf(httpUrl));
            sb.append(",\"status\":").append(httpStatus).append('}');
        }
        if (excType != null) {
            sb.append(",\"exc\":{\"type\":");
            Json.quote(sb, excType);
            sb.append(",\"msg\":");
            if (excMessage == null) {
                sb.append("null");
            } else {
                Json.quote(sb, excMessage);
            }
            sb.append(",\"origin\":").append(excOrigin);
            if (excStack != null) {
                sb.append(",\"stack\":[");
                for (int i = 0; i < excStack.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    Json.quote(sb, excStack.get(i));
                }
                sb.append(']');
            }
            sb.append('}');
        }
        sb.append(",\"start\":").append(startMs).append(",\"durUs\":").append(durationMicros);
        if (children != null) {
            sb.append(",\"children\":[");
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                children.get(i).toJson(sb);
            }
            sb.append(']');
        }
        sb.append('}');
    }

    private static void appendArray(StringBuilder sb, String name, String[] values) {
        if (values == null) {
            return;
        }
        sb.append(",\"").append(name).append("\":[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
        sb.append(']');
    }
}
