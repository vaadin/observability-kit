package com.vaadin.extension.instrumentation;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.flow.server.VaadinRequest;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;

class WebComponentProviderInstrumentationTest
        extends AbstractInstrumentationTest {

    @Test
    public void handleRequest_requestHandled_createsSpan() {
        VaadinRequest request = Mockito.mock(VaadinRequest.class);
        Mockito.when(request.getPathInfo())
                .thenReturn("/vaadin/web-component/fire-event.js");
        Instant startTimestamp = Instant.ofEpochSecond(123);

        WebComponentProviderInstrumentation.SynchronizedHandleRequestAdvice
                .onEnter(null);
        WebComponentProviderInstrumentation.SynchronizedHandleRequestAdvice
                .onExit(null, request, true, startTimestamp);

        SpanData span = getExportedSpan(0);
        assertEquals("WebComponentProvider : Load Resource", span.getName());

        long expectedEpochNanos = SECONDS.toNanos(
                startTimestamp.getEpochSecond()) + startTimestamp.getNano();
        assertEquals(expectedEpochNanos, span.getStartEpochNanos());
    }

    @Test
    public void providerCalled_rootSpanUpdated() {
        VaadinRequest request = Mockito.mock(VaadinRequest.class);
        Mockito.when(request.getPathInfo())
                .thenReturn("/vaadin/web-component/fire-event.js");
        Instant startTimestamp = Instant.ofEpochSecond(123);

        try (var ignored = withRootContext()) {
            WebComponentProviderInstrumentation.SynchronizedHandleRequestAdvice
                    .onEnter(null);
            WebComponentProviderInstrumentation.SynchronizedHandleRequestAdvice
                    .onExit(null, request, true, startTimestamp);
        }

        assertEquals(getExportedSpanCount(), 2);
        SpanData rootSpan = getExportedSpan(1);
        assertEquals(request.getPathInfo() + " : WebComponentProvider",
                rootSpan.getName());
        SpanData span = getExportedSpan(0);
        assertEquals("WebComponentProvider : Load Resource", span.getName());

    }
}