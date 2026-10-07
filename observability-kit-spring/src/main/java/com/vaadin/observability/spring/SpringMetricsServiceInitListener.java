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
import org.springframework.util.ClassUtils;

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
 * {@code http post /**}, and marking it errored when Vaadin request handling
 * raises an exception the servlet chain never sees. The enrichment is skipped
 * when spring-web is not on the classpath: there is then no HTTP observation to
 * enrich, and the enricher could not even be loaded.
 * <p>
 * This class is declared {@code public} so it can be reused by both
 * {@link ObservabilityConfiguration} (plain-Spring import) and the Boot
 * auto-configuration starter.
 */
public final class SpringMetricsServiceInitListener
        extends MetricsServiceInitListener {

    /**
     * Whether spring-web, which {@link SpringHttpObservationEnricher} links
     * against, is on the classpath. Neither the starter nor vaadin-spring bring
     * it at runtime, and a missing class would surface as a
     * {@link NoClassDefFoundError} on the first request.
     */
    private static final boolean SPRING_WEB_PRESENT = ClassUtils.isPresent(
            "org.springframework.web.filter.ServerHttpObservationFilter",
            SpringMetricsServiceInitListener.class.getClassLoader());

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
        if (SPRING_WEB_PRESENT) {
            SpringHttpObservationEnricher.enrich(request, requestType);
        }
    }

    @Override
    protected void enrichHttpObservationRoute(VaadinRequest request,
            String routeTemplate) {
        if (SPRING_WEB_PRESENT) {
            SpringHttpObservationEnricher.route(request, routeTemplate);
        }
    }

    @Override
    protected void markHttpObservationError(VaadinRequest request,
            Throwable failure) {
        if (SPRING_WEB_PRESENT) {
            SpringHttpObservationEnricher.error(request, failure);
        }
    }
}
