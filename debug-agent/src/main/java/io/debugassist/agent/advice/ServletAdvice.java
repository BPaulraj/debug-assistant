package io.debugassist.agent.advice;

import io.debugassist.agent.core.Recorder;
import net.bytebuddy.asm.Advice;

/** {@code HttpServlet.service(ServletRequest, ServletResponse)} for both javax.servlet and jakarta.servlet. */
public final class ServletAdvice {

    private ServletAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Object enter(@Advice.Argument(0) Object request, @Advice.Argument(1) Object response) {
        return Recorder.httpServerEnter(request, response);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter Object token, @Advice.Argument(1) Object response,
                            @Advice.Thrown Throwable thrown) {
        Recorder.httpServerExit(token, response, thrown);
    }
}
