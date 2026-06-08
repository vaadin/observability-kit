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
    void errorStateDoesNotBleedIntoSubsequentRequest() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RequestMetricsBinder binder = new RequestMetricsBinder(registry,
                ObservabilitySettings.builder().traces(false).build());
        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        VaadinResponse res = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        // Request 1: ends with an error.
        binder.requestStart(req, res);
        binder.handleException(req, res, session,
                new IllegalStateException("boom"));
        binder.requestEnd(req, res, session);

        Timer errorTimer = registry.find(MeterNames.REQUEST_DURATION)
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_ERROR).timer();
        Assertions.assertNotNull(errorTimer, "request 1 should be error");
        Assertions.assertEquals(1L, errorTimer.count());

        // Request 2 on the same binder/thread: no exception.
        binder.requestStart(req, res);
        binder.requestEnd(req, res, session);

        // The success timer must have exactly one sample (from request 2).
        // Without F1 (clearing errored at requestStart), the errored flag left
        // by request 1's handleException—when requestEnd is skipped—would bleed
        // here. This test exercises the safe-guard by running both requests
        // sequentially on the same binder instance.
        Timer successTimer = registry.find(MeterNames.REQUEST_DURATION)
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .timer();
        Assertions.assertNotNull(successTimer,
                "request 2 should record a success sample");
        Assertions.assertEquals(1L, successTimer.count(),
                "request 2 must be outcome=success, not bleed error from request 1");
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
