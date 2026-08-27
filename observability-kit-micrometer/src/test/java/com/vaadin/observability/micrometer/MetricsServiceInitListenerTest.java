/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.time.Instant;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.SessionDestroyListener;
import com.vaadin.flow.server.SessionInitListener;
import com.vaadin.flow.server.SessionLockRequestedEvent;
import com.vaadin.flow.server.UIInitListener;
import com.vaadin.flow.server.VaadinRequestInterceptor;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServiceEventBus;
import com.vaadin.flow.server.communication.AbstractRpcInvocationEvent;
import com.vaadin.flow.server.communication.RpcInvocationEndedEvent;
import com.vaadin.flow.server.communication.RpcInvocationFailedEvent;
import com.vaadin.flow.server.communication.RpcInvocationListener;
import com.vaadin.flow.server.communication.RpcInvocationStartedEvent;
import com.vaadin.observability.micrometer.insights.CapturedInteraction;
import com.vaadin.observability.micrometer.insights.RecentInteractions;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MetricsServiceInitListenerTest {

    @AfterEach
    void tearDown() {
        ObservabilityKit.reset();
    }

    /**
     * A production-mode service, so the license gate passes without a runtime
     * license check and these tests can focus on binder registration.
     */
    private static VaadinService licensedService() {
        VaadinService service = mock(VaadinService.class, RETURNS_DEEP_STUBS);
        when(service.getDeploymentConfiguration().isProductionMode())
                .thenReturn(true);
        // A real bus, so the binders that subscribe to events rather than
        // to an addXListener method can be asserted on
        when(service.getEventBus())
                .thenReturn(new VaadinServiceEventBus(service));
        return service;
    }

    @Test
    void registersSessionBinderWhenSessionsEnabled() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().build());
        VaadinService service = licensedService();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        Assertions.assertTrue(
                registeredSessionInitListeners(service).stream()
                        .anyMatch(l -> l instanceof SessionMetricsBinder),
                "sessions enabled should register the SessionMetricsBinder");
        verify(service)
                .addSessionDestroyListener(any(SessionDestroyListener.class));
        Assertions.assertEquals(1,
                service.getEventBus()
                        .getListeners(SessionLockRequestedEvent.class).size(),
                "sessions enabled should subscribe the session lock binder");
    }

    @Test
    void registersUiStateBinderWhenUiStateEnabled() {
        // Everything else off, so the three subscriptions verified below can
        // only come from the UI-state binder.
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().sessions(false).uis(false)
                        .navigation(false).client(false).requests(false)
                        .errors(false).traces(false).uiState(true).build());
        VaadinService service = licensedService();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        verify(service).addUIInitListener(any(UIInitListener.class));
        verify(service)
                .addSessionDestroyListener(any(SessionDestroyListener.class));
        Assertions.assertEquals(1,
                service.getEventBus()
                        .getListeners(RpcInvocationEndedEvent.class).size(),
                "ui state enabled should subscribe to RPC invocation ends");
    }

    @Test
    void skipsUiStateBinderByDefault() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().sessions(false).uis(false)
                        .navigation(false).client(false).requests(false)
                        .errors(false).traces(false).build());
        VaadinService service = licensedService();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        verify(service, never()).addUIInitListener(any());
        verify(service, never()).addSessionDestroyListener(any());
        Assertions.assertTrue(
                service.getEventBus()
                        .getListeners(RpcInvocationEndedEvent.class).isEmpty(),
                "ui state disabled should subscribe nothing");
    }

    @Test
    void doesNothingWhenNotInstalled() {
        VaadinService service = mock(VaadinService.class);
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        verifyNoInteractions(service);
    }

    @Test
    void skipsSessionBinderWhenSessionsDisabled() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().sessions(false).build());
        VaadinService service = licensedService();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        // The error binder also hooks session init, so assert on the type
        // rather than on the hook being unused.
        Assertions.assertTrue(
                registeredSessionInitListeners(service).stream()
                        .noneMatch(l -> l instanceof SessionMetricsBinder),
                "sessions disabled should not register the SessionMetricsBinder");
        verify(service, never()).addSessionDestroyListener(any());
        Assertions.assertTrue(service.getEventBus()
                .getListeners(SessionLockRequestedEvent.class).isEmpty(),
                "sessions disabled should not subscribe the lock binder");
    }

    @Test
    void registersErrorBinderWhenErrorsEnabled() {
        // The session error handler is where Flow routes the failures a user
        // triggers, so the binder needs both hooks: session init to instrument
        // a new session, RPC start to re-instrument one whose error handler
        // the application replaced.
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().build());
        VaadinService service = licensedService();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        Assertions.assertTrue(
                registeredSessionInitListeners(service).stream()
                        .anyMatch(l -> l instanceof ErrorMetricsBinder),
                "errors enabled should hook session init");
        Assertions.assertTrue(
                registeredUiInitListeners(service).stream()
                        .anyMatch(l -> l instanceof ErrorMetricsBinder),
                "errors enabled should hook UI init, which still runs while "
                        + "the bootstrap request is being handled");
        ArgumentCaptor<RpcInvocationListener> rpc = ArgumentCaptor
                .forClass(RpcInvocationListener.class);
        verify(service, atLeastOnce()).addRpcInvocationListener(rpc.capture());
        Assertions.assertTrue(
                rpc.getAllValues().stream()
                        .anyMatch(l -> l instanceof ErrorMetricsBinder),
                "errors enabled should hook RPC invocation start");
    }

    @Test
    void skipsErrorBinderWhenErrorsDisabled() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().errors(false).build());
        VaadinService service = licensedService();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        Assertions.assertTrue(
                registeredSessionInitListeners(service).stream()
                        .noneMatch(l -> l instanceof ErrorMetricsBinder),
                "errors disabled should not instrument the error handler");
    }

    @Test
    void registersUiInitListenerWhenUisEnabled() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().build());
        VaadinService service = licensedService();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        Assertions.assertTrue(
                registeredUiInitListeners(service).stream()
                        .anyMatch(l -> l instanceof UiMetricsBinder),
                "UIs enabled should register the UiMetricsBinder");
    }

    @Test
    void registersUiInitListenerWhenNavigationEnabledAndUisDisabled() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().uis(false).navigation(true)
                        .build());
        VaadinService service = licensedService();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        Assertions.assertTrue(
                registeredUiInitListeners(service).stream()
                        .anyMatch(l -> l instanceof UiMetricsBinder),
                "navigation enabled should register the UiMetricsBinder");
    }

    @Test
    void skipsUiInitListenerWhenUisAndNavigationAndClientDisabled() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().uis(false).navigation(false)
                        .client(false).build());
        VaadinService service = licensedService();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        // The error binder also hooks UI init, so assert on the type rather
        // than on the hook being unused.
        Assertions.assertTrue(
                registeredUiInitListeners(service).stream()
                        .noneMatch(l -> l instanceof UiMetricsBinder),
                "UI metrics disabled should not register the UiMetricsBinder");
    }

    @Test
    void registersUiInitListenerWhenOnlyClientEnabled() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().uis(false).navigation(false)
                        .client(true).build());
        VaadinService service = licensedService();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        Assertions.assertTrue(
                registeredUiInitListeners(service).stream()
                        .anyMatch(l -> l instanceof UiMetricsBinder),
                "client metrics enabled should register the UiMetricsBinder");
    }

    @Test
    void registersRequestInterceptorWhenRequestsEnabled() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().build());
        VaadinService service = licensedService();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        verify(event).addVaadinRequestInterceptor(
                any(VaadinRequestInterceptor.class));
    }

    @Test
    void registersRequestInterceptorWhenOnlyErrorsEnabled() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().requests(false).errors(true)
                        .build());
        VaadinService service = licensedService();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        verify(event).addVaadinRequestInterceptor(
                any(VaadinRequestInterceptor.class));
    }

    @Test
    void skipsRequestInterceptorWhenRequestsAndErrorsDisabled() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().requests(false).errors(false)
                        .build());
        VaadinService service = licensedService();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        verify(event, never()).addVaadinRequestInterceptor(any());
    }

    @Test
    void registersRpcMetricsBinderAndInteractionCollectorWhenRequestsEnabled() {
        // Defaults enable both requests and errors: the RpcMetricsBinder
        // (timing/tracing) and the InteractionCollector (insights)
        // are both registered as RPC invocation listeners.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservabilityKit.install(registry,
                ObservabilitySettings.builder().build());

        initAndFireFailedInvocation();

        Assertions.assertNotNull(registry.find(MeterNames.RPC_DURATION).timer(),
                "the metrics binder should have timed the invocation");
        Assertions.assertFalse(
                ObservabilityKit.getRecentInteractions().snapshot().isEmpty(),
                "the collector should have captured the failed interaction");
    }

    @Test
    void registersOnlyInteractionCollectorWhenOnlyErrorsEnabled() {
        // Errors on, requests off: the collector is still needed to capture
        // failed interactions, but the RpcMetricsBinder is not.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservabilityKit.install(registry, ObservabilitySettings.builder()
                .requests(false).errors(true).build());

        initAndFireFailedInvocation();

        Assertions.assertNull(registry.find(MeterNames.RPC_DURATION).timer(),
                "no RPC timer when request metrics are off");
        Assertions.assertFalse(
                ObservabilityKit.getRecentInteractions().snapshot().isEmpty(),
                "the collector still captures the failed interaction");
    }

    @Test
    void skipsRpcEventSubscribersWhenRequestsAndErrorsDisabled() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().requests(false).errors(false)
                        .build());
        Assertions.assertTrue(initAndFireFailedInvocation().getEventBus()
                .getListeners(RpcInvocationStartedEvent.class).isEmpty(),
                "no RPC subscribers when requests and errors are both off");
    }

    @Test
    void insightsCanBeDisabledWithoutGivingUpErrorOrRequestMetrics() {
        // Insights is a feature in its own right: switching it off must not
        // force a choice between error metrics and request metrics.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservabilityKit.install(registry,
                ObservabilitySettings.builder().insights(false).build());

        initAndFireFailedInvocation();

        Assertions.assertNotNull(registry.find(MeterNames.RPC_DURATION).timer(),
                "request metrics should survive insights being off");
        Assertions.assertNull(ObservabilityKit.getRecentInteractions(),
                "no buffer should be bound when insights are off");
    }

    @Test
    void insightsBufferHonoursTheConfiguredCapacity() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().insightsCapacity(2).build());
        initAndFireFailedInvocation();

        RecentInteractions buffer = ObservabilityKit.getRecentInteractions();
        Assertions.assertNotNull(buffer);
        buffer.add(interaction("a"));
        buffer.add(interaction("b"));
        buffer.add(interaction("c"));
        Assertions.assertEquals(2, buffer.snapshot().size(),
                "the buffer should be bounded by the configured capacity");
    }

    private static CapturedInteraction interaction(String component) {
        return new CapturedInteraction(Instant.now(), "orders", "orders/17",
                component, "click", "event",
                CapturedInteraction.OUTCOME_SUCCESS, 1500, 1000, false, null,
                null, null, null, "session", 0);
    }

    private static List<UIInitListener> registeredUiInitListeners(
            VaadinService service) {
        ArgumentCaptor<UIInitListener> captor = ArgumentCaptor
                .forClass(UIInitListener.class);
        verify(service, atLeastOnce()).addUIInitListener(captor.capture());
        return captor.getAllValues();
    }

    private static List<SessionInitListener> registeredSessionInitListeners(
            VaadinService service) {
        ArgumentCaptor<SessionInitListener> captor = ArgumentCaptor
                .forClass(SessionInitListener.class);
        verify(service, atLeastOnce()).addSessionInitListener(captor.capture());
        return captor.getAllValues();
    }

    /**
     * Runs {@code serviceInit} against a real event bus and then fires one
     * failed RPC invocation through it.
     * <p>
     * Asserting on the effect rather than on the number of subscribers is what
     * keeps the wirings apart: counting cannot distinguish "only the metrics
     * binder" from "only the interaction collector", and would pass if the two
     * were swapped or if one subscribed twice.
     *
     * @return the service, so a test can reach the bus it was wired against
     */
    private static VaadinService initAndFireFailedInvocation() {
        VaadinService service = licensedService();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        UI ui = mock(UI.class, RETURNS_DEEP_STUBS);
        VaadinServiceEventBus bus = service.getEventBus();
        bus.fireEvent(rpcEvent(RpcInvocationStartedEvent.class, ui));
        RpcInvocationFailedEvent failed = rpcEvent(
                RpcInvocationFailedEvent.class, ui);
        when(failed.getError()).thenReturn(new IllegalStateException("boom"));
        bus.fireEvent(failed);
        bus.fireEvent(rpcEvent(RpcInvocationEndedEvent.class, ui));
        return service;
    }

    private static <T extends AbstractRpcInvocationEvent> T rpcEvent(
            Class<T> type, UI ui) {
        T event = mock(type);
        when(event.getType()).thenReturn("event");
        when(event.getName()).thenReturn("click");
        when(event.getUI()).thenReturn(ui);
        when(event.getNodeId()).thenReturn(-1);
        return event;
    }
}
