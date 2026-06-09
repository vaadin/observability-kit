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
import org.springframework.context.annotation.Bean;

import com.vaadin.flow.server.VaadinService;
import com.vaadin.observability.micrometer.MetricsServiceInitListener;
import com.vaadin.observability.micrometer.ObservabilitySettings;
import com.vaadin.observability.spring.SpringMetricsServiceInitListener;

/**
 * Auto-configures the Observability Kit {@link MetricsServiceInitListener} when
 * a {@link MeterRegistry} is present in the Spring context.
 * <p>
 * Activation is gated by the {@code vaadin.observability.enabled} property
 * (default {@code true}); the listener is also skipped when the user supplies
 * their own {@link MetricsServiceInitListener} bean.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration" })
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
}
