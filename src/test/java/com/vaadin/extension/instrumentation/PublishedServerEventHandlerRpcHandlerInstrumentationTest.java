package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import elemental.json.JsonObject;

import com.vaadin.extension.instrumentation.util.OpenTelemetryTestTools;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.server.communication.rpc.PublishedServerEventHandlerRpcHandler;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PublishedServerEventHandlerRpcHandlerInstrumentationTest
        extends AbstractInstrumentationTest {

    private PublishedServerEventHandlerRpcHandler publishedServerEventHandlerRpcHandler;
    TestComponent component;
    JsonObject jsonObject;

    @BeforeEach
    public void setup() {
        publishedServerEventHandlerRpcHandler = Mockito
                .mock(PublishedServerEventHandlerRpcHandler.class);
        component = new TestComponent();
        getMockUI().add(component);
    }

    @Test
    public void handleNode_eventHandlerStartsSpan() {
        PublishedServerEventHandlerRpcHandlerInstrumentation.HandleAdvice
                .onEnter(publishedServerEventHandlerRpcHandler, "handleNode",
                        null);
        PublishedServerEventHandlerRpcHandlerInstrumentation.HandleAdvice
                .onExit(null, getCapturedSpan(0));

        SpanData span = getExportedSpan(0);
        assertEquals("PublishedServerEventHandlerRpcHandler.handleNode",
                span.getName());
    }

    @Test
    public void invokeAdvice_connectClientIsIgnored()
            throws NoSuchMethodException {

        PublishedServerEventHandlerRpcHandlerInstrumentation.InvokeAdvice
                .onEnter(component,
                        TestComponent.class.getMethod("connectClient"), null);

        Assertions.assertTrue(
                OpenTelemetryTestTools.getSpanBuilderCapture().getSpans()
                        .isEmpty(),
                "No span should have been made for connectClient");
    }

    @Test
    public void invokeAdvice_callInformationIsAddedToSpan()
            throws NoSuchMethodException {

        PublishedServerEventHandlerRpcHandlerInstrumentation.InvokeAdvice
                .onEnter(component,
                        TestComponent.class.getMethod("clientEvent"), null);

        PublishedServerEventHandlerRpcHandlerInstrumentation.HandleAdvice
                .onExit(null, getCapturedSpan(0));

        SpanData span = getExportedSpan(0);
        assertEquals("Invoke server method [clientEvent]", span.getName());
        assertEquals(
                "com.vaadin.extension.instrumentation.PublishedServerEventHandlerRpcHandlerInstrumentationTest$TestComponent",
                span.getAttributes()
                        .get(AttributeKey.stringKey("vaadin.component")));
        assertEquals(
                "public void com.vaadin.extension.instrumentation.PublishedServerEventHandlerRpcHandlerInstrumentationTest$TestComponent.clientEvent()",
                span.getAttributes()
                        .get(AttributeKey.stringKey("vaadin.callable.method")));
    }

    @Tag("test-component")
    private static class TestComponent extends Component {
        @ClientCallable
        public void clientEvent() {
        }

        @ClientCallable
        public void connectClient() {
        }
    }
}
