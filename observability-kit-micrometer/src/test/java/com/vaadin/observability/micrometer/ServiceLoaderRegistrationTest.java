/*
 * Copyright 2000-2026 Vaadin Ltd.
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full license.
 */
package com.vaadin.observability.micrometer;

import java.util.ServiceLoader;

import com.vaadin.flow.server.VaadinServiceInitListener;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceLoaderRegistrationTest {

    @Test
    void metricsListenerIsRegisteredViaServiceLoader() {
        boolean found = ServiceLoader.load(VaadinServiceInitListener.class)
                .stream().map(ServiceLoader.Provider::get).anyMatch(
                        listener -> listener instanceof MetricsServiceInitListener);

        assertTrue(found,
                "MetricsServiceInitListener should be discoverable via the Java SPI");
    }
}
