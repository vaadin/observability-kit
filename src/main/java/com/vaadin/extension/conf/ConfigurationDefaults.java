package com.vaadin.extension.conf;

import static java.util.Collections.emptyMap;

import com.vaadin.extension.Constants;

import com.google.auto.service.AutoService;
import io.opentelemetry.instrumentation.api.internal.ConfigPropertiesUtil;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

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
@AutoService(AutoConfigurationCustomizerProvider.class)
public class ConfigurationDefaults
        implements AutoConfigurationCustomizerProvider {

    private static final Logger logger = Logger.getLogger(ConfigurationDefaults.class.getName());

    static final String CONFIGURATION_FILE_PROPERTY = "otel.javaagent.configuration-file";

    @Override
    public int order() {
        return 1000;
    }

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addPropertiesSupplier(this::getDefaultProperties);
    }

    private Map<String, String> getDefaultProperties() {
        Map<String, String> properties = new HashMap<>();
        final Map<String, String> defaultconfig = getPropertyFileProperties();
        // Disable the built-in vaadin instrumentation
        addProperty(properties, "otel.instrumentation.vaadin.enabled", "false",
                defaultconfig);
        // Set the service name to vaadin by default.
        addProperty(properties, "otel.service.name", "vaadin", defaultconfig);

        addProperty(properties, "otel.instrumentation.java-http-client.enabled",
                "false", defaultconfig);
        addProperty(properties, "otel.instrumentation.jetty.enabled", "false",
                defaultconfig);
        addProperty(properties, "otel.instrumentation.jetty-httpclient.enabled",
                "false", defaultconfig);
        addProperty(properties, "otel.instrumentation.servlet.enabled", "false",
                defaultconfig);
        addProperty(properties, "otel.instrumentation.tomcat.enabled", "false",
                defaultconfig);
        // Configure default trace level
        addProperty(properties, Constants.CONFIG_TRACE_LEVEL,
                TraceLevel.DEFAULT.name(), defaultconfig);
        return properties;
    }

    private void addProperty(Map<String, String> map, String key,
            String defaultValue, Map<String, String> defaultConfig) {
        map.put(key, defaultConfig.getOrDefault(key, defaultValue));
    }


    public Map<String, String> getPropertyFileProperties() {

        // Reading from system property first and from env after
        String configurationFilePath = ConfigPropertiesUtil
                .getString(CONFIGURATION_FILE_PROPERTY);
        if (configurationFilePath == null) {
            return emptyMap();
        }

        // Normalizing tilde (~) paths for unix systems
        configurationFilePath = configurationFilePath.replaceFirst("^~",
                System.getProperty("user.home"));

        // Configuration properties file is optional
        File configurationFile = new File(configurationFilePath);
        if (!configurationFile.exists()) {
            logger.log(Level.SEVERE, "Configuration file \"{0}\" not found.",
                    configurationFilePath);
            return emptyMap();
        }

        Properties properties = new Properties();
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(configurationFile),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (FileNotFoundException fnf) {
            logger.log(Level.SEVERE, "Configuration file \"{0}\" not found.",
                    configurationFilePath);
        } catch (IOException ioe) {
            logger.log(Level.SEVERE,
                    "Configuration file \"{0}\" cannot be accessed or correctly parsed.",
                    configurationFilePath);
        }

        return properties.entrySet().stream().collect(Collectors.toMap(
                e -> e.getKey().toString(), e -> e.getValue().toString()));
    }
}
