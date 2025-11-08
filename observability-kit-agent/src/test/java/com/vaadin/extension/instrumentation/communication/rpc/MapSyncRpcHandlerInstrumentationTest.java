package com.vaadin.extension.instrumentation.communication.rpc;

import static com.vaadin.extension.Constants.SESSION_ID;
import static org.junit.jupiter.api.Assertions.*;

import com.vaadin.extension.conf.TraceLevel;
import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class MapSyncRpcHandlerInstrumentationTest extends AbstractInstrumentationTest {

    static final    ObjectMapper MAPPER = new ObjectMapper();

    TestComponent component;
    ObjectNode jsonObject;

    @BeforeEach
    public void setup() {
        component = new TestComponent();
        getMockUI().add(component);
        jsonObject = MAPPER.createObjectNode();
        jsonObject.put("type", "mSync");
        jsonObject.put("node", 128);
        jsonObject.put("feature", 1);
        jsonObject.put("property", "value");
        jsonObject.put("value", "foo");
    }

    @Test
    public void handleNode_createsSpan() {
        component.getElement().setText("foo");

        MapSyncRpcHandlerInstrumentation.MethodAdvice.onEnter(
                component.getElement().getNode(), jsonObject, null, null);
        MapSyncRpcHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Sync: test-component[foo].value", span.getName());
        assertEquals("test-component", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.element.tag")));
        assertEquals("value", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.element.property")));
        assertEquals("TestView", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.view")));
        assertEquals(getMockSessionId(),
                span.getAttributes().get(AttributeKey.stringKey(SESSION_ID)));
    }

    @Test
    public void handleNodeWithException_setsErrorStatus() {
        MapSyncRpcHandlerInstrumentation.MethodAdvice.onEnter(
                component.getElement().getNode(), jsonObject, null, null);
        Exception exception = new RuntimeException("test error");
        MapSyncRpcHandlerInstrumentation.MethodAdvice.onExit(exception,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        this.assertSpanHasException(span, exception);
    }

    @Test
    public void mapChange_existingValue_spanSkipped() {
        jsonObject.put("property", "value");
        jsonObject.put("value", "default");
        component.getElement().setProperty("value", "default");

        MapSyncRpcHandlerInstrumentation.MethodAdvice.onEnter(
                component.getElement().getNode(), jsonObject, null, null);
        assertEquals(0, getCapturedSpanCount());
    }

    @Tag("test-component")
    private static class TestComponent extends Component {
    }

    @Test
    public void handleNode_respectsTraceLevels() {
        configureTraceLevel(TraceLevel.MINIMUM);
        MapSyncRpcHandlerInstrumentation.MethodAdvice.onEnter(
                component.getElement().getNode(), jsonObject, null, null);
        MapSyncRpcHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpanOrNull(0), null);

        assertEquals(0, getExportedSpanCount());

        configureTraceLevel(TraceLevel.DEFAULT);
        MapSyncRpcHandlerInstrumentation.MethodAdvice.onEnter(
                component.getElement().getNode(), jsonObject, null, null);
        MapSyncRpcHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpanOrNull(0), null);

        assertEquals(1, getExportedSpanCount());

        configureTraceLevel(TraceLevel.MAXIMUM);
        MapSyncRpcHandlerInstrumentation.MethodAdvice.onEnter(
                component.getElement().getNode(), jsonObject, null, null);
        MapSyncRpcHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpanOrNull(0), null);

        assertEquals(1, getExportedSpanCount());
    }
}
