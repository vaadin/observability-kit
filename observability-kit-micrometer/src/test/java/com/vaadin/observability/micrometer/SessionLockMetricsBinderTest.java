/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.lang.ref.Reference;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.internal.CurrentInstance;
import com.vaadin.flow.server.SessionLockAcquiredEvent;
import com.vaadin.flow.server.SessionLockReleasedEvent;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class SessionLockMetricsBinderTest {

    private final VaadinService service = mock(VaadinService.class);
    private final VaadinSession session = mock(VaadinSession.class);

    @AfterEach
    void clearCurrentInstance() {
        CurrentInstance.clearAll();
    }

    @Test
    void acquireRecordsWaitTimerWithAccessContext() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SessionLockMetricsBinder binder = new SessionLockMetricsBinder(
                registry);

        binder.lockAcquired(new SessionLockAcquiredEvent(service, session,
                Duration.ofMillis(42)));

        Timer wait = registry.get(MeterNames.SESSION_LOCK_WAIT)
                .tag(MeterNames.TAG_CONTEXT, MeterNames.CONTEXT_ACCESS).timer();
        assertEquals(1L, wait.count());
        assertEquals(42.0, wait.totalTime(TimeUnit.MILLISECONDS));
    }

    @Test
    void releaseRecordsHoldTimerWithAccessContext() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SessionLockMetricsBinder binder = new SessionLockMetricsBinder(
                registry);

        binder.lockReleased(new SessionLockReleasedEvent(service, session,
                Duration.ofMillis(7)));

        Timer hold = registry.get(MeterNames.SESSION_LOCK_HOLD)
                .tag(MeterNames.TAG_CONTEXT, MeterNames.CONTEXT_ACCESS).timer();
        assertEquals(1L, hold.count());
        assertEquals(7.0, hold.totalTime(TimeUnit.MILLISECONDS));
    }

    @Test
    void acquireAndReleaseRecordTimersWithRequestContext() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SessionLockMetricsBinder binder = new SessionLockMetricsBinder(
                registry);

        // Hold a strong reference for the duration of the test:
        // CurrentInstance stores values in WeakReferences, so an inline mock
        // could be collected between set() and the binder's
        // getCurrentRequest(), flipping the context tag to "access".
        VaadinRequest request = mock(VaadinRequest.class);
        CurrentInstance.set(VaadinRequest.class, request);
        try {
            binder.lockAcquired(new SessionLockAcquiredEvent(service, session,
                    Duration.ZERO));
            binder.lockReleased(new SessionLockReleasedEvent(service, session,
                    Duration.ZERO));

            assertEquals(1L, registry.get(MeterNames.SESSION_LOCK_WAIT)
                    .tag(MeterNames.TAG_CONTEXT, MeterNames.CONTEXT_REQUEST)
                    .timer().count());
            assertEquals(1L, registry.get(MeterNames.SESSION_LOCK_HOLD)
                    .tag(MeterNames.TAG_CONTEXT, MeterNames.CONTEXT_REQUEST)
                    .timer().count());
        } finally {
            // Keeps the mock strongly reachable through the assertions above;
            // without this the JIT may let GC clear the weak reference after
            // the last use of the local.
            Reference.reachabilityFence(request);
            CurrentInstance.clearAll();
        }
    }
}
