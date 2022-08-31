package com.vaadin.extension.conf;

import com.vaadin.extension.Constants;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.config.ConfigPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Provides default values for global and extension-specific OpenTelemetry
 * configuration. The defaults can be overwritten by a configuration mechanism
 * with a higher priority.
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
public class ConfigurationDefaults implements ConfigPropertySource {

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();
        // Disable the built-in vaadin instrumentation
        properties.put("otel.instrumentation.vaadin.enabled", "false");
        // Set the service name to vaadin by default.
        properties.put("otel.service.name", "vaadin");

        properties.put("otel.instrumentation.java-http-client.enabled",
                "false");
        properties.put("otel.instrumentation.jetty.enabled", "false");
        properties.put("otel.instrumentation.jetty-httpclient.enabled",
                "false");
        properties.put("otel.instrumentation.servlet.enabled", "false");
        // Configure default trace level
        properties.put(Constants.CONFIG_TRACE_LEVEL, TraceLevel.DEFAULT.name());
        return properties;
    }
}
