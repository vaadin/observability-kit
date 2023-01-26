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

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.extension.conf.Configuration;
import com.vaadin.extension.conf.TraceLevel;
import com.vaadin.flow.server.HandlerHelper;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Instruments VaadinServlet to create a server root span if none exists yet.
 */
public class VaadinServletInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed("com.vaadin.flow.server.VaadinServlet");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("com.vaadin.flow.server.VaadinServlet");
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(
                named("service")
                        .and(takesArgument(0, named(
                                "jakarta.servlet.http.HttpServletRequest")))
                        .and(takesArgument(1, named(
                                "jakarta.servlet.http.HttpServletResponse"))),
                this.getClass().getName() + "$MethodAdvice");
    }

    public static class ScopeContainer {
        Scope scope;

        public Scope getScope() {
            return scope;
        }

        public void setScope(Scope scope) {
            this.scope = scope;
        }

        public ScopeContainer() {
        }

        public ScopeContainer(Scope scope) {
            this.scope = scope;
        }
    }

    @SuppressWarnings("unused")
    public static class MethodAdvice {
        @Advice.OnMethodEnter()
        public static void onEnter(
                @Advice.Argument(0) HttpServletRequest servletRequest,
                @Advice.Local("otelContext") Context context,
                @Advice.Local("scopeContainer") ScopeContainer scopeContainer,
                @Advice.Local("rootSpanCreated") boolean rootSpanCreated) {
            rootSpanCreated = false;
            if (InstrumentationHelper.isRequestType(servletRequest,
                    HandlerHelper.RequestType.HEARTBEAT)
                    && !Configuration.isEnabled(TraceLevel.MAXIMUM)) {
                return;
            }
            // Create a server root span if it doesn't exist yet. This can be
            // the case when disabling the default servlet and application
            // server instrumentations
            context = Context.current();
            if (!InstrumentationHelper.checkRootSpan()) {
                context = InstrumentationHelper.startRootSpan(servletRequest);
                rootSpanCreated = true;
            }
            // Add additional data to root span and context. This should cover
            // both, either an existing root span or a root span created above.
            context = InstrumentationHelper.enhanceRootSpan(servletRequest,
                    context);
            Scope scope = context.makeCurrent();
            if (scopeContainer == null) {
                scopeContainer = new ScopeContainer(scope);
            } else {
                scopeContainer.setScope(scope);
            }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Thrown Throwable throwable,
                @Advice.Argument(1) HttpServletResponse servletResponse,
                @Advice.Local("otelContext") Context context,
                @Advice.Local("scopeContainer") ScopeContainer scopeContainer,
                @Advice.Local("rootSpanCreated") boolean rootSpanCreated) {
            if (scopeContainer != null && scopeContainer.getScope() != null) {
                scopeContainer.getScope().close();
            }
            // If this instrumentation created the root span, then update it
            // from the response and end it
            if (rootSpanCreated) {
                InstrumentationHelper.endRootSpan(servletResponse, context,
                        throwable);
            }
        }
    }
}
