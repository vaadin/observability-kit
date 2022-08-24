package com.vaadin.extension.instrumentation;

import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.vaadin.extension.ElementInstrumentationInfo;
import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.internal.StateNode;
import com.vaadin.flow.internal.nodefeature.AttachExistingElementFeature;
import com.vaadin.flow.server.communication.rpc.AttachExistingElementRpcHandler;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments AttachExistingElementRpcHandler to add information about existing
 * client-side elements being attached to the UI on the server.
 */
public class AttachExistingElementRpcHandlerInstrumentation
        implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed(
                "com.vaadin.flow.server.communication.rpc.AttachExistingElementRpcHandler");
    }

    // This instrumentation only matches AttachExistingElementRpcHandler on the
    // rpcEvent stack.
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named(
                "com.vaadin.flow.server.communication.rpc.AttachExistingElementRpcHandler");
    }

    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("attachElement"),
                this.getClass().getName() + "$AttachElementAdvice");
    }

    @SuppressWarnings("unused")
    public static class AttachElementAdvice {
        @Advice.OnMethodEnter()
        public static void onEnter(
                @Advice.This AttachExistingElementRpcHandler attachExistingElementRpcHandler,
                @Advice.Origin("#m") String methodName,
                @Advice.Argument(0) AttachExistingElementFeature feature,
                @Advice.Argument(3) StateNode node,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {

            // Info for the element that is being attached
            ElementInstrumentationInfo elementInfo = new ElementInstrumentationInfo(
                    node);
            // Info for the element that the node is being attached to
            ElementInstrumentationInfo targetInfo = new ElementInstrumentationInfo(
                    feature.getNode());

            span = InstrumentationHelper.startSpan("Attach existing element: "
                    + elementInfo.getElementLabel());
            span.setAttribute("vaadin.element.tag",
                    elementInfo.getElement().getTag());
            span.setAttribute("vaadin.element.target",
                    targetInfo.getElementLabel());
            // If possible add active view class name as an attribute to the
            // span
            if (elementInfo.getViewLabel() != null) {
                span.setAttribute("vaadin.view", elementInfo.getViewLabel());
            }

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
}
