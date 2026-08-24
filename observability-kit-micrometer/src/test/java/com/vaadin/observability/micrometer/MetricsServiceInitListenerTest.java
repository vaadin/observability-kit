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
import com.vaadin.flow.server.UIInitListener;
import com.vaadin.flow.server.VaadinRequestInterceptor;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServiceEventBus;
import com.vaadin.flow.server.SessionLockRequestedEvent;
import com.vaadin.flow.server.communication.RpcInvocationStartedEvent;
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

        verify(service).addSessionInitListener(any(SessionInitListener.class));
        verify(service)
                .addSessionDestroyListener(any(SessionDestroyListener.class));
        Assertions.assertEquals(1,
                service.getEventBus()
                        .getListeners(SessionLockRequestedEvent.class).size(),
                "sessions enabled should subscribe the session lock binder");
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
        Assertions.assertTrue(
                service.getEventBus()
                        .getListeners(SessionLockRequestedEvent.class).isEmpty(),
                "sessions disabled should not subscribe the lock binder");
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
        // (timing/tracing) and the InteractionCollector (insights)
        // are both registered as RPC invocation listeners.
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().build());
        Assertions.assertEquals(2, rpcEventSubscribers(),
                "requests enabled should subscribe both the RpcMetricsBinder "
                        + "and the interaction collector");
    }

    @Test
    void registersOnlyInteractionCollectorWhenOnlyErrorsEnabled() {
        // Errors on, requests off: the collector is still needed to capture
        // failed interactions, but the RpcMetricsBinder is not.
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().requests(false).errors(true)
                        .build());
        Assertions.assertEquals(1, rpcEventSubscribers(),
                "only the interaction collector should subscribe when requests "
                        + "are off");
    }

    @Test
    void skipsRpcEventSubscribersWhenRequestsAndErrorsDisabled() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().requests(false).errors(false)
                        .build());
        VaadinService service = licensedService();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        Assertions.assertTrue(
                service.getEventBus()
                        .getListeners(RpcInvocationStartedEvent.class).isEmpty(),
                "no RPC subscribers when requests and errors are both off");
    }

    @Test
    void insightsCanBeDisabledWithoutGivingUpErrorOrRequestMetrics() {
        // Insights is a feature in its own right: switching it off must not
        // force a choice between error metrics and request metrics.
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().insights(false).build());
        Assertions.assertEquals(1, rpcEventSubscribers(),
                "insights off should leave only the RpcMetricsBinder "
                        + "subscribed");
        Assertions.assertNull(ObservabilityKit.getRecentInteractions(),
                "no buffer should be bound when insights are off");
    }

    @Test
    void insightsBufferHonoursTheConfiguredCapacity() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().insightsCapacity(2).build());
        rpcEventSubscribers();

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

    /**
     * Runs {@code serviceInit} against a real event bus and reports how many
     * subscribers the RPC invocation events ended up with. The binders now
     * register method references rather than themselves, so the count is what
     * identifies the wiring: the metrics binder and the interaction collector
     * subscribe one each.
     */
    private static int rpcEventSubscribers() {
        VaadinService service = licensedService();
        VaadinServiceEventBus eventBus = service.getEventBus();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        return eventBus.getListeners(RpcInvocationStartedEvent.class).size();
    }
}
