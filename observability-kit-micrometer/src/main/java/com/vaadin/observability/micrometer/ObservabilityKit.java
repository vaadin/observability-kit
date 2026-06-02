package com.vaadin.observability.micrometer;

import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

/**
 * Programmatic bootstrap for standalone (non-Spring) deployments. Call
 * {@link #install(MeterRegistry, ObservabilitySettings)} once at startup; the
 * SPI-loaded {@code MetricsServiceInitListener} reads the stored registry and
 * settings when the {@code VaadinService} initializes.
 */
public final class ObservabilityKit {

    private static final AtomicReference<MeterRegistry> METER_REGISTRY =
            new AtomicReference<>();
    private static final AtomicReference<ObservationRegistry> OBSERVATION_REGISTRY =
            new AtomicReference<>();
    private static final AtomicReference<ObservabilitySettings> SETTINGS =
            new AtomicReference<>();

    private ObservabilityKit() {
    }

    public static void install(MeterRegistry meterRegistry,
            ObservabilitySettings settings) {
        install(meterRegistry, null, settings);
    }

    public static void install(MeterRegistry meterRegistry,
            ObservationRegistry observationRegistry,
            ObservabilitySettings settings) {
        METER_REGISTRY.set(meterRegistry);
        OBSERVATION_REGISTRY.set(observationRegistry);
        SETTINGS.set(settings);
    }

    static MeterRegistry getMeterRegistry() {
        return METER_REGISTRY.get();
    }

    static ObservationRegistry getObservationRegistry() {
        return OBSERVATION_REGISTRY.get();
    }

    static ObservabilitySettings getSettings() {
        return SETTINGS.get();
    }

    /** Clears all installed state. Intended for tests and redeploys. */
    static void reset() {
        METER_REGISTRY.set(null);
        OBSERVATION_REGISTRY.set(null);
        SETTINGS.set(null);
    }
}
