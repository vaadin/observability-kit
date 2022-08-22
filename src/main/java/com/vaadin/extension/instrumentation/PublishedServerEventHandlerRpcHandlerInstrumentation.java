package com.vaadin.extension.instrumentation;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.lang.reflect.Method;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.instrumentation.api.instrumenter.LocalRootSpan;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.internal.StateTree;
import com.vaadin.flow.server.communication.rpc.NavigationRpcHandler;
import com.vaadin.flow.server.communication.rpc.PublishedServerEventHandlerRpcHandler;

import static com.vaadin.extension.InstrumentationHelper.getActiveRouteTemplate;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.isSubTypeOf;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

/**
 * Instruments PublishedServerEventHandlerRpcHandler in order to add a span
 * when the handler is triggered by a <code>element.$server</code> or
 * <code>$server</code> call from the client.
 */
public class PublishedServerEventHandlerRpcHandlerInstrumentation
        implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed(
                "com.vaadin.flow.server.communication.rpc.PublishedServerEventHandlerRpcHandler");
    }

    // This instrumentation only matches NavigationRpcHandler on the rpcEvent stack.
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named(
                "com.vaadin.flow.server.communication.rpc.PublishedServerEventHandlerRpcHandler");
    }

    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("handleNode"),
                this.getClass().getName() + "$HandleAdvice");

        transformer.applyAdviceToMethod(named("invokeMethod")
                        .and(takesArgument(0, named("com.vaadin.flow.component.Component")))
                        .and(takesArgument(1, named("java.lang.reflect.Method")))
                        .and(takesArgument(2, named("elemental.json.JsonArray")))
                        .and(takesArgument(3, int.class))
                        .and(takesArgument(4, boolean.class)),
                this.getClass().getName() + "$InvokeAdvice");
    }

    @SuppressWarnings("unused")
    public static class HandleAdvice {
        @Advice.OnMethodEnter()
        public static void onEnter(
                @Advice.This
                PublishedServerEventHandlerRpcHandler rpcHandler,
                @Advice.Origin("#m")
                String methodName,
                @Advice.Local("otelSpan")
                Span span) {

            Tracer tracer = InstrumentationHelper.getTracer();

            span = tracer.spanBuilder(
                    rpcHandler.getClass().getSimpleName() + "."
                            + methodName).startSpan();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(
                @Advice.Thrown
                Throwable throwable,
                @Advice.Local("otelSpan")
                Span span) {
            if (span == null) {
                return;
            }
            InstrumentationHelper.handleException(span, throwable);
            span.end();
        }
    }


    @SuppressWarnings("unused")
    public static class InvokeAdvice {
        @Advice.OnMethodEnter()
        public static void onEnter(
                @Advice.Argument(0)
                Component component,
                @Advice.Argument(1)
                Method method,
                @Advice.Argument(3)
                int promiseId,
                @Advice.Local("otelSpan")
                Span span) {
            if(method.getName().equals("connectClient")) {
                return;
            }

            Tracer tracer = InstrumentationHelper.getTracer();

            span = tracer.spanBuilder("Invoke server method ["
                            + method.getName() + "]").startSpan();
            span.setAttribute("vaadin.component", component.getClass().getCanonicalName());

            span.setAttribute("vaadin.callable.method", method.toString());

            // Set the root span name to be the event
            LocalRootSpan.current().updateName("/" + getActiveRouteTemplate(
                    component.getUI().get()).get() + " : ClientCallable");
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(
                @Advice.Thrown
                Throwable throwable,
                @Advice.Local("otelSpan")
                Span span) {
            if (span == null) {
                return;
            }
            InstrumentationHelper.handleException(span, throwable);
            span.end();
        }
    }
}
