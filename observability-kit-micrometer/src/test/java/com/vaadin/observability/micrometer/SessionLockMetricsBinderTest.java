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
import com.vaadin.flow.server.SessionLockEvent;
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
        SessionLockEvent event = new SessionLockEvent(
                mock(VaadinService.class));

        binder.lockRequested(event);
        binder.lockAcquired(event);

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
        SessionLockEvent event = new SessionLockEvent(
                mock(VaadinService.class));

        binder.lockRequested(event);
        binder.lockAcquired(event);
        binder.lockReleased(event);

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
        SessionLockEvent event = new SessionLockEvent(
                mock(VaadinService.class));

        CurrentInstance.set(VaadinRequest.class, mock(VaadinRequest.class));
        try {
            binder.lockRequested(event);
            binder.lockAcquired(event);
            binder.lockReleased(event);

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
        SessionLockEvent event = new SessionLockEvent(
                mock(VaadinService.class));

        binder.lockReleased(event);

        assertNull(registry.find(MeterNames.SESSION_LOCK_HOLD).timer());
    }
}
