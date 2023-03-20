package com.vaadin.extension.instrumentation.client;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
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

        static final String FRONTEND_ID = "vaadin.front-end.id";

        @Advice.OnMethodEnter()
        public static void onEnter(@Advice.Argument(0) JsonNode root,
                @Advice.FieldValue("id") String observabilityClientId) {
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

                        Span parentSpan = createSpan(tracer, parentSpanNode,
                                observabilityClientId);
                        try (Scope ignored = parentSpan.makeCurrent()) {
                            for (Map.Entry<String, JsonNode> childEntry : childSpanNodes
                                    .entrySet()) {
                                JsonNode childSpanNode = childEntry.getValue();

                                if (parentSpanId.equals(childSpanNode
                                        .get("parentSpanId").asText())) {
                                    Span childSpan = createSpan(tracer,
                                            childSpanNode,
                                            observabilityClientId);
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

                            Span childSpan = createSpan(tracer, childSpanNode,
                                    observabilityClientId);
                            childSpan.end(childSpanNode.get("endTimeUnixNano")
                                    .asLong(), TimeUnit.NANOSECONDS);
                        }
                    }
                }
            }
        }

        public static Span createSpan(Tracer tracer, JsonNode spanNode,
                String observabilityClientId) {
            SpanBuilder spanBuilder = tracer
                    .spanBuilder("Client: " + spanNode.get("name").asText());
            spanBuilder.setSpanKind(SpanKind.CLIENT);
            spanBuilder.setStartTimestamp(
                    spanNode.get("startTimeUnixNano").asLong(),
                    TimeUnit.NANOSECONDS);

            Span span = spanBuilder.startSpan();
            int status = spanNode.get("status").get("code").asInt();
            span.setStatus(StatusCode.values()[status]);

            span.setAllAttributes(extractAttributes(spanNode));
            span.setAttribute(FRONTEND_ID, observabilityClientId);
            for (JsonNode eventNode : spanNode.get("events")) {
                Attributes attributes = extractAttributes(eventNode);
                span.addEvent(eventNode.get("name").asText(), attributes,
                        eventNode.get("timeUnixNano").asLong(),
                        TimeUnit.NANOSECONDS);
            }

            return span;
        }

        @SuppressWarnings({ "rawtypes", "unchecked" })
        private static Attributes extractAttributes(JsonNode node) {
            if (node.has("attributes")) {
                AttributesBuilder builder = Attributes.builder();
                for (JsonNode attributeNode : node.get("attributes")) {
                    String key = attributeNode.get("key").asText();
                    JsonNode valueNode = attributeNode.get("value");
                    Map.Entry<AttributeKey, Object> entry = attributeValue(key,
                            valueNode);
                    if (entry != null) {
                        builder.put(entry.getKey(), entry.getValue());
                    }
                }
                return builder.build();
            }
            return Attributes.empty();
        }

        // https://open-telemetry.github.io/opentelemetry-js/interfaces/_opentelemetry_otlp_transformer.IAnyValue.html
        @SuppressWarnings("rawtypes")
        private static Map.Entry<AttributeKey, Object> attributeValue(
                String key, JsonNode valueNode) {
            if (valueNode.has("stringValue")) {
                return new AbstractMap.SimpleImmutableEntry<>(
                        AttributeKey.stringKey(key),
                        valueNode.get("stringValue").asText());
            } else if (valueNode.has("intValue")) {
                return new AbstractMap.SimpleImmutableEntry<>(
                        AttributeKey.longKey(key),
                        valueNode.get("intValue").asLong());
            } else if (valueNode.has("boolValue")) {
                return new AbstractMap.SimpleImmutableEntry<>(
                        AttributeKey.booleanKey(key),
                        valueNode.get("boolValue").asBoolean());
            } else if (valueNode.has("doubleValue")) {
                return new AbstractMap.SimpleImmutableEntry<>(
                        AttributeKey.doubleKey(key),
                        valueNode.get("doubleValue").asDouble());
            } else if (valueNode.has("arrayValue")) {

                AttributeKey<?> attributeKey = null;
                List<Object> values = new ArrayList<>();
                for (JsonNode element : valueNode.get("arrayValue")
                        .get("values")) {
                    Map.Entry<AttributeKey, Object> entry = attributeValue(key,
                            element);
                    if (entry != null) {
                        values.add(entry.getValue());
                        if (attributeKey == null) {
                            // As per specs, the array MUST be homogeneous,
                            // i.e., it MUST NOT contain values of different
                            // types.
                            attributeKey = entry.getKey();
                        }
                    }
                }
                if (attributeKey == null) {
                    // Rejecting empty array, as the type can't be determined
                    return null;
                }

                switch (attributeKey.getType()) {
                case LONG:
                    attributeKey = AttributeKey.longArrayKey(key);
                    break;
                case DOUBLE:
                    attributeKey = AttributeKey.doubleArrayKey(key);
                    break;
                case STRING:
                    attributeKey = AttributeKey.stringArrayKey(key);
                    break;
                case BOOLEAN:
                    attributeKey = AttributeKey.booleanArrayKey(key);
                    break;
                default:
                    // Arrays can contain only primitive values
                    attributeKey = null;
                    break;
                }
                if (attributeKey == null) {
                    return null;
                }
                return new AbstractMap.SimpleImmutableEntry<>(attributeKey,
                        values);
            }
            return null;
        }

    }
}
