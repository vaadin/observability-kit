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
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.mockito.Mockito;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.dom.ElementFactory;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.server.communication.RpcInvocationEndedEvent;
import com.vaadin.flow.server.communication.RpcInvocationFailedEvent;
import com.vaadin.flow.server.communication.RpcInvocationStartedEvent;
import com.vaadin.observability.micrometer.ObservabilitySettings;

class InteractionCollectorTest {

    /** Budget of 0 makes any successful invocation qualify as "slow". */
    private static final long CAPTURE_ALL = 0;
    /** Budget beyond any real elapsed time, so nothing qualifies as slow. */
    private static final long CAPTURE_NONE = Long.MAX_VALUE;

    private final RecentInteractions buffer = new RecentInteractions(100);

    private static ObservabilitySettings settings(boolean errors,
            boolean requests) {
        return ObservabilitySettings.builder().errors(errors).requests(requests)
                .build();
    }

    private static ObservabilitySettings withDetails(boolean details) {
        return ObservabilitySettings.builder().errors(true).requests(true)
                .insightsDetails(details).build();
    }

    /** A UI with one attached component, plus a mocked event targeting it. */
    private record Target(UI ui, Component component,
            RpcInvocationStartedEvent started, RpcInvocationEndedEvent ended) {
    }

    private static Target target() {
        UI ui = new UI();
        Element element = ElementFactory.createDiv();
        Component component = new Component(element) {
        };
        ui.getElement().appendChild(element);
        int nodeId = element.getNode().getId();

        RpcInvocationStartedEvent started = Mockito
                .mock(RpcInvocationStartedEvent.class);
        Mockito.when(started.getType()).thenReturn("event");
        Mockito.when(started.getName()).thenReturn("click");
        Mockito.when(started.getUI()).thenReturn(ui);
        Mockito.when(started.getNodeId()).thenReturn(nodeId);

        RpcInvocationEndedEvent ended = Mockito
                .mock(RpcInvocationEndedEvent.class);
        Mockito.when(ended.getType()).thenReturn("event");
        Mockito.when(ended.getName()).thenReturn("click");
        Mockito.when(ended.getUI()).thenReturn(ui);
        Mockito.when(ended.getNodeId()).thenReturn(nodeId);

        return new Target(ui, component, started, ended);
    }

    /** A failure event for the given target, carrying the given throwable. */
    private static RpcInvocationFailedEvent failedEvent(Target target,
            Throwable error) {
        RpcInvocationFailedEvent event = Mockito
                .mock(RpcInvocationFailedEvent.class);
        Mockito.when(event.getType()).thenReturn("event");
        Mockito.when(event.getName()).thenReturn("click");
        Mockito.when(event.getUI()).thenReturn(target.ui());
        Mockito.when(event.getError()).thenReturn(error);
        return event;
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
        InteractionCollector collector = new InteractionCollector(buffer,
                settings(true, true));
        Target target = target();

        collector.invocationStarted(target.started());
        collector.invocationFailed(failedEvent(target, failure()));
        collector.invocationEnded(target.ended());

        List<CapturedInteraction> snapshot = buffer.snapshot();
        Assertions.assertEquals(1, snapshot.size());
        CapturedInteraction interaction = snapshot.get(0);
        Assertions.assertEquals(CapturedInteraction.OUTCOME_ERROR,
                interaction.outcome());
        Assertions.assertEquals(target.component().getClass().getName(),
                interaction.component(),
                "the interacted component should be resolved");
        Assertions.assertEquals("click", interaction.event());
        Assertions.assertEquals("java.lang.RuntimeException",
                interaction.exceptionType());
        Assertions.assertEquals(
                "com.example.OrderService.ship(OrderService.java:30)",
                interaction.applicationFrame(),
                "the first non-framework frame is the likely bug location");
    }

