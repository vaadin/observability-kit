/*-
 * Copyright (C) 2022 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.extension.instrumentation.communication.rpc;

import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.vaadin.extension.ElementInstrumentationInfo;
import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.extension.conf.Configuration;
import com.vaadin.extension.conf.TraceLevel;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.StateNode;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import tools.jackson.databind.JsonNode;

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
    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named(
                "com.vaadin.flow.server.communication.rpc.EventRpcHandler");
    }

    // Here we are interested in the handleNode method where we already have
    // resolved some of the json.
    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("handleNode"),
                this.getClass().getName() + "$MethodAdvice");
    }

    @SuppressWarnings("unused")
    public static class MethodAdvice {

        @Advice.OnMethodEnter()
        public static void onEnter(@Advice.Argument(0) StateNode node,
                @Advice.Argument(1) JsonNode invocationJson,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {

            if (Configuration.isEnabled(TraceLevel.DEFAULT)) {
                String eventType = invocationJson.get("event").asString();
                ElementInstrumentationInfo elementInfo = new ElementInstrumentationInfo(
                        node);
                Element element = elementInfo.getElement();
                // append event type to make span name more descriptive
                String spanName = "Event: " + elementInfo.getElementLabel()
                        + " :: " + eventType;
                span = InstrumentationHelper.startSpan(spanName);

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
