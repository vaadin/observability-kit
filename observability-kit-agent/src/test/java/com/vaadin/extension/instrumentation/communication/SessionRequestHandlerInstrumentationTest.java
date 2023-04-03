package com.vaadin.extension.instrumentation.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.vaadin.extension.Constants;
import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.communication.SessionRequestHandler;
import com.vaadin.flow.shared.ApplicationConstants;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class SessionRequestHandlerInstrumentationTest
        extends AbstractInstrumentationTest {
    private VaadinRequest request;
    private SessionRequestHandler sessionRequestHandlerMock;

    @BeforeEach
    public void setup() {
        request = Mockito.mock(VaadinRequest.class);
        sessionRequestHandlerMock = Mockito.mock(SessionRequestHandler.class);
    }

    @Test
    public void handleRequest_createsSpan() {
        SessionRequestHandlerInstrumentation.HandleRequestAdvice.onEnter(
                request, sessionRequestHandlerMock, "handleRequest",
                null, null);
        SessionRequestHandlerInstrumentation.HandleRequestAdvice.onExit(null,
                true, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("SessionRequestHandler.handleRequest", span.getName());
    }

    @Test
    public void handleRequest_observabilityRequest_noSpanCreated() {
        when(request.getParameter(ApplicationConstants.REQUEST_TYPE_PARAMETER))
                .thenReturn(Constants.REQUEST_TYPE_OBSERVABILITY);

        SessionRequestHandlerInstrumentation.HandleRequestAdvice.onEnter(
                request, sessionRequestHandlerMock, "handleRequest",
                null, null);
        SessionRequestHandlerInstrumentation.HandleRequestAdvice.onExit(null,
                true, null, null);

        assertEquals(0, getExportedSpanCount(),
                "No span should be made for observability");
    }

    @Test
    public void handleRequestWithException_setsErrorStatus() {
        SessionRequestHandlerInstrumentation.HandleRequestAdvice.onEnter(
                request, sessionRequestHandlerMock, "handleRequest",
                null, null);
        Exception exception = new RuntimeException("test error");
        SessionRequestHandlerInstrumentation.HandleRequestAdvice
                .onExit(exception, true, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertSpanHasException(span, exception);
    }
}
