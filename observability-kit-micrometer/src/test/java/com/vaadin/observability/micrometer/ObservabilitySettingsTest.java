/*
 * Copyright 2000-2026 Vaadin Ltd.
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full license.
 */
package com.vaadin.observability.micrometer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservabilitySettingsTest {

    @Test
    void defaults_allFeaturesEnabledExceptSessionIdTracing() {
        ObservabilitySettings settings = ObservabilitySettings.builder()
                .build();

        assertTrue(settings.isSessions());
        assertTrue(settings.isUis());
        assertTrue(settings.isNavigation());
        assertTrue(settings.isRequests());
        assertTrue(settings.isErrors());
        assertTrue(settings.isClient());
        assertTrue(settings.isTraces());
        assertFalse(settings.isTracesSessionId());
        assertEquals(200, settings.getRouteCardinalityLimit());
        assertEquals(100, settings.getClientRatePerSession());
    }

    @Test
    void builder_overridesAreApplied() {
        ObservabilitySettings settings = ObservabilitySettings.builder()
                .sessions(false).tracesSessionId(true).routeCardinalityLimit(50)
                .clientRatePerSession(10).build();

        assertFalse(settings.isSessions());
        assertTrue(settings.isTracesSessionId());
        assertEquals(50, settings.getRouteCardinalityLimit());
        assertEquals(10, settings.getClientRatePerSession());
    }
}
