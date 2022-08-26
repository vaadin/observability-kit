package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.flow.server.communication.SessionRequestHandler;

import io.opentelemetry.sdk.trace.data.SpanData;
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
    public void handleRequestWithException_setsErrorStatus() {
        SessionRequestHandlerInstrumentation.HandleRequestAdvice.onEnter(
                sessionRequestHandlerMock, "handleRequest", null, null);
        Exception exception = new RuntimeException("test error");
        SessionRequestHandlerInstrumentation.HandleRequestAdvice
                .onExit(exception, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertSpanHasException(span, exception);
    }
}
