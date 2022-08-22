package com.vaadin.extension;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.config.ConfigPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * disable the automatic vaadin instrumentation Custom distributions can supply
 * their own default configuration by implementing {@link ConfigPropertySource}.
 *
 * <p>
 * The configuration priority, from highest to lowest is:
 *
 * <ul>
 * <li>system properties
 * <li>environment variables
 * <li>configuration file
 * <li>PropertySource SPI
 * <li>hard-coded defaults
 * </ul>
 */
@AutoService(ConfigPropertySource.class)
public class Configuration implements ConfigPropertySource {

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();
        // Disable the built-in vaadin instrumentation
        properties.put("otel.instrumentation.vaadin.enabled", "false");
        // Set the service name to vaadin by default.
        properties.put("otel.service.name", "vaadin");
        return properties;
    }
}
