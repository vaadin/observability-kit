package com.vaadin.extension.instrumentation.client;

import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
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
            if (root == null || !root.has("resourceSpans")) {
                return;
            }

            for (JsonNode resourceSpanNode : root.get("resourceSpans")) {
                JsonNode resourceNode = resourceSpanNode.get("resource");
                for (JsonNode scopeSpanNode : resourceSpanNode
                        .get("scopeSpans")) {
                    JsonNode scopeNode = scopeSpanNode.get("scope");
                    for (JsonNode spanNode : scopeSpanNode.get("spans")) {
                        String traceId = spanNode.get("traceId").asText();
                        String spanId = spanNode.get("spanId").asText();
                        IdGenerator generator =
                                new JsonNodeIdGenerator(spanNode);

                        Tracer tracer = SdkTracerProvider.builder()
                                .setIdGenerator(generator).build()
                                .get(InstrumentationHelper.INSTRUMENTATION_NAME,
                                        InstrumentationHelper.INSTRUMENTATION_VERSION);

                        if (spanNode.has("parentSpanId")) {
                            String parentSpanId =
                                    spanNode.get("parentSpanId").asText();
                            Context parentContext =
                                    Context.root().with(Span.wrap(SpanContext
                                    .createFromRemoteParent(
                                            traceId, parentSpanId,
                                            TraceFlags.getDefault(),
                                            TraceState.getDefault())));
                            try (Scope ignored = parentContext.makeCurrent()) {
                                Span span = createSpan(tracer, spanNode, false);
                                span.end(spanNode.get("endTimeUnixNano").asLong(),
                                        TimeUnit.NANOSECONDS);
                            }
                        } else {
                            Span span = createSpan(tracer, spanNode, true);
                            span.end(spanNode.get("endTimeUnixNano").asLong(),
                                    TimeUnit.NANOSECONDS);
                        }
                    }
                }
            }
        }
    }

    public static Span createSpan(Tracer tracer, JsonNode spanNode,
            boolean parent) {
        SpanBuilder spanBuilder = tracer
                .spanBuilder("Client: " + spanNode.get("name").asText());
        if (!parent) {
            spanBuilder.setNoParent();
        }
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

    public static class JsonNodeIdGenerator implements IdGenerator {
        private String traceId;
        private String spanId;

        public JsonNodeIdGenerator(JsonNode spanNode) {
            traceId = spanNode.get("traceId").asText();
            spanId = spanNode.get("spanId").asText();
        }

        @Override
        public String generateTraceId() {
            return traceId;
        }

        @Override
        public String generateSpanId() {
            return spanId;
        }
    }
}
