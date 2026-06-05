/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.client;

import org.junit.jupiter.api.Test;

import com.vaadin.observability.micrometer.MeterNames;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ClientMetricNames}.
 */
class ClientMetricNamesTest {

    @Test
    void bootstrapDurationIsAllowed() {
        assertTrue(ClientMetricNames
                .isAllowed(MeterNames.CLIENT_BOOTSTRAP_DURATION));
    }

    @Test
    void navigationDurationIsAllowed() {
        assertTrue(ClientMetricNames
                .isAllowed(MeterNames.CLIENT_NAVIGATION_DURATION));
    }

    @Test
    void webVitalsLcpIsAllowed() {
        assertTrue(
                ClientMetricNames.isAllowed(MeterNames.CLIENT_WEB_VITALS_LCP));
    }

    @Test
    void webVitalsFcpIsAllowed() {
        assertTrue(
                ClientMetricNames.isAllowed(MeterNames.CLIENT_WEB_VITALS_FCP));
    }

    @Test
    void clientErrorsIsAllowed() {
        assertTrue(ClientMetricNames.isAllowed(MeterNames.CLIENT_ERRORS));
    }

    @Test
    void clientRpcDurationIsNotAllowed() {
        // Deliberate reduction: RPC is measured server-side; client RPC timing
        // removed.
        assertFalse(
                ClientMetricNames.isAllowed(MeterNames.CLIENT_RPC_DURATION));
    }

    @Test
    void unknownNameIsNotAllowed() {
        assertFalse(
                ClientMetricNames.isAllowed("vaadin.client.unknown.metric"));
    }

    @Test
    void nullNameIsNotAllowed() {
        assertFalse(ClientMetricNames.isAllowed(null));
    }

    @Test
    void clientErrorsIsCounter() {
        assertTrue(ClientMetricNames.isCounter(MeterNames.CLIENT_ERRORS));
    }

    @Test
    void bootstrapDurationIsNotCounter() {
        assertFalse(ClientMetricNames
                .isCounter(MeterNames.CLIENT_BOOTSTRAP_DURATION));
    }

    @Test
    void navigationDurationIsNotCounter() {
        assertFalse(ClientMetricNames
                .isCounter(MeterNames.CLIENT_NAVIGATION_DURATION));
    }

    @Test
    void lcpIsNotCounter() {
        assertFalse(
                ClientMetricNames.isCounter(MeterNames.CLIENT_WEB_VITALS_LCP));
    }

    @Test
    void fcpIsNotCounter() {
        assertFalse(
                ClientMetricNames.isCounter(MeterNames.CLIENT_WEB_VITALS_FCP));
    }
}
