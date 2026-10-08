package io.debugassist.agent.advice;

import io.debugassist.agent.core.Recorder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/** Inlined into every recorded method of the application. Failures are suppressed so the app is never affected. */
public final class MethodAdvice {

    private MethodAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Object enter(@Advice.Origin Class<?> type,
                               @Advice.Origin("#m#d") String signature,
                               @Advice.This(optional = true) Object self,
                               @Advice.AllArguments Object[] args) {
        return Recorder.enter(type, signature, self, args);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter Object token,
                            @Advice.AllArguments Object[] args,
                            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result,
                            @Advice.Thrown Throwable thrown) {
        Recorder.exit(token, args, result, thrown);
    }
}
