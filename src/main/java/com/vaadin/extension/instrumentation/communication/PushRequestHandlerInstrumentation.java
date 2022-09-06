package com.vaadin.extension.instrumentation.communication;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.server.VaadinRequest;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.api.instrumenter.LocalRootSpan;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.time.Instant;

public class PushRequestHandlerInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed(
                "com.vaadin.flow.server.communication.PushRequestHandler");
    }

    // This instrumentation only matches AttachExistingElementRpcHandler on the
    // rpcEvent stack.
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("com.vaadin.flow.server.communication.PushRequestHandler");
    }

    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("handleRequest"),
                this.getClass().getName() + "$HandleAdvice");
    }

    @SuppressWarnings("unused")
    public static class HandleAdvice {
        @Advice.OnMethodEnter()
        public static void onEnter(
                @Advice.Local("startTimestamp") Instant startTimestamp) {
            startTimestamp = Instant.now();
        }

        public static void onExit(@Advice.Thrown Throwable throwable,
                @Advice.Argument(1) VaadinRequest request,
                @Advice.Return boolean handled,
                @Advice.Local("startTimestamp") Instant startTimestamp) {
            if (handled) {
                Span span = InstrumentationHelper.startSpan("PushRequest",
                        startTimestamp);
                InstrumentationHelper.endSpan(span, throwable, null);
                LocalRootSpan.current()
                        .updateName(request.getPathInfo() + " : Push");
            }
        }
    }
}