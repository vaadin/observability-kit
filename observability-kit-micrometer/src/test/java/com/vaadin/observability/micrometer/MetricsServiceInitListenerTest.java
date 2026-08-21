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

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.SessionDestroyListener;
import com.vaadin.flow.server.SessionInitListener;
import com.vaadin.flow.server.SessionLockListener;
import com.vaadin.flow.server.UIInitListener;
import com.vaadin.flow.server.VaadinRequestInterceptor;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.communication.RpcInvocationListener;
import com.vaadin.observability.micrometer.insights.CapturedInteraction;
import com.vaadin.observability.micrometer.insights.InteractionCollector;
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
        verify(service).addSessionLockListener(any(SessionLockListener.class));
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
        verify(service, never()).addSessionLockListener(any());
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
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().build());
        List<RpcInvocationListener> listeners = registeredRpcListeners();

        Assertions.assertTrue(
                listeners.stream().anyMatch(l -> l instanceof RpcMetricsBinder),
                "requests enabled should register the RpcMetricsBinder");
        Assertions.assertTrue(
                listeners.stream()
                        .anyMatch(l -> l instanceof InteractionCollector),
                "requests enabled should register the interaction collector");
    }

    @Test
    void registersOnlyInteractionCollectorWhenOnlyErrorsEnabled() {
        // Errors on, requests off: the collector is still needed to capture
        // failed interactions, but the RpcMetricsBinder is not.
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().requests(false).errors(true)
                        .build());
        List<RpcInvocationListener> listeners = registeredRpcListeners();

        Assertions.assertTrue(
                listeners.stream()
                        .anyMatch(l -> l instanceof InteractionCollector),
                "errors enabled should register the interaction collector");
        Assertions.assertTrue(
                listeners.stream()
                        .noneMatch(l -> l instanceof RpcMetricsBinder),
                "RpcMetricsBinder should not be registered when requests are off");
    }

    @Test
    void skipsRpcInvocationListenersWhenRequestsAndErrorsDisabled() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().requests(false).errors(false)
                        .build());
        VaadinService service = licensedService();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        verify(service, never()).addRpcInvocationListener(any());
    }

    @Test
    void insightsCanBeDisabledWithoutGivingUpErrorOrRequestMetrics() {
        // Insights is a feature in its own right: switching it off must not
        // force a choice between error metrics and request metrics.
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().insights(false).build());
        List<RpcInvocationListener> listeners = registeredRpcListeners();

        Assertions.assertTrue(
                listeners.stream()
                        .noneMatch(l -> l instanceof InteractionCollector),
                "insights off should not register the interaction collector");
        Assertions.assertTrue(
                listeners.stream().anyMatch(l -> l instanceof RpcMetricsBinder),
                "request metrics should survive insights being off");
        Assertions.assertNull(ObservabilityKit.getRecentInteractions(),
                "no buffer should be bound when insights are off");
    }

    @Test
    void insightsBufferHonoursTheConfiguredCapacity() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().insightsCapacity(2).build());
        registeredRpcListeners();

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

    private static List<RpcInvocationListener> registeredRpcListeners() {
        VaadinService service = licensedService();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        ArgumentCaptor<RpcInvocationListener> captor = ArgumentCaptor
                .forClass(RpcInvocationListener.class);
        verify(service, atLeastOnce())
                .addRpcInvocationListener(captor.capture());
        return captor.getAllValues();
    }
}
