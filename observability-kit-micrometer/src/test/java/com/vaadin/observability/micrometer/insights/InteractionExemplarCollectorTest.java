/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.dom.ElementFactory;
import com.vaadin.flow.server.communication.RpcInvocationEvent;
import com.vaadin.observability.micrometer.ObservabilitySettings;

class InteractionExemplarCollectorTest {

    /** Budget of 0 makes any successful invocation qualify as "slow". */
    private static final long CAPTURE_ALL = 0;
    /** Budget beyond any real elapsed time, so nothing qualifies as slow. */
    private static final long CAPTURE_NONE = Long.MAX_VALUE;

    private final ExemplarBuffer buffer = new ExemplarBuffer(100);

    private static ObservabilitySettings settings(boolean errors,
            boolean requests) {
        return ObservabilitySettings.builder().errors(errors).requests(requests)
                .build();
    }

    /** A UI with one attached component, plus a mocked event targeting it. */
    private record Target(UI ui, Component component,
            RpcInvocationEvent event) {
    }

    private static Target target() {
        UI ui = new UI();
        Element element = ElementFactory.createDiv();
        Component component = new Component(element) {
        };
        ui.getElement().appendChild(element);

        RpcInvocationEvent event = Mockito.mock(RpcInvocationEvent.class);
        Mockito.when(event.getType()).thenReturn("event");
        Mockito.when(event.getName()).thenReturn("click");
        Mockito.when(event.getUI()).thenReturn(ui);
        Mockito.when(event.getNodeId()).thenReturn(element.getNode().getId());
        return new Target(ui, component, event);
    }

