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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.observability.micrometer.MetricsServiceInitListener;
import com.vaadin.observability.micrometer.ObservabilitySettings;

/**
 * Plain-Spring (non-Boot) configuration for Observability Kit.
 * <p>
 * Users opt in by importing this class:
 *
 * <pre>
 * {@code
 * &#64;Configuration
 * &#64;Import(ObservabilityConfiguration.class)
 * public class MyAppConfig { ... }
 * }
 * </pre>
 *
 * Requires a {@link MeterRegistry} bean to be defined elsewhere in the
 * application context. An {@link ObservationRegistry} bean is picked up if
 * present (Spring Boot Actuator supplies one); otherwise the Observation code
 * paths are skipped and traces aren't emitted.
 */
@Configuration
public class ObservabilityConfiguration {

    private final ObservabilitySettings settings;

    /**
     * Binds {@code vaadin.observability.*} properties (all optional, each with
     * a sensible default) and builds the {@link ObservabilitySettings} used by
     * the instrumentation.
     *
     * @param sessions
     *            whether to track session metrics (default {@code true})
     * @param uis
     *            whether to track UI metrics (default {@code true})
     * @param navigation
     *            whether to track navigation metrics (default {@code true})
     * @param requests
     *            whether to track request metrics (default {@code true})
     * @param errors
     *            whether to track error metrics (default {@code true})
     * @param traces
     *            whether to emit traces (default {@code true})
     * @param tracesSessionId
     *            whether to include session ID in traces (default
     *            {@code false})
     * @param routeCardinalityLimit
     *            maximum number of distinct route tag values (default
     *            {@code 200})
     * @param client
     *            whether to track client-side metrics (default {@code true})
     * @param clientRatePerSession
     *            maximum client-side metric events per session (default
     *            {@code 100})
     */
    public ObservabilityConfiguration(
            @Value("${vaadin.observability.sessions:true}") boolean sessions,
            @Value("${vaadin.observability.uis:true}") boolean uis,
            @Value("${vaadin.observability.navigation:true}") boolean navigation,
            @Value("${vaadin.observability.requests:true}") boolean requests,
            @Value("${vaadin.observability.errors:true}") boolean errors,
            @Value("${vaadin.observability.traces:true}") boolean traces,
            @Value("${vaadin.observability.traces.session-id:false}") boolean tracesSessionId,
            @Value("${vaadin.observability.route-cardinality-limit:200}") int routeCardinalityLimit,
            @Value("${vaadin.observability.client:true}") boolean client,
            @Value("${vaadin.observability.client-rate-per-session:100}") int clientRatePerSession) {
        this.settings = ObservabilitySettings.builder().sessions(sessions)
                .uis(uis).navigation(navigation).requests(requests)
                .errors(errors).traces(traces).tracesSessionId(tracesSessionId)
                .routeCardinalityLimit(routeCardinalityLimit).client(client)
                .clientRatePerSession(clientRatePerSession).build();
    }

    /**
     * Exposes the {@link ObservabilitySettings} bound from
     * {@code vaadin.observability.*} as a bean.
     *
     * @return the configured settings
     */
    @Bean
    ObservabilitySettings observabilitySettings() {
        return settings;
    }

    /**
     * Creates the Spring-aware {@link MetricsServiceInitListener} bean that
     * wires instrumentation into the Vaadin service.
     *
     * @param registry
     *            the Micrometer meter registry, must be present in the context
     * @param observationRegistry
     *            optional Micrometer observation registry; picked up if a bean
     *            is present
     * @return a Spring-aware {@link MetricsServiceInitListener}
     */
    @Bean
    MetricsServiceInitListener metricsServiceInitListener(
            MeterRegistry registry,
            ObjectProvider<ObservationRegistry> observationRegistry) {
        return new SpringMetricsServiceInitListener(registry,
                observationRegistry.getIfAvailable(), settings);
    }

    /**
     * Spring-aware subclass that skips the default Observation handler
     * registration: in Spring/Boot setups the framework already registers a
     * {@code DefaultMeterObservationHandler} on the shared
     * {@link ObservationRegistry} (via Boot's
     * {@code ObservationAutoConfiguration} or the user's own
     * {@code @Configuration}), so re-registering here would double-emit Timers.
     * It also delegates HTTP observation enrichment to
     * {@link SpringHttpObservationEnricher}.
     */
    static class SpringMetricsServiceInitListener
            extends MetricsServiceInitListener {

        SpringMetricsServiceInitListener(MeterRegistry registry,
                ObservationRegistry observationRegistry,
                ObservabilitySettings settings) {
            super(registry, observationRegistry, settings);
        }

        @Override
        protected void installDefaultObservationHandlers(
                ObservationRegistry observationRegistry,
                MeterRegistry registry) {
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
}
