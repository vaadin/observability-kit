package com.vaadin.observability;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.UIInitEvent;
import com.vaadin.flow.server.UIInitListener;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ObservabilityServiceInitListenerTest {
    @Test
    public void serviceInit_handlerAndClientInstalled() {
        ServiceInitEvent serviceInitEvent = mock(ServiceInitEvent.class);
        VaadinService service = mock(VaadinService.class);
        ArgumentCaptor<UIInitListener> listenerArgument = ArgumentCaptor
                .forClass(UIInitListener.class);
        when(service.addUIInitListener(listenerArgument.capture()))
                .thenReturn(null);
        doAnswer(invocation -> {
            UI ui = invocation.getArgument(0);
            UIInitEvent uiInitEvent = new UIInitEvent(ui, service);
            listenerArgument.getValue().uiInit(uiInitEvent);
            return null;
        }).when(service).fireUIInitListeners(any());
        when(serviceInitEvent.getSource()).thenReturn(service);

        ObservabilityServiceInitListener listener = new ObservabilityServiceInitListener();
        listener.serviceInit(serviceInitEvent);

        verify(service).addUIInitListener(any());

        UI ui = mock(UI.class);
        when(ui.getChildren()).thenReturn(Stream.empty());
        VaadinSession session = mock(VaadinSession.class);
        when(ui.getSession()).thenReturn(session);

        service.fireUIInitListeners(ui);

        verify(session).addRequestHandler(any());
        verify(ui).add(any(ObservabilityClient.class));
    }

    @Test
    public void serviceInit_clientExists_clientNotInstalled() {
        ServiceInitEvent serviceInitEvent = mock(ServiceInitEvent.class);
        VaadinService service = mock(VaadinService.class);
        ArgumentCaptor<UIInitListener> listenerArgument = ArgumentCaptor
                .forClass(UIInitListener.class);
        when(service.addUIInitListener(listenerArgument.capture()))
                .thenReturn(null);
        doAnswer(invocation -> {
            UI ui = invocation.getArgument(0);
            UIInitEvent uiInitEvent = new UIInitEvent(ui, service);
            listenerArgument.getValue().uiInit(uiInitEvent);
            return null;
        }).when(service).fireUIInitListeners(any());
        when(serviceInitEvent.getSource()).thenReturn(service);

        ObservabilityServiceInitListener listener = new ObservabilityServiceInitListener();
        listener.serviceInit(serviceInitEvent);

        verify(service).addUIInitListener(any());

        UI ui = mock(UI.class);
        ObservabilityClient client = mock(ObservabilityClient.class);
        when(ui.getChildren()).thenReturn(Stream.of(client));
        VaadinSession session = mock(VaadinSession.class);
        when(ui.getSession()).thenReturn(session);

        service.fireUIInitListeners(ui);

        verify(session).addRequestHandler(any());
        verify(ui).remove(any(ObservabilityClient.class));
        verify(ui, never()).add(any(ObservabilityClient.class));
    }
}
