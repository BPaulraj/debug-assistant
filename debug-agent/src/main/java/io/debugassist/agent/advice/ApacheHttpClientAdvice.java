package io.debugassist.agent.advice;

import io.debugassist.agent.core.Recorder;
import net.bytebuddy.asm.Advice;

/** Apache HttpClient 4 and 5 {@code doExecute(host, request, context)} - adds the trace header. */
public final class ApacheHttpClientAdvice {

    private ApacheHttpClientAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(1) Object request) {
        Recorder.injectHeader(request, "setHeader");
    }
}
