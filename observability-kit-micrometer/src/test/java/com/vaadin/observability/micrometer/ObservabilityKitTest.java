package com.vaadin.observability.micrometer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
        ObservabilitySettings settings = ObservabilitySettings.builder().build();

        ObservabilityKit.install(registry, settings);

        assertSame(registry, ObservabilityKit.getMeterRegistry());
        assertSame(settings, ObservabilityKit.getSettings());
        assertNull(ObservabilityKit.getObservationRegistry());
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
