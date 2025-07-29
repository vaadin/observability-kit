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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import io.opentelemetry.sdk.trace.data.SpanData;

import com.vaadin.extension.conf.ConfigurationDefaults;
import com.vaadin.extension.metrics.Metrics;

/**
 * This is a consumer callback that is injected into an ObservabilityHandler
 * instance. It handles the export of a Frontend Observability trace. This
 * consists of a resource, representing the span processor, one or more
 * scopes, representing the instrumentation type, and for each of those, one
 * or more spans.
 */
public class ObjectMapExporter
        implements BiConsumer<String, Map<String, Object>> {
    /**
     * Accepts an observability trace and exports the spans found within it.
     *
     * @param id the ID of the installed handler
     * @param objectMap the object map of the observability trace
     */
    @Override
    public void accept(String id, Map<String, Object> objectMap) {
        Collection<SpanData> exportSpans = new ArrayList<>();
        if (!objectMap.containsKey("resourceSpans") ||
            !(objectMap.get("resourceSpans") instanceof List)) {
            throw new RuntimeException("Malformed span data");
        }

        Collection<Map<String, Object>> resourceSpans =
            (Collection<Map<String, Object>>) objectMap.get("resourceSpans");
        for (Map<String, Object> resourceSpan : resourceSpans) {
            Map<String, Object> resource =
                    (Map<String, Object>) resourceSpan.get("resource");
            Collection<Map<String, Object>> scopeSpans =
                    (Collection<Map<String, Object>>) resourceSpan.get("scopeSpans");
            for (Map<String, Object> scopeSpan : scopeSpans) {
                Map<String, Object> scope =
                        (Map<String, Object>) scopeSpan.get("scope");
                Collection<Map<String, Object>> spans =
                        (Collection<Map<String, Object>>) scopeSpan.get("spans");
                for (Map<String, Object> span : spans) {
                    exportSpans.add(new ObjectMapSpanData(id, resource,
                            scope, span));
                }
            }
        }

        exportSpans.forEach(span -> {
                Long durationNanos = span.getEndEpochNanos() - span.getStartEpochNanos();
                Long durationMs = durationNanos / 1000000;  
                Metrics.recordSpanDuration(span.getName(), durationMs, span.getSpanContext());
        });

        ConfigurationDefaults.spanExporter.export(exportSpans);
        
    }
}
