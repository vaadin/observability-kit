/*-
 * Copyright (C) 2022 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.extension.instrumentation.client;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

public class ObjectMapSpanData implements SpanData {
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

    public ObjectMapSpanData(String frontendId, Map<String, Object> resource,
            Map<String, Object> scope, Map<String, Object> span) {
        String traceId = (String) span.get("traceId");
        String spanId = (String) span.get("spanId");
        this.spanContext = SpanContext.create(traceId, spanId,
                TraceFlags.getDefault(), TraceState.getDefault());

        String parentSpanId = (String) span.get("parentSpanId");
        this.parentSpanContext = SpanContext.create(traceId, parentSpanId,
                TraceFlags.getDefault(), TraceState.getDefault());

        ResourceBuilder resourceBuilder = Resource.builder();
        this.resource = resourceBuilder
                .putAll(getAttributes(resource).build())
                .build();

        this.instrumentationLibraryInfo =
                InstrumentationLibraryInfo.create(
                        (String) scope.get("name"),
                        (String) scope.get("version"));

        this.instrumentationScopeInfo = InstrumentationScopeInfo.create(
                (String) scope.get("name"), (String) scope.get("version"),
                null);

        this.name = "Frontend: " + span.get("name");
        this.kind = SpanKind.CLIENT;
        this.startEpochNanos = (long) span.get("startTimeUnixNano");
        this.endEpochNanos = (long) span.get("endTimeUnixNano");

        AttributesBuilder attributesBuilder = getAttributes(span);
        this.attributes = attributesBuilder
                .put(FRONTEND_ID, frontendId)
                .build();

        this.events = new ArrayList<>();
        for (Map<String, Object> event :
                (List<Map<String, Object>>) span.get("events")) {
            EventData eventData = EventData.create(
                    (long) event.get("timeUnixNano"),
                    (String) event.get("name"),
                    getAttributes(event).build());
            events.add(eventData);
        }

        this.links = Collections.emptyList();

        Map<String, Object> status = (Map<String, Object>) span.get("status");
        this.status = ((int) status.get("code") == 0) ? StatusData.ok() :
                StatusData.error();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static AttributesBuilder getAttributes(Map<String, Object> node) {
        AttributesBuilder builder = Attributes.builder();
        if (node.containsKey("attributes")) {
            List<Map<String, Object>> attributes =
                    (List<Map<String, Object>>) node.get("attributes");
            for (Map<String, Object> attribute : attributes) {
                String key = (String) attribute.get("key");
                Map<String, Object> value =
                        (Map<String, Object>) attribute.get("value");
                Map.Entry<AttributeKey, Object> entry =
                        attributeValue(key, value);
                if (entry != null) {
                    builder.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return builder;
    }

    // https://open-telemetry.github.io/opentelemetry-js/interfaces/_opentelemetry_otlp_transformer.IAnyValue.html
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map.Entry<AttributeKey, Object> attributeValue(
            String key, Map<String, Object> value) {
        if (value.containsKey("stringValue")) {
            String stringValue = (String) value.get("stringValue");
            return new AbstractMap.SimpleImmutableEntry<>(
                    AttributeKey.stringKey(key), stringValue);
        } else if (value.containsKey("intValue")) {
            Integer intValue = (Integer) value.get("intValue");
            long longValue = (long) intValue;
            return new AbstractMap.SimpleImmutableEntry<>(
                    AttributeKey.longKey(key), longValue);
        } else if (value.containsKey("boolValue")) {
            Boolean boolValue = (Boolean) value.get("boolValue");
            return new AbstractMap.SimpleImmutableEntry<>(
                    AttributeKey.booleanKey(key), boolValue);
        } else if (value.containsKey("doubleValue")) {
            Double doubleValue = (Double) value.get("doubleValue");
            return new AbstractMap.SimpleImmutableEntry<>(
                    AttributeKey.doubleKey(key), doubleValue);
        } else if (value.containsKey("arrayValue")) {
            AttributeKey<?> attributeKey = null;
            List<Object> values = new ArrayList<>();
            for (Map<String, Object> item :
                    (List<Map<String, Object>>) value.get("arrayValue")) {
                Map.Entry<AttributeKey, Object> entry = attributeValue(key,
                        item);
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
