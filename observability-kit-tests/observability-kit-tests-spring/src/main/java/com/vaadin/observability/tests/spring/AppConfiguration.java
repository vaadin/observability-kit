/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.tests.spring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.vaadin.observability.spring.ObservabilityConfiguration;

/**
 * Plain Spring (non-Boot) configuration that imports the Observability Kit
 * Spring wiring and provides a {@link MeterRegistry}. Component-scans the test
 * package so the views and the metrics servlet are picked up.
 */
@Configuration
@ComponentScan
@Import(ObservabilityConfiguration.class)
public class AppConfiguration {

    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
