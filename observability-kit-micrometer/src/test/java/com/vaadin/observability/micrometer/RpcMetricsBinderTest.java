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
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.vaadin.flow.server.communication.RpcInvocationEvent;
import com.vaadin.observability.micrometer.trace.ObservationNames;

class RpcMetricsBinderTest {

    @Test
    void directPathSuccessRecordsTimerWithTypeAndOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RpcMetricsBinder binder = new RpcMetricsBinder(registry, null,
                ObservabilitySettings.builder().traces(false).build());

        RpcInvocationEvent event = Mockito.mock(RpcInvocationEvent.class);
        Mockito.when(event.getType()).thenReturn("event");

        binder.invocationStarted(event);
        binder.invocationEnded(event);

        Timer timer = registry.find(MeterNames.RPC_DURATION)
                .tag(MeterNames.TAG_TYPE, "event")
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .timer();
        Assertions.assertNotNull(timer,
                "vaadin.rpc.duration timer with type=event, outcome=success should exist");
        Assertions.assertEquals(1L, timer.count());
    }

    @Test
    void directPathErrorRecordsTimerWithErrorOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RpcMetricsBinder binder = new RpcMetricsBinder(registry, null,
                ObservabilitySettings.builder().traces(false).build());

        RpcInvocationEvent event = Mockito.mock(RpcInvocationEvent.class);
        Mockito.when(event.getType()).thenReturn("event");

        binder.invocationStarted(event);
        binder.invocationFailed(event, new RuntimeException("boom"));
        binder.invocationEnded(event);

        Timer timer = registry.find(MeterNames.RPC_DURATION)
                .tag(MeterNames.TAG_TYPE, "event")
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_ERROR).timer();
        Assertions.assertNotNull(timer,
                "vaadin.rpc.duration timer with outcome=error should exist");
        Assertions.assertEquals(1L, timer.count());
    }

    @Test
    void invocationStartedMarksRequestInteractionAsRpc() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RpcMetricsBinder binder = new RpcMetricsBinder(registry, null,
                ObservabilitySettings.builder().traces(false).build());

        RpcInvocationEvent event = Mockito.mock(RpcInvocationEvent.class);
        Mockito.when(event.getType()).thenReturn("publishedEventHandler");

        binder.invocationStarted(event);

        Assertions.assertEquals(ObservationNames.INTERACTION_RPC,
                RequestInteraction.take(),
                "invocationStarted should mark the request interaction as rpc");

        // clean up
        binder.invocationEnded(event);
    }

    @Test
    void observationPathEmitsTimerWithTypeAndOutcomeTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(
                new DefaultMeterObservationHandler(registry));

        RpcMetricsBinder binder = new RpcMetricsBinder(registry,
                observationRegistry, ObservabilitySettings.builder().build());

        RpcInvocationEvent event = Mockito.mock(RpcInvocationEvent.class);
        Mockito.when(event.getType()).thenReturn("event");

        binder.invocationStarted(event);
        binder.invocationEnded(event);

        Timer timer = registry.find(MeterNames.RPC_DURATION)
                .tag(MeterNames.TAG_TYPE, "event")
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .timer();
        Assertions.assertNotNull(timer,
                "DefaultMeterObservationHandler should emit vaadin.rpc.duration timer");
        Assertions.assertEquals(1L, timer.count());
    }

    @Test
    void errorStateDoesNotBleedIntoSubsequentInvocation() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RpcMetricsBinder binder = new RpcMetricsBinder(registry, null,
                ObservabilitySettings.builder().traces(false).build());

        RpcInvocationEvent event = Mockito.mock(RpcInvocationEvent.class);
        Mockito.when(event.getType()).thenReturn("event");

        // First invocation: error
        binder.invocationStarted(event);
        binder.invocationFailed(event, new RuntimeException("boom"));
        binder.invocationEnded(event);

        // Second invocation on the same binder instance/thread: no error
        binder.invocationStarted(event);
        binder.invocationEnded(event);

        Timer successTimer = registry.find(MeterNames.RPC_DURATION)
                .tag(MeterNames.TAG_TYPE, "event")
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .timer();
        Assertions.assertNotNull(successTimer,
                "second invocation should record success outcome");
        Assertions.assertEquals(1L, successTimer.count(),
                "error state must not bleed into next invocation");
    }
}
