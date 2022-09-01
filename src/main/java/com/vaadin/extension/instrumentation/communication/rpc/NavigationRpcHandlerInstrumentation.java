package com.vaadin.extension.instrumentation.communication.rpc;

import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.extension.conf.Configuration;
import com.vaadin.extension.conf.TraceLevel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments NavigationRpcHandler in order to add a span when the handler is
 * called with a navigation message from the client. This can be a router link
 * navigation, or history navigation (back / forward button). This handler is
 * only called when using the (deprecated) V14 bootstrap mode.
 */
public class NavigationRpcHandlerInstrumentation
        implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed(
                "com.vaadin.flow.server.communication.rpc.NavigationRpcHandler");
    }

    // This instrumentation only matches NavigationRpcHandler on the rpcEvent
    // stack.
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named(
                "com.vaadin.flow.server.communication.rpc.NavigationRpcHandler");
    }

    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("handle"),
                this.getClass().getName() + "$MethodAdvice");
    }

    @SuppressWarnings("unused")
    public static class MethodAdvice {
        @Advice.OnMethodEnter()
        public static void onEnter(@Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            if (!Configuration.isEnabled(TraceLevel.MAXIMUM)) {
                return;
            }

            String spanName = "Handle navigation";
            span = InstrumentationHelper.startSpan(spanName);

            Context context = currentContext().with(span);
            scope = context.makeCurrent();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Thrown Throwable throwable,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            InstrumentationHelper.endSpan(span, throwable, scope);
        }
    }
}
