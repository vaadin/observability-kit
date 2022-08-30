package com.vaadin.extension.instrumentation;

import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.vaadin.extension.ElementInstrumentationInfo;
import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.extension.conf.Configuration;
import com.vaadin.extension.conf.TraceLevel;
import com.vaadin.flow.internal.StateNode;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments AttachTemplateChildRpcHandler to add information about
 * client-side response on attach existing element by id request.
 */
public class AttachTemplateChildRpcHandlerInstrumentation
        implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed(
                "com.vaadin.flow.server.communication.rpc.AttachTemplateChildRpcHandler");
    }

    // This instrumentation only matches AttachExistingElementRpcHandler on the
    // rpcEvent stack.
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named(
                "com.vaadin.flow.server.communication.rpc.AttachTemplateChildRpcHandler");
    }

    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("handleNode"),
                this.getClass().getName() + "$AttachElementAdvice");
    }

    @SuppressWarnings("unused")
    public static class AttachElementAdvice {
        @Advice.OnMethodEnter()
        public static void onEnter(@Advice.Argument(0) StateNode node,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            if (Configuration.isEnabled(TraceLevel.DEFAULT)) {
                // Info for the element that is being attached
                ElementInstrumentationInfo elementInfo = new ElementInstrumentationInfo(
                        node);

                span = InstrumentationHelper.startSpan("Attach template child: "
                        + elementInfo.getElementLabel());
                span.setAttribute("vaadin.element.tag",
                        elementInfo.getElement().getTag());
                // If possible add active view class name as an attribute to the
                // span
                if (elementInfo.getViewLabel() != null) {
                    span.setAttribute("vaadin.view",
                            elementInfo.getViewLabel());
                }

                Context context = currentContext().with(span);
                scope = context.makeCurrent();
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
