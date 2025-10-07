package com.vaadin.observability;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
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
import static org.mockito.Mockito.atLeastOnce;
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
        service = mock(VaadinService.class);
        serviceInitEvent = new ServiceInitEvent(service);
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

        verify(ui.getSession())
                .addRequestHandler(any(ObservabilityHandler.class));
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

        verify(ui.getSession())
                .addRequestHandler(any(ObservabilityHandler.class));
        verify(ui, times(2)).remove(any(ObservabilityClient.class));
        verify(ui).add(any(ObservabilityClient.class));
    }

    @Test
    void customObservabilityClientConfigurer_configurationApplied() {
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
        Assertions.assertTrue(client.isLongTaskEnabled(),
                "Expecting long task to be enabled");
        Assertions.assertFalse(client.isDocumentLoadEnabled(),
                "Expecting document load to be disabled");
        Assertions.assertFalse(client.isFrontendErrorEnabled(),
                "Expecting frontend error to be disabled");
        Assertions.assertFalse(client.isXMLHttpRequestEnabled(),
                "Expecting xml http request to be disabled");
        Assertions.assertEquals(Set.of("mousedown", "play"),
                client.getUserInteractionEvents(), "User Interaction Events");
    }

    @Test
    void customObservabilityClientConfigurer_disabledObservability_clientNotAdded() {
        ObservabilityClientConfigurer configurer = config -> config
                .setEnabled(false);
        when(lookup.lookup(ObservabilityClientConfigurer.class))
                .thenReturn(configurer);

        ObservabilityServiceInitListener listener = new ObservabilityServiceInitListener();
        listener.serviceInit(serviceInitEvent);

        service.fireUIInitListeners(ui);

        verify(ui, never()).add(any(ObservabilityClient.class));
    }

    @Test
    void observabilityClientConfiguration_agentConfiguration_settingsApplied() {
        ObservabilityServiceInitListener listener = new ObservabilityServiceInitListener();
        listener.serviceInit(serviceInitEvent);

        assertAgentConfigurationApplied(flagSetter -> {
            flagSetter.accept("enabled", true);
            flagSetter.accept("document-load", true);
            flagSetter.accept("user-interaction", true);
            flagSetter.accept("xml-http-request", true);
            flagSetter.accept("long-task", true);
            flagSetter.accept("frontend-error", true);
        });

        assertAgentConfigurationApplied(flagSetter -> {
            flagSetter.accept("enabled", true);
            flagSetter.accept("document-load", false);
            flagSetter.accept("user-interaction", true);
            flagSetter.accept("xml-http-request", false);
            flagSetter.accept("long-task", true);
            flagSetter.accept("frontend-error", true);
        });
    }

    @Test
    void observabilityClientConfiguration_enabledByAgentConfiguration_allInstrumentationEnabled() {
        ObservabilityServiceInitListener listener = new ObservabilityServiceInitListener();
        listener.serviceInit(serviceInitEvent);

        assertAgentConfigurationApplied(
                flagSetter -> flagSetter.accept("enabled", true));
    }

    @Test
    void observabilityClientConfiguration_allInstrumentationDisabledInAgentConfiguration_clientDisabled() {
        ObservabilityServiceInitListener listener = new ObservabilityServiceInitListener();
        listener.serviceInit(serviceInitEvent);

        mockAgentConfiguration(flagSetter -> {
            flagSetter.accept("enabled", true);
            flagSetter.accept("document-load", false);
            flagSetter.accept("user-interaction", false);
            flagSetter.accept("xml-http-request", false);
            flagSetter.accept("long-task", false);
            flagSetter.accept("frontend-error", false);
        });

        service.fireUIInitListeners(ui);

        verify(ui, never()).add(any(ObservabilityClient.class));
    }

    private Function<String, Optional<Boolean>> mockAgentConfiguration(
            Consumer<BiConsumer<String, Boolean>> flagSetter) {
        Map<String, String> agentConfiguration = new HashMap<>();
        flagSetter.accept((key, value) -> agentConfiguration.put(
                ObservabilityServiceInitListener.CONFIG_PROPERTY_PREFIX + key,
                Boolean.toString(value)));
        ObservabilityHandler handler = ObservabilityHandler.ensureInstalled(ui);
        handler.config = agentConfiguration::get;

        return key -> Optional.ofNullable(agentConfiguration.get(
                ObservabilityServiceInitListener.CONFIG_PROPERTY_PREFIX + key))
                .map(Boolean::parseBoolean);
    }

    private void assertAgentConfigurationApplied(
            Consumer<BiConsumer<String, Boolean>> flagSetter) {
        Function<String, Boolean> flagEnabled = mockAgentConfiguration(
                flagSetter).andThen(opt -> opt.orElse(true));

        service.fireUIInitListeners(ui);

        ArgumentCaptor<ObservabilityClient> captor = ArgumentCaptor
                .forClass(ObservabilityClient.class);
        verify(ui, atLeastOnce()).add(captor.capture());

        ObservabilityClient client = captor.getValue();

        Assertions.assertTrue(client.isEnabled(),
                "Expecting client to be enabled");
        Assertions.assertEquals(flagEnabled.apply("document-load"),
                client.isDocumentLoadEnabled(), "Document Load");
        Assertions.assertEquals(flagEnabled.apply("user-interaction"),
                client.isUserInteractionEnabled(), "User Interaction");
        Assertions.assertEquals(flagEnabled.apply("xml-http-request"),
                client.isXMLHttpRequestEnabled(), "XML HTTP Request");
        Assertions.assertEquals(flagEnabled.apply("long-task"),
                client.isLongTaskEnabled(), "Long Task");
        Assertions.assertEquals(flagEnabled.apply("frontend-error"),
                client.isFrontendErrorEnabled(), "Frontend Error");
    }

}
