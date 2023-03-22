/*-
 * Copyright (C) 2022 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.resources.ResourceBuilder;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;

public class JsonNodeSpanWrapper implements SpanData {
    private static final String FRONTEND_ID = "vaadin.frontend.id";

    private final SpanContext spanContext;
    private final SpanContext parentSpanContext;
    private final Resource resource;
    private final InstrumentationLibraryInfo instrumentationLibraryInfo;
    private final InstrumentationScopeInfo instrumentationScopeInfo;
    private final String name;
    private final SpanKind kind;
    private final long startEpochNanos;
    private final long endEpochNanos;
    private final Attributes attributes;
    private final List<EventData> events;
    private final List<LinkData> links;
    private final StatusData status;

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static AttributesBuilder getAttributes(JsonNode node) {
        AttributesBuilder builder = Attributes.builder();
        if (node.has("attributes")) {
            for (JsonNode attributeNode : node.get("attributes")) {
                String key = attributeNode.get("key").asText();
                JsonNode valueNode = attributeNode.get("value");
                Map.Entry<AttributeKey, Object> entry = attributeValue(key,
                        valueNode);
                if (entry != null) {
                    builder.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return builder;
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

    public JsonNodeSpanWrapper(String frontendId, JsonNode resourceNode,
            JsonNode scopeNode, JsonNode spanNode) {
        this.spanContext = SpanContext.create(spanNode.get("traceId").asText(),
                spanNode.get("spanId").asText(), TraceFlags.getDefault(),
                TraceState.getDefault());

        this.parentSpanContext = SpanContext.create(
                spanNode.get("traceId").asText(),
                spanNode.has("parentSpanId") ?
                        spanNode.get("parentSpanId").asText() : null,
                TraceFlags.getDefault(),
                TraceState.getDefault());

        ResourceBuilder resourceBuilder = Resource.builder();
        this.resource = resourceBuilder
                .putAll(getAttributes(resourceNode).build())
                .build();

        this.instrumentationLibraryInfo =
                InstrumentationLibraryInfo.create(
                        scopeNode.get("name").asText(),
                        scopeNode.get("version").asText());

        this.instrumentationScopeInfo = InstrumentationScopeInfo.create(
                scopeNode.get("name").asText(),
                scopeNode.get("version").asText(), null);

        this.name = "Frontend: " + spanNode.get("name").asText();
        this.kind = SpanKind.values()[spanNode.get("kind").asInt() + 1];
        this.startEpochNanos = spanNode.get("startTimeUnixNano").asLong();
        this.endEpochNanos = spanNode.get("endTimeUnixNano").asLong();

        AttributesBuilder attributesBuilder = getAttributes(spanNode);
        this.attributes = attributesBuilder
                .put(FRONTEND_ID, frontendId)
                .build();

        this.events = new ArrayList<>();
        for (JsonNode eventNode : spanNode.get("events")) {
            EventData eventData = EventData.create(
                    eventNode.get("timeUnixNano").asLong(),
                    eventNode.get("name").asText(),
                    getAttributes(eventNode).build());
            events.add(eventData);
        }

        this.links = Collections.emptyList();

        this.status = (spanNode.get("status").get("code").asInt() == 0) ?
                StatusData.ok() : StatusData.error();
    }

    @Override
    public SpanContext getSpanContext() {
        return spanContext;
    }

    @Override
    public SpanContext getParentSpanContext() {
        return parentSpanContext;
    }

    @Override
    public Resource getResource() {
        return resource;
    }

    @Override
    @Deprecated
    public InstrumentationLibraryInfo getInstrumentationLibraryInfo() {
        return instrumentationLibraryInfo;
    }

    @Override
    public InstrumentationScopeInfo getInstrumentationScopeInfo() {
        return instrumentationScopeInfo;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public SpanKind getKind() {
        return kind;
    }

    @Override
    public long getStartEpochNanos() {
        return startEpochNanos;
    }

    @Override
    public long getEndEpochNanos() {
        return endEpochNanos;
    }

    @Override
    public Attributes getAttributes() {
        return attributes;
    }

    @Override
    public List<EventData> getEvents() {
        return events;
    }

    @Override
    public List<LinkData> getLinks() {
        return links;
    }

    @Override
    public StatusData getStatus() {
        return status;
    }

    @Override
    public boolean hasEnded() {
        return true;
    }

    @Override
    public int getTotalRecordedEvents() {
        return events.size();
    }

    @Override
    public int getTotalRecordedLinks() {
        return links.size();
    }

    @Override
    public int getTotalAttributeCount() {
        return attributes.size();
    }
}
