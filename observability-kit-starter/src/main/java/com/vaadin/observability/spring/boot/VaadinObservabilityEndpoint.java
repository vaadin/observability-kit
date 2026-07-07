/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring.boot;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

import com.vaadin.observability.micrometer.ObservabilityKit;
import com.vaadin.observability.micrometer.insights.ExemplarBuffer;
import com.vaadin.observability.micrometer.insights.InsightsService;

/**
 * The {@code /actuator/vaadin/observability} insights endpoint. Actuator
 * endpoint ids cannot contain slashes, so the path is realized as endpoint id
 * {@code vaadin} plus a selector, claiming the {@code vaadin} Actuator
 * namespace with room for future sub-resources.
 * <p>
 * The exemplar buffer is bound at {@code serviceInit} time, after this bean is
 * created, so it is looked up per request via
 * {@link ObservabilityKit#getActiveErrorExemplars()}, mirroring how the
 * dev-tools stats panel reads the active meter registry.
 */
@Endpoint(id = "vaadin")
public class VaadinObservabilityEndpoint {

    static final String SECTION_OBSERVABILITY = "observability";

    @ReadOperation
    public Map<String, Object> section(@Selector String section) {
        if (!SECTION_OBSERVABILITY.equals(section)) {
            // Unknown selector: null renders as 404.
            return null;
        }
        ExemplarBuffer exemplars = ObservabilityKit.getActiveExemplars();
        if (exemplars == null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schemaVersion", 1);
            payload.put("generated", Instant.now().toString());
            payload.put("insights", List.of());
            return payload;
        }
        return new InsightsService(exemplars).payload();
    }
}
