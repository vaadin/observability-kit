package com.vaadin.extension.instrumentation;

import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.server.communication.rpc.NavigationRpcHandler;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments NavigationRpcHandler in order to add a span when the handler is
 * called with a navigation message from the client. This can be a router link
 * navigation, or history navigation (back / forward button). This handler is
 * only called when using the (deprecated) V14 bootstrap mode.
 */
public class NavigationRpcHandlerInstrumentation
        implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed(
                "com.vaadin.flow.server.communication.rpc.NavigationRpcHandler");
    }

    // This instrumentation only matches NavigationRpcHandler on the rpcEvent
    // stack.
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
        public static void onEnter(
                @Advice.This NavigationRpcHandler navigationRpcHandler,
                @Advice.Origin("#m") String methodName,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {

            Tracer tracer = InstrumentationHelper.getTracer();
            String spanName = navigationRpcHandler.getClass().getSimpleName()
                    + "." + methodName;
            span = tracer.spanBuilder(spanName).startSpan();

            Context context = currentContext().with(span);
            scope = context.makeCurrent();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Thrown Throwable throwable,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            if (scope != null) {
                scope.close();
            }
            if (span == null) {
                return;
            }
            InstrumentationHelper.handleException(span, throwable);
            span.end();
        }
    }
}
