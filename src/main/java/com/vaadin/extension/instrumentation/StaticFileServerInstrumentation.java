package com.vaadin.extension.instrumentation;

import static com.vaadin.extension.InstrumentationHelper.getRequestFilename;
import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.server.HttpStatusCode;
import com.vaadin.flow.server.StaticFileServer;

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
        public static void onEnter(@Advice.This StaticFileServer requestHandler,
                @Advice.Argument(0) HttpServletRequest request,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            final String requestFilename = getRequestFilename(request);
            if (requestFilename.endsWith("/")) {
                // The request is for a path or a directory and not a file
                return;
            }
            final String spanName = String.format("Static file request: %s",
                    requestFilename);
            span = InstrumentationHelper.startSpan(spanName);

            Span localRootSpan = LocalRootSpan.current();
            if (requestFilename.startsWith("/VAADIN/build/vaadin-bundle")) {
                // Loading the bundle we do not have a registry to lean on.
                localRootSpan.updateName("/ : Load frontend bundle");
            } else {
                localRootSpan.updateName(requestFilename);
            }

            Context context = currentContext().with(span);
            scope = context.makeCurrent();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Thrown Throwable throwable,
                @Advice.Return boolean handled,
                @Advice.Argument(0) HttpServletRequest request,
                @Advice.Argument(1) HttpServletResponse response,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            if (!handled) {
                span.setAttribute("vaadin.resolution",
                        "unhandled file request");
            }
            if (response.getStatus() == HttpStatusCode.BAD_REQUEST.getCode()) {
                // Also mark a bad request as an exception
                span.setStatus(StatusCode.ERROR, "Bad Request");
            }
            if (response.getStatus() == HttpStatusCode.NOT_MODIFIED.getCode()) {
                span.setAttribute("vaadin.resolution", "Up to date");
            }
            InstrumentationHelper.endSpan(span, throwable, scope);
        }
    }
}
