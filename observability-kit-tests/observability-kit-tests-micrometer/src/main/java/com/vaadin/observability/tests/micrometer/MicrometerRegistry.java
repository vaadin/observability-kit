/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.tests.micrometer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Static holder so the Vaadin micrometer binders and the {@link MetricsServlet}
 * share the same {@link SimpleMeterRegistry} for the duration of the
 * deployment.
 */
public final class MicrometerRegistry {

    static final SimpleMeterRegistry INSTANCE = new SimpleMeterRegistry();

    private MicrometerRegistry() {
    }
}
