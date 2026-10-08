package io.debugassist.agent.core;

import java.util.ArrayDeque;
import java.util.concurrent.ThreadLocalRandom;

/** Per-thread recording state: the trace id and the stack of calls currently in progress. */
final class TraceContext {

    final ArrayDeque<CallNode> stack = new ArrayDeque<CallNode>();
    String traceId;
    CallNode root;
    long startMs;
    int nextId;
    int calls;
    int methodCalls;
    boolean truncated;
    /** Set while the recorder itself runs, so serializing a value never records its own calls. */
    boolean busy;

    boolean active() {
        return !stack.isEmpty();
    }

    void begin(String incomingTraceId) {
        traceId = incomingTraceId != null ? incomingTraceId : newTraceId();
        root = null;
        startMs = System.currentTimeMillis();
        nextId = 0;
        calls = 0;
        methodCalls = 0;
        truncated = false;
    }

    int nextId() {
        return ++nextId;
    }

    void push(CallNode node) {
        CallNode parent = stack.peek();
        if (parent == null) {
            root = node;
        } else {
            parent.add(node);
        }
        stack.push(node);
        calls++;
    }

    /** Pops {@code node} (and anything left above it). Returns true when the trace is complete. */
    boolean pop(CallNode node) {
        if (!stack.contains(node)) {
            return false;
        }
        while (!stack.isEmpty() && stack.pop() != node) {
            // unwind calls whose exit was never observed
        }
        return stack.isEmpty();
    }

    static String newTraceId() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return String.format("%016x%016x", r.nextLong(), r.nextLong());
    }
}
