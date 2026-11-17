/*-
 * Copyright (C) 2024 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.hilla.observability;

import java.io.IOException;
import java.util.Properties;

import com.vaadin.flow.server.startup.BaseLicenseCheckerServiceInitListener;

public class LicenseCheckerServiceInitListener
        extends BaseLicenseCheckerServiceInitListener {

    static final String PROPERTIES_RESOURCE = "observability-kit.properties";

    static final String VERSION_PROPERTY = "observability-kit.version";

    static final String PRODUCT_NAME = "hilla-observability-kit";

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
