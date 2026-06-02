/*
 * Copyright 2000-2026 Vaadin Ltd.
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full license.
 */
package com.vaadin.observability.micrometer;

/**
 * Names of the meters published by Observability Kit. These form the public
 * telemetry contract scraped by metrics backends, so treat changes as breaking.
 */
public final class MeterNames {

    /** Gauge: number of currently active Vaadin sessions. */
    public static final String SESSIONS_ACTIVE = "vaadin.sessions.active";

    private MeterNames() {
    }
}
