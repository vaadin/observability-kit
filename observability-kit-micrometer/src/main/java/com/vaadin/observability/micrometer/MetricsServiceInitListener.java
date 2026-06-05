/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import io.micrometer.core.instrument.MeterRegistry;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServiceInitListener;

/**
 * Wires Observability Kit instrumentation into a {@code VaadinService} at
 * initialization. The no-arg constructor (used by the Java SPI) resolves the
 * registry and settings from {@link ObservabilityKit}; the registry/settings
 * constructor supports dependency-injection environments.
 */
public class MetricsServiceInitListener implements VaadinServiceInitListener {

    private final MeterRegistry registry;
    private final ObservabilitySettings settings;

    public MetricsServiceInitListener() {
        this(null, null);
    }

    public MetricsServiceInitListener(MeterRegistry registry,
            ObservabilitySettings settings) {
        this.registry = registry;
        this.settings = settings;
    }

    @Override
    public void serviceInit(ServiceInitEvent event) {
        MeterRegistry meterRegistry = registry != null ? registry
                : ObservabilityKit.getMeterRegistry();
        ObservabilitySettings effectiveSettings = settings != null ? settings
                : ObservabilityKit.getSettings();
        if (meterRegistry == null || effectiveSettings == null) {
            return;
        }

        VaadinService service = event.getSource();
        if (effectiveSettings.isSessions()) {
            SessionMetricsBinder binder = new SessionMetricsBinder(
                    meterRegistry);
            service.addSessionInitListener(binder);
            service.addSessionDestroyListener(binder);
            service.addSessionLockListener(
                    new SessionLockMetricsBinder(meterRegistry));
        }
    }
}
