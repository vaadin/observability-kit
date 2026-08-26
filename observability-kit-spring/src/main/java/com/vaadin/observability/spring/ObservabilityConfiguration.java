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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.vaadin.observability.micrometer.MetricsServiceInitListener;
import com.vaadin.observability.micrometer.ObservabilitySettings;
import com.vaadin.observability.micrometer.insights.RecentInteractions;

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
     * Binds {@code vaadin.observability.*} from the environment and builds the
     * {@link ObservabilitySettings} used by the instrumentation. All properties
     * are optional and default to the same values as
     * {@link ObservabilitySettings#builder()}.
     * <p>
     * Read from the {@link Environment} rather than declared as one
     * {@code @Value} parameter per property: with a parameter list the class
     * became a second source of truth for the settings, and drifted from it.
     * The {@code data}, {@code resync}, {@code database} and
     * {@code database-statement} settings were all unbindable here while
     * working in the Boot starter.
     *
     * @param environment
     *            the Spring environment to read the properties from
     */
    ObservabilityConfiguration(Environment environment) {
        ObservabilitySettings.Builder builder = ObservabilitySettings.builder();
        this.settings = builder.sessions(flag(environment, "sessions", true))
                .uis(flag(environment, "uis", true))
                .uiState(flag(environment, "ui-state", false))
                .uiStateSampleInterval(
                        number(environment, "ui-state-sample-interval", 10000))
                .uiStateBytesPerNode(
                        number(environment, "ui-state-bytes-per-node", 0))
                .navigation(flag(environment, "navigation", true))
                .requests(flag(environment, "requests", true))
                .data(flag(environment, "data", true))
                .errors(flag(environment, "errors", true))
                .client(flag(environment, "client", true))
                .resync(flag(environment, "resync", true))
                .traces(flag(environment, "traces", true))
                .tracesSessionId(flag(environment, "traces-session-id", false))
                .database(flag(environment, "database", false))
                .databaseStatement(
                        flag(environment, "database-statement", false))
                .insights(flag(environment, "insights", true))
                .insightsDetails(flag(environment, "insights-details", false))
                .routeCardinalityLimit(
                        number(environment, "route-cardinality-limit", 200))
                .clientRatePerSession(
                        number(environment, "client-rate-per-session", 100))
                .insightsCapacity(number(environment, "insights-capacity",
                        RecentInteractions.DEFAULT_CAPACITY))
                .build();
    }

    private static boolean flag(Environment environment, String name,
            boolean defaultValue) {
        return environment.getProperty("vaadin.observability." + name,
                Boolean.class, defaultValue);
    }

    private static int number(Environment environment, String name,
            int defaultValue) {
        return environment.getProperty("vaadin.observability." + name,
                Integer.class, defaultValue);
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

}
