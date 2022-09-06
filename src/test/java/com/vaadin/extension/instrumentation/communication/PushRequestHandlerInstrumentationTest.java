package com.vaadin.extension.instrumentation.communication;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.flow.server.VaadinRequest;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;

class PushRequestHandlerInstrumentationTest
        extends AbstractInstrumentationTest {

    @Test
    public void handleRequest_requestHandled_createsSpan() {
        VaadinRequest request = Mockito.mock(VaadinRequest.class);
        Mockito.when(request.getPathInfo()).thenReturn("/");
        Instant startTimestamp = Instant.ofEpochSecond(123);

        PushRequestHandlerInstrumentation.HandleAdvice.onEnter(null);
        PushRequestHandlerInstrumentation.HandleAdvice.onExit(null, request,
                true, startTimestamp);

        SpanData span = getExportedSpan(0);
        assertEquals("PushRequest", span.getName());

        long expectedEpochNanos = SECONDS.toNanos(
                startTimestamp.getEpochSecond()) + startTimestamp.getNano();
        assertEquals(expectedEpochNanos, span.getStartEpochNanos());
    }

    @Test
    public void handleRequest_requestNotHandled_doesNotCreateSpan() {
        VaadinRequest request = Mockito.mock(VaadinRequest.class);
        Mockito.when(request.getPathInfo()).thenReturn("/");
        Instant startTimestamp = Instant.now();

        PushRequestHandlerInstrumentation.HandleAdvice.onEnter(null);
        PushRequestHandlerInstrumentation.HandleAdvice.onExit(null, request,
                false, startTimestamp);

        assertEquals(0, getExportedSpanCount());
    }

    @Test
    public void handleRequest_requestHandled_updatesRootSpan() {
        try (var ignored = withRootContext()) {
            VaadinRequest request = Mockito.mock(VaadinRequest.class);
            Mockito.when(request.getPathInfo()).thenReturn("/");
            Instant startTimestamp = Instant.now();

            PushRequestHandlerInstrumentation.HandleAdvice.onEnter(null);
            PushRequestHandlerInstrumentation.HandleAdvice.onExit(null, request,
                    true, startTimestamp);
        }

        assertEquals(getExportedSpanCount(), 2);
        SpanData rootSpan = getExportedSpan(1);
        assertEquals("/ : Push", rootSpan.getName());
    }

    @Test
    public void handleRequest_requestNotHandled_doesNotUpdateRootSpan() {
        try (var ignored = withRootContext()) {
            VaadinRequest request = Mockito.mock(VaadinRequest.class);
            Mockito.when(request.getPathInfo()).thenReturn("/");
            Instant startTimestamp = Instant.now();

            PushRequestHandlerInstrumentation.HandleAdvice.onEnter(null);
            PushRequestHandlerInstrumentation.HandleAdvice.onExit(null, request,
                    false, startTimestamp);
        }

        assertEquals(getExportedSpanCount(), 1);
        SpanData rootSpan = getExportedSpan(0);
        assertEquals("/", rootSpan.getName());
    }
}