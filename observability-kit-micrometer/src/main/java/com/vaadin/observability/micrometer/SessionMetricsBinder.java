package com.vaadin.observability.micrometer;

import java.util.concurrent.atomic.AtomicInteger;

import com.vaadin.flow.server.SessionDestroyEvent;
import com.vaadin.flow.server.SessionDestroyListener;
import com.vaadin.flow.server.SessionInitEvent;
import com.vaadin.flow.server.SessionInitListener;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Tracks the number of active Vaadin sessions as a gauge. Register the same
 * instance as both a {@link SessionInitListener} and a
 * {@link SessionDestroyListener} on the {@code VaadinService}.
 */
class SessionMetricsBinder implements SessionInitListener, SessionDestroyListener {

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
