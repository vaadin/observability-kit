/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;

import com.vaadin.observability.micrometer.insights.RecentClientErrors;
import com.vaadin.observability.micrometer.insights.RecentInteractions;
import com.vaadin.observability.micrometer.insights.RecentQueries;

/**
 * Programmatic bootstrap for standalone (non-Spring) deployments. Call
 * {@link #install(MeterRegistry, ObservabilitySettings)} once at startup; the
 * SPI-loaded {@code MetricsServiceInitListener} reads the stored registry and
 * settings when the {@code VaadinService} initializes.
 */
public final class ObservabilityKit {

    private static final AtomicReference<MeterRegistry> METER_REGISTRY = new AtomicReference<>();
    private static final AtomicReference<ObservationRegistry> OBSERVATION_REGISTRY = new AtomicReference<>();
    private static final AtomicReference<ObservabilitySettings> SETTINGS = new AtomicReference<>();

    /**
     * The registry instrumentation was actually bound to, recorded at
     * {@code serviceInit} time. Unlike {@link #METER_REGISTRY} (only populated
     * by {@link #install} in standalone deployments) this is set for every
     * deployment type, including Spring where the registry arrives via DI. Used
     * by the dev-mode Copilot metrics panel to read the live meters.
     */
    private static final AtomicReference<MeterRegistry> ACTIVE_METER_REGISTRY = new AtomicReference<>();

    /**
     * The recent-interactions buffer instrumentation was bound to, recorded at
     * {@code serviceInit} time like {@link #ACTIVE_METER_REGISTRY}. Read by the
     * insights endpoint.
     */
    private static final AtomicReference<RecentInteractions> RECENT_INTERACTIONS = new AtomicReference<>();
    private static final AtomicReference<RecentQueries> RECENT_QUERIES = new AtomicReference<>();

    /**
     * The browser-error buffer instrumentation was bound to, recorded at
     * {@code serviceInit} time like the two above. Read by the insights
     * endpoint.
     */
    private static final AtomicReference<RecentClientErrors> RECENT_CLIENT_ERRORS = new AtomicReference<>();

    private ObservabilityKit() {
    }

    public static void install(MeterRegistry meterRegistry,
            ObservabilitySettings settings) {
        Objects.requireNonNull(meterRegistry, "meterRegistry");
        Objects.requireNonNull(settings, "settings");
        ObservationRegistry observationRegistry = null;
        if (settings.isTraces()) {
            observationRegistry = ObservationRegistry.create();
            observationRegistry.observationConfig().observationHandler(
                    new DefaultMeterObservationHandler(meterRegistry));
        }
        install(meterRegistry, observationRegistry, settings);
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

    /**
     * Records the registry instrumentation was bound to. Called from
     * {@code MetricsServiceInitListener} for all deployment types.
     */
    static void setActiveMeterRegistry(MeterRegistry registry) {
        ACTIVE_METER_REGISTRY.set(registry);
    }

    /**
     * The registry instrumentation is currently publishing to, or {@code null}
     * if instrumentation has not been bound. Read by the dev-mode Copilot
     * metrics panel.
     *
     * @return the active meter registry, or {@code null}
     */
    public static MeterRegistry getActiveMeterRegistry() {
        return ACTIVE_METER_REGISTRY.get();
    }

    /**
     * Records the recent-queries buffer instrumentation was bound to. Called
     * from {@code MetricsServiceInitListener} for all deployment types.
     */
    static void setRecentQueries(RecentQueries buffer) {
        RECENT_QUERIES.set(buffer);
    }

    /**
     * Gets the retained data provider queries, or {@code null} when the query
     * collector was not registered.
     *
     * @return the query buffer, or {@code null}
     */
    public static RecentQueries getRecentQueries() {
        return RECENT_QUERIES.get();
    }

    /**
     * Records the browser-error buffer instrumentation was bound to. Called
     * from {@code MetricsServiceInitListener} for all deployment types.
     */
    static void setRecentClientErrors(RecentClientErrors buffer) {
        RECENT_CLIENT_ERRORS.set(buffer);
    }

    /**
     * Gets the retained browser errors, or {@code null} when any of the three
     * settings they need was off — the in-browser collector, insights, or error
     * instrumentation.
     *
     * @return the browser-error buffer, or {@code null}
     */
    public static RecentClientErrors getRecentClientErrors() {
        return RECENT_CLIENT_ERRORS.get();
    }

    static void setRecentInteractions(RecentInteractions buffer) {
        RECENT_INTERACTIONS.set(buffer);
    }

    /**
     * The recent-interactions buffer instrumentation is currently recording
     * into, or {@code null} if interaction backtracking has not been bound.
     * Read by the insights endpoint.
     *
     * @return the active recent-interactions buffer, or {@code null}
     */
    public static RecentInteractions getRecentInteractions() {
        return RECENT_INTERACTIONS.get();
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
        ACTIVE_METER_REGISTRY.set(null);
        RECENT_INTERACTIONS.set(null);
        RECENT_QUERIES.set(null);
        RECENT_CLIENT_ERRORS.set(null);
    }
}
