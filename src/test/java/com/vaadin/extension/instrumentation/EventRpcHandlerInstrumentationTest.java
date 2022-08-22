package com.vaadin.extension.instrumentation;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.server.communication.rpc.EventRpcHandler;
import elemental.json.Json;
import elemental.json.JsonObject;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        EventRpcHandlerInstrumentation.MethodAdvice.onEnter(
                eventRpcHandlerMock,
                "handleNode",
                component.getElement().getNode(),
                jsonObject,
                null
        );
        EventRpcHandlerInstrumentation.MethodAdvice.onExit(null, getCapturedSpan(0));

        SpanData span = getExportedSpan(0);
        assertEquals("test-component[foo] :: click", span.getName());
        assertEquals("test-component", span.getAttributes().get(AttributeKey.stringKey("vaadin.element.tag")));
        assertEquals("TestView", span.getAttributes().get(AttributeKey.stringKey("vaadin.view")));
    }

    @Test
    public void handleNode_updatesRootSpan() {
        try(var ignored = withRootContext()) {
            EventRpcHandlerInstrumentation.MethodAdvice.onEnter(
                    eventRpcHandlerMock,
                    "handleNode",
                    component.getElement().getNode(),
                    jsonObject,
                    null
            );
            EventRpcHandlerInstrumentation.MethodAdvice.onExit(null, getCapturedSpan(0));
        }

        SpanData exportedRootSpan = getExportedSpan(1);
        assertEquals("/test-route : event", exportedRootSpan.getName());
    }

    @Tag("test-component")
    private static class TestComponent extends Component {
    }
}