    @Test
    void capturesSuccessfulInteractionOverBudget() {
        InteractionCollector collector = new InteractionCollector(buffer,
                settings(true, true), CAPTURE_ALL);
        Target target = target();

        collector.invocationStarted(target.started());
        collector.invocationEnded(target.ended());

        List<CapturedInteraction> snapshot = buffer.snapshot();
        Assertions.assertEquals(1, snapshot.size());
        CapturedInteraction interaction = snapshot.get(0);
        Assertions.assertEquals(CapturedInteraction.OUTCOME_SUCCESS,
                interaction.outcome());
        Assertions.assertEquals(target.component().getClass().getName(),
                interaction.component());
        Assertions.assertTrue(interaction.durationMs() >= 0,
                "a successful interaction should carry its handling duration");
        Assertions.assertNull(interaction.exceptionType(),
                "successful interactions have no exception");
    }

    @Test
    void doesNotCaptureSuccessfulInteractionWithinBudget() {
        InteractionCollector collector = new InteractionCollector(buffer,
                settings(true, true), CAPTURE_NONE);
        Target target = target();

        collector.invocationStarted(target.started());
        collector.invocationEnded(target.ended());

        Assertions.assertTrue(buffer.snapshot().isEmpty(),
                "interactions within the UX budget are not retained");
    }

    @Test
    void resolvesComponentAtStartNotAtEnd() {
        // Regression: the handler may detach the target node before the
        // invocation ends (e.g. a Grid component column refreshing its item).
        // The component must be resolved at invocationStarted.
        InteractionCollector collector = new InteractionCollector(buffer,
                settings(true, true), CAPTURE_ALL);
        Target target = target();

        collector.invocationStarted(target.started());
        // Simulate the node no longer being resolvable at end time.
        Mockito.when(target.ended().getNodeId()).thenReturn(-1);
        collector.invocationEnded(target.ended());

        CapturedInteraction interaction = buffer.snapshot().get(0);
        Assertions.assertEquals(target.component().getClass().getName(),
                interaction.component(),
                "component resolved at start must survive node detachment");
    }

    @Test
    void doesNotCaptureFailureWhenErrorsDisabled() {
        InteractionCollector collector = new InteractionCollector(buffer,
                settings(false, true), CAPTURE_NONE);
        Target target = target();

        collector.invocationStarted(target.started());
        collector.invocationFailed(failedEvent(target, failure()));
        collector.invocationEnded(target.ended());

        Assertions.assertTrue(buffer.snapshot().isEmpty(),
                "no error interaction should be retained when errors are disabled");
    }

    @Test
    void doesNotCaptureSlowInteractionWhenRequestsDisabled() {
        InteractionCollector collector = new InteractionCollector(buffer,
                settings(true, false), CAPTURE_ALL);
        Target target = target();

        collector.invocationStarted(target.started());
        collector.invocationEnded(target.ended());

        Assertions.assertTrue(buffer.snapshot().isEmpty(),
                "no slow interaction should be retained when requests are disabled");
    }

