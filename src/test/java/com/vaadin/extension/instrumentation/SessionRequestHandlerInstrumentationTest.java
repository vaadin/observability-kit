package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.flow.server.communication.SessionRequestHandler;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class SessionRequestHandlerInstrumentationTest
        extends AbstractInstrumentationTest {
    private SessionRequestHandler sessionRequestHandlerMock;

    @BeforeEach
    public void setup() {
        sessionRequestHandlerMock = Mockito.mock(SessionRequestHandler.class);
    }

    @Test
    public void handleRequest_createsSpan() {
        SessionRequestHandlerInstrumentation.HandleRequestAdvice.onEnter(
                sessionRequestHandlerMock, "handleRequest", null, null);
        SessionRequestHandlerInstrumentation.HandleRequestAdvice.onExit(null,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("SessionRequestHandler.handleRequest", span.getName());
    }

    @Test
    public void handleRequest_updatesRootSpan() {
        try (var ignored = withRootContext()) {
            SessionRequestHandlerInstrumentation.HandleRequestAdvice.onEnter(
                    sessionRequestHandlerMock, "handleRequest", null, null);
            SessionRequestHandlerInstrumentation.HandleRequestAdvice
                    .onExit(null, getCapturedSpan(0), null);
        }

        SpanData exportedRootSpan = getExportedSpan(1);
        assertEquals("/", exportedRootSpan.getName());
    }

    @Test
    public void handleRequestWithException_setsErrorStatus() {
        SessionRequestHandlerInstrumentation.HandleRequestAdvice.onEnter(
                sessionRequestHandlerMock, "handleRequest", null, null);
        Exception exception = new RuntimeException("test error");
        SessionRequestHandlerInstrumentation.HandleRequestAdvice
                .onExit(exception, getCapturedSpan(0), null);

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
}
