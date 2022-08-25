package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.*;

import com.vaadin.flow.server.communication.UidlRequestHandler;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class UidlRequestHandlerInstrumentationTest
        extends AbstractInstrumentationTest {

    @Test
    public void synchronizedHandleRequest_createsSpan() {
        UidlRequestHandler uidlRequestHandler = Mockito
                .mock(UidlRequestHandler.class);

        UidlRequestHandlerInstrumentation.SynchronizedHandleRequestAdvice
                .onEnter(uidlRequestHandler, "synchronizedHandleRequest", null,
                        null);
        UidlRequestHandlerInstrumentation.SynchronizedHandleRequestAdvice
                .onExit(null, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("UidlRequestHandler.synchronizedHandleRequest",
                span.getName());
    }
}