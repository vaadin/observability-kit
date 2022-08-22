package com.vaadin.extension.instrumentation;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

import elemental.json.JsonObject;

import com.vaadin.extension.ElementInstrumentationInfo;
import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.StateNode;
import com.vaadin.flow.server.communication.rpc.MapSyncRpcHandler;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * This is a targeted instrumentation for MapSyncRpcHandler which adds
 * information that a node's/element's properties/attributes are synced from the
 * client.
 */
public class MapSyncRpcHandlerInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed(
                "com.vaadin.flow.server.communication.rpc.MapSyncRpcHandler");
    }

    // This instrumentation only matches MapSyncRpcHandler on the rpcEvent
    // stack.
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named(
                "com.vaadin.flow.server.communication.rpc.MapSyncRpcHandler");
    }

    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("handleNode"),
                this.getClass().getName() + "$MethodAdvice");

    }

    @SuppressWarnings("unused")
    public static class MethodAdvice {

        @Advice.OnMethodEnter()
        public static void onEnter(
                @Advice.This MapSyncRpcHandler mapSyncRpcHandler,
                @Advice.Origin("#m") String methodName,
                @Advice.Argument(0) StateNode node,
                @Advice.Argument(1) JsonObject jsonObject,
                @Advice.Local("otelSpan") Span span) {
            final ElementInstrumentationInfo elementInfo = ElementInstrumentationInfo
                    .create(node);
            final Element element = elementInfo.getElement();

            Tracer tracer = InstrumentationHelper.getTracer();
            span = tracer.spanBuilder("Sync: " + elementInfo.getElementLabel())
                    .startSpan();
            span.setAttribute("vaadin.element.tag", element.getTag());
            // If possible add active view class name as an attribute to the
            // span
            if (elementInfo.getViewLabel() != null) {
                span.setAttribute("vaadin.view", elementInfo.getViewLabel());
            }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Thrown Throwable throwable,
                @Advice.Local("otelSpan") Span span) {
            if (span == null) {
                return;
            }
            if (throwable != null) {
                // This will actually mark the span as having an exception which
                // shows on the dataUI
                span.setStatus(StatusCode.ERROR, throwable.getMessage());
                // Add log trace of exception.
                span.recordException(throwable);
            }
            span.end();
        }
    }
}
