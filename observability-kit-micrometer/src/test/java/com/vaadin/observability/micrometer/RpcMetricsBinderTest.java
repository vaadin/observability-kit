/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.dom.ElementFactory;
import com.vaadin.flow.server.communication.RpcInvocationEndedEvent;
import com.vaadin.flow.server.communication.RpcInvocationFailedEvent;
import com.vaadin.flow.server.communication.RpcInvocationStartedEvent;
import com.vaadin.observability.micrometer.trace.ObservationNames;

class RpcMetricsBinderTest {

    private static final class RecordingHandler
            implements ObservationHandler<Observation.Context> {

        final List<String> names = new ArrayList<>();
        final List<Map<String, String>> tags = new ArrayList<>();
        final List<Map<String, String>> highCardinalityTags = new ArrayList<>();
        final AtomicBoolean errored = new AtomicBoolean();
        Observation.Context lastContext;

        @Override
        public void onStop(Observation.Context ctx) {
            lastContext = ctx;
            names.add(ctx.getName());
            Map<String, String> snap = new HashMap<>();
            for (KeyValue kv : ctx.getLowCardinalityKeyValues()) {
                snap.put(kv.getKey(), kv.getValue());
            }
            tags.add(snap);
            Map<String, String> highSnap = new HashMap<>();
            for (KeyValue kv : ctx.getHighCardinalityKeyValues()) {
                highSnap.put(kv.getKey(), kv.getValue());
            }
            highCardinalityTags.add(highSnap);
            if (ctx.getError() != null) {
                errored.set(true);
            }
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }

    @Test
    void directPathSuccessRecordsTimerWithTypeAndOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RpcMetricsBinder binder = new RpcMetricsBinder(registry, null,
                ObservabilitySettings.builder().traces(false).build());

        RpcInvocationStartedEvent eventStarted = started("event");
        RpcInvocationEndedEvent eventEnded = ended("event");

        binder.invocationStarted(eventStarted);
        binder.invocationEnded(eventEnded);

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

        RpcInvocationStartedEvent eventStarted = started("event");
        RpcInvocationEndedEvent eventEnded = ended("event");

        binder.invocationStarted(eventStarted);
        RpcInvocationFailedEvent eventFailed = failed(
                new RuntimeException("boom"));
        binder.invocationFailed(eventFailed);
        binder.invocationEnded(eventEnded);

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

        RpcInvocationStartedEvent eventStarted = started(
                "publishedEventHandler");
        RpcInvocationEndedEvent eventEnded = ended("publishedEventHandler");

        binder.invocationStarted(eventStarted);

        Assertions.assertEquals(ObservationNames.INTERACTION_RPC,
                RequestInteraction.take(),
                "invocationStarted should mark the request interaction as rpc");

        // clean up
        binder.invocationEnded(eventEnded);
    }

    @Test
    void observationPathEmitsTimerWithTypeAndOutcomeTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(
                new DefaultMeterObservationHandler(registry));

        RpcMetricsBinder binder = new RpcMetricsBinder(registry,
                observationRegistry, ObservabilitySettings.builder().build());

        RpcInvocationStartedEvent eventStarted = started("event");
        RpcInvocationEndedEvent eventEnded = ended("event");

        binder.invocationStarted(eventStarted);
        binder.invocationEnded(eventEnded);

