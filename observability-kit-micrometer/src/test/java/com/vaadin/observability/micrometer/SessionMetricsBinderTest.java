/*
 * Copyright 2000-2026 Vaadin Ltd.
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full license.
 */
package com.vaadin.observability.micrometer;

import com.vaadin.flow.server.SessionDestroyEvent;
import com.vaadin.flow.server.SessionInitEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class SessionMetricsBinderTest {

    @Test
    void gaugeTracksActiveSessionCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SessionMetricsBinder binder = new SessionMetricsBinder(registry);

        assertEquals(0.0,
                registry.get(MeterNames.SESSIONS_ACTIVE).gauge().value());

        binder.sessionInit(mock(SessionInitEvent.class));
        binder.sessionInit(mock(SessionInitEvent.class));
        assertEquals(2.0,
                registry.get(MeterNames.SESSIONS_ACTIVE).gauge().value());

        binder.sessionDestroy(mock(SessionDestroyEvent.class));
        assertEquals(1.0,
                registry.get(MeterNames.SESSIONS_ACTIVE).gauge().value());
    }
}
