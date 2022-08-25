package com.vaadin.extension.instrumentation;

import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.server.communication.SessionRequestHandler;

/**
 * Instruments SessionRequestHandler
 */
public class SessionRequestHandlerInstrumentation
        implements TypeInstrumentation {
    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed(
                "com.vaadin.flow.server.communication.SessionRequestHandler");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named(
                "com.vaadin.flow.server.communication.SessionRequestHandler");
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("handleNode"),
                this.getClass().getName() + "$MethodAdvice");
    }

    public static class MethodAdvice {
        @Advice.OnMethodEnter()
        public static void onEnter(
                @Advice.This SessionRequestHandler sessionRequestHandler,
                @Advice.Origin("#m") String methodName,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            String spanName = sessionRequestHandler.getClass().getSimpleName()
                    + "." + methodName;
            span = InstrumentationHelper.startSpan(spanName);

            Context context = currentContext().with(span);
            scope = context.makeCurrent();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress =
                Throwable.class)
        public static void onExit(@Advice.Thrown Throwable throwable,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            InstrumentationHelper.endSpan(span, throwable, scope);
        }
    }
}