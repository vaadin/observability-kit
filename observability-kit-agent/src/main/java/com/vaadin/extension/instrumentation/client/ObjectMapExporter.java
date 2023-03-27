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

public class ObjectMapExporter
        implements BiConsumer<String, Map<String, Object>> {
    @Override
    public void accept(String id, Map<String, Object> objectMap) {
        Collection<SpanData> exportSpans = new ArrayList<>();
        if (!objectMap.containsKey("resourceSpans") ||
            !(objectMap.get("resourceSpans") instanceof List)) {
            throw new RuntimeException("Malformed span data");
        }

        List<Map<String, Object>> resourceSpans =
            (List<Map<String, Object>>) objectMap.get("resourceSpans");
        for (Map<String, Object> resourceSpan : resourceSpans) {
            Map<String, Object> resource =
                    (Map<String, Object>) resourceSpan.get("resource");
            List<Map<String, Object>> scopeSpans =
                    (List<Map<String, Object>>) resourceSpan.get("scopeSpans");
            for (Map<String, Object> scopeSpan : scopeSpans) {
                Map<String, Object> scope =
                        (Map<String, Object>) scopeSpan.get("scope");
                List<Map<String, Object>> spans =
                        (List<Map<String, Object>>) scopeSpan.get("spans");
                for (Map<String, Object> span : spans) {
                    exportSpans.add(new ObjectMapSpanData(id, resource,
                            scope, span));
                }
            }
        }

        ConfigurationDefaults.spanExporter.export(exportSpans);
    }
}
