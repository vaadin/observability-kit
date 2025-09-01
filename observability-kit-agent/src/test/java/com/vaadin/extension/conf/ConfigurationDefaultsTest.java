package com.vaadin.extension.conf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vaadin.extension.Constants;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SpanProcessor;
import org.junit.jupiter.api.Test;

class ConfigurationDefaultsTest {

    @Test
    public void spanToMetricProcessor_whenEnabled_addsSpanToMetricProcessor() {
        ConfigurationDefaults configDefaults = new ConfigurationDefaults();
        ConfigProperties configProperties = mock(ConfigProperties.class);
        SpanProcessor originalProcessor = mock(SpanProcessor.class);
        
        // Mock configuration to return true for span-to-metrics enabled
        when(configProperties.getBoolean(Constants.CONFIG_SPAN_TO_METRICS_ENABLED, false))
                .thenReturn(true);
        
        SpanProcessor result = configDefaults.spanToMetricProcessor(originalProcessor, configProperties);
        
        // Should return a composite processor (different from the original)
        assertNotSame(originalProcessor, result, "Should return composite processor when enabled");
    }

    @Test
    public void spanToMetricProcessor_whenDisabled_returnsOriginalProcessor() {
        ConfigurationDefaults configDefaults = new ConfigurationDefaults();
        ConfigProperties configProperties = mock(ConfigProperties.class);
        SpanProcessor originalProcessor = mock(SpanProcessor.class);
        
        // Mock configuration to return false for span-to-metrics enabled (default)
        when(configProperties.getBoolean(Constants.CONFIG_SPAN_TO_METRICS_ENABLED, false))
                .thenReturn(false);
        
        SpanProcessor result = configDefaults.spanToMetricProcessor(originalProcessor, configProperties);
        
        // Should return the same original processor
        assertSame(originalProcessor, result, "Should return original processor when disabled");
    }

    @Test
    public void spanToMetricProcessor_whenNotConfigured_defaultsToDisabled() {
        ConfigurationDefaults configDefaults = new ConfigurationDefaults();
        ConfigProperties configProperties = mock(ConfigProperties.class);
        SpanProcessor originalProcessor = mock(SpanProcessor.class);
        
        // Mock configuration to use default value (false) when not explicitly set
        when(configProperties.getBoolean(Constants.CONFIG_SPAN_TO_METRICS_ENABLED, false))
                .thenReturn(false); // This simulates the default case
        
        SpanProcessor result = configDefaults.spanToMetricProcessor(originalProcessor, configProperties);
        
        // Should return the same original processor (disabled by default)
        assertSame(originalProcessor, result, "Should default to disabled when not configured");
    }
}