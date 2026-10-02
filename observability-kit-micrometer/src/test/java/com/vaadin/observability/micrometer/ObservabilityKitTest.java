/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vaadin.observability.micrometer.insights.RecentClientErrors;
import com.vaadin.observability.micrometer.insights.RecentInteractions;
import com.vaadin.observability.micrometer.insights.RecentQueries;
import com.vaadin.observability.micrometer.insights.RetainedStateGrowth;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ObservabilityKitTest {

    @AfterEach
    void tearDown() {
        ObservabilityKit.reset();
    }

    @Test
    void install_storesRegistryAndSettings() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservabilitySettings settings = ObservabilitySettings.builder()
                .build();

        ObservabilityKit.install(registry, settings);

        assertSame(registry, ObservabilityKit.getMeterRegistry());
        assertSame(settings, ObservabilityKit.getSettings());
        assertNotNull(ObservabilityKit.getObservationRegistry());
    }

    @Test
    void install_tracesEnabled_createsObservationRegistry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservabilitySettings settings = ObservabilitySettings.builder()
                .traces(true).build();

        ObservabilityKit.install(registry, settings);

        assertNotNull(ObservabilityKit.getObservationRegistry());
    }

    @Test
    void install_tracesDisabled_observationRegistryIsNull() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservabilitySettings settings = ObservabilitySettings.builder()
                .traces(false).build();

        ObservabilityKit.install(registry, settings);

        assertNull(ObservabilityKit.getObservationRegistry());
    }

    @Test
    void clearBound_clearsWhatTheServiceBound() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RecentInteractions interactions = new RecentInteractions(1);
        RecentQueries queries = new RecentQueries(1);
        RecentClientErrors clientErrors = new RecentClientErrors(1);
        RetainedStateGrowth growth = List::of;
        ObservabilityKit.setActiveMeterRegistry(registry);
        ObservabilityKit.setRecentInteractions(interactions);
        ObservabilityKit.setRecentQueries(queries);
        ObservabilityKit.setRecentClientErrors(clientErrors);
        ObservabilityKit.setRetainedStateGrowth(growth);

        ObservabilityKit.clearBound(registry, interactions, queries,
                clientErrors, growth);

        assertNull(ObservabilityKit.getActiveMeterRegistry());
        assertNull(ObservabilityKit.getRecentInteractions());
        assertNull(ObservabilityKit.getRecentQueries());
        assertNull(ObservabilityKit.getRecentClientErrors());
        assertNull(ObservabilityKit.getRetainedStateGrowth());
    }

    @Test
    void clearBound_leavesWhatAnotherServiceBoundSince() {
        SimpleMeterRegistry destroyed = new SimpleMeterRegistry();
        RecentInteractions destroyedBuffer = new RecentInteractions(1);
        SimpleMeterRegistry current = new SimpleMeterRegistry();
        RecentInteractions currentBuffer = new RecentInteractions(1);
        ObservabilityKit.setActiveMeterRegistry(current);
        ObservabilityKit.setRecentInteractions(currentBuffer);

        ObservabilityKit.clearBound(destroyed, destroyedBuffer, null, null,
                null);

        assertSame(current, ObservabilityKit.getActiveMeterRegistry());
        assertSame(currentBuffer, ObservabilityKit.getRecentInteractions());
    }

    @Test
    void reset_clearsState() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().build());

        ObservabilityKit.reset();

        assertNull(ObservabilityKit.getMeterRegistry());
        assertNull(ObservabilityKit.getSettings());
    }
}
