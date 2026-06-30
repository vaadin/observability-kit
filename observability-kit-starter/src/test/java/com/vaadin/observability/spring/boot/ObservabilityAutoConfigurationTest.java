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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.vaadin.observability.micrometer.MetricsServiceInitListener;
import com.vaadin.observability.micrometer.ObservabilitySettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ObservabilityAutoConfiguration} using
 * {@link ApplicationContextRunner}.
 */
class ObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(ObservabilityAutoConfiguration.class));

    /**
     * Default context with a MeterRegistry bean: both ObservabilitySettings and
     * MetricsServiceInitListener should be present.
     */
    @Test
    void defaultConfiguration_withMeterRegistry_registersBeansExpected() {
        contextRunner
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context)
                            .hasSingleBean(ObservabilitySettings.class);
                    assertThat(context)
                            .hasSingleBean(MetricsServiceInitListener.class);
                });
    }

    /**
     * Real-starter behavior: with Boot's Micrometer metrics auto-configuration
     * on the classpath (as the starter pulls it in) and no manually supplied
     * registry, a {@link MeterRegistry} is wired out of the box and our
     * listener activates on top of it.
     */
    @Test
    void metricsAutoConfigurationPresent_wiresRegistryAndListener() {
        contextRunner
                .withConfiguration(
                        AutoConfigurations.of(MetricsAutoConfiguration.class,
                                CompositeMeterRegistryAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(MeterRegistry.class);
                    assertThat(context)
                            .hasSingleBean(MetricsServiceInitListener.class);
                });
    }

    /**
     * When vaadin.observability.enabled=false, the auto-configuration should
     * not activate and no beans should be registered.
     */
    @Test
    void disabledProperty_doesNotRegisterBeans() {
        contextRunner.withPropertyValues("vaadin.observability.enabled=false")
                .run(context -> {
                    assertThat(context)
                            .doesNotHaveBean(MetricsServiceInitListener.class);
                    assertThat(context)
                            .doesNotHaveBean(ObservabilitySettings.class);
                });
    }

    /**
     * Property binding: sessions=false and route-cardinality-limit=42 should be
     * reflected in the ObservabilitySettings bean.
     */
    @Test
    void propertyBinding_reflectedInSettings() {
        contextRunner
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("vaadin.observability.sessions=false",
                        "vaadin.observability.route-cardinality-limit=42")
                .run(context -> {
                    assertThat(context)
                            .hasSingleBean(ObservabilitySettings.class);
                    ObservabilitySettings settings = context
                            .getBean(ObservabilitySettings.class);
                    assertThat(settings.isSessions()).isFalse();
                    assertThat(settings.getRouteCardinalityLimit())
                            .isEqualTo(42);
                });
    }

    /**
     * Without a MeterRegistry bean, the MetricsServiceInitListener should not
     * be registered (gated by @ConditionalOnBean(MeterRegistry.class)). The
     * ObservabilitySettings bean is NOT gated on a MeterRegistry, so it must
     * still be present.
     */
    @Test
    void noMeterRegistry_doesNotRegisterListener() {
        contextRunner.run(context -> {
            assertThat(context)
                    .doesNotHaveBean(MetricsServiceInitListener.class);
            assertThat(context).hasSingleBean(ObservabilitySettings.class);
        });
    }

    /**
     * User-supplied ObservabilitySettings bean: our auto-configured settings
     * bean should back off (@ConditionalOnMissingBean), and the custom bean
     * should be used.
     */
    @Test
    void userSuppliedSettings_autoConfigBacksOff() {
        contextRunner
                .withBean(SimpleMeterRegistry.class,
                        SimpleMeterRegistry::new)
                .withBean(ObservabilitySettings.class,
                        () -> ObservabilitySettings.builder().sessions(false)
                                .build())
                .run(context -> {
                    assertThat(context)
                            .hasSingleBean(ObservabilitySettings.class);
                    assertThat(context.getBean(ObservabilitySettings.class)
                            .isSessions()).isFalse();
                });
    }

    /**
     * Database monitoring is opt-in: with the default property a DataSource
     * bean is left untouched and the post-processor is not registered.
     */
    @Test
    void databaseMonitoringDisabledByDefault_dataSourceNotWrapped() {
        contextRunner
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            DataSourceFetchMetricsBeanPostProcessor.class);
                    assertThat(context.getBean(DataSource.class))
                            .isNotInstanceOf(RowCountingDataSource.class);
                });
    }

    /**
     * With {@code vaadin.observability.database=true} the post-processor is
     * registered and wraps the application's DataSource bean.
     */
    @Test
    void databaseMonitoringEnabled_dataSourceWrapped() {
        contextRunner
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues("vaadin.observability.database=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            DataSourceFetchMetricsBeanPostProcessor.class);
                    assertThat(context.getBean(DataSource.class))
                            .isInstanceOf(RowCountingDataSource.class);
                });
    }

    /**
     * User-supplied MetricsServiceInitListener bean: our auto-configured
     * listener should back off (@ConditionalOnMissingBean), and the custom bean
     * should be used.
     */
    @Test
    void userSuppliedListener_autoConfigBacksOff() {
        MetricsServiceInitListener customListener = new MetricsServiceInitListener(
                new SimpleMeterRegistry(),
                ObservabilitySettings.builder().build());
        contextRunner
                .withBean("custom", MetricsServiceInitListener.class,
                        () -> customListener)
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context)
                            .hasSingleBean(MetricsServiceInitListener.class);
                    assertThat(
                            context.getBean(MetricsServiceInitListener.class))
                            .isSameAs(customListener);
                });
    }
}
