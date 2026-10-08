package io.debugassist.agent.advice;

import io.debugassist.agent.core.Recorder;
import net.bytebuddy.asm.Advice;

/** {@code feign.SynchronousMethodHandler.targetRequest(RequestTemplate)} - adds the trace header to Feign calls. */
public final class FeignAdvice {

    private FeignAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(0) Object requestTemplate) {
        Recorder.injectHeader(requestTemplate, "header");
    }
}
