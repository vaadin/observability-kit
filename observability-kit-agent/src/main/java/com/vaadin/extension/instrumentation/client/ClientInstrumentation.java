package com.vaadin.extension.instrumentation.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

public class ClientInstrumentation implements TypeInstrumentation {
    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed("com.vaadin.observability.ObservabilityHandler");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("com.vaadin.observability.ObservabilityHandler");
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("handleTraces"),
                this.getClass().getName() + "$MethodAdvice");
    }

    public static class MethodAdvice {
        @Advice.OnMethodEnter()
        public static void onEnter(@Advice.Argument(0) JsonNode root) {
            if (!root.has("resourceSpans")) {
                return;
            }

            SpanExporterWrapper.current().export(root);
        }
    }
}
