/*-
 * Copyright (C) 2022 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.extension.instrumentation.communication;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static io.opentelemetry.semconv.HttpAttributes.HTTP_ROUTE;
import static io.opentelemetry.semconv.UrlAttributes.URL_PATH;
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

            // Update root span
            String pathInfo = request.getPathInfo();
            String[] pathParts = pathInfo.split("/");
            String filename = pathParts[pathParts.length - 1];
            String rootSpanName = "/dynamic/resource/[ui]/[secret]/" + filename;

            Span localRootSpan = LocalRootSpan.current();
            localRootSpan.updateName(rootSpanName);
            localRootSpan.setAttribute(URL_PATH,
                    request.getPathInfo());
            localRootSpan.setAttribute(HTTP_ROUTE,
                    rootSpanName);

            InstrumentationHelper.endSpan(span, throwable, null);
        }
    }
}
