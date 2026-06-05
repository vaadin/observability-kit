/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinSession;

class RequestMetricsBinderTest {

    @Test
    void successfulRequestRecordsDurationWithSuccessOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RequestMetricsBinder binder = new RequestMetricsBinder(registry,
                ObservabilitySettings.builder().traces(false).build());
        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        VaadinResponse res = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        binder.requestStart(req, res);
        binder.requestEnd(req, res, session);

        Timer timer = registry.find(MeterNames.REQUEST_DURATION)
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .timer();
        Assertions.assertNotNull(timer);
        Assertions.assertEquals(1L, timer.count());
    }

    @Test
    void exceptionRecordsErrorOutcomeAndExceptionCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RequestMetricsBinder binder = new RequestMetricsBinder(registry,
                ObservabilitySettings.builder().traces(false).build());
        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        VaadinResponse res = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        binder.requestStart(req, res);
        binder.handleException(req, res, session,
                new IllegalStateException("boom"));
        binder.requestEnd(req, res, session);

        Timer timer = registry.find(MeterNames.REQUEST_DURATION)
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_ERROR).timer();
        Assertions.assertNotNull(timer);
        Assertions.assertEquals(1L, timer.count());

        Assertions.assertEquals(1.0,
                registry.find(MeterNames.ERRORS)
                        .tag(MeterNames.TAG_EXCEPTION, "IllegalStateException")
                        .counter().count(),
                0.0);
    }
}
