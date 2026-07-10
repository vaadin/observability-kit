/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

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
import com.vaadin.observability.micrometer.insights.InteractionExemplarCollector;

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

        verify(service).addSessionInitListener(any(SessionInitListener.class));
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

        verify(service, never()).addSessionInitListener(any());
        verify(service, never()).addSessionDestroyListener(any());
        verify(service, never()).addSessionLockListener(any());
    }

    @Test
    void registersUiInitListenerWhenUisEnabled() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().build());
        VaadinService service = licensedService();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        verify(service).addUIInitListener(any(UIInitListener.class));
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

        verify(service).addUIInitListener(any(UIInitListener.class));
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

        verify(service, never()).addUIInitListener(any());
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

        verify(service).addUIInitListener(any(UIInitListener.class));
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
        // (timing/tracing) and the InteractionExemplarCollector (insights)
        // are both registered as RPC invocation listeners.
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().build());
        List<RpcInvocationListener> listeners = registeredRpcListeners();

        Assertions.assertTrue(
                listeners.stream().anyMatch(l -> l instanceof RpcMetricsBinder),
                "requests enabled should register the RpcMetricsBinder");
        Assertions.assertTrue(
                listeners.stream()
                        .anyMatch(l -> l instanceof InteractionExemplarCollector),
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
                        .anyMatch(l -> l instanceof InteractionExemplarCollector),
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
