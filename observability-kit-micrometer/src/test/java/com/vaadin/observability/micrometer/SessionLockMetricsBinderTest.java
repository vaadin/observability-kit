/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.internal.CurrentInstance;
import com.vaadin.flow.server.SessionLockAcquiredEvent;
import com.vaadin.flow.server.SessionLockReleasedEvent;
import com.vaadin.flow.server.SessionLockRequestedEvent;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class SessionLockMetricsBinderTest {

    @AfterEach
    void clearCurrentInstance() {
        CurrentInstance.clearAll();
    }

    @Test
    void acquireRecordsWaitTimerWithAccessContext() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SessionLockMetricsBinder binder = new SessionLockMetricsBinder(
                registry);

        binder.lockRequested(mock(SessionLockRequestedEvent.class));
        binder.lockAcquired(mock(SessionLockAcquiredEvent.class));

        assertEquals(1L,
                registry.get(MeterNames.SESSION_LOCK_WAIT)
                        .tag(MeterNames.TAG_CONTEXT, MeterNames.CONTEXT_ACCESS)
                        .timer().count());
    }

    @Test
    void releaseRecordsHoldTimerWithAccessContext() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SessionLockMetricsBinder binder = new SessionLockMetricsBinder(
                registry);

        binder.lockRequested(mock(SessionLockRequestedEvent.class));
        binder.lockAcquired(mock(SessionLockAcquiredEvent.class));
        binder.lockReleased(mock(SessionLockReleasedEvent.class));

        assertEquals(1L,
                registry.get(MeterNames.SESSION_LOCK_HOLD)
                        .tag(MeterNames.TAG_CONTEXT, MeterNames.CONTEXT_ACCESS)
                        .timer().count());
    }

    @Test
    void acquireAndReleaseRecordTimersWithRequestContext() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SessionLockMetricsBinder binder = new SessionLockMetricsBinder(
                registry);

        CurrentInstance.set(VaadinRequest.class, mock(VaadinRequest.class));
        try {
            binder.lockRequested(mock(SessionLockRequestedEvent.class));
            binder.lockAcquired(mock(SessionLockAcquiredEvent.class));
            binder.lockReleased(mock(SessionLockReleasedEvent.class));

            assertEquals(1L, registry.get(MeterNames.SESSION_LOCK_WAIT)
                    .tag(MeterNames.TAG_CONTEXT, MeterNames.CONTEXT_REQUEST)
                    .timer().count());
            assertEquals(1L, registry.get(MeterNames.SESSION_LOCK_HOLD)
                    .tag(MeterNames.TAG_CONTEXT, MeterNames.CONTEXT_REQUEST)
                    .timer().count());
        } finally {
            CurrentInstance.clearAll();
        }
    }

    @Test
    void releaseWithoutAcquireDoesNotRecordHold() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SessionLockMetricsBinder binder = new SessionLockMetricsBinder(
                registry);

        binder.lockReleased(mock(SessionLockReleasedEvent.class));

        assertNull(registry.find(MeterNames.SESSION_LOCK_HOLD).timer());
    }
}
