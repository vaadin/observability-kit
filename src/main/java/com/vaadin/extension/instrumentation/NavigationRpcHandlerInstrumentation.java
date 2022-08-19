package com.vaadin.extension.instrumentation;

import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.server.communication.rpc.NavigationRpcHandler;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * Instruments NavigationRpcHandler in order to add a span when the handler is called
 * with a navigation message from the client. This can be a router link navigation, or
 * history navigation (back / forward button).
 * This handler is only called when using the (deprecated) V14 bootstrap mode.
 */
public class NavigationRpcHandlerInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed(
                "com.vaadin.flow.server.communication.rpc.NavigationRpcHandler");
    }

    // This instrumentation only matches NavigationRpcHandler on the rpcEvent stack.
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named(
                "com.vaadin.flow.server.communication.rpc.NavigationRpcHandler");
    }

    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("handle"),
                this.getClass().getName() + "$MethodAdvice");
    }

    @SuppressWarnings("unused")
    public static class MethodAdvice {
        @Advice.OnMethodEnter()
        public static void onEnter(@Advice.This NavigationRpcHandler navigationRpcHandler,
                                   @Advice.Origin("#m") String methodName,
                                   @Advice.Local("otelSpan") Span span) {

            Tracer tracer = InstrumentationHelper.getTracer();

            span = tracer.spanBuilder(
                    navigationRpcHandler.getClass().getSimpleName() + "."
                            + methodName).startSpan();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(
                @Advice.Thrown
                Throwable throwable,
                @Advice.Local("otelSpan")
                Span span) {
            if (span == null) {
                return;
            }
            if (throwable != null) {
                // This will actually mark the span as having an exception which
                // shows on the dataUI
                span.setStatus(StatusCode.ERROR, throwable.getMessage());
                // Add log trace of exception.
                span.recordException(throwable);
            }
            span.end();
        }
    }
}
