/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.tests;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

import com.vaadin.observability.micrometer.ObservabilityKit;
import com.vaadin.observability.micrometer.ObservabilitySettings;

/**
 * Boots observability-kit-micrometer at servlet-context startup so the
 * SPI-loaded {@code MetricsServiceInitListener} can pick up the registry when
 * Vaadin's service initializes.
 */
@WebListener
public class MicrometerSetup implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        ObservabilityKit.install(MicrometerRegistry.INSTANCE,
                ObservabilitySettings.builder().build());
    }
}
