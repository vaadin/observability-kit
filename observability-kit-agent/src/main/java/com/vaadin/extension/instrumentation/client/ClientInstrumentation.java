package com.vaadin.extension.instrumentation.client;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import com.vaadin.extension.InstrumentationHelper;

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

            Tracer tracer = InstrumentationHelper.getTracer();
            for (JsonNode resourceSpanNode : root.get("resourceSpans")) {
                for (JsonNode scopeSpanNode : resourceSpanNode
                        .get("scopeSpans")) {

                    Map<String, JsonNode> parentSpanNodes = new HashMap<>();
                    Map<String, JsonNode> childSpanNodes = new HashMap<>();
                    for (JsonNode spanNode : scopeSpanNode.get("spans")) {
                        if (spanNode.has("parentSpanId")) {
                            childSpanNodes.put(spanNode.get("spanId").asText(),
                                    spanNode);
                        } else {
                            parentSpanNodes.put(spanNode.get("spanId").asText(),
                                    spanNode);
                        }
                    }

                    for (Map.Entry<String, JsonNode> parentEntry : parentSpanNodes
                            .entrySet()) {
                        String parentSpanId = parentEntry.getKey();
                        JsonNode parentSpanNode = parentEntry.getValue();

                        Span parentSpan = createSpan(tracer, parentSpanNode);
                        try (Scope ignored = parentSpan.makeCurrent()) {
                            for (Map.Entry<String, JsonNode> childEntry : childSpanNodes
                                    .entrySet()) {
                                JsonNode childSpanNode = childEntry.getValue();

                                if (parentSpanId.equals(childSpanNode
                                        .get("parentSpanId").asText())) {
                                    Span childSpan = createSpan(tracer,
                                            childSpanNode);
                                    childSpan.end(
                                            childSpanNode.get("endTimeUnixNano")
                                                    .asLong(),
                                            TimeUnit.NANOSECONDS);
                                }
                            }
                        }

                        parentSpan.end(
                                parentSpanNode.get("endTimeUnixNano").asLong(),
                                TimeUnit.NANOSECONDS);
                    }

                    if (parentSpanNodes.size() == 0) {
                        for (Map.Entry<String, JsonNode> childEntry : childSpanNodes
                                .entrySet()) {
                            JsonNode childSpanNode = childEntry.getValue();

                            Span childSpan = createSpan(tracer, childSpanNode);
                            childSpan.end(childSpanNode.get("endTimeUnixNano")
                                    .asLong(), TimeUnit.NANOSECONDS);
                        }
                    }
                }
            }
        }

        public static Span createSpan(Tracer tracer, JsonNode spanNode) {
            SpanBuilder spanBuilder = tracer
                    .spanBuilder("Client: " + spanNode.get("name").asText());
            spanBuilder.setSpanKind(SpanKind.SERVER);
            spanBuilder.setStartTimestamp(
                    spanNode.get("startTimeUnixNano").asLong(),
                    TimeUnit.NANOSECONDS);

            Span span = spanBuilder.startSpan();
            int status = spanNode.get("status").get("code").asInt();
            span.setStatus(StatusCode.values()[status + 1]);

            for (JsonNode attributeNode : spanNode.get("attributes")) {
                if (attributeNode.get("value").has("stringValue")) {
                    span.setAttribute(attributeNode.get("key").asText(),
                            attributeNode.get("value").get("stringValue")
                                    .asText());
                } else if (attributeNode.get("value").has("intValue")) {
                    span.setAttribute(attributeNode.get("key").asText(),
                            attributeNode.get("value").get("intValue").asInt());
                }
            }

            for (JsonNode eventNode : spanNode.get("events")) {
                span.addEvent(eventNode.get("name").asText(),
                        eventNode.get("timeUnixNano").asLong(),
                        TimeUnit.NANOSECONDS);
            }

            return span;
        }
    }
}
