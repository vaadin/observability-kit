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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VaadinObservabilityEndpointTest {

    private final VaadinObservabilityEndpoint endpoint = new VaadinObservabilityEndpoint();

    @Test
    void observabilitySelectorReturnsSchemaVersionedPayload() {
        Map<String, Object> payload = endpoint
                .section(VaadinObservabilityEndpoint.SECTION_OBSERVABILITY);

        assertThat(payload).isNotNull();
        assertThat(payload).containsEntry("schemaVersion", 1);
        assertThat(payload).containsKey("insights");
    }

    @Test
    void saysInstrumentationIsInactiveWhenNothingIsBound() {
        // No serviceInit has run here, so no interaction buffer is bound. The
        // endpoint must say that outright: an empty "insights" list would read
        // as "we looked and found nothing", which is a different answer and
        // would send a consumer looking for a bug that is not there.
        Map<String, Object> payload = endpoint
                .section(VaadinObservabilityEndpoint.SECTION_OBSERVABILITY);

        assertThat(payload).containsEntry("instrumentation", "inactive");
        assertThat(payload).extracting("insights").asList().isEmpty();
    }

    @Test
    void unknownSelectorReturnsNullSoActuatorRenders404() {
        assertThat(endpoint.section("does-not-exist")).isNull();
    }
}
