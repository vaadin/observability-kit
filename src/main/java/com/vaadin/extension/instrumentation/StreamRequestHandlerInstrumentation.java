package com.vaadin.extension.instrumentation;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

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
        transformer.applyAdviceToMethod(
                named("handleRequest").and(takesArgument(1,
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
        public static void onExit(@Advice.Thrown Throwable throwable,
                @Advice.Return boolean handled,
                @Advice.Argument(1) VaadinRequest request,
                @Advice.Local("startTimestamp") Instant startTimestamp) {
            if (!handled) {
                // Do not add a span if static file is not served from here.
                return;
            }

            final String spanName = "Handle dynamic file";
            Span span = InstrumentationHelper.startSpan(spanName,
                    startTimestamp);

            // Replace the UIID and security key
            Span localRootSpan = LocalRootSpan.current();
            String pathInfo = request.getPathInfo();
            String prefix = "/VAADIN/dynamic/resource/";
            if (pathInfo.contains(prefix)) {
                int index = pathInfo.indexOf(prefix) + prefix.length();
                String[] parts = pathInfo.substring(index).split("/", 3);
                String requestFilename = pathInfo.substring(0, index)
                        + "[UIID]/[SECKEY]/" + parts[2];

                localRootSpan.updateName(requestFilename);
                localRootSpan.setAttribute("http.target", requestFilename);
            } else {
                localRootSpan.updateName(pathInfo);
            }

            InstrumentationHelper.endSpan(span, throwable, null);
        }
    }
}
