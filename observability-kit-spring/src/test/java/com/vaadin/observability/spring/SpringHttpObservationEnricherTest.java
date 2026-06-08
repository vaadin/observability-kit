/**
 * Copyright (C) 2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.server.observation.ServerRequestObservationContext;
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

    @Test
    void enrichmentSetsPathPatternAndContextualName() {
        HttpServletRequest httpRequest = Mockito.mock(HttpServletRequest.class);
        HttpServletResponse httpResponse = Mockito
                .mock(HttpServletResponse.class);
        ServerRequestObservationContext context = new ServerRequestObservationContext(
                httpRequest, httpResponse);

        VaadinRequest request = Mockito.mock(VaadinRequest.class);
        Mockito.when(request.getMethod()).thenReturn("POST");
        Mockito.when(request.getAttribute(
                ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE))
                .thenReturn(context);

        SpringHttpObservationEnricher.enrich(request, "uidl");

        Assertions.assertEquals("/vaadin/uidl", context.getPathPattern());
        Assertions.assertEquals("http post vaadin uidl",
                context.getContextualName());
    }
}
