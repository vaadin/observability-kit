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

import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.vaadin.extension.Constants;
import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.communication.SessionRequestHandler;
import com.vaadin.flow.shared.ApplicationConstants;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

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
        transformer.applyAdviceToMethod(named("handleRequest")
                        .and(takesArgument(1,
                                named("com.vaadin.flow.server.VaadinRequest"))),
                this.getClass().getName() + "$HandleRequestAdvice");
    }

    @SuppressWarnings("unused")
    public static class HandleRequestAdvice {
        @Advice.OnMethodEnter()
        public static void onEnter(
                @Advice.Argument(1) VaadinRequest vaadinRequest,
                @Advice.This SessionRequestHandler sessionRequestHandler,
                @Advice.Origin("#m") String methodName,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            if (Constants.REQUEST_TYPE_OBSERVABILITY.equals(
                    vaadinRequest.getParameter(
                            ApplicationConstants.REQUEST_TYPE_PARAMETER))) {
                return;
            }

            String spanName = sessionRequestHandler.getClass().getSimpleName()
                    + "." + methodName;
            span = InstrumentationHelper.startSpan(spanName);

            Context context = currentContext().with(span);
            scope = context.makeCurrent();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Thrown Throwable throwable,
                @Advice.Return boolean handled,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            if (span != null) {
                InstrumentationHelper.endSpan(span, throwable, scope);
            }
        }
    }
}
