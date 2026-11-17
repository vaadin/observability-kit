package com.vaadin.observability;

import java.io.IOException;
import java.util.Properties;

import com.vaadin.flow.internal.UsageStatistics;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.pro.licensechecker.BuildType;
import com.vaadin.pro.licensechecker.LicenseChecker;

public class LicenseCheckerServiceInitListener
        implements VaadinServiceInitListener {

    static final String PROPERTIES_RESOURCE = "observability-kit.properties";

    static final String VERSION_PROPERTY = "observability-kit.version";

    static final String PRODUCT_NAME = "vaadin-observability-kit";

    @Override
    public void serviceInit(ServiceInitEvent serviceInitEvent) {
        final var service = serviceInitEvent.getSource();
        final var properties = loadAllProperties(PROPERTIES_RESOURCE);
        final var version = properties.getProperty(VERSION_PROPERTY);

        UsageStatistics.markAsUsed(PRODUCT_NAME, version);

        // Check the license at runtime if in development mode
        if (!service.getDeploymentConfiguration().isProductionMode()) {
            // Using a null BuildType to allow trial licensing builds
            // The variable is defined to avoid method signature ambiguity
            BuildType buildType = null;
            LicenseChecker.checkLicense(PRODUCT_NAME, version, buildType);
        }
    }


    static Properties loadAllProperties(String propertiesResource) {
        final var cl = LicenseCheckerServiceInitListener.class.getClassLoader();
        try (final var stream = cl.getResourceAsStream(propertiesResource)) {
            final var properties = new Properties();
            properties.load(stream);
            return properties;
        } catch (NullPointerException | IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
