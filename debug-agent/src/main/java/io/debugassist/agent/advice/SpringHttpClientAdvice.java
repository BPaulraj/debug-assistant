package io.debugassist.agent.advice;

import io.debugassist.agent.core.Recorder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/** {@code AbstractClientHttpRequest.execute()} - covers RestTemplate and RestClient (Spring 5 and 6). */
public final class SpringHttpClientAdvice {

    private SpringHttpClientAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Object enter(@Advice.This Object request) {
        return Recorder.springClientEnter(request);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter Object token,
                            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object response,
                            @Advice.Thrown Throwable thrown) {
        Recorder.springClientExit(token, response, thrown);
    }
}
