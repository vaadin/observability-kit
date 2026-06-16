/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring.boot;

import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;

import com.vaadin.flow.server.VaadinService;
import com.vaadin.observability.micrometer.MetricsServiceInitListener;
import com.vaadin.observability.micrometer.ObservabilitySettings;
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
     * Wraps {@link DataSource} beans to record {@code vaadin.db.fetch.rows}.
     * Opt-in via {@code vaadin.observability.database=true} since it reaches
     * outside the Vaadin runtime and adds a small per-row cost. Declared as an
     * infrastructure-role {@code static} method so the post-processor is
     * created early enough to wrap the {@code DataSource}.
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnClass(DataSource.class)
    @ConditionalOnProperty(prefix = "vaadin.observability", name = "database", havingValue = "true")
    static DataSourceFetchMetricsBeanPostProcessor dataSourceFetchMetricsBeanPostProcessor(
            ObjectProvider<MeterRegistry> meterRegistry,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<ObservabilitySettings> settings) {
        return new DataSourceFetchMetricsBeanPostProcessor(meterRegistry,
                observationRegistry, settings);
    }
}
