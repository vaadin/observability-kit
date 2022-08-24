package com.vaadin.extension.instrumentation;

import static com.vaadin.extension.InstrumentationHelper.getActiveRouteTemplate;
import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

import elemental.json.JsonObject;

import com.vaadin.extension.ElementInstrumentationInfo;
import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.StateNode;
import com.vaadin.flow.internal.StateTree;
import com.vaadin.flow.server.communication.rpc.EventRpcHandler;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.instrumenter.LocalRootSpan;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * This is a Targeted instrumentation for EventRpcHandler which adds information
 * on the element that got an action and on which view.
 */
public class EventRpcHandlerInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed(
                "com.vaadin.flow.server.communication.rpc.EventRpcHandler");
    }

    // This intrumentation only matches EventRpcHandler on the rpcEvent stack.
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named(
                "com.vaadin.flow.server.communication.rpc.EventRpcHandler");
    }

    // Here we are interested in the handleNode method where we already have
    // resolved some of the json.
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("handleNode"),
                this.getClass().getName() + "$MethodAdvice");
    }

    @SuppressWarnings("unused")
    public static class MethodAdvice {

        @Advice.OnMethodEnter()
        public static void onEnter(@Advice.This EventRpcHandler eventRpcHandler,
                @Advice.Origin("#m") String methodName,
                @Advice.Argument(0) StateNode node,
                @Advice.Argument(1) JsonObject jsonObject,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelContext") Context context,
                @Advice.Local("otelScope") Scope scope) {

            Tracer tracer = InstrumentationHelper.getTracer();
            String spanName = eventRpcHandler.getClass().getSimpleName() + "."
                    + methodName;
            span = tracer.spanBuilder(spanName).startSpan();

            String eventType = jsonObject.getString("event");

            if (eventType != null) {
                ElementInstrumentationInfo elementInfo = new ElementInstrumentationInfo(
                        node);
                Element element = elementInfo.getElement();
                span.setAttribute("vaadin.element.tag", element.getTag());
                span.setAttribute("vaadin.event.type", eventType);

                if (element.hasProperty("opened")
                        && eventType.equals("opened-changed")) {
                    // Note! False means opening as we are checking before the
                    // attribute changes for the event have been written!
                    if (element.getProperty("opened", false)) {
                        span.setAttribute("vaadin.state.change", "opening");
                    } else {
                        span.setAttribute("vaadin.state.change", "closing");
                    }
                }
                // If possible add active view class name as an attribute to the
                // span
                if (elementInfo.getViewLabel() != null) {
                    span.setAttribute("vaadin.view",
                            elementInfo.getViewLabel());
                }
                // append event type to make span name more descriptive
                span.updateName("Event: " + elementInfo.getElementLabel()
                        + " :: " + eventType);
                // This will make for instance a click span `vaadin-button ::
                // click` instead of `EventRpcHandler.handle` which leaves open
                // that what was this about

                // Set the root span name to be the event
                String routeName = getActiveRouteTemplate(
                        ((StateTree) node.getOwner()).getUI()).get();
                String eventRootSpanName = String.format("/%s : event",
                        routeName);
                LocalRootSpan.current().updateName(eventRootSpanName);
            }

            context = currentContext().with(span);
            scope = context.makeCurrent();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Thrown Throwable throwable,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelContext") Context context,
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
