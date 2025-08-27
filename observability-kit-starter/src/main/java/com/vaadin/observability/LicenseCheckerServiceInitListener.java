package com.vaadin.observability;

import java.io.IOException;
import java.util.Properties;

import com.vaadin.flow.internal.UsageStatistics;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.startup.BaseLicenseCheckerServiceInitListener;
import com.vaadin.pro.licensechecker.BuildType;
import com.vaadin.pro.licensechecker.LicenseChecker;

public class LicenseCheckerServiceInitListener
        extends BaseLicenseCheckerServiceInitListener {

    static final String PROPERTIES_RESOURCE = "observability-kit.properties";

    static final String VERSION_PROPERTY = "observability-kit.version";

    static final String PRODUCT_NAME = "vaadin-observability-kit";

    static final String PRODUCT_VERSION;

    static {
        final var properties = loadAllProperties(PROPERTIES_RESOURCE);
        PRODUCT_VERSION = properties.getProperty(VERSION_PROPERTY);
    }

    public LicenseCheckerServiceInitListener() {
        super(PRODUCT_NAME, PRODUCT_VERSION);
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
