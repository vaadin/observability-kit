package com.vaadin.extension.instrumentation;

import static com.vaadin.extension.InstrumentationHelper.getActiveRouteTemplate;
import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.server.communication.rpc.PublishedServerEventHandlerRpcHandler;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.instrumenter.LocalRootSpan;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.lang.reflect.Method;

/**
 * Instruments PublishedServerEventHandlerRpcHandler in order to add a span when
 * the handler is triggered by a <code>element.$server</code> or
 * <code>$server</code> call from the client.
 */
public class PublishedServerEventHandlerRpcHandlerInstrumentation
        implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed(
                "com.vaadin.flow.server.communication.rpc.PublishedServerEventHandlerRpcHandler");
    }

    // This instrumentation only matches NavigationRpcHandler on the rpcEvent
    // stack.
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named(
                "com.vaadin.flow.server.communication.rpc.PublishedServerEventHandlerRpcHandler");
    }

    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("handleNode"),
                this.getClass().getName() + "$HandleAdvice");

        transformer
                .applyAdviceToMethod(
                        named("invokeMethod")
                                .and(takesArgument(0, named(
                                        "com.vaadin.flow.component.Component")))
                                .and(takesArgument(1,
                                        named("java.lang.reflect.Method")))
                                .and(takesArgument(2,
                                        named("elemental.json.JsonArray")))
                                .and(takesArgument(3, int.class))
                                .and(takesArgument(4, boolean.class)),
                        this.getClass().getName() + "$InvokeAdvice");
    }

    @SuppressWarnings("unused")
    public static class HandleAdvice {
        @Advice.OnMethodEnter()
        public static void onEnter(
                @Advice.This PublishedServerEventHandlerRpcHandler rpcHandler,
                @Advice.Origin("#m") String methodName,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {

            String spanName = rpcHandler.getClass().getSimpleName() + "."
                    + methodName;
            span = InstrumentationHelper.startSpan(spanName);

            Context context = currentContext().with(span);
            scope = context.makeCurrent();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Thrown Throwable throwable,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            if (scope != null) {
                scope.close();
            }
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
        public static void onEnter(@Advice.Argument(0) Component component,
                @Advice.Argument(1) Method method,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            if (method.getName().equals("connectClient")) {
                return;
            }

            String spanName = String.format("Invoke server method: %s.%s",
                    component.getClass().getSimpleName(), method.getName());
            span = InstrumentationHelper.startSpan(spanName);
            span.setAttribute("vaadin.component",
                    component.getClass().getName());
            span.setAttribute("vaadin.callable.method", method.toString());

            Context context = currentContext().with(span);
            scope = context.makeCurrent();

            // Set the root span name to be the event
            LocalRootSpan.current().updateName(
                    "/" + getActiveRouteTemplate(component.getUI().get()).get()
                            + " : ClientCallable");
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Thrown Throwable throwable,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            if (scope != null) {
                scope.close();
            }
            if (span == null) {
                return;
            }
            InstrumentationHelper.handleException(span, throwable);
            span.end();
        }
    }
}
