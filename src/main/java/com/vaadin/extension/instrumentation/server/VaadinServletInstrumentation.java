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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Instruments VaadinServlet to create a server root span if none exists yet.
 */
public class VaadinServletInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed("com.vaadin.flow.server.VaadinServlet");
    }

    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("com.vaadin.flow.server.VaadinServlet");
    }

    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(
                named("service")
                        .and(takesArgument(0,
                                named("javax.servlet.http.HttpServletRequest")))
                        .and(takesArgument(1, named(
                                "javax.servlet.http.HttpServletResponse"))),
                this.getClass().getName() + "$MethodAdvice");
    }

    @SuppressWarnings("unused")
    public static class MethodAdvice {
        @Advice.OnMethodEnter()
        public static void onEnter(
                @Advice.Argument(0) HttpServletRequest servletRequest,
                @Advice.Local("otelContext") Context context,
                @Advice.Local("otelScope") Scope scope) {
            if (InstrumentationHelper.isRequestType(servletRequest,
                    HandlerHelper.RequestType.HEARTBEAT)
                    && !Configuration.isEnabled(TraceLevel.MAXIMUM)) {
                return;
            }
            // Create a server root span if it doesn't exist yet
            // This can be the case when disabling the default servlet
            // instrumentations
            if (!InstrumentationHelper.doesRootSpanExist()) {
                context = InstrumentationHelper.startRootSpan(servletRequest);
                scope = context.makeCurrent();
            }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Thrown Throwable throwable,
                @Advice.Argument(1) HttpServletResponse servletResponse,
                @Advice.Local("otelContext") Context context,
                @Advice.Local("otelScope") Scope scope) {
            // If this instrumentation created the root span, then update it
            // from the response and end it
            if (context != null) {
                InstrumentationHelper.endRootSpan(servletResponse, context,
                        throwable, scope);
            }
        }
    }
}
