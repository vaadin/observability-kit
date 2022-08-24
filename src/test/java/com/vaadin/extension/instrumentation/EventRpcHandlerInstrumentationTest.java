package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import elemental.json.Json;
import elemental.json.JsonObject;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.server.communication.rpc.EventRpcHandler;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EventRpcHandlerInstrumentationTest extends AbstractInstrumentationTest {

    private EventRpcHandler eventRpcHandlerMock;
    TestComponent component;
    JsonObject jsonObject;

    @BeforeEach
    public void setup() {
        eventRpcHandlerMock = Mockito.mock(EventRpcHandler.class);
        component = new TestComponent();
        getMockUI().add(component);
        jsonObject = Json.createObject();
        jsonObject.put("event", "click");
    }

    @Test
    public void handleNode_createsSpan() {
        component.getElement().setText("foo");

        EventRpcHandlerInstrumentation.MethodAdvice.onEnter(eventRpcHandlerMock,
                "handleNode", component.getElement().getNode(), jsonObject,
                null, null, null);
        EventRpcHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpan(0), null, null);

        SpanData span = getExportedSpan(0);
        assertEquals("Event: test-component[foo] :: click", span.getName());
        assertEquals("test-component", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.element.tag")));
        assertEquals("click", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.event.type")));
        assertEquals("TestView", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.view")));
    }

    @Test
    public void handleNodeWithOpenedChangedEvent_addsOpenedChangedState() {
        // Opened
        jsonObject.put("event", "opened-changed");
        component.getElement().setProperty("opened", true);

        EventRpcHandlerInstrumentation.MethodAdvice.onEnter(eventRpcHandlerMock,
                "handleNode", component.getElement().getNode(), jsonObject,
                null, null, null);
        EventRpcHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpan(0), null, null);

        SpanData span = getExportedSpan(0);
        assertEquals("opening", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.state.change")));

        // Closed
        resetSpans();

        component.getElement().setProperty("opened", false);

        EventRpcHandlerInstrumentation.MethodAdvice.onEnter(eventRpcHandlerMock,
                "handleNode", component.getElement().getNode(), jsonObject,
                null, null, null);
        EventRpcHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpan(0), null, null);

        span = getExportedSpan(0);
        assertEquals("closing", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.state.change")));
    }

    @Test
    public void handleNode_updatesRootSpan() {
        try (var ignored = withRootContext()) {
            EventRpcHandlerInstrumentation.MethodAdvice.onEnter(
                    eventRpcHandlerMock, "handleNode",
                    component.getElement().getNode(), jsonObject, null, null,
                    null);
            EventRpcHandlerInstrumentation.MethodAdvice.onExit(null,
                    getCapturedSpan(0), null, null);
        }

        SpanData exportedRootSpan = getExportedSpan(1);
        assertEquals("/test-route : event", exportedRootSpan.getName());
    }

    @Test
    public void handleNodeWithException_setsErrorStatus() {
        EventRpcHandlerInstrumentation.MethodAdvice.onEnter(eventRpcHandlerMock,
                "handleNode", component.getElement().getNode(), jsonObject,
                null, null, null);
        Exception exception = new RuntimeException("test error");
        EventRpcHandlerInstrumentation.MethodAdvice.onExit(exception,
                getCapturedSpan(0), null, null);

        SpanData span = getExportedSpan(0);
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals("test error", span.getStatus().getDescription());

        assertEquals(1, span.getEvents().size());
        EventData eventData = span.getEvents().get(0);
        assertEquals(RuntimeException.class.getCanonicalName(), eventData
                .getAttributes().get(SemanticAttributes.EXCEPTION_TYPE));
        assertEquals("test error", eventData.getAttributes()
                .get(SemanticAttributes.EXCEPTION_MESSAGE));
    }

    @Tag("test-component")
    private static class TestComponent extends Component {
    }
}
