package com.vaadin.extension.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.instrumenter.InstrumenterBuilder;
import org.jetbrains.annotations.Nullable;

public class ClientTracer implements Tracer {

    @Override
    public SpanBuilder spanBuilder(String s) {
        return null;
    }

    private static Instrumenter<JsonNode,Void> getInstrumenter(final OpenTelemetry openTelemetry) {
        InstrumenterBuilder<JsonNode,Void> builder = Instrumenter.builder(
                openTelemetry, "", jsonNode -> jsonNode.get("name").asText());
        return builder
                .buildInstrumenter();
    }

    private static class JsonNodeAttributesExtractor
            implements AttributesExtractor<JsonNode,Void> {
        @Override
        public void onStart(
                final AttributesBuilder attributes,
                final Context context,
                final JsonNode jsonNode) {
            for (JsonNode attribute : jsonNode.get("attributes")) {
                String key = attribute.get("key").asText();
                if (attribute.has("stringValue")) {
                    attributes.put(key, attribute.get("stringValue").asText());
                } else if (attribute.has("intValue")) {
                    attributes.put(key, attribute.get("intValue").asInt());
                } else if (attribute.has("longValue")) {
                    attributes.put(key, attribute.get("longValue").asLong());
                }
            }
        }

        @Override
        public void onEnd(AttributesBuilder attributesBuilder, Context context, JsonNode jsonNode, @Nullable Void unused, @Nullable Throwable throwable) {

        }
    }
}
