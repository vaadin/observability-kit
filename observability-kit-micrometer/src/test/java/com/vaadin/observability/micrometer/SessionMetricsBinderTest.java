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
import org.junit.jupiter.api.Test;

import com.vaadin.flow.server.SessionDestroyEvent;
import com.vaadin.flow.server.SessionInitEvent;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class SessionMetricsBinderTest {

    @Test
    void gaugeTracksActiveSessionCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SessionMetricsBinder binder = new SessionMetricsBinder(registry);
        VaadinService service = mock(VaadinService.class);
        VaadinSession session1 = mock(VaadinSession.class);
        VaadinSession session2 = mock(VaadinSession.class);

        assertEquals(0.0,
                registry.get(MeterNames.SESSIONS_ACTIVE).gauge().value());

        binder.sessionInit(new SessionInitEvent(service, session1, null));
        binder.sessionInit(new SessionInitEvent(service, session2, null));
        assertEquals(2.0,
                registry.get(MeterNames.SESSIONS_ACTIVE).gauge().value());

        binder.sessionDestroy(new SessionDestroyEvent(service, session1));
        assertEquals(1.0,
                registry.get(MeterNames.SESSIONS_ACTIVE).gauge().value());
    }

    @Test
    void initIncrementsCreatedCounterAndActiveGauge() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SessionMetricsBinder binder = new SessionMetricsBinder(registry);
        VaadinService service = mock(VaadinService.class);
        VaadinSession session = mock(VaadinSession.class);
        SessionInitEvent event = new SessionInitEvent(service, session, null);

        binder.sessionInit(event);

        assertEquals(1.0,
                registry.get(MeterNames.SESSIONS_CREATED).counter().count(),
                0.0);
        assertEquals(1.0,
                registry.get(MeterNames.SESSIONS_ACTIVE).gauge().value(), 0.0);
    }

    @Test
    void destroyDecrementsActiveAndRecordsDuration() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SessionMetricsBinder binder = new SessionMetricsBinder(registry);
        VaadinService service = mock(VaadinService.class);
        VaadinSession session = mock(VaadinSession.class);

        binder.sessionInit(new SessionInitEvent(service, session, null));
        binder.sessionDestroy(new SessionDestroyEvent(service, session));

        assertEquals(0.0,
                registry.get(MeterNames.SESSIONS_ACTIVE).gauge().value(), 0.0);
        assertEquals(1L,
                registry.get(MeterNames.SESSIONS_DURATION).timer().count());
    }

    @Test
    void destroyWithoutInitOnlyDecrementsAndDoesNotRecordDuration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SessionMetricsBinder binder = new SessionMetricsBinder(registry);
        VaadinService service = mock(VaadinService.class);
        VaadinSession session = mock(VaadinSession.class);

        binder.sessionDestroy(new SessionDestroyEvent(service, session));

        assertEquals(-1.0,
                registry.get(MeterNames.SESSIONS_ACTIVE).gauge().value(), 0.0);
        assertEquals(0L,
                registry.get(MeterNames.SESSIONS_DURATION).timer().count());
    }
}
