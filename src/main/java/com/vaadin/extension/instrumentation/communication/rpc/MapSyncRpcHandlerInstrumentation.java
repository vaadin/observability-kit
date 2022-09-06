package com.vaadin.extension.instrumentation.communication.rpc;

import static com.vaadin.extension.Constants.VIEW;
import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

import elemental.json.JsonObject;

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
        public static void onEnter(@Advice.Argument(0) StateNode node,
                @Advice.Argument(1) JsonObject jsonObject,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            if (!Configuration.isEnabled(TraceLevel.DEFAULT)) {
                return;
            }

            final ElementInstrumentationInfo elementInfo = new ElementInstrumentationInfo(
                    node);
            final Element element = elementInfo.getElement();

            if (jsonObject.hasKey("property") && jsonObject.hasKey("value")) {
                final String property = jsonObject.getString("property");
                final String value = jsonObject.get("value").asString();
                // skip if property or attribute is same
                if (value.equals(element.getProperty(property, null))
                        || value.equals(element.getAttribute(property))) {
                    return;
                }
            }

            String spanName = "Sync: " + elementInfo.getElementLabel();
            span = InstrumentationHelper.startSpan(spanName);
            span.setAttribute("vaadin.element.tag", element.getTag());
            // If possible add active view class name as an attribute to the
            // span
            if (elementInfo.getViewLabel() != null) {
                span.setAttribute(VIEW, elementInfo.getViewLabel());
            }

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
