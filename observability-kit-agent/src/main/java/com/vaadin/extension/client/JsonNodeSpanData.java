package com.vaadin.extension.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
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

public class JsonNodeSpanData implements SpanData {
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

    public static Attributes getAttributes(JsonNode node) {
        AttributesBuilder attributesBuilder = Attributes.builder();
        for (JsonNode attributeNode : node.get("attributes")) {
            String key = attributeNode.get("key").asText();
            JsonNode valueNode = attributeNode.get("value");
            if (valueNode.has("stringValue")) {
                attributesBuilder.put(key,
                        valueNode.get("stringValue").asText());
            } else if (valueNode.has("intValue")) {
                attributesBuilder.put(key,
                        valueNode.get("intValue").asInt());
            } else if (valueNode.has("longValue")) {
                attributesBuilder.put(key,
                        valueNode.get("longValue").asLong());
            }
        }
        return attributesBuilder.build();
    }

    public JsonNodeSpanData(JsonNode resourceNode, JsonNode scopeNode,
            JsonNode spanNode) {
        this.spanContext = SpanContext.create(spanNode.get("traceId").asText(),
                spanNode.get("spanId").asText(), TraceFlags.getDefault(),
                TraceState.getDefault());

        this.parentSpanContext = spanNode.has("parentSpanId") ?
                SpanContext.create(spanNode.get("traceId").asText(),
                    spanNode.get("parentSpanId").asText(),
                    TraceFlags.getDefault(),
                    TraceState.getDefault()) : null;

        ResourceBuilder resourceBuilder = Resource.builder();
        for (JsonNode attributeNode : resourceNode.get("attributes")) {
            String key = attributeNode.get("key").asText();
            JsonNode valueNode = attributeNode.get("value");
            if (valueNode.has("stringValue")) {
                resourceBuilder.put(key,
                        valueNode.get("stringValue").asText());
            } else if (valueNode.has("intValue")) {
                resourceBuilder.put(key,
                        valueNode.get("intValue").asInt());
            } else if (valueNode.has("longValue")) {
                resourceBuilder.put(key,
                        valueNode.get("longValue").asLong());
            }
        }
        this.resource = resourceBuilder.build();

        this.instrumentationLibraryInfo =
                InstrumentationLibraryInfo.create(
                        scopeNode.get("name").asText(),
                        scopeNode.get("version").asText());

        this.instrumentationScopeInfo = InstrumentationScopeInfo.create(
                scopeNode.get("name").asText(),
                scopeNode.get("version").asText(), null);

        this.name = spanNode.get("name").asText();
        this.kind = SpanKind.values()[spanNode.get("kind").asInt() + 1];
        this.startEpochNanos = spanNode.get("startTimeUnixNano").asLong();
        this.endEpochNanos = spanNode.get("endTimeUnixNano").asLong();

        this.attributes = getAttributes(spanNode);

        this.events = new ArrayList<>();
        for (JsonNode eventNode : spanNode.get("events")) {
            EventData eventData = EventData.create(
                    eventNode.get("timeUnixNano").asLong(),
                    eventNode.get("name").asText(),
                    getAttributes(eventNode));
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
    public io.opentelemetry.sdk.common.InstrumentationLibraryInfo getInstrumentationLibraryInfo() {
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
