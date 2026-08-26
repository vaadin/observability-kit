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

import io.micrometer.core.instrument.MeterRegistry;

import com.vaadin.flow.server.SessionLockAcquiredEvent;
import com.vaadin.flow.server.SessionLockReleasedEvent;
import com.vaadin.flow.server.SessionLockRequestedEvent;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServiceEventBus;
import com.vaadin.flow.shared.Registration;

/**
 * Records session-lock wait and hold times from the session lock events on the
 * {@link com.vaadin.flow.server.VaadinService#getEventBus() service event bus}.
 * <p>
 * Vaadin serializes all server-side work for a session behind one lock, so
 * {@code vaadin.session.lock.wait} (time blocked acquiring the lock) is the
 * session-contention signal, and {@code vaadin.session.lock.hold} (time the
 * lock was held) is how long each unit of work monopolized the session. The
 * {@code context} tag distinguishes request-thread acquisitions from
 * {@code UI.access}/background acquisitions.
 * <p>
 * The events are delivered on the same thread for one outermost hold, so wait
 * and hold start times are kept in thread locals.
 */
final class SessionLockMetricsBinder {

    private final MeterRegistry registry;
    private final ThreadLocal<Long> waitStart = new ThreadLocal<>();
    private final ThreadLocal<Long> holdStart = new ThreadLocal<>();

    SessionLockMetricsBinder(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Subscribes to the session lock events on the given bus.
     *
     * @param eventBus
     *            the service event bus to listen on
     * @return a handle removing every subscription made here
     */
    Registration register(VaadinServiceEventBus eventBus) {
        return Registration.combine(
                eventBus.addListener(SessionLockRequestedEvent.class,
                        this::lockRequested),
                eventBus.addListener(SessionLockAcquiredEvent.class,
                        this::lockAcquired),
                eventBus.addListener(SessionLockReleasedEvent.class,
                        this::lockReleased));
    }

    void lockRequested(SessionLockRequestedEvent event) {
        waitStart.set(System.nanoTime());
    }

    void lockAcquired(SessionLockAcquiredEvent event) {
        long now = System.nanoTime();
        Long started = waitStart.get();
        waitStart.remove();
        String context = context();
        if (started != null) {
            registry.timer(MeterNames.SESSION_LOCK_WAIT, MeterNames.TAG_CONTEXT,
                    context).record(Duration.ofNanos(now - started));
        }
        holdStart.set(now);
    }

    void lockReleased(SessionLockReleasedEvent event) {
        long now = System.nanoTime();
        Long started = holdStart.get();
        holdStart.remove();
        if (started != null) {
            registry.timer(MeterNames.SESSION_LOCK_HOLD, MeterNames.TAG_CONTEXT,
                    context()).record(Duration.ofNanos(now - started));
        }
    }

    /**
     * Best-effort classification: a lock taken while a Vaadin request is
     * current is attributed to request handling, otherwise to a
     * {@code UI.access}/background acquisition.
     */
    private static String context() {
        return VaadinService.getCurrentRequest() != null
                ? MeterNames.CONTEXT_REQUEST
                : MeterNames.CONTEXT_ACCESS;
    }
}
