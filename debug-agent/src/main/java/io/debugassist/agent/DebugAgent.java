package io.debugassist.agent;

import io.debugassist.agent.advice.ApacheHttpClientAdvice;
import io.debugassist.agent.advice.FeignAdvice;
import io.debugassist.agent.advice.MethodAdvice;
import io.debugassist.agent.advice.ServletAdvice;
import io.debugassist.agent.advice.SpringHttpClientAdvice;
import io.debugassist.agent.core.Recorder;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Entry point: {@code java -javaagent:debug-agent.jar=packages=com.acme,app=order-service -jar app.jar}
 */
public final class DebugAgent {

    private DebugAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            AgentConfig config = AgentConfig.parse(agentArgs);
            if (!config.enabled) {
                log("disabled (enabled=false)");
                return;
            }
            if (config.packages.isEmpty()) {
                log("no 'packages' configured - nothing to record. Example: -javaagent:debug-agent.jar=packages=com.acme");
                return;
            }
            Recorder.init(config);
            install(config, inst);
            log("recording " + config);
        } catch (Throwable t) {
            // The agent must never stop the application from starting.
            log("failed to start, application continues without recording: " + t);
        }
    }

    private static void install(final AgentConfig config, Instrumentation inst) {
        ElementMatcher.Junction<TypeDescription> userPackages = none();
        for (String pkg : config.packages) {
            userPackages = userPackages.or(nameStartsWith(pkg.endsWith(".") ? pkg : pkg + "."));
        }
        ElementMatcher.Junction<TypeDescription> excluded = none();
        for (String ex : config.excludes) {
            excluded = excluded.or(nameStartsWith(ex));
        }

        ElementMatcher.Junction<TypeDescription> userClasses = userPackages
                .and(not(isInterface()))
                .and(not(isAnnotation()))
                .and(not(nameContains("$$")))          // Spring CGLIB / Hibernate proxies: the real class is recorded instead
                .and(not(nameContains("$HibernateProxy")))
                .and(not(isAnnotatedWith(named("org.springframework.context.annotation.Configuration"))))
                .and(not(isAnnotatedWith(named("org.springframework.boot.autoconfigure.SpringBootApplication"))))
                .and(not(excluded));

        // JDK dynamic proxies for the app's own interfaces (e.g. Spring Data repositories, Feign clients).
        ElementMatcher.Junction<TypeDescription> userProxies =
                nameStartsWith("jdk.proxy").or(nameStartsWith("com.sun.proxy.")).or(nameContains(".$Proxy"))
                        .and(hasSuperType(isInterface().and(userPackages)));

        ElementMatcher.Junction<MethodDescription> methods = isMethod()
                .and(not(isAbstract()))
                .and(not(isNative()))
                .and(not(isSynthetic()))
                .and(not(isBridge()))
                .and(not(named("toString").or(named("hashCode")).or(named("equals")).or(named("finalize"))))
                .and(not(isMain()));
        if (config.skipAccessors) {
            methods = methods.and(not(isGetter())).and(not(isSetter())).and(not(isRecordAccessor()));
        }

        ElementMatcher.Junction<MethodDescription> proxyMethods = isMethod().and(isPublic()).and(not(isStatic()))
                .and(not(named("toString").or(named("hashCode")).or(named("equals"))));

        final Advice methodAdvice = Advice.to(MethodAdvice.class);
        final Advice servletAdvice = Advice.to(ServletAdvice.class);
        final Advice springClientAdvice = Advice.to(SpringHttpClientAdvice.class);
        final Advice feignAdvice = Advice.to(FeignAdvice.class);
        final Advice apacheAdvice = Advice.to(ApacheHttpClientAdvice.class);

        AgentBuilder.Listener listener = config.verbose ? new VerboseListener() : AgentBuilder.Listener.NoOp.INSTANCE;

        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.TypeStrategy.Default.DECORATE)
                .with(listener)
                .ignore(nameStartsWith("io.debugassist.agent.")
                        .or(nameStartsWith("io.debugassist.shaded."))
                        .or(nameStartsWith("io.debugassist.cli."))
                        .or(isSynthetic()))
                // 1) the application's own code
                .type(userClasses, canSeeAgent())
                .transform(adviceOn(methodAdvice, methods))
                .assureReadEdgeTo(inst, Recorder.class)
                .type(userProxies, canSeeAgent())
                .transform(adviceOn(methodAdvice, proxyMethods))
                .assureReadEdgeTo(inst, Recorder.class)
                // 2) incoming HTTP: javax (Spring Boot 2 / Java 8-11) and jakarta (Spring Boot 3 / Java 17+)
                .type(named("javax.servlet.http.HttpServlet").or(named("jakarta.servlet.http.HttpServlet")), canSeeAgent())
                .transform(adviceOn(servletAdvice, named("service").and(isPublic()).and(takesArguments(2))))
                .assureReadEdgeTo(inst, Recorder.class)
                // 3) outgoing HTTP: pass the trace id on to the next application
                .type(named("org.springframework.http.client.AbstractClientHttpRequest"), canSeeAgent())
                .transform(adviceOn(springClientAdvice, named("execute").and(takesArguments(0))))
                .assureReadEdgeTo(inst, Recorder.class)
                .type(named("feign.SynchronousMethodHandler"), canSeeAgent())
                .transform(adviceOn(feignAdvice, named("targetRequest").and(takesArguments(1))))
                .assureReadEdgeTo(inst, Recorder.class)
                .type(nameStartsWith("org.apache.http.impl.").or(nameStartsWith("org.apache.hc.client5.http.impl."))
                        .and(not(isAbstract()))
                        .and(hasSuperType(named("org.apache.http.impl.client.CloseableHttpClient")
                                .or(named("org.apache.hc.client5.http.impl.classic.CloseableHttpClient")))), canSeeAgent())
                .transform(adviceOn(apacheAdvice, named("doExecute").and(takesArguments(3))))
                .assureReadEdgeTo(inst, Recorder.class)
                .installOn(inst);
    }

    private static AgentBuilder.Transformer adviceOn(final Advice advice, final ElementMatcher<? super MethodDescription> where) {
        return new AgentBuilder.Transformer() {
            @Override
            public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription type,
                                                    ClassLoader loader, JavaModule module, ProtectionDomain pd) {
                return builder.visit(advice.on(where));
            }
        };
    }

    /** Only instrument classes whose class loader can reach the agent, otherwise the injected calls could not link. */
    private static ElementMatcher<ClassLoader> canSeeAgent() {
        return new ElementMatcher<ClassLoader>() {
            @Override
            public boolean matches(ClassLoader loader) {
                if (loader == null) {
                    return false;
                }
                try {
                    return Class.forName(Recorder.class.getName(), false, loader) == Recorder.class;
                } catch (Throwable t) {
                    return false;
                }
            }
        };
    }

    /** {@code sku()} on {@code record OrderLine(String sku, ...)} - the record equivalent of a getter. */
    private static ElementMatcher.Junction<MethodDescription> isRecordAccessor() {
        return new ElementMatcher.Junction.AbstractBase<MethodDescription>() {
            @Override
            public boolean matches(MethodDescription m) {
                TypeDescription owner = m.getDeclaringType().asErasure();
                return owner.isRecord() && m.getParameters().isEmpty() && !m.isStatic()
                        && !owner.getRecordComponents().filter(named(m.getName())).isEmpty();
            }
        };
    }

    private static ElementMatcher.Junction<MethodDescription> isMain() {
        return named("main").and(isStatic()).and(takesArguments(String[].class));
    }

    static void log(String msg) {
        System.err.println("[debug-agent] " + msg);
    }

    private static final class VerboseListener extends AgentBuilder.Listener.Adapter {
        @Override
        public void onTransformation(TypeDescription type, ClassLoader loader, JavaModule module, boolean loaded,
                                     DynamicType dynamicType) {
            log("instrumented " + type.getName());
        }

        @Override
        public void onError(String typeName, ClassLoader loader, JavaModule module, boolean loaded, Throwable t) {
            log("could not instrument " + typeName + ": " + t);
        }
    }
}
