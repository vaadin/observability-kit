package com.vaadin.extension.instrumentation.server;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.implementsInterface;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.vaadin.flow.server.ErrorEvent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.instrumenter.LocalRootSpan;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments the ErrorHandler that is executed for unhandled exceptions from
 * request handlers.
 */
public class ErrorHandlerInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed("com.vaadin.flow.server.ErrorHandler");
    }

    public ElementMatcher<TypeDescription> typeMatcher() {
        return implementsInterface(
                named("com.vaadin.flow.server.ErrorHandler"));
    }

    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("error"),
                this.getClass().getName() + "$ErrorAdvice");
    }

    @SuppressWarnings("unused")
    public static class ErrorAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(@Advice.Argument(0) ErrorEvent event,
                @Advice.Local("otelContext") Context context,
                @Advice.Local("otelScope") Scope scope) {
            final Span rootSpan = LocalRootSpan.current();
            // Also mark root span as having an error, as several monitoring
            // solutions (New Relic, DataDog) only monitor for errors in root /
            // server spans
            String errorName = event.getThrowable().getClass()
                    .getCanonicalName() + ": "
                    + event.getThrowable().getMessage();
            rootSpan.setStatus(StatusCode.ERROR, errorName);
        }
    }
}
