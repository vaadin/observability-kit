/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.io.IOException;
import java.util.Properties;

import com.vaadin.pro.licensechecker.BuildType;
import com.vaadin.pro.licensechecker.Capabilities;
import com.vaadin.pro.licensechecker.Capability;
import com.vaadin.pro.licensechecker.LicenseChecker;
import com.vaadin.pro.licensechecker.LicenseException;
import com.vaadin.pro.licensechecker.MissingLicenseKeyException;

/**
 * Validates the Observability Kit commercial license.
 * <p>
 * Unlike a license-checking {@code VaadinServiceInitListener}, this does not
 * fail application startup. It is consulted by
 * {@link MetricsServiceInitListener} as a gate: when the kit is not licensed,
 * the instrumentation (meter and observation registries, binders and trackers)
 * is simply not registered, and the application keeps running without
 * telemetry.
 */
final class ObservabilityLicense {

    static final String PROPERTIES_RESOURCE = "observability-kit.properties";

    static final String VERSION_PROPERTY = "observability-kit.version";

    static final String PRODUCT_NAME = "vaadin-observability-kit";

    static final String PRODUCT_VERSION;

    static {
        final var properties = loadAllProperties(PROPERTIES_RESOURCE);
        PRODUCT_VERSION = properties.getProperty(VERSION_PROPERTY);
    }

    private ObservabilityLicense() {
    }

    /**
     * Checks whether Observability Kit is licensed for use.
     * <p>
     * Production builds are validated at build time, so this returns
     * {@code true} without a runtime check. Development builds are validated
     * against the local license key; a missing, invalid or outdated key makes
     * this return {@code false} instead of throwing, so the caller can skip
     * registering instrumentation rather than failing the whole application.
     *
     * @param productionMode
     *            whether the deployment runs in production mode
     * @return {@code true} if the kit may register its instrumentation
     */
    static boolean isLicensed(boolean productionMode) {
        if (productionMode) {
            return true;
        }
        try {
            // A null BuildType allows trial licensing builds. The no-key
            // handler throws instead of opening a browser so the check stays
            // non-blocking, and the zero timeout avoids waiting for a download.
            BuildType buildType = null;
            LicenseChecker.checkLicense(PRODUCT_NAME, PRODUCT_VERSION,
                    buildType, url -> {
                        throw new MissingLicenseKeyException(
                                "No license key present");
                    }, 0, Capabilities.of(Capability.PRE_TRIAL));
            return true;
        } catch (LicenseException e) {
            return false;
        }
    }

    static Properties loadAllProperties(String propertiesResource) {
        final var cl = ObservabilityLicense.class.getClassLoader();
        try (final var stream = cl.getResourceAsStream(propertiesResource)) {
            final var properties = new Properties();
            properties.load(stream);
            return properties;
        } catch (NullPointerException | IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