    private static RuntimeException failure() {
        RuntimeException error = new RuntimeException("boom");
        error.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.example.OrderService", "ship",
                        "OrderService.java", 30),
                new StackTraceElement(
                        "com.vaadin.flow.component.ComponentEventBus",
                        "fireEvent", "ComponentEventBus.java", 100) });
        return error;
    }

    @Test
    void capturesFailedInteractionWithComponentAndApplicationFrame() {
        InteractionExemplarCollector collector = new InteractionExemplarCollector(
                buffer, settings(true, true));
        Target target = target();

        collector.invocationStarted(target.event());
        collector.invocationFailed(target.event(), failure());
        collector.invocationEnded(target.event());

        List<InteractionExemplar> snapshot = buffer.snapshot();
        Assertions.assertEquals(1, snapshot.size());
        InteractionExemplar exemplar = snapshot.get(0);
        Assertions.assertEquals(InteractionExemplar.OUTCOME_ERROR,
                exemplar.outcome());
        Assertions.assertEquals(target.component().getClass().getName(),
                exemplar.component(),
                "the interacted component should be resolved");
        Assertions.assertEquals("click", exemplar.event());
        Assertions.assertEquals("java.lang.RuntimeException",
                exemplar.exceptionType());
        Assertions.assertEquals(
                "com.example.OrderService.ship(OrderService.java:30)",
                exemplar.applicationFrame(),
                "the first non-framework frame is the likely bug location");
    }

    @Test
    void capturesSuccessfulInteractionOverBudget() {
        InteractionExemplarCollector collector = new InteractionExemplarCollector(
                buffer, settings(true, true), CAPTURE_ALL);
        Target target = target();

        collector.invocationStarted(target.event());
        collector.invocationEnded(target.event());

        List<InteractionExemplar> snapshot = buffer.snapshot();
        Assertions.assertEquals(1, snapshot.size());
        InteractionExemplar exemplar = snapshot.get(0);
        Assertions.assertEquals(InteractionExemplar.OUTCOME_SUCCESS,
                exemplar.outcome());
        Assertions.assertEquals(target.component().getClass().getName(),
                exemplar.component());
        Assertions.assertTrue(exemplar.durationMs() >= 0,
                "a successful exemplar should carry its handling duration");
        Assertions.assertNull(exemplar.exceptionType(),
                "successful interactions have no exception");
    }

    @Test
    void doesNotCaptureSuccessfulInteractionWithinBudget() {
        InteractionExemplarCollector collector = new InteractionExemplarCollector(
                buffer, settings(true, true), CAPTURE_NONE);
        Target target = target();

        collector.invocationStarted(target.event());
        collector.invocationEnded(target.event());

        Assertions.assertTrue(buffer.snapshot().isEmpty(),
                "interactions within the UX budget are not retained");
    }

    @Test
    void resolvesComponentAtStartNotAtEnd() {
        // Regression: the handler may detach the target node before the
        // invocation ends (e.g. a Grid component column refreshing its item).
        // The component must be resolved at invocationStarted.
        InteractionExemplarCollector collector = new InteractionExemplarCollector(
                buffer, settings(true, true), CAPTURE_ALL);
        Target target = target();

        collector.invocationStarted(target.event());
        // Simulate the node no longer being resolvable at end time.
        Mockito.when(target.event().getNodeId()).thenReturn(-1);
        collector.invocationEnded(target.event());

        InteractionExemplar exemplar = buffer.snapshot().get(0);
        Assertions.assertEquals(target.component().getClass().getName(),
                exemplar.component(),
                "component resolved at start must survive node detachment");
    }

    @Test
    void doesNotCaptureFailureWhenErrorsDisabled() {
        InteractionExemplarCollector collector = new InteractionExemplarCollector(
                buffer, settings(false, true), CAPTURE_NONE);
        Target target = target();

        collector.invocationStarted(target.event());
        collector.invocationFailed(target.event(), failure());
        collector.invocationEnded(target.event());

        Assertions.assertTrue(buffer.snapshot().isEmpty(),
                "no error exemplar should be retained when errors are disabled");
    }

    @Test
    void doesNotCaptureSlowInteractionWhenRequestsDisabled() {
        InteractionExemplarCollector collector = new InteractionExemplarCollector(
                buffer, settings(true, false), CAPTURE_ALL);
        Target target = target();

        collector.invocationStarted(target.event());
        collector.invocationEnded(target.event());

        Assertions.assertTrue(buffer.snapshot().isEmpty(),
                "no slow exemplar should be retained when requests are disabled");
    }

    @Test
    void slowExemplarNotEmittedForFailedInteraction() {
        // A failure is captured once as an error; invocationEnded must not also
        // emit a slow exemplar for the same invocation.
        InteractionExemplarCollector collector = new InteractionExemplarCollector(
                buffer, settings(true, true), CAPTURE_ALL);
        Target target = target();

        collector.invocationStarted(target.event());
        collector.invocationFailed(target.event(), failure());
        collector.invocationEnded(target.event());

        List<InteractionExemplar> snapshot = buffer.snapshot();
        Assertions.assertEquals(1, snapshot.size(),
                "a failed interaction yields exactly one (error) exemplar");
        Assertions.assertEquals(InteractionExemplar.OUTCOME_ERROR,
                snapshot.get(0).outcome());
    }

    @Test
    void errorStateDoesNotBleedIntoNextInvocation() {
        InteractionExemplarCollector collector = new InteractionExemplarCollector(
                buffer, settings(true, true), CAPTURE_ALL);
        Target target = target();

        collector.invocationStarted(target.event());
        collector.invocationFailed(target.event(), failure());
        collector.invocationEnded(target.event());

        // Second invocation succeeds; must be captured as slow, not error.
        collector.invocationStarted(target.event());
        collector.invocationEnded(target.event());

        List<InteractionExemplar> snapshot = buffer.snapshot();
        Assertions.assertEquals(2, snapshot.size());
        Assertions.assertEquals(InteractionExemplar.OUTCOME_SUCCESS,
                snapshot.get(0).outcome(),
                "second (newest) invocation must record success, not a stale error");
    }
}
