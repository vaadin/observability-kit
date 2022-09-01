package com.vaadin.extension.instrumentation.communication.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.extension.conf.TraceLevel;
import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
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

    @BeforeEach
    public void setup() {
        publishedServerEventHandlerRpcHandler = Mockito
                .mock(PublishedServerEventHandlerRpcHandler.class);
        component = new TestComponent();
        getMockUI().add(component);
    }

    @Test
    public void handleNode_eventHandlerStartsSpan() {
        configureTraceLevel(TraceLevel.MAXIMUM);
        PublishedServerEventHandlerRpcHandlerInstrumentation.HandleAdvice
                .onEnter(publishedServerEventHandlerRpcHandler, "handleNode",
                        null, null);
        PublishedServerEventHandlerRpcHandlerInstrumentation.HandleAdvice
                .onExit(null, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("PublishedServerEventHandlerRpcHandler.handleNode",
                span.getName());
    }

    @Test
    public void handleNode_respectsTraceLevel() {
        configureTraceLevel(TraceLevel.MINIMUM);
        PublishedServerEventHandlerRpcHandlerInstrumentation.HandleAdvice
                .onEnter(publishedServerEventHandlerRpcHandler, "handleNode",
                        null, null);
        PublishedServerEventHandlerRpcHandlerInstrumentation.HandleAdvice
                .onExit(null, getCapturedSpanOrNull(0), null);

        assertEquals(0, getExportedSpanCount());

        configureTraceLevel(TraceLevel.DEFAULT);
        resetSpans();
        PublishedServerEventHandlerRpcHandlerInstrumentation.HandleAdvice
                .onEnter(publishedServerEventHandlerRpcHandler, "handleNode",
                        null, null);
        PublishedServerEventHandlerRpcHandlerInstrumentation.HandleAdvice
                .onExit(null, getCapturedSpanOrNull(0), null);

        assertEquals(0, getExportedSpanCount());

        configureTraceLevel(TraceLevel.MAXIMUM);
        resetSpans();
        PublishedServerEventHandlerRpcHandlerInstrumentation.HandleAdvice
                .onEnter(publishedServerEventHandlerRpcHandler, "handleNode",
                        null, null);
        PublishedServerEventHandlerRpcHandlerInstrumentation.HandleAdvice
                .onExit(null, getCapturedSpanOrNull(0), null);

        assertEquals(1, getExportedSpanCount());
    }

    @Test
    public void invokeAdvice_connectClientIsIgnored()
            throws NoSuchMethodException {

        PublishedServerEventHandlerRpcHandlerInstrumentation.InvokeAdvice
                .onEnter(component,
                        TestComponent.class.getMethod("connectClient"), null,
                        null);

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
                        TestComponent.class.getMethod("clientEvent"), null,
                        null);

        PublishedServerEventHandlerRpcHandlerInstrumentation.HandleAdvice
                .onExit(null, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Invoke server method: TestComponent.clientEvent",
                span.getName());
        assertEquals(
                "com.vaadin.extension.instrumentation.communication.rpc.PublishedServerEventHandlerRpcHandlerInstrumentationTest$TestComponent",
                span.getAttributes()
                        .get(AttributeKey.stringKey("vaadin.component")));
        assertEquals(
                "public void com.vaadin.extension.instrumentation.communication.rpc.PublishedServerEventHandlerRpcHandlerInstrumentationTest$TestComponent.clientEvent()",
                span.getAttributes()
                        .get(AttributeKey.stringKey("vaadin.callable.method")));
    }

    @Test
    public void invokeAdvice_respectsTraceLevel() throws NoSuchMethodException {
        configureTraceLevel(TraceLevel.MINIMUM);
        try (var ignored = withRootContext()) {
            PublishedServerEventHandlerRpcHandlerInstrumentation.InvokeAdvice
                    .onEnter(component,
                            TestComponent.class.getMethod("clientEvent"), null,
                            null);

            PublishedServerEventHandlerRpcHandlerInstrumentation.HandleAdvice
                    .onExit(null, getCapturedSpanOrNull(0), null);
        }

        // Should not export span, apart from root span
        assertEquals(1, getExportedSpanCount());
        // Should update root span
        SpanData rootSpan = getExportedSpan(0);
        assertEquals("/test-route : ClientCallable", rootSpan.getName());

        configureTraceLevel(TraceLevel.DEFAULT);
        resetSpans();
        try (var ignored = withRootContext()) {
            PublishedServerEventHandlerRpcHandlerInstrumentation.InvokeAdvice
                    .onEnter(component,
                            TestComponent.class.getMethod("clientEvent"), null,
                            null);

            PublishedServerEventHandlerRpcHandlerInstrumentation.HandleAdvice
                    .onExit(null, getCapturedSpanOrNull(0), null);
        }

        // Should export span
        assertEquals(2, getExportedSpanCount());
        // Should update root span
        rootSpan = getExportedSpan(1);
        assertEquals("/test-route : ClientCallable", rootSpan.getName());

        configureTraceLevel(TraceLevel.MAXIMUM);
        resetSpans();
        try (var ignored = withRootContext()) {
            PublishedServerEventHandlerRpcHandlerInstrumentation.InvokeAdvice
                    .onEnter(component,
                            TestComponent.class.getMethod("clientEvent"), null,
                            null);

            PublishedServerEventHandlerRpcHandlerInstrumentation.HandleAdvice
                    .onExit(null, getCapturedSpanOrNull(0), null);
        }

        // Should export span, apart from root span
        assertEquals(2, getExportedSpanCount());
        // Should update root span
        rootSpan = getExportedSpan(1);
        assertEquals("/test-route : ClientCallable", rootSpan.getName());
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
