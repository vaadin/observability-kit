package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.*;

import elemental.json.Json;
import elemental.json.JsonObject;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.server.communication.rpc.MapSyncRpcHandler;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MapSyncRpcHandlerInstrumentationTest extends AbstractInstrumentationTest {
    private MapSyncRpcHandler mapSyncRpcHandler;
    TestComponent component;
    JsonObject jsonObject;

    @BeforeEach
    public void setup() {
        mapSyncRpcHandler = Mockito.mock(MapSyncRpcHandler.class);
        component = new TestComponent();
        getMockUI().add(component);
        jsonObject = Json.createObject();
        jsonObject.put("event", "click");
    }

    @Test
    public void handleNode_createsSpan() {
        component.getElement().setText("foo");

        MapSyncRpcHandlerInstrumentation.MethodAdvice.onEnter(mapSyncRpcHandler,
                "handleNode", component.getElement().getNode(), jsonObject,
                null, null, null);
        MapSyncRpcHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpan(0), null, null);

        SpanData span = getExportedSpan(0);
        assertEquals("Sync: test-component[foo]", span.getName());
        assertEquals("test-component", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.element.tag")));
        assertEquals("TestView", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.view")));
    }

    @Test
    public void handleNodeWithException_setsErrorStatus() {
        MapSyncRpcHandlerInstrumentation.MethodAdvice.onEnter(mapSyncRpcHandler,
                "handleNode", component.getElement().getNode(), jsonObject,
                null, null, null);
        Exception exception = new RuntimeException("test error");
        MapSyncRpcHandlerInstrumentation.MethodAdvice.onExit(exception,
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