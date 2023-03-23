package com.vaadin.observability;

import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.di.Lookup;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.UIInitEvent;
import com.vaadin.flow.server.UIInitListener;
import com.vaadin.flow.server.VaadinContext;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WrappedSession;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ObservabilityServiceInitListenerTest {

    private ServiceInitEvent serviceInitEvent;
    private VaadinService service;
    private Lookup lookup;
    private UI ui;

    @BeforeEach
    void setup() {
        serviceInitEvent = mock(ServiceInitEvent.class);
        service = mock(VaadinService.class);
        VaadinContext context = mock(VaadinContext.class);
        lookup = mock(Lookup.class);

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
        when(service.getContext()).thenReturn(context);
        when(serviceInitEvent.getSource()).thenReturn(service);

        when(context.getAttribute(Lookup.class)).thenReturn(lookup);

        WrappedSession wrappedSession = mock(WrappedSession.class);
        when(wrappedSession.getId()).thenReturn("THESESSIONID");
        VaadinSession session = mock(VaadinSession.class);
        when(session.getSession()).thenReturn(wrappedSession);
        ui = mock(UI.class);
        when(ui.getSession()).thenReturn(session);
    }

    @Test
    public void serviceInit_handlerAndClientInstalled() {

        ObservabilityServiceInitListener listener = new ObservabilityServiceInitListener();
        listener.serviceInit(serviceInitEvent);

        verify(service).addUIInitListener(any());

        when(ui.getChildren()).thenReturn(Stream.empty());

        service.fireUIInitListeners(ui);

        verify(ui.getSession()).addRequestHandler(any(ObservabilityHandler.class));
        verify(ui).add(any(ObservabilityClient.class));
    }

    @Test
    public void serviceInit_clientExists_removesExistingClients() {
        ObservabilityServiceInitListener listener = new ObservabilityServiceInitListener();
        listener.serviceInit(serviceInitEvent);

        verify(service).addUIInitListener(any());

        ObservabilityClient client1 = new ObservabilityClient("X");
        ObservabilityClient client2 = new ObservabilityClient("Y");
        when(ui.getChildren()).thenReturn(Stream.of(client1, client2));

        service.fireUIInitListeners(ui);

        verify(ui.getSession()).addRequestHandler(any(ObservabilityHandler.class));
        verify(ui, times(2)).remove(any(ObservabilityClient.class));
        verify(ui).add(any(ObservabilityClient.class));
    }

    @Test
    void observabilityClientConfigurer_configurationApplied() {
        ObservabilityClientConfigurer configurer = config -> {
            config.setDocumentLoadEnabled(false);
            config.setFrontendErrorEnabled(false);
            config.setXMLHttpRequestEnabled(false);
            config.setUserInteractionEvents("mousedown", "play");
        };
        when(lookup.lookup(ObservabilityClientConfigurer.class))
                .thenReturn(configurer);

        ObservabilityServiceInitListener listener = new ObservabilityServiceInitListener();
        listener.serviceInit(serviceInitEvent);

        service.fireUIInitListeners(ui);

        ArgumentCaptor<ObservabilityClient> captor = ArgumentCaptor
                .forClass(ObservabilityClient.class);
        verify(ui).add(captor.capture());

        ObservabilityClient client = captor.getValue();
        Assertions.assertTrue(client.isEnabled(),
                "Expecting client to be enabled");
        Assertions.assertFalse(client.isDocumentLoadEnabled(),
                "Expecting document load to be disabled");
        Assertions.assertFalse(client.isFrontendErrorEnabled(),
                "Expecting frontend error to be disabled");
        Assertions.assertFalse(client.isXMLHttpRequestEnabled(),
                "Expecting xml http request to be disabled");
        Assertions.assertEquals(Set.of("mousedown", "play"),
                client.getUserInteractionEvents(),
                "User Interaction Events");
    }

    @Test
    void observabilityClientConfigurer_disableObservability_clientNotAdded() {
        ObservabilityClientConfigurer configurer = config -> config
                .setEnabled(false);
        when(lookup.lookup(ObservabilityClientConfigurer.class))
                .thenReturn(configurer);

        ObservabilityServiceInitListener listener = new ObservabilityServiceInitListener();
        listener.serviceInit(serviceInitEvent);

        service.fireUIInitListeners(ui);

        verify(ui, never()).add(any(ObservabilityClient.class));
    }

}
