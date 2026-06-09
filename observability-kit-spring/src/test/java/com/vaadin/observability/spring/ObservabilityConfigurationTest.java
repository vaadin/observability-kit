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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockPropertySource;

import com.vaadin.observability.micrometer.MetricsServiceInitListener;
import com.vaadin.observability.micrometer.ObservabilitySettings;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityConfigurationTest {

    @Configuration
    @Import(ObservabilityConfiguration.class)
    static class TestConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        static PropertySourcesPlaceholderConfigurer propertyConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }

    @Test
    void defaultsExposeBindersAndConfig() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(
                TestConfig.class)) {
            ObservabilitySettings settings = ctx
                    .getBean(ObservabilitySettings.class);
            assertThat(settings.isSessions()).isTrue();
            assertThat(settings.isUis()).isTrue();
            assertThat(settings.isNavigation()).isTrue();
            assertThat(settings.isRequests()).isTrue();
            assertThat(settings.isErrors()).isTrue();
            assertThat(settings.isClient()).isTrue();
            assertThat(settings.isTraces()).isTrue();
            assertThat(settings.isTracesSessionId()).isFalse();
            assertThat(settings.getRouteCardinalityLimit()).isEqualTo(200);
            assertThat(settings.getClientRatePerSession()).isEqualTo(100);

            assertThat(ctx.getBean(MetricsServiceInitListener.class))
                    .isNotNull();
        }
    }

    @Test
    void propertyOverridesAreHonored() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            MockPropertySource props = new MockPropertySource()
                    .withProperty("vaadin.observability.sessions", "false")
                    .withProperty("vaadin.observability.navigation", "false")
                    .withProperty(
                            "vaadin.observability.route-cardinality-limit",
                            "42");
            ((ConfigurableEnvironment) ctx.getEnvironment())
                    .getPropertySources().addFirst(props);
            ctx.register(TestConfig.class);
            ctx.refresh();

            ObservabilitySettings settings = ctx
                    .getBean(ObservabilitySettings.class);
            assertThat(settings.isSessions()).isFalse();
            assertThat(settings.isNavigation()).isFalse();
            assertThat(settings.isUis()).isTrue();
            assertThat(settings.getRouteCardinalityLimit()).isEqualTo(42);
        }
    }

    @Test
    void clientPropertyOverridesAreHonored() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            MockPropertySource props = new MockPropertySource()
                    .withProperty("vaadin.observability.client", "false")
                    .withProperty(
                            "vaadin.observability.client-rate-per-session",
                            "25");
            ((ConfigurableEnvironment) ctx.getEnvironment())
                    .getPropertySources().addFirst(props);
            ctx.register(TestConfig.class);
            ctx.refresh();

            ObservabilitySettings settings = ctx
                    .getBean(ObservabilitySettings.class);
            assertThat(settings.isClient()).isFalse();
            assertThat(settings.getClientRatePerSession()).isEqualTo(25);
        }
    }
}