    @Test
    void slowInteractionNotEmittedForFailedInteraction() {
        // A failure is captured once as an error; invocationEnded must not also
        // emit a slow interaction for the same invocation.
        InteractionCollector collector = new InteractionCollector(buffer,
                settings(true, true), CAPTURE_ALL);
        Target target = target();

        collector.invocationStarted(target.started());
        collector.invocationFailed(failedEvent(target, failure()));
        collector.invocationEnded(target.ended());

        List<CapturedInteraction> snapshot = buffer.snapshot();
        Assertions.assertEquals(1, snapshot.size(),
                "a failed interaction yields exactly one (error) interaction");
        Assertions.assertEquals(CapturedInteraction.OUTCOME_ERROR,
                snapshot.get(0).outcome());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    void terminatesOnACyclicCausalChain() {
        // A cause cycle (A caused by B caused by A) is legal and would spin
        // forever on a self-reference-only guard — while the server is already
        // handling a failure.
        RuntimeException outer = new RuntimeException("outer");
        RuntimeException inner = new RuntimeException("inner", outer);
        outer.initCause(inner);

        InteractionCollector collector = new InteractionCollector(buffer,
                settings(true, true));
        Target target = target();

        collector.invocationStarted(target.started());
        collector.invocationFailed(failedEvent(target, outer));
        collector.invocationEnded(target.ended());

        List<CapturedInteraction> snapshot = buffer.snapshot();
        Assertions.assertEquals(1, snapshot.size(),
                "a cyclic cause chain should still yield one interaction");
        Assertions.assertNotNull(snapshot.get(0).exceptionType(),
                "the walk should stop at a cause rather than hang");
    }

    /** A named view class, so the route key is stable across navigations. */
    private static class OrdersView extends Component {
        OrdersView() {
            super(ElementFactory.createDiv());
        }
    }

    @Test
    void groupsByRouteKeySoParameterValuesDoNotSplitInsights() {
        // The route key must identify the view, not the resolved path:
        // otherwise orders/17 and orders/18 look like two different problems.
        // (Turning the view into an actual "orders/:orderId" template needs a
        // session-scoped RouteConfiguration and is covered by
        // RouteTagResolverTest; with no session the resolver falls back to the
        // view class, which is still one key per view, not per parameter.)
        OrdersView view = new OrdersView();
        InteractionCollector collector = new InteractionCollector(buffer,
                settings(true, true));

        for (int id : new int[] { 17, 18 }) {
            UI ui = Mockito.mock(UI.class, Mockito.RETURNS_DEEP_STUBS);
            Mockito.when(ui.getInternals().getActiveRouterTargetsChain())
                    .thenReturn(List.of(view));
            Mockito.when(ui.getInternals().getActiveViewLocation())
                    .thenReturn(new Location("orders/" + id));

            RpcInvocationStartedEvent started = Mockito
                    .mock(RpcInvocationStartedEvent.class);
            Mockito.when(started.getType()).thenReturn("event");
            Mockito.when(started.getName()).thenReturn("click");
            Mockito.when(started.getUI()).thenReturn(ui);
            // No target node: this test is about the route, not the component.
            Mockito.when(started.getNodeId()).thenReturn(-1);

            RpcInvocationFailedEvent failed = Mockito
                    .mock(RpcInvocationFailedEvent.class);
            Mockito.when(failed.getType()).thenReturn("event");
            Mockito.when(failed.getName()).thenReturn("click");
            Mockito.when(failed.getUI()).thenReturn(ui);
            Mockito.when(failed.getNodeId()).thenReturn(-1);
            Mockito.when(failed.getError()).thenReturn(failure());

            RpcInvocationEndedEvent ended = Mockito
                    .mock(RpcInvocationEndedEvent.class);
            Mockito.when(ended.getType()).thenReturn("event");

            collector.invocationStarted(started);
            collector.invocationFailed(failed);
            collector.invocationEnded(ended);
        }

        List<String> routes = buffer.snapshot().stream()
                .map(CapturedInteraction::route).distinct().toList();
        Assertions.assertEquals(1, routes.size(),
                "both interactions should share one route key, got: " + routes);
        List<String> locations = buffer.snapshot().stream()
                .map(CapturedInteraction::location).distinct().sorted()
                .toList();
        Assertions.assertEquals(List.of("orders/17", "orders/18"), locations,
                "the concrete locations should still be reported per example");
    }

    @Test
    void slowInteractionCarriesTheBudgetItWasMeasuredAgainst() {
        // A collector configured with a non-default budget must record that
        // budget, so a report never has to assume the static default.
        // CAPTURE_ALL (0) is used so the instantaneous invocation qualifies
        // while still differing from the static UX_BUDGET_MS default, which is
        // the value this test must prove is not assumed.
        long budget = CAPTURE_ALL;
        InteractionCollector collector = new InteractionCollector(buffer,
                settings(true, true), budget);
        Target target = target();

        collector.invocationStarted(target.started());
        collector.invocationEnded(target.ended());

        CapturedInteraction interaction = buffer.snapshot().get(0);
        Assertions.assertEquals(budget, interaction.thresholdMs(),
                "the configured budget should travel with the interaction");
        Assertions.assertNotEquals(InteractionCollector.UX_BUDGET_MS,
                interaction.thresholdMs(),
                "the static default must not be assumed");
    }

    @Test
    void errorStateDoesNotBleedIntoNextInvocation() {
        InteractionCollector collector = new InteractionCollector(buffer,
                settings(true, true), CAPTURE_ALL);
        Target target = target();

        collector.invocationStarted(target.started());
        collector.invocationFailed(failedEvent(target, failure()));
        collector.invocationEnded(target.ended());

        // Second invocation succeeds; must be captured as slow, not error.
        collector.invocationStarted(target.started());
        collector.invocationEnded(target.ended());

        List<CapturedInteraction> snapshot = buffer.snapshot();
        Assertions.assertEquals(2, snapshot.size());
        Assertions.assertEquals(CapturedInteraction.OUTCOME_SUCCESS,
                snapshot.get(0).outcome(),
                "second (newest) invocation must record success, not a stale error");
    }

    @Test
    void withholdsSensitiveDetailByDefault() {
        // The payload is meant to travel, so the message and the stack frames
        // are not collected unless asked for, and the session id is reduced to
        // a hash. What stays is what makes the insight actionable.
        InteractionCollector collector = new InteractionCollector(buffer,
                withDetails(false));
        Target target = target();
        String realSessionId = target.ui().getSession() == null ? null
                : target.ui().getSession().getSession().getId();

        collector.invocationStarted(target.started());
        collector.invocationFailed(failedEvent(target, failure()));
        collector.invocationEnded(target.ended());

        CapturedInteraction interaction = buffer.snapshot().get(0);
        Assertions.assertFalse(interaction.detailsIncluded(),
                "the interaction should record that detail was withheld");
        Assertions.assertNull(interaction.exceptionMessage(),
                "the exception message should not be collected by default");
        Assertions.assertNull(interaction.stackTop(),
                "stack frames should not be collected by default");
        // Still actionable without the sensitive parts.
        Assertions.assertEquals("java.lang.RuntimeException",
                interaction.exceptionType());
        Assertions.assertEquals(
                "com.example.OrderService.ship(OrderService.java:30)",
                interaction.applicationFrame());
        if (realSessionId != null) {
            Assertions.assertNotEquals(realSessionId, interaction.sessionId(),
                    "the raw session id must not be exposed by default");
        }
    }

    @Test
    void collectsSensitiveDetailWhenOptedIn() {
        InteractionCollector collector = new InteractionCollector(buffer,
                withDetails(true));
        Target target = target();

        collector.invocationStarted(target.started());
        collector.invocationFailed(failedEvent(target, failure()));
        collector.invocationEnded(target.ended());

        CapturedInteraction interaction = buffer.snapshot().get(0);
        Assertions.assertTrue(interaction.detailsIncluded());
        Assertions.assertEquals("boom", interaction.exceptionMessage(),
                "the message should be collected when opted in");
        Assertions.assertNotNull(interaction.stackTop(),
                "stack frames should be collected when opted in");
        Assertions.assertFalse(interaction.stackTop().isEmpty());
    }

    @Test
    void truncatesAVeryLongExceptionMessage() {
        // A message is free-form text and can carry a whole payload, so it is
        // capped even when detail is enabled.
        RuntimeException error = new RuntimeException("x".repeat(5000));
        error.setStackTrace(failure().getStackTrace());
        InteractionCollector collector = new InteractionCollector(buffer,
                withDetails(true));
        Target target = target();

        collector.invocationStarted(target.started());
        collector.invocationFailed(failedEvent(target, error));
        collector.invocationEnded(target.ended());

        String message = buffer.snapshot().get(0).exceptionMessage();
        Assertions.assertTrue(message.length() < 5000,
                "a long message should be truncated, got " + message.length()
                        + " characters");
        Assertions.assertTrue(message.endsWith("…"),
                "truncation should be visible in the value");
    }

    @Test
    void hashedSessionIdIsStableSoExamplesStillCorrelate() {
        // The hash replaces the identifier but must still group the examples of
        // one insight together.
        InteractionCollector collector = new InteractionCollector(buffer,
                withDetails(false));
        Target target = target();

        for (int i = 0; i < 2; i++) {
            collector.invocationStarted(target.started());
            collector.invocationFailed(failedEvent(target, failure()));
            collector.invocationEnded(target.ended());
        }

        List<String> ids = buffer.snapshot().stream()
                .map(CapturedInteraction::sessionId).distinct().toList();
        Assertions.assertEquals(1, ids.size(),
                "the same session should hash to the same value, got: " + ids);
    }
}
