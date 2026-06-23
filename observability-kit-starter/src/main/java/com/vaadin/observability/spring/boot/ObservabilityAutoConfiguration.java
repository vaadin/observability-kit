/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring.boot;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import com.vaadin.flow.server.VaadinService;
import com.vaadin.observability.micrometer.MetricsServiceInitListener;
import com.vaadin.observability.micrometer.ObservabilitySettings;
import com.vaadin.observability.micrometer.ResyncDetectionFilter;
import com.vaadin.observability.spring.SpringMetricsServiceInitListener;

/**
 * Auto-configures the Observability Kit {@link MetricsServiceInitListener} when
 * a {@link MeterRegistry} is present in the Spring context.
 * <p>
 * The starter pulls in Boot's Micrometer metrics auto-configuration, so a
 * {@link MeterRegistry} is wired out of the box and this listener activates
 * without further setup; the application only chooses the registry backend
 * (Prometheus, OTLP, ...) and whether to expose Actuator endpoints. Ordered
 * after {@link MetricsAutoConfiguration} and
 * {@link CompositeMeterRegistryAutoConfiguration} so that registry is visible
 * when this runs.
 * <p>
 * Activation is gated by the {@code vaadin.observability.enabled} property
 * (default {@code true}); the listener is also skipped when the user supplies
 * their own {@link MetricsServiceInitListener} bean.
 */
@AutoConfiguration(after = { MetricsAutoConfiguration.class,
        CompositeMeterRegistryAutoConfiguration.class })
@ConditionalOnClass({ MeterRegistry.class, VaadinService.class })
@ConditionalOnProperty(prefix = "vaadin.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ObservabilitySettings observabilitySettings(
            ObservabilityProperties properties) {
        return properties.toSettings();
    }

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean(MetricsServiceInitListener.class)
    MetricsServiceInitListener metricsServiceInitListener(
            MeterRegistry registry,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObservabilitySettings settings) {
        return new SpringMetricsServiceInitListener(registry,
                observationRegistry.getIfAvailable(), settings);
    }

    /**
     * Registers the prototype {@link ResyncDetectionFilter}, which observes
     * UIDL message resends and client-requested resynchronizations by
     * inspecting incoming UIDL request bodies. Runs at highest precedence so
     * the body is buffered before any other filter consumes it, and gated by
     * {@code vaadin.observability.resync} (default {@code true}).
     */
    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "vaadin.observability", name = "resync", havingValue = "true", matchIfMissing = true)
    FilterRegistrationBean<ResyncDetectionFilter> resyncDetectionFilter(
            MeterRegistry registry) {
        FilterRegistrationBean<ResyncDetectionFilter> registration = new FilterRegistrationBean<>(
                new ResyncDetectionFilter(registry));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
