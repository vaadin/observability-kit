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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.filter.ServerHttpObservationFilter;

import com.vaadin.flow.server.VaadinRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpringHttpObservationEnricherTest {

    @Test
    void nullRequestDoesNotThrow() {
        assertThatCode(() -> SpringHttpObservationEnricher.enrich(null, "uidl"))
                .doesNotThrowAnyException();
    }

    @Test
    void nullTypeDoesNotThrow(@Mock VaadinRequest request) {
        assertThatCode(
                () -> SpringHttpObservationEnricher.enrich(request, null))
                .doesNotThrowAnyException();
    }

    @Test
    void missingObservationContextDoesNotThrow(@Mock VaadinRequest request) {
        when(request.getAttribute(
                ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE))
                .thenReturn(null);
        assertThatCode(
                () -> SpringHttpObservationEnricher.enrich(request, "uidl"))
                .doesNotThrowAnyException();
    }

    @Test
    void enrichmentSetsPathPatternAndContextualName(@Mock VaadinRequest request,
            @Mock HttpServletRequest httpRequest,
            @Mock HttpServletResponse httpResponse) {
        ServerRequestObservationContext context = new ServerRequestObservationContext(
                httpRequest, httpResponse);

        when(request.getMethod()).thenReturn("POST");
        when(request.getAttribute(
                ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE))
                .thenReturn(context);

        SpringHttpObservationEnricher.enrich(request, "uidl");

        assertThat(context.getPathPattern()).isEqualTo("/vaadin/uidl");
        assertThat(context.getContextualName())
                .isEqualTo("http post vaadin uidl");
    }
}
