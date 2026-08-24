/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring.boot;

import java.util.Map;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

import com.vaadin.observability.micrometer.ObservabilityKit;
import com.vaadin.observability.micrometer.insights.InsightsService;

/**
 * The {@code /actuator/vaadin/observability} insights endpoint. Actuator
 * endpoint ids cannot contain slashes, so the path is realized as endpoint id
 * {@code vaadin} plus a selector, claiming the {@code vaadin} Actuator
 * namespace with room for future sub-resources.
 * <p>
 * The interaction buffer is bound at {@code serviceInit} time, after this bean
 * is created, so it is looked up per request via
 * {@link ObservabilityKit#getRecentInteractions()}, mirroring how the dev-tools
 * stats panel reads the active meter registry. A {@code null} buffer means the
 * kit registered no instrumentation; {@link InsightsService} renders that as an
 * explicit {@code instrumentation: inactive} rather than an empty result, so
 * this endpoint does not need a second code path for it.
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
        return new InsightsService(ObservabilityKit.getRecentInteractions(),
                ObservabilityKit.getRecentQueries()).payload();
    }
}
