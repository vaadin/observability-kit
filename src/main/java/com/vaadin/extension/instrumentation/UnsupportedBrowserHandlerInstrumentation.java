package com.vaadin.extension.instrumentation;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.server.UnsupportedBrowserHandler;
import com.vaadin.flow.server.VaadinRequest;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.time.Instant;

/**
 * Instruments UnsupportedBrowserHandler to add a span for its execution
 */
public class UnsupportedBrowserHandlerInstrumentation
        implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed(
                "com.vaadin.flow.server.communication.UnsupportedBrowserHandler");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named(
                "com.vaadin.flow.server.communication.UnsupportedBrowserHandler");
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("synchronizedHandleRequest"),
                this.getClass().getName() + "$SynchronizedHandleRequestAdvice");
    }

    @SuppressWarnings("unused")
    public static class SynchronizedHandleRequestAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(
                @Advice.Local("startTimestamp") Instant startTimestamp) {
            startTimestamp = Instant.now();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(
                @Advice.This UnsupportedBrowserHandler unsupportedBrowserHandler,
                @Advice.Origin("#m") String methodName,
                @Advice.Thrown Throwable throwable,
                @Advice.Argument(1) VaadinRequest request,
                @Advice.Return boolean handled,
                @Advice.Local("startTimestamp") Instant startTimestamp) {
            if (handled) {
                String spanName = unsupportedBrowserHandler.getClass()
                        .getSimpleName() + "." + methodName;
                Span span = InstrumentationHelper.startSpan(spanName,
                        startTimestamp);
                InstrumentationHelper.endSpan(span, throwable, null);
            }
        }
    }
}
