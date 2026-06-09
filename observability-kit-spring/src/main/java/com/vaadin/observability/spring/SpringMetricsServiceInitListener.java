/**
 * Copyright (C) 2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.observability.micrometer.MetricsServiceInitListener;
import com.vaadin.observability.micrometer.ObservabilitySettings;

/**
 * Spring/Boot-aware {@link MetricsServiceInitListener} that skips the default
 * Observation handler registration and enriches the HTTP server observation.
 * <p>
 * In Spring/Boot setups the framework already registers a
 * {@code DefaultMeterObservationHandler} on the shared
 * {@link ObservationRegistry} (via Boot's {@code ObservationAutoConfiguration}
 * or the user's own {@code @Configuration}), so re-registering here would
 * double-emit Timers. HTTP observation enrichment is delegated to
 * {@link SpringHttpObservationEnricher}, making the parent HTTP span render as
 * e.g. {@code http post /vaadin/uidl} instead of the generic
 * {@code http post /**}.
 * <p>
 * This class is declared {@code public} so it can be reused by both
 * {@link ObservabilityConfiguration} (plain-Spring import) and the Boot
 * auto-configuration starter.
 */
public final class SpringMetricsServiceInitListener
        extends MetricsServiceInitListener {

    /**
     * Creates a new listener.
     *
     * @param registry
     *            the Micrometer meter registry, must not be {@code null}
     * @param observationRegistry
     *            the Micrometer observation registry; may be {@code null} when
     *            no {@link ObservationRegistry} bean is present — traces will
     *            be skipped in that case
     * @param settings
     *            the observability settings, must not be {@code null}
     */
    public SpringMetricsServiceInitListener(MeterRegistry registry,
            ObservationRegistry observationRegistry,
            ObservabilitySettings settings) {
        super(registry, observationRegistry, settings);
    }

    @Override
    protected void installDefaultObservationHandlers(
            ObservationRegistry observationRegistry, MeterRegistry registry) {
        // No-op: Spring Boot Actuator's ObservationAutoConfiguration
        // registers DefaultMeterObservationHandler; re-registering would
        // double-emit Timers.
    }

    @Override
    protected void enrichHttpObservation(VaadinRequest request,
            String requestType) {
        SpringHttpObservationEnricher.enrich(request, requestType);
    }
}
