package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.communication.StreamRequestHandler;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class StreamRequestHandlerInstrumentationTest
        extends AbstractInstrumentationTest {
    private StreamRequestHandler streamRequestHandler;

    @BeforeEach
    public void setup() {
        streamRequestHandler = Mockito.mock(StreamRequestHandler.class);
    }

    @Test
    public void handleRequest_createsSpan() {
        final VaadinRequest request = Mockito.mock(VaadinRequest.class);

        final String fileName = "/VAADIN/dynamic/resource/file.png";
        Mockito.when(request.getPathInfo()).thenReturn(fileName);

        StreamRequestHandlerInstrumentation.HandleRequestAdvice
                .onEnter(streamRequestHandler, "handleRequest", request,
                        null, null);
        StreamRequestHandlerInstrumentation.HandleRequestAdvice.onExit(null,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("StreamRequestHandler.handleRequest", span.getName());
    }

    @Test
    public void handleRequestWithException_setsErrorStatus() {
        final VaadinRequest request = Mockito.mock(VaadinRequest.class);

        final String fileName = "/VAADIN/dynamic/resource/file.png";
        Mockito.when(request.getPathInfo()).thenReturn(fileName);

        StreamRequestHandlerInstrumentation.HandleRequestAdvice
                .onEnter(streamRequestHandler, "handleRequest", request,
                        null, null);
        Exception exception = new RuntimeException("test error");
        StreamRequestHandlerInstrumentation.HandleRequestAdvice
                .onExit(exception, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertSpanHasException(span, exception);
    }
}
