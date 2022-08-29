package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.communication.StreamRequestHandler;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;

public class StreamRequestHandlerInstrumentationTest
        extends AbstractInstrumentationTest {
    private StreamRequestHandler streamRequestHandler;
    Instant startTimestamp = Instant.ofEpochSecond(123);

    @BeforeEach
    public void setup() {
        streamRequestHandler = Mockito.mock(StreamRequestHandler.class);
    }

    @Test
    public void handleRequest_createsSpan() {
        final VaadinRequest request = Mockito.mock(VaadinRequest.class);

        final String fileName = "/VAADIN/dynamic/resource/file";
        Mockito.when(request.getPathInfo()).thenReturn(fileName);

        StreamRequestHandlerInstrumentation.HandleRequestAdvice.onEnter(null);
        StreamRequestHandlerInstrumentation.HandleRequestAdvice.onExit(
                streamRequestHandler, "handleRequest", null, true, request,
                startTimestamp);

        SpanData span = getExportedSpan(0);
        assertEquals("StreamRequestHandler.handleRequest", span.getName());
    }

    @Test
    public void handleRequest_mainSpanIsUpdated() {
        final VaadinRequest request = Mockito.mock(VaadinRequest.class);

        final String fileName = "/VAADIN/dynamic/resource/file";
        Mockito.when(request.getPathInfo()).thenReturn(fileName);

        try (var ignored = withRootContext()) {
            StreamRequestHandlerInstrumentation.HandleRequestAdvice
                    .onEnter(null);
            StreamRequestHandlerInstrumentation.HandleRequestAdvice.onExit(
                    streamRequestHandler, "handleRequest", null, true, request,
                    startTimestamp);
        }

        assertEquals("StreamRequestHandler.handleRequest",
                getExportedSpan(0).getName());
        assertEquals(fileName, getExportedSpan(1).getName());
    }

    @Test
    public void handleRequestWithException_setsErrorStatus() {
        final VaadinRequest request = Mockito.mock(VaadinRequest.class);

        final String fileName = "/VAADIN/dynamic/resource/file";
        Mockito.when(request.getPathInfo()).thenReturn(fileName);

        StreamRequestHandlerInstrumentation.HandleRequestAdvice.onEnter(null);
        Exception exception = new RuntimeException("test error");
        StreamRequestHandlerInstrumentation.HandleRequestAdvice.onExit(
                streamRequestHandler, "handleRequest", exception, true, request,
                startTimestamp);

        SpanData span = getExportedSpan(0);
        assertSpanHasException(span, exception);
    }

    @Test
    public void handleRequest_notHandled() {
        final VaadinRequest request = Mockito.mock(VaadinRequest.class);

        final String fileName = "/VAADIN/dynamic/resource/file";
        Mockito.when(request.getPathInfo()).thenReturn(fileName);

        StreamRequestHandlerInstrumentation.HandleRequestAdvice.onEnter(null);
        StreamRequestHandlerInstrumentation.HandleRequestAdvice.onExit(
                streamRequestHandler, "handleRequest", null, false, request,
                startTimestamp);

        assertEquals(0, getExportedSpanCount());
    }
}
