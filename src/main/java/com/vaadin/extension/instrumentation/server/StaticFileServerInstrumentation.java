/*-
 * Copyright (C) 2022 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.extension.instrumentation.server;

import static com.vaadin.extension.InstrumentationHelper.getRequestFilename;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.vaadin.extension.HttpStatusCode;
import com.vaadin.extension.InstrumentationHelper;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.instrumentation.api.instrumenter.LocalRootSpan;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.time.Instant;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class StaticFileServerInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed("com.vaadin.flow.server.StaticFileHandler");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("com.vaadin.flow.server.StaticFileServer");
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(
                named("serveStaticResource")
                        .and(takesArgument(0,
                                named("javax.servlet.http.HttpServletRequest")))
                        .and(takesArgument(1, named(
                                "javax.servlet.http.HttpServletResponse"))),
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
                @Advice.Argument(0) HttpServletRequest request,
                @Advice.Argument(1) HttpServletResponse response,
                @Advice.Local("startTimestamp") Instant startTimestamp) {
            if (!handled) {
                // Do not add a span if static file is not served from here.
                return;
            }

            final String requestFilename = getRequestFilename(request);

            final String spanName = "StaticFileRequest";
            Span span = InstrumentationHelper.startSpan(spanName,
                    startTimestamp);
            span.setAttribute("http.request.file", requestFilename);

            Span localRootSpan = LocalRootSpan.current();
            if (requestFilename.startsWith("/VAADIN/build/vaadin-")) {
                // Loading the bundle we do not have a registry to lean on.
                localRootSpan.updateName("/ : Load frontend bundle");
            } else {
                localRootSpan.updateName(requestFilename);
            }

            if (response.getStatus() == HttpStatusCode.BAD_REQUEST.getCode()) {
                // Also mark a bad request as an exception
                span.setStatus(StatusCode.ERROR, "Bad Request");
            }
            if (response.getStatus() == HttpStatusCode.NOT_MODIFIED.getCode()) {
                span.setAttribute("vaadin.resolution", "Up to date");
            }
            InstrumentationHelper.endSpan(span, throwable, null);
        }
    }
}
