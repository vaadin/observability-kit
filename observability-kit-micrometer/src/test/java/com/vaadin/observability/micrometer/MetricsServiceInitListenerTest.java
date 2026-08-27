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
import com.vaadin.flow.server.communication.RpcInvocationStartedEvent;
import com.vaadin.observability.micrometer.insights.CapturedInteraction;
import com.vaadin.observability.micrometer.insights.RecentInteractions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeast;
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

    /**
     * Counts the registered interceptors of the given type. Several binders
     * intercept requests (request timing, navigation clean-up), so tests match
     * on the concrete type instead of the plain invocation count.
     */
    private static long interceptorsOfType(ServiceInitEvent event,
            Class<? extends VaadinRequestInterceptor> type) {
        ArgumentCaptor<VaadinRequestInterceptor> captor = ArgumentCaptor
                .forClass(VaadinRequestInterceptor.class);
        verify(event, atLeast(0)).addVaadinRequestInterceptor(captor.capture());
        return captor.getAllValues().stream().filter(type::isInstance).count();
    }

    /**
     * The registration index of the first interceptor of the given type, or
     * {@code -1} when none was registered. Vaadin reverses the interceptor
     * list, so a higher index means the interceptor runs earlier.
     */
    private static int indexOfType(ServiceInitEvent event,
            Class<? extends VaadinRequestInterceptor> type) {
        ArgumentCaptor<VaadinRequestInterceptor> captor = ArgumentCaptor
                .forClass(VaadinRequestInterceptor.class);
        verify(event, atLeast(0)).addVaadinRequestInterceptor(captor.capture());
        List<VaadinRequestInterceptor> registered = captor.getAllValues();
        for (int i = 0; i < registered.size(); i++) {
            if (type.isInstance(registered.get(i))) {
                return i;
            }
        }
        return -1;
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

        verify(service, never()).addSessionInitListener(any());
        verify(service, never()).addSessionDestroyListener(any());
        Assertions.assertTrue(service.getEventBus()
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

        assertEquals(1, interceptorsOfType(event, RequestMetricsBinder.class));
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

        assertEquals(1, interceptorsOfType(event, RequestMetricsBinder.class));
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

        assertEquals(0, interceptorsOfType(event, RequestMetricsBinder.class));
    }

    @Test
    void navigationInterceptorRunsBeforeTheRequestInterceptor() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().build());
        VaadinService service = licensedService();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        // Vaadin reverses the interceptor list, so the navigation binder has to
        // be registered after the request binder in order to run first: its
        // requestEnd closes the navigation scope, which is only correct while
        // the enclosing request scope is still open.
        Assertions.assertTrue(
                indexOfType(event, NavigationMetricsBinder.class) > indexOfType(
                        event, RequestMetricsBinder.class),
                "the navigation interceptor must be registered last so that "
                        + "Vaadin runs it first");
    }

    @Test
    void registersNavigationInterceptorWhenNavigationEnabled() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().build());
        VaadinService service = licensedService();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        // The navigation binder also intercepts requests so it can close out
        // navigations that never reach afterNavigation.
        assertEquals(1,
                interceptorsOfType(event, NavigationMetricsBinder.class));
    }

    @Test
    void skipsNavigationInterceptorWhenNavigationDisabled() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().navigation(false).build());
        VaadinService service = licensedService();
        ServiceInitEvent event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);

        new MetricsServiceInitListener().serviceInit(event);

        assertEquals(0,
                interceptorsOfType(event, NavigationMetricsBinder.class));
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
