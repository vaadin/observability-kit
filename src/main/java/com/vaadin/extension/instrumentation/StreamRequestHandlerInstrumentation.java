package com.vaadin.extension.instrumentation;

import java.time.Instant;

import static com.vaadin.flow.server.Constants.VAADIN_MAPPING;
import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.communication.StreamRequestHandler;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.instrumenter.LocalRootSpan;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments StreamRequestHandler in order to add a span when the handler is
 * called.
 */
public class StreamRequestHandlerInstrumentation
        implements TypeInstrumentation {
    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed(
                "com.vaadin.flow.server.communication.StreamRequestHandler");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named(
                "com.vaadin.flow.server.communication.StreamRequestHandler");
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("handleRequest")
                        .and(takesArgument(1,
                                named("com.vaadin.flow.server.VaadinRequest"))),
                this.getClass().getName() + "$HandleRequestAdvice");
    }

    @SuppressWarnings("unused")
    public static class HandleRequestAdvice {
        @Advice.OnMethodEnter()
        public static void onEnter(
                @Advice.Local("startTimestamp") Instant startTimestamp) {
            startTimestamp = Instant.now();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(
                @Advice.This StreamRequestHandler streamRequestHandler,
                @Advice.Origin("#m") String methodName,
                @Advice.Thrown Throwable throwable,
                @Advice.Return boolean handled,
                @Advice.Argument(1) VaadinRequest request,
                @Advice.Local("startTimestamp") Instant startTimestamp) {
            if (!handled) {
                // Do not add a span if static file is not served from here.
                return;
            }

            final String spanName =
                    streamRequestHandler.getClass().getSimpleName()
                    + "." + methodName;
            Span span = InstrumentationHelper.startSpan(spanName);

            final String requestFilename = request.getPathInfo();
            Span localRootSpan = LocalRootSpan.current();
            localRootSpan.updateName(requestFilename);

            InstrumentationHelper.endSpan(span, throwable, null);
        }
    }
}
