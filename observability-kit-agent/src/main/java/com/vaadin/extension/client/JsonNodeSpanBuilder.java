package com.vaadin.extension.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.internal.ImmutableSpanContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.internal.AttributesMap;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.data.LinkData;

final class JsonNodeSpanBuilder implements SpanBuilder {
    private final JsonNode resourceNode;
    private final JsonNode scopeNode;
    private final JsonNode spanNode;
    private final SpanLimits spanLimits;
    private Context parent;
    private SpanKind spanKind;
    private AttributesMap attributes;
    private List<LinkData> links;
    private int totalNumberOfLinksAdded;
    private long startEpochNanos;

    JsonNodeSpanBuilder(JsonNode resourceNode, JsonNode scopeNode,
            JsonNode spanNode, SpanLimits spanLimits) {
        this.scopeNode = scopeNode;
        this.resourceNode = resourceNode;
        this.spanNode = spanNode;
        this.spanKind = SpanKind.INTERNAL;
        this.spanLimits = spanLimits;
    }

    @Override
    public SpanBuilder setParent(Context context) {
        if (context != null) {
            this.parent = context;
        }
        return this;
    }

    @Override
    public SpanBuilder setNoParent() {
        this.parent = Context.root();
        return this;
    }

    @Override
    public SpanBuilder addLink(SpanContext spanContext) {
        if (spanContext != null && spanContext.isValid()) {
            this.addLink(LinkData.create(spanContext));
        }
        return this;
    }

    @Override
    public SpanBuilder addLink(SpanContext spanContext, Attributes attributes) {
        if (spanContext != null && spanContext.isValid()) {
            if (attributes == null) {
                attributes = Attributes.empty();
            }

            int totalAttributeCount = attributes.size();
            this.addLink(LinkData.create(spanContext, attributes, totalAttributeCount));
        }
        return this;
    }

    private void addLink(LinkData link) {
        this.totalNumberOfLinksAdded++;
        if (this.links == null) {
            this.links = new ArrayList<>();
        }
        this.links.add(link);
    }

    @Override
    public SpanBuilder setAttribute(String key, String value) {
        return this.setAttribute((AttributeKey)AttributeKey.stringKey(key), (Object)value);
    }

    @Override
    public SpanBuilder setAttribute(String key, long value) {
        return this.setAttribute((AttributeKey)AttributeKey.longKey(key), (Object)value);
    }

    @Override
    public SpanBuilder setAttribute(String key, double value) {
        return this.setAttribute((AttributeKey)AttributeKey.doubleKey(key), (Object)value);
    }

    @Override
    public SpanBuilder setAttribute(String key, boolean value) {
        return this.setAttribute((AttributeKey)AttributeKey.booleanKey(key), (Object)value);
    }

    @Override
    public <T> SpanBuilder setAttribute(AttributeKey<T> key, T value) {
        if (key != null && !key.getKey().isEmpty() && value != null) {
            this.attributes().put(key, value);
        }
        return this;
    }

    @Override
    public SpanBuilder setSpanKind(SpanKind spanKind) {
        if (spanKind != null) {
            this.spanKind = spanKind;
        }
        return this;
    }

    @Override
    public SpanBuilder setStartTimestamp(long startTimestamp, TimeUnit unit) {
        if (startTimestamp >= 0L && unit != null) {
            this.startEpochNanos = unit.toNanos(startTimestamp);
        }
        return this;
    }

    @Override
    public Span startSpan() {

        String traceId = spanNode.get("traceId").asText();
        String spanId = spanNode.get("spanId").asText();
        SpanContext spanContext = ImmutableSpanContext.create(traceId, spanId,
                TraceFlags.getDefault(), TraceState.getDefault(), true, true);
        return null;
    }

    private AttributesMap attributes() {
        AttributesMap attributes = this.attributes;
        if (attributes == null) {
            this.attributes = AttributesMap.create(
                    this.spanLimits.getMaxNumberOfAttributes(),
                    this.spanLimits.getMaxAttributeValueLength());
            attributes = this.attributes;
        }

        return attributes;
    }
}
