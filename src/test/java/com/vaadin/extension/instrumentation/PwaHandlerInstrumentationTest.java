package com.vaadin.extension.instrumentation;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

import com.vaadin.flow.server.VaadinRequest;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;

class PwaHandlerInstrumentationTest extends AbstractInstrumentationTest {

    @Test
    public void handleRequest_requestHandled_createsSpan() {
        VaadinRequest request = Mockito.mock(VaadinRequest.class);
        Mockito.when(request.getPathInfo())
                .thenReturn("/sw-runtime-resources-precache.js");
        Instant startTimestamp = Instant.ofEpochSecond(123);

        PwaHandlerInstrumentation.HandleRequestAdvice.onEnter(null);
        PwaHandlerInstrumentation.HandleRequestAdvice.onExit(null, request,
                true, startTimestamp);

        SpanData span = getExportedSpan(0);
        assertEquals("Load PWA Resource", span.getName());

        long expectedEpochNanos = SECONDS.toNanos(
                startTimestamp.getEpochSecond()) + startTimestamp.getNano();
        assertEquals(expectedEpochNanos, span.getStartEpochNanos());
    }

    @Test
    public void handleRequest_requestNotHandled_doesNotCreateSpan() {
        VaadinRequest request = Mockito.mock(VaadinRequest.class);
        Mockito.when(request.getPathInfo())
                .thenReturn("/sw-runtime-resources-precache.js");
        Instant startTimestamp = Instant.now();

        PwaHandlerInstrumentation.HandleRequestAdvice.onEnter(null);
        PwaHandlerInstrumentation.HandleRequestAdvice.onExit(null, request,
                false, startTimestamp);

        assertEquals(0, getExportedSpanCount());
    }

    @Test
    public void handleRequest_requestHandled_updatesRootSpan() {
        try (var ignored = withRootContext()) {
            VaadinRequest request = Mockito.mock(VaadinRequest.class);
            Mockito.when(request.getPathInfo())
                    .thenReturn("/sw-runtime-resources-precache.js");
            Instant startTimestamp = Instant.now();

            PwaHandlerInstrumentation.HandleRequestAdvice.onEnter(null);
            PwaHandlerInstrumentation.HandleRequestAdvice.onExit(null, request,
                    true, startTimestamp);
        }

        assertEquals(getExportedSpanCount(), 2);
        SpanData rootSpan = getExportedSpan(1);
        assertEquals("/sw-runtime-resources-precache.js : PWA",
                rootSpan.getName());
    }

    @Test
    public void handleRequest_requestNotHandled_doesNotUpdateRootSpan() {
        try (var ignored = withRootContext()) {
            VaadinRequest request = Mockito.mock(VaadinRequest.class);
            Mockito.when(request.getPathInfo())
                    .thenReturn("/sw-runtime-resources-precache.js");
            Instant startTimestamp = Instant.now();

            PwaHandlerInstrumentation.HandleRequestAdvice.onEnter(null);
            PwaHandlerInstrumentation.HandleRequestAdvice.onExit(null, request,
                    false, startTimestamp);
        }

        assertEquals(getExportedSpanCount(), 1);
        SpanData rootSpan = getExportedSpan(0);
        assertEquals("/", rootSpan.getName());
    }
}