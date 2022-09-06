package com.vaadin.extension.instrumentation.communication;

import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.vaadin.extension.Constants;
import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.UI;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.List;

public class PushHandlerInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed(
                "com.vaadin.flow.server.communication.PushHandler");
    }

    // This instrumentation only matches AttachExistingElementRpcHandler on the
    // rpcEvent stack.
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("com.vaadin.flow.server.communication.PushHandler");
    }

    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("onMessage"),
                this.getClass().getName() + "$MessageAdvice");
        transformer.applyAdviceToMethod(named("onConnect"),
                this.getClass().getName() + "$ConnectionAdvice");
        transformer.applyAdviceToMethod(named("connectionLost"),
                this.getClass().getName() + "$ConnectionAdvice");
    }

    @SuppressWarnings("unused")
    public static class MessageAdvice {
        @Advice.OnMethodEnter()
        public static void onEnter(@Advice.Origin("#m") String methodName,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {

            span = InstrumentationHelper.startSpan("Push : " + methodName);

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

    @SuppressWarnings("unused")
    public static class ConnectionAdvice {
        @Advice.OnMethodEnter()
        public static void onEnter(@Advice.Origin("#m") String methodName,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {

            span = InstrumentationHelper.startSpan("Push : " + methodName);

            Context context = currentContext().with(span);
            scope = context.makeCurrent();

            final List<HasElement> activeRouterTargetsChain = UI.getCurrent()
                    .getInternals().getActiveRouterTargetsChain();
            if (!activeRouterTargetsChain.isEmpty()) {
                span.setAttribute(Constants.VIEW, activeRouterTargetsChain
                        .get(0).getClass().getSimpleName());
            }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Thrown Throwable throwable,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            InstrumentationHelper.endSpan(span, throwable, scope);
        }
    }
}
