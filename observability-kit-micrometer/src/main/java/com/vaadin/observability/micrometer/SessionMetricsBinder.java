/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import com.vaadin.flow.server.SessionDestroyEvent;
import com.vaadin.flow.server.SessionDestroyListener;
import com.vaadin.flow.server.SessionInitEvent;
import com.vaadin.flow.server.SessionInitListener;
import com.vaadin.flow.server.VaadinSession;

/**
 * Tracks session lifecycle metrics: cumulative count, currently-active count,
 * and per-session lifetime.
 */
final class SessionMetricsBinder
        implements SessionInitListener, SessionDestroyListener {

    private final Counter created;
    private final AtomicLong active = new AtomicLong();
    private final Timer duration;
    private final Map<VaadinSession, Long> startNanos = new ConcurrentHashMap<>();

    SessionMetricsBinder(MeterRegistry registry) {
        this.created = Counter.builder(MeterNames.SESSIONS_CREATED)
                .register(registry);
        Gauge.builder(MeterNames.SESSIONS_ACTIVE, active, AtomicLong::get)
                .register(registry);
        this.duration = Timer.builder(MeterNames.SESSIONS_DURATION)
                .register(registry);
    }

    @Override
    public void sessionInit(SessionInitEvent event) {
        created.increment();
        active.incrementAndGet();
        startNanos.put(event.getSession(), System.nanoTime());
    }

    @Override
    public void sessionDestroy(SessionDestroyEvent event) {
        Long start = startNanos.remove(event.getSession());
        active.decrementAndGet();
        if (start != null) {
            duration.record(Duration.ofNanos(System.nanoTime() - start));
        }
    }
}