        Timer timer = registry.find(MeterNames.RPC_DURATION)
                .tag(MeterNames.TAG_TYPE, "event")
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .timer();
        Assertions.assertNotNull(timer,
                "DefaultMeterObservationHandler should emit vaadin.rpc.duration timer");
        Assertions.assertEquals(1L, timer.count());
    }

    @Test
    void observationPathErrorSetsOutcomeAndRecordsError() {
        SimpleMeterRegistry simpleRegistry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        observationRegistry.observationConfig()
                .observationHandler(
                        new DefaultMeterObservationHandler(simpleRegistry))
                .observationHandler(recorder);

        RpcMetricsBinder binder = new RpcMetricsBinder(simpleRegistry,
                observationRegistry,
                ObservabilitySettings.builder().traces(true).build());

        RpcInvocationStartedEvent eventStarted = started("event");
        RpcInvocationEndedEvent eventEnded = ended("event");

        binder.invocationStarted(eventStarted);
        RpcInvocationFailedEvent eventFailed = failed(
                new RuntimeException("boom"));
        binder.invocationFailed(eventFailed);
        binder.invocationEnded(eventEnded);

        Timer timer = simpleRegistry.find(MeterNames.RPC_DURATION)
                .tag(MeterNames.TAG_TYPE, "event")
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_ERROR).timer();
        Assertions.assertNotNull(timer,
                "vaadin.rpc.duration timer with outcome=error should exist");
        Assertions.assertEquals(1L, timer.count());
        Assertions.assertNotNull(recorder.lastContext,
                "recording handler should have been called");
        Assertions.assertNotNull(recorder.lastContext.getError(),
                "observation context should carry the recorded error");
        Assertions.assertTrue(recorder.errored.get(),
                "errored flag should be set by the recording handler");
    }

    @Test
    void leakedScopeIsClosedSoNextInvocationIsNotParentedOnStaleObservation() {
        SimpleMeterRegistry simpleRegistry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        observationRegistry.observationConfig().observationHandler(recorder);

        RpcMetricsBinder binder = new RpcMetricsBinder(simpleRegistry,
                observationRegistry,
                ObservabilitySettings.builder().traces(true).build());

        RpcInvocationStartedEvent eventStarted = started("event");
        RpcInvocationEndedEvent eventEnded = ended("event");

        Assertions.assertNull(observationRegistry.getCurrentObservation(),
                "precondition: no observation is current on this thread");

        // An invocation whose invocationEnded never ran (e.g. mid-request
        // shutdown) leaves its scope open on this thread.
        binder.invocationStarted(eventStarted);
        Assertions.assertNotNull(observationRegistry.getCurrentObservation(),
                "precondition: the first invocation opened a scope");

        // The pooled thread now serves the next invocation.
        binder.invocationStarted(eventStarted);
        binder.invocationEnded(eventEnded);

        Assertions.assertNotNull(recorder.lastContext);
        Assertions.assertNull(recorder.lastContext.getParentObservation(),
                "new RPC span must not be parented on the stale observation");
        Assertions.assertNull(observationRegistry.getCurrentObservation(),
                "no scope should remain current once the invocation ended");
    }

    @Test
    void noObservationWhenTracesDisabled() {
        SimpleMeterRegistry simpleRegistry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        observationRegistry.observationConfig().observationHandler(recorder);

        RpcMetricsBinder binder = new RpcMetricsBinder(simpleRegistry,
                observationRegistry,
                ObservabilitySettings.builder().traces(false).build());

        RpcInvocationStartedEvent eventStarted = started("event");
        RpcInvocationEndedEvent eventEnded = ended("event");

        binder.invocationStarted(eventStarted);
        binder.invocationEnded(eventEnded);

        Assertions.assertTrue(recorder.names.isEmpty(),
                "no observation should fire when traces are disabled");
        Timer timer = simpleRegistry.find(MeterNames.RPC_DURATION)
                .tag(MeterNames.TAG_TYPE, "event")
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .timer();
        Assertions.assertNotNull(timer,
                "direct-path timer should be recorded even when traces are disabled");
        Assertions.assertEquals(1L, timer.count());
    }

    @Test
    void errorStateDoesNotBleedIntoSubsequentInvocation() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RpcMetricsBinder binder = new RpcMetricsBinder(registry, null,
                ObservabilitySettings.builder().traces(false).build());

        RpcInvocationStartedEvent eventStarted = started("event");
        RpcInvocationEndedEvent eventEnded = ended("event");

        // First invocation: error
        binder.invocationStarted(eventStarted);
        RpcInvocationFailedEvent eventFailed = failed(
                new RuntimeException("boom"));
        binder.invocationFailed(eventFailed);
        binder.invocationEnded(eventEnded);

        // Second invocation on the same binder instance/thread: no error
        binder.invocationStarted(eventStarted);
        binder.invocationEnded(eventEnded);

        Timer successTimer = registry.find(MeterNames.RPC_DURATION)
                .tag(MeterNames.TAG_TYPE, "event")
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .timer();
        Assertions.assertNotNull(successTimer,
                "second invocation should record success outcome");
        Assertions.assertEquals(1L, successTimer.count(),
                "error state must not bleed into next invocation");
    }

    @Test
    void observationPathAddsEventNameAsHighCardinalitySpanAttribute() {
        SimpleMeterRegistry simpleRegistry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        observationRegistry.observationConfig()
                .observationHandler(
                        new DefaultMeterObservationHandler(simpleRegistry))
                .observationHandler(recorder);

        RpcMetricsBinder binder = new RpcMetricsBinder(simpleRegistry,
                observationRegistry,
                ObservabilitySettings.builder().traces(true).build());

        RpcInvocationStartedEvent eventStarted = started("event");
        RpcInvocationEndedEvent eventEnded = ended("event");
        Mockito.when(eventStarted.getName()).thenReturn("click");

        binder.invocationStarted(eventStarted);
        binder.invocationEnded(eventEnded);

        Assertions.assertEquals("click",
                recorder.highCardinalityTags.get(0)
                        .get(ObservationNames.KEY_EVENT_NAME),
                "span should carry the invocation name as a high-cardinality attribute");

        // The event name must not leak into the Timer tags.
        Assertions.assertFalse(
                recorder.tags.get(0)
                        .containsKey(ObservationNames.KEY_EVENT_NAME),
                "event name must not be a low-cardinality Timer tag");
        Timer timer = simpleRegistry.find(MeterNames.RPC_DURATION)
                .tag(MeterNames.TAG_TYPE, "event")
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .timer();
        Assertions.assertNotNull(timer,
                "Timer tags should remain type + outcome only");
        Assertions.assertEquals(1L, timer.count());
    }

    @Test
    void observationPathOmitsEventNameWhenNull() {
        SimpleMeterRegistry simpleRegistry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        observationRegistry.observationConfig()
                .observationHandler(
                        new DefaultMeterObservationHandler(simpleRegistry))
                .observationHandler(recorder);

        RpcMetricsBinder binder = new RpcMetricsBinder(simpleRegistry,
                observationRegistry,
                ObservabilitySettings.builder().traces(true).build());

        RpcInvocationStartedEvent eventStarted = started("event");
        RpcInvocationEndedEvent eventEnded = ended("event");
        Mockito.when(eventStarted.getName()).thenReturn(null);

        binder.invocationStarted(eventStarted);
        binder.invocationEnded(eventEnded);

        Assertions.assertFalse(
                recorder.highCardinalityTags.get(0)
                        .containsKey(ObservationNames.KEY_EVENT_NAME),
                "no event name attribute should be added when getName() is null");
    }

    @Test
    void observationPathAddsComponentClassWhenNodeResolves() {
        SimpleMeterRegistry simpleRegistry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        observationRegistry.observationConfig()
                .observationHandler(
                        new DefaultMeterObservationHandler(simpleRegistry))
                .observationHandler(recorder);

        RpcMetricsBinder binder = new RpcMetricsBinder(simpleRegistry,
                observationRegistry,
                ObservabilitySettings.builder().traces(true).build());

        // Build a real UI with an attached component so the binder can resolve
        // the component class from the target node id.
        UI ui = new UI();
        Element element = ElementFactory.createDiv();
        Component component = new Component(element) {
        };
        ui.getElement().appendChild(element);
        int nodeId = element.getNode().getId();

        RpcInvocationStartedEvent eventStarted = started("event");
        RpcInvocationEndedEvent eventEnded = ended("event");
        Mockito.when(eventStarted.getUI()).thenReturn(ui);
        Mockito.when(eventStarted.getNodeId()).thenReturn(nodeId);

        binder.invocationStarted(eventStarted);
        binder.invocationEnded(eventEnded);

        Assertions.assertEquals(component.getClass().getName(),
                recorder.highCardinalityTags.get(0)
                        .get(ObservationNames.KEY_COMPONENT),
                "span should carry the targeted component class as a high-cardinality attribute");
        Assertions.assertFalse(
                recorder.tags.get(0)
                        .containsKey(ObservationNames.KEY_COMPONENT),
                "component class must not be a low-cardinality Timer tag");
    }

    @Test
    void observationPathResolvesEnclosingComponentForSubElement() {
        SimpleMeterRegistry simpleRegistry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        observationRegistry.observationConfig()
                .observationHandler(
                        new DefaultMeterObservationHandler(simpleRegistry))
                .observationHandler(recorder);

        RpcMetricsBinder binder = new RpcMetricsBinder(simpleRegistry,
                observationRegistry,
                ObservabilitySettings.builder().traces(true).build());

        // Build a component whose root has a nested sub-element that is not
        // itself mapped to a component. An RPC targeting the sub-element must
        // resolve to the enclosing component by walking up the element tree.
        UI ui = new UI();
        Element root = ElementFactory.createDiv();
        Component component = new Component(root) {
        };
        Element subElement = ElementFactory.createSpan();
        root.appendChild(subElement);
        ui.getElement().appendChild(root);
        int nodeId = subElement.getNode().getId();

        RpcInvocationStartedEvent eventStarted = started("event");
        RpcInvocationEndedEvent eventEnded = ended("event");
        Mockito.when(eventStarted.getUI()).thenReturn(ui);
        Mockito.when(eventStarted.getNodeId()).thenReturn(nodeId);

        binder.invocationStarted(eventStarted);
        binder.invocationEnded(eventEnded);

        Assertions.assertEquals(component.getClass().getName(),
                recorder.highCardinalityTags.get(0)
                        .get(ObservationNames.KEY_COMPONENT),
                "span should resolve the enclosing component for a sub-element target");
    }

    @Test
    void observationPathOmitsComponentWhenNodeIdNegative() {
        SimpleMeterRegistry simpleRegistry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        observationRegistry.observationConfig()
                .observationHandler(
                        new DefaultMeterObservationHandler(simpleRegistry))
                .observationHandler(recorder);

        RpcMetricsBinder binder = new RpcMetricsBinder(simpleRegistry,
                observationRegistry,
                ObservabilitySettings.builder().traces(true).build());

        RpcInvocationStartedEvent eventStarted = started("event");
        RpcInvocationEndedEvent eventEnded = ended("event");
        Mockito.when(eventStarted.getNodeId()).thenReturn(-1);

        binder.invocationStarted(eventStarted);
        binder.invocationEnded(eventEnded);

        Assertions.assertFalse(
                recorder.highCardinalityTags.get(0)
                        .containsKey(ObservationNames.KEY_COMPONENT),
                "no component attribute should be added when the invocation targets no node");
    }

    /** A started event of the given RPC type, with no target node. */
    private static RpcInvocationStartedEvent started(String type) {
        RpcInvocationStartedEvent event = Mockito
                .mock(RpcInvocationStartedEvent.class);
        Mockito.when(event.getType()).thenReturn(type);
        return event;
    }

    /** An ended event of the given RPC type. */
    private static RpcInvocationEndedEvent ended(String type) {
        RpcInvocationEndedEvent event = Mockito
                .mock(RpcInvocationEndedEvent.class);
        Mockito.when(event.getType()).thenReturn(type);
        return event;
    }

    /** A failed event carrying the given throwable. */
    private static RpcInvocationFailedEvent failed(Throwable error) {
        RpcInvocationFailedEvent event = Mockito
                .mock(RpcInvocationFailedEvent.class);
        Mockito.when(event.getError()).thenReturn(error);
        return event;
    }
}
