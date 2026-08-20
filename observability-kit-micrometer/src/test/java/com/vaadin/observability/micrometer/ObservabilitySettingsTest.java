/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertEquals(10000, settings.getUiStateSampleInterval());
        assertEquals(0, settings.getUiStateBytesPerNode());
    }

    @Test
    void defaults_uiStateIsOptIn() {
        // Measuring per-UI state walks a component tree, so unlike the counting
        // binders it is not on until asked for.
        assertFalse(ObservabilitySettings.builder().build().isUiState());
        assertTrue(ObservabilitySettings.builder().uiState(true).build()
                .isUiState());
    }

    @Test
    void uiStateSampleInterval_negative_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> ObservabilitySettings
                .builder().uiStateSampleInterval(-1));
    }

    @Test
    void uiStateSampleInterval_zero_isAllowed() {
        assertDoesNotThrow(
                () -> ObservabilitySettings.builder().uiStateSampleInterval(0));
    }

    @Test
    void uiStateBytesPerNode_negative_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> ObservabilitySettings.builder().uiStateBytesPerNode(-1));
    }

    @Test
    void routeCardinalityLimit_zero_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> ObservabilitySettings.builder().routeCardinalityLimit(0));
    }

    @Test
    void routeCardinalityLimit_negative_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> ObservabilitySettings
                .builder().routeCardinalityLimit(-5));
    }

    @Test
    void clientRatePerSession_negative_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> ObservabilitySettings.builder().clientRatePerSession(-1));
    }

    @Test
    void clientRatePerSession_zero_isAllowed() {
        assertDoesNotThrow(
                () -> ObservabilitySettings.builder().clientRatePerSession(0));
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
