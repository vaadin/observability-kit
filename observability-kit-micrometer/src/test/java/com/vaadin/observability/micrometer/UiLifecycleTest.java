/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.UIInitEvent;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.observability.micrometer.client.MetricsCollectorElement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Verifies that {@link UiMetricsBinder} correctly tracks UI lifecycle via the
 * {@code vaadin.ui.created} counter and {@code vaadin.ui.active} gauge.
 */
class UiLifecycleTest {

    private SimpleMeterRegistry registry;
    private UiMetricsBinder binder;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        binder = new UiMetricsBinder(registry, null,
                ObservabilitySettings.builder().build());
    }

    @Test
    void uiInitIncrementsCreatedCounterAndActiveGauge() {
        UI ui = mock(UI.class);
        VaadinService service = mock(VaadinService.class);
        UIInitEvent event = new UIInitEvent(ui, service);

        binder.uiInit(event);

        assertEquals(1.0, registry.counter(MeterNames.UI_CREATED).count(), 0.0);
        assertEquals(1.0, registry.find(MeterNames.UI_ACTIVE).gauge().value(),
                0.0);
    }

    @Test
    void multipleUiInitsAccumulate() {
        VaadinService service = mock(VaadinService.class);
        for (int i = 0; i < 3; i++) {
            binder.uiInit(new UIInitEvent(mock(UI.class), service));
        }

        assertEquals(3.0, registry.counter(MeterNames.UI_CREATED).count(), 0.0);
        assertEquals(3.0, registry.find(MeterNames.UI_ACTIVE).gauge().value(),
                0.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void detachDecrementsActiveGauge() {
        UI ui = mock(UI.class);
        VaadinService service = mock(VaadinService.class);
        UIInitEvent event = new UIInitEvent(ui, service);

        binder.uiInit(event);
        assertEquals(1.0, registry.find(MeterNames.UI_ACTIVE).gauge().value(),
                0.0);

        // Capture and invoke the detach listener to simulate UI detach
        ArgumentCaptor<ComponentEventListener<DetachEvent>> captor = ArgumentCaptor
                .forClass(ComponentEventListener.class);
        verify(ui).addDetachListener(captor.capture());
        captor.getValue().onComponentEvent(mock(DetachEvent.class));

        assertEquals(0.0, registry.find(MeterNames.UI_ACTIVE).gauge().value(),
                0.0);
    }

    @Test
    void doesNotTrackUisWhenUisDisabled() {
        SimpleMeterRegistry freshRegistry = new SimpleMeterRegistry();
        UiMetricsBinder disabledBinder = new UiMetricsBinder(freshRegistry,
                null, ObservabilitySettings.builder().uis(false)
                        .navigation(false).build());
        UI ui = mock(UI.class);
        VaadinService service = mock(VaadinService.class);

        disabledBinder.uiInit(new UIInitEvent(ui, service));

        assertEquals(0.0, freshRegistry.counter(MeterNames.UI_CREATED).count(),
                0.0);
        // gauge is pre-registered at construction and stays at 0
        assertEquals(0.0,
                freshRegistry.find(MeterNames.UI_ACTIVE).gauge().value(), 0.0);
    }

    @Test
    void registersNavigationBinderWhenNavigationEnabled() {
        // binder is created with navigation=true (default)
        UI ui = mock(UI.class);
        VaadinService service = mock(VaadinService.class);

        binder.uiInit(new UIInitEvent(ui, service));

        verify(ui).addBeforeEnterListener(any());
        verify(ui).addAfterNavigationListener(any());
    }

    @Test
    void skipsNavigationBinderWhenNavigationDisabled() {
        binder = new UiMetricsBinder(registry, null,
                ObservabilitySettings.builder().navigation(false).build());
        UI ui = mock(UI.class);
        VaadinService service = mock(VaadinService.class);

        binder.uiInit(new UIInitEvent(ui, service));

        verify(ui, org.mockito.Mockito.never()).addBeforeEnterListener(any());
        verify(ui, org.mockito.Mockito.never())
                .addAfterNavigationListener(any());
    }

    @Test
    void registersPollListenerWhenTracesEnabled() {
        ObservationRegistry or = ObservationRegistry.create();
        UiMetricsBinder b = new UiMetricsBinder(new SimpleMeterRegistry(), or,
                ObservabilitySettings.builder().traces(true).build());
        UI ui = mock(UI.class);
        b.uiInit(new UIInitEvent(ui, mock(VaadinService.class)));
        verify(ui).addPollListener(any());
    }

    @Test
    void skipsPollListenerWhenTracesDisabled() {
        UiMetricsBinder b = new UiMetricsBinder(new SimpleMeterRegistry(), null,
                ObservabilitySettings.builder().traces(false).build());
        UI ui = mock(UI.class);
        b.uiInit(new UIInitEvent(ui, mock(VaadinService.class)));
        verify(ui, org.mockito.Mockito.never()).addPollListener(any());
    }

    @Test
    void uiInitAddsMetricsCollectorElementWhenClientEnabled() {
        // client is enabled by default
        UI ui = mock(UI.class);
        binder.uiInit(new UIInitEvent(ui, mock(VaadinService.class)));

        ArgumentCaptor<Component[]> captor = ArgumentCaptor
                .forClass(Component[].class);
        verify(ui).add(captor.capture());
        Component[] added = captor.getValue();
        assertEquals(1, added.length);
        assertEquals(MetricsCollectorElement.class, added[0].getClass());
    }

    @Test
    void uiInitDoesNotAddMetricsCollectorElementWhenClientDisabled() {
        UiMetricsBinder b = new UiMetricsBinder(new SimpleMeterRegistry(), null,
                ObservabilitySettings.builder().client(false).build());
        UI ui = mock(UI.class);
        b.uiInit(new UIInitEvent(ui, mock(VaadinService.class)));

        verify(ui, never()).add(any(Component[].class));
    }
}
