/**
 * Copyright (C) 2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.filter.ServerHttpObservationFilter;

import com.vaadin.flow.server.VaadinRequest;

class SpringHttpObservationEnricherTest {

    @Test
    void nullRequestDoesNotThrow() {
        Assertions.assertDoesNotThrow(
                () -> SpringHttpObservationEnricher.enrich(null, "uidl"));
    }

    @Test
    void nullTypeDoesNotThrow() {
        VaadinRequest request = Mockito.mock(VaadinRequest.class);
        Assertions.assertDoesNotThrow(
                () -> SpringHttpObservationEnricher.enrich(request, null));
    }

    @Test
    void missingObservationContextDoesNotThrow() {
        VaadinRequest request = Mockito.mock(VaadinRequest.class);
        Mockito.when(request.getAttribute(
                ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE))
                .thenReturn(null);
        Assertions.assertDoesNotThrow(
                () -> SpringHttpObservationEnricher.enrich(request, "uidl"));
    }
}
