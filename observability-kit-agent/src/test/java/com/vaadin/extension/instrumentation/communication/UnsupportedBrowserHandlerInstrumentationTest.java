package com.vaadin.extension.instrumentation.communication;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.flow.server.UnsupportedBrowserHandler;
import com.vaadin.flow.server.VaadinRequest;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;

class UnsupportedBrowserHandlerInstrumentationTest
        extends AbstractInstrumentationTest {

    @Test
    public void synchronizedHandleRequest_requestHandled_createsSpan() {
        UnsupportedBrowserHandler unsupportedBrowserHandler = Mockito
                .mock(UnsupportedBrowserHandler.class);
        VaadinRequest request = Mockito.mock(VaadinRequest.class);
        Instant startTimestamp = Instant.ofEpochSecond(123);

        UnsupportedBrowserHandlerInstrumentation.SynchronizedHandleRequestAdvice
                .onEnter(null);
        UnsupportedBrowserHandlerInstrumentation.SynchronizedHandleRequestAdvice
                .onExit(unsupportedBrowserHandler, "synchronizedHandleRequest",
                        null, request, true, startTimestamp);

        SpanData span = getExportedSpan(0);
        assertEquals("UnsupportedBrowserHandler.synchronizedHandleRequest",
                span.getName());

        long expectedEpochNanos = SECONDS.toNanos(
                startTimestamp.getEpochSecond()) + startTimestamp.getNano();
        assertEquals(expectedEpochNanos, span.getStartEpochNanos());
    }

    @Test
    public void synchronizedHandleRequest_requestNotHandled_doesNotCreateSpan() {
        UnsupportedBrowserHandler unsupportedBrowserHandler = Mockito
                .mock(UnsupportedBrowserHandler.class);
        VaadinRequest request = Mockito.mock(VaadinRequest.class);
        Instant startTimestamp = Instant.now();

        UnsupportedBrowserHandlerInstrumentation.SynchronizedHandleRequestAdvice
                .onEnter(null);
        UnsupportedBrowserHandlerInstrumentation.SynchronizedHandleRequestAdvice
                .onExit(unsupportedBrowserHandler, "synchronizedHandleRequest",
                        null, request, false, startTimestamp);

        assertEquals(0, getExportedSpanCount());
    }
}
