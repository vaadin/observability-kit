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
import io.micrometer.observation.ObservationRegistry;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinRequest;
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

    /**
     * Hook for DI integrations to enrich the framework-level HTTP observation
     * (e.g. Spring's {@code ServerHttpObservationFilter} span) with
     * Vaadin-specific information so the parent HTTP span renders informatively
     * in the trace UI. Called from {@link RequestMetricsBinder} after the
     * Vaadin request type has been determined and before the
     * {@code vaadin.request.<type>} child observation is started.
     * <p>
     * Default implementation no-ops, keeping the framework-agnostic core free
     * of Spring imports. The Spring/Boot integration modules override this to
     * call into their respective HTTP-observation APIs.
     *
     * @param request
     *            the current Vaadin request
     * @param requestType
     *            the classified request type (e.g. {@code uidl},
     *            {@code heartbeat}, {@code push}, {@code static},
     *            {@code other})
     */
    protected void enrichHttpObservation(VaadinRequest request,
            String requestType) {
        // no-op by default
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
        ObservationRegistry observationRegistry = ObservabilityKit
                .getObservationRegistry();

        VaadinService service = event.getSource();
        if (effectiveSettings.isSessions()) {
            SessionMetricsBinder binder = new SessionMetricsBinder(
                    meterRegistry);
            service.addSessionInitListener(binder);
            service.addSessionDestroyListener(binder);
            service.addSessionLockListener(
                    new SessionLockMetricsBinder(meterRegistry));
        }

        if (effectiveSettings.isUis() || effectiveSettings.isNavigation()) {
            service.addUIInitListener(new UiMetricsBinder(meterRegistry,
                    observationRegistry, effectiveSettings));
        }

        if (effectiveSettings.isRequests() || effectiveSettings.isErrors()) {
            event.addVaadinRequestInterceptor(
                    new RequestMetricsBinder(meterRegistry, observationRegistry,
                            effectiveSettings, this::enrichHttpObservation));
        }
    }
}
