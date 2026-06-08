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
import org.junit.jupiter.api.Assertions;
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
            Assertions.assertTrue(settings.isSessions());
            Assertions.assertTrue(settings.isUis());
            Assertions.assertTrue(settings.isNavigation());
            Assertions.assertTrue(settings.isRequests());
            Assertions.assertTrue(settings.isErrors());
            Assertions.assertTrue(settings.isClient());
            Assertions.assertEquals(200, settings.getRouteCardinalityLimit());
            Assertions.assertEquals(100, settings.getClientRatePerSession());

            Assertions.assertNotNull(
                    ctx.getBean(MetricsServiceInitListener.class));
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
            Assertions.assertFalse(settings.isSessions());
            Assertions.assertFalse(settings.isNavigation());
            Assertions.assertTrue(settings.isUis());
            Assertions.assertEquals(42, settings.getRouteCardinalityLimit());
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
            Assertions.assertFalse(settings.isClient());
            Assertions.assertEquals(25, settings.getClientRatePerSession());
        }
    }
}
