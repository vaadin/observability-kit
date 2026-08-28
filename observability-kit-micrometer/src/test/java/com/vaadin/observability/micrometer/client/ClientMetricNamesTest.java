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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void connectionIsAllowedAndIsCounter() {
        assertTrue(ClientMetricNames.isAllowed(MeterNames.CLIENT_CONNECTION));
        assertTrue(ClientMetricNames.isCounter(MeterNames.CLIENT_CONNECTION));
    }

    @Test
    void connectionDowntimeIsAllowedAndIsNotCounter() {
        assertTrue(ClientMetricNames
                .isAllowed(MeterNames.CLIENT_CONNECTION_DOWNTIME));
        assertFalse(ClientMetricNames
                .isCounter(MeterNames.CLIENT_CONNECTION_DOWNTIME));
    }

    @Test
    void connectionStatesTheStoreCanHoldAreAdmitted() {
        assertEquals(MeterNames.STATE_CONNECTED,
                ClientMetricNames.connectionState("connected"));
        assertEquals(MeterNames.STATE_CONNECTION_LOST,
                ClientMetricNames.connectionState("CONNECTION-LOST"));
        assertEquals(MeterNames.STATE_RECONNECTING,
                ClientMetricNames.connectionState("reconnecting"));
    }

    @Test
    void loadingIsNotAConnectionState() {
        // Flow enters it around every UIDL request to drive the loading
        // indicator; the collector normalizes it away, and a payload that
        // reports it anyway must not get its own series.
        assertEquals(MeterNames.STATE_UNKNOWN,
                ClientMetricNames.connectionState("loading"));
    }

    @Test
    void craftedConnectionStateIsBucketed() {
        assertEquals(MeterNames.STATE_UNKNOWN,
                ClientMetricNames.connectionState("whatever-the-client-said"));
        assertEquals(MeterNames.STATE_UNKNOWN,
                ClientMetricNames.connectionState(null));
    }

    @Test
    void onlyTheUnreachableStatesCarryDowntime() {
        assertEquals(MeterNames.STATE_CONNECTION_LOST,
                ClientMetricNames.downtimeState("connection-lost"));
        assertEquals(MeterNames.STATE_RECONNECTING,
                ClientMetricNames.downtimeState("reconnecting"));
        // A browser does not spend time being unreachable while connected.
        assertEquals(MeterNames.STATE_UNKNOWN,
                ClientMetricNames.downtimeState("connected"));
        assertEquals(MeterNames.STATE_UNKNOWN,
                ClientMetricNames.downtimeState(null));
    }
}
