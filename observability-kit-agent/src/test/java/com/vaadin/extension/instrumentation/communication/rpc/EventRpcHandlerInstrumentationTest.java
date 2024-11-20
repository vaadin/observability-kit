package com.vaadin.extension.instrumentation.communication.rpc;

import static com.vaadin.extension.Constants.SESSION_ID;
import static io.opentelemetry.semconv.ExceptionAttributes.EXCEPTION_MESSAGE;
import static io.opentelemetry.semconv.ExceptionAttributes.EXCEPTION_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import elemental.json.Json;
import elemental.json.JsonObject;

import com.vaadin.extension.conf.TraceLevel;
import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventRpcHandlerInstrumentationTest extends AbstractInstrumentationTest {

    TestComponent component;
    JsonObject jsonObject;

    @BeforeEach
    public void setup() {
        component = new TestComponent();
        getMockUI().add(component);
        jsonObject = Json.createObject();
        jsonObject.put("event", "click");
    }

    @Test
    public void handleNode_createsSpan() {
        component.getElement().setText("foo");

        EventRpcHandlerInstrumentation.MethodAdvice.onEnter(
                component.getElement().getNode(), jsonObject, null, null);
        EventRpcHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Event: test-component[foo] :: click", span.getName());
        assertEquals("test-component", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.element.tag")));
        assertEquals("click", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.event.type")));
        assertEquals("TestView", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.view")));
        assertEquals(getMockSessionId(),
                span.getAttributes().get(AttributeKey.stringKey(SESSION_ID)));
    }

    @Test
    public void handleNode_componentWithId_idUsedInSpan() {
        component.setId("id");
        component.getElement().setText("foo");

        EventRpcHandlerInstrumentation.MethodAdvice.onEnter(
                component.getElement().getNode(), jsonObject, null, null);
        EventRpcHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Event: test-component[id='id'] :: click", span.getName(),
                "Id should be chosen over text");
    }

    @Test
    public void handleNodeWithOpenedChangedEvent_addsOpenedChangedState() {
        // Opened
        jsonObject.put("event", "opened-changed");
        component.getElement().setProperty("opened", true);

        EventRpcHandlerInstrumentation.MethodAdvice.onEnter(
                component.getElement().getNode(), jsonObject, null, null);
        EventRpcHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("opening", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.state.change")));

        // Closed
        resetSpans();

        component.getElement().setProperty("opened", false);

        EventRpcHandlerInstrumentation.MethodAdvice.onEnter(
                component.getElement().getNode(), jsonObject, null, null);
        EventRpcHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpan(0), null);

        span = getExportedSpan(0);
        assertEquals("closing", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.state.change")));
    }

    @Test
    public void handleNodeWithException_setsErrorStatus() {
        EventRpcHandlerInstrumentation.MethodAdvice.onEnter(
                component.getElement().getNode(), jsonObject, null, null);
        Exception exception = new RuntimeException("test error");
        EventRpcHandlerInstrumentation.MethodAdvice.onExit(exception,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals("test error", span.getStatus().getDescription());

        assertEquals(1, span.getEvents().size());
        EventData eventData = span.getEvents().get(0);
        assertEquals(RuntimeException.class.getCanonicalName(), eventData
                .getAttributes().get(EXCEPTION_TYPE));
        assertEquals("test error", eventData.getAttributes()
                .get(EXCEPTION_MESSAGE));
    }

    @Test
    public void handleNode_respectsTraceLevel() {
        configureTraceLevel(TraceLevel.MINIMUM);
        EventRpcHandlerInstrumentation.MethodAdvice.onEnter(
                component.getElement().getNode(), jsonObject, null, null);
        EventRpcHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpanOrNull(0), null);
        // Should not export span
        assertEquals(0, getExportedSpanCount());

        configureTraceLevel(TraceLevel.DEFAULT);
        resetSpans();
        EventRpcHandlerInstrumentation.MethodAdvice.onEnter(
                component.getElement().getNode(), jsonObject, null, null);
        EventRpcHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpanOrNull(0), null);
        // Should export span
        assertEquals(1, getExportedSpanCount());

        configureTraceLevel(TraceLevel.MAXIMUM);
        resetSpans();
        EventRpcHandlerInstrumentation.MethodAdvice.onEnter(
                component.getElement().getNode(), jsonObject, null, null);
        EventRpcHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpanOrNull(0), null);
        // Should export span
        assertEquals(1, getExportedSpanCount());
    }

    @Tag("test-component")
    private static class TestComponent extends Component {
    }
}
