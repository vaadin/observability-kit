package com.vaadin.extension.instrumentation.communication;

import static io.opentelemetry.semconv.HttpAttributes.HTTP_ROUTE;
import static io.opentelemetry.semconv.UrlAttributes.URL_PATH;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.flow.server.VaadinRequest;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;

public class StreamRequestHandlerInstrumentationTest
        extends AbstractInstrumentationTest {
    Instant startTimestamp = Instant.ofEpochSecond(123);

    @Test
    public void handleRequest_createsSpan() {
        final VaadinRequest request = Mockito.mock(VaadinRequest.class);

        final String fileName = "/VAADIN/dynamic/resource/0/aa284e2/file";
        Mockito.when(request.getPathInfo()).thenReturn(fileName);

        StreamRequestHandlerInstrumentation.HandleRequestAdvice.onEnter(null);
        StreamRequestHandlerInstrumentation.HandleRequestAdvice.onExit(null,
                true, request, startTimestamp);

        SpanData span = getExportedSpan(0);
        assertEquals("Handle dynamic file", span.getName());
    }

    @Test
    public void handleRequest_mainSpanIsUpdated() {
        final VaadinRequest request = Mockito.mock(VaadinRequest.class);

        final String fileName = "/dynamic/resource/0/aa284e2/image.png";
        Mockito.when(request.getPathInfo()).thenReturn(fileName);

        try (var ignored = withRootContext()) {
            StreamRequestHandlerInstrumentation.HandleRequestAdvice
                    .onEnter(null);
            StreamRequestHandlerInstrumentation.HandleRequestAdvice.onExit(null,
                    true, request, startTimestamp);
        }

        assertEquals("Handle dynamic file", getExportedSpan(0).getName());
        assertEquals("/dynamic/resource/[ui]/[secret]/image.png",
                getExportedSpan(1).getName());
        assertEquals("/dynamic/resource/[ui]/[secret]/image.png",
                getExportedSpan(1).getAttributes()
                        .get(HTTP_ROUTE));
        assertEquals("/dynamic/resource/0/aa284e2/image.png", getExportedSpan(1)
                .getAttributes().get(URL_PATH));
    }

    @Test
    public void handleRequestWithException_setsErrorStatus() {
        final VaadinRequest request = Mockito.mock(VaadinRequest.class);

        final String fileName = "/VAADIN/dynamic/resource/0/aa284e2/file";
        Mockito.when(request.getPathInfo()).thenReturn(fileName);

        StreamRequestHandlerInstrumentation.HandleRequestAdvice.onEnter(null);
        Exception exception = new RuntimeException("test error");
        StreamRequestHandlerInstrumentation.HandleRequestAdvice
                .onExit(exception, true, request, startTimestamp);

        SpanData span = getExportedSpan(0);
        assertSpanHasException(span, exception);
    }

    @Test
    public void handleRequest_notHandled() {
        final VaadinRequest request = Mockito.mock(VaadinRequest.class);

        final String fileName = "/VAADIN/dynamic/resource/0/aa284e2/file";
        Mockito.when(request.getPathInfo()).thenReturn(fileName);

        StreamRequestHandlerInstrumentation.HandleRequestAdvice.onEnter(null);
        StreamRequestHandlerInstrumentation.HandleRequestAdvice.onExit(null,
                false, request, startTimestamp);

        assertEquals(0, getExportedSpanCount());
    }
}
