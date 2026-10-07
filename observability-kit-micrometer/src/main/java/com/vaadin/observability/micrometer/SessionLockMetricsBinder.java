/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import io.micrometer.core.instrument.MeterRegistry;

import com.vaadin.flow.server.SessionLockAcquiredEvent;
import com.vaadin.flow.server.SessionLockReleasedEvent;
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
 * Flow measures both times itself and reports them on the acquired and released
 * events, for the outermost acquisition of a reentrant hold only.
 */
final class SessionLockMetricsBinder {

    private final MeterRegistry registry;

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
                eventBus.addListener(SessionLockAcquiredEvent.class,
                        this::lockAcquired),
                eventBus.addListener(SessionLockReleasedEvent.class,
                        this::lockReleased));
    }

    void lockAcquired(SessionLockAcquiredEvent event) {
        registry.timer(MeterNames.SESSION_LOCK_WAIT, MeterNames.TAG_CONTEXT,
                context()).record(event.getWaitTime());
    }

    void lockReleased(SessionLockReleasedEvent event) {
        registry.timer(MeterNames.SESSION_LOCK_HOLD, MeterNames.TAG_CONTEXT,
                context()).record(event.getHoldTime());
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
