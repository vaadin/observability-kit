package com.vaadin.extension.instrumentation;

import io.opentelemetry.api.GlobalOpenTelemetry;
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
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.StateNode;
import com.vaadin.flow.internal.StateTree;
import com.vaadin.flow.server.communication.rpc.EventRpcHandler;

import elemental.json.JsonObject;
import static com.vaadin.extension.InstrumentationHelper.getActiveRouteTemplate;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

/**
 * This is a Targeted instrumentation for EventRpcHandler which
 * adds information on the element that got an action and on which view.
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
        transformer.applyAdviceToMethod(named("handleNode").and(
                                takesArgument(0, named("com.vaadin.flow.internal.StateNode")))
                        .and(takesArgument(1, named("elemental.json.JsonObject"))),
                this.getClass().getName() + "$MethodAdvice");

    }

    @SuppressWarnings("unused")
    public static class MethodAdvice {

        @Advice.OnMethodEnter()
        public static void onEnter(
                @Advice.This
                EventRpcHandler eventRpcHandler,
                @Advice.Origin("#m")
                String methodName,
                @Advice.Argument(0)
                StateNode node,
                @Advice.Argument(1)
                JsonObject jsonObject,
                @Advice.Local("otelSpan")
                Span span) {

            Tracer tracer = InstrumentationHelper.getTracer();

            span = tracer.spanBuilder(
                    eventRpcHandler.getClass().getSimpleName() + "."
                            + methodName).startSpan();

            String eventType = jsonObject.getString("event");

            final Element element = Element.get(node);
            span.setAttribute("vaadin.element.tag", element.getTag());

            if (eventType != null) {
                span.setAttribute("vaadin.event.type", eventType);

                if (element.hasProperty("opened") && eventType.equals("opened-changed")) {
                    // Note! False means opening as we are checking before the
                    // attribute changes for the event have been written!
                    if (element.getProperty("opened", false)) {
                        span.setAttribute("vaadin.state.change", "opening");
                    } else {
                        span.setAttribute("vaadin.state.change", "closing");
                    }
                }
                // If possible add active view class name as an attribute to the span
                if (node.getOwner() instanceof StateTree) {
                    span.setAttribute("vaadin.view",
                            ((StateTree) node.getOwner()).getUI().getInternals()
                                    .getActiveRouterTargetsChain().get(0)
                                    .getClass().getSimpleName());
                }
                String identifier = "";
                if(element.getText() != null && !element.getText().isEmpty()) {
                    identifier = String.format("[%s]", element.getText());
                }
                // append event type to make span name more descriptive
                span.updateName(element.getTag() + identifier + " :: " + eventType);
                // This will make for instance a click span `vaadin-button :: click`
                // instead of `EventRpcHandler.handle` which leaves open that what was this about

                // Set the root span name to be the event
                LocalRootSpan.current().updateName(getActiveRouteTemplate(
                        ((StateTree) node.getOwner()).getUI()).get() + " : event");
            }
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
