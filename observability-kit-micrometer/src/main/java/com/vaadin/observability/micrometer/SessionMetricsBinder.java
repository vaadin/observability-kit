/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import com.vaadin.flow.server.SessionDestroyEvent;
import com.vaadin.flow.server.SessionDestroyListener;
import com.vaadin.flow.server.SessionInitEvent;
import com.vaadin.flow.server.SessionInitListener;

/**
 * Tracks the number of active Vaadin sessions as a gauge. Register the same
 * instance as both a {@link SessionInitListener} and a
 * {@link SessionDestroyListener} on the {@code VaadinService}.
 */
class SessionMetricsBinder
        implements SessionInitListener, SessionDestroyListener {

    private final AtomicInteger activeSessions = new AtomicInteger();

    SessionMetricsBinder(MeterRegistry registry) {
        Gauge.builder(MeterNames.SESSIONS_ACTIVE, activeSessions,
                AtomicInteger::doubleValue)
                .description("Number of active Vaadin sessions")
                .register(registry);
    }

    @Override
    public void sessionInit(SessionInitEvent event) {
        activeSessions.incrementAndGet();
    }

    @Override
    public void sessionDestroy(SessionDestroyEvent event) {
        activeSessions.decrementAndGet();
    }
}
