package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;

class PushAtmosphereHandlerInstrumentationTest
        extends AbstractInstrumentationTest {

    @Test
    public void onMessage_createsSpan() {
        PushAtmosphereHandlerInstrumentation.MessageAdvice.onEnter("onMessage",
                null, null);
        PushAtmosphereHandlerInstrumentation.MessageAdvice.onExit(null,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Push : onMessage", span.getName());
    }

    @Test
    public void onRequest_createsSpan() {
        PushAtmosphereHandlerInstrumentation.MessageAdvice.onEnter("onRequest",
                null, null);
        PushAtmosphereHandlerInstrumentation.MessageAdvice.onExit(null,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Push : onRequest", span.getName());
    }

    @Test
    public void handleWithException_setsErrorStatus() {
        PushAtmosphereHandlerInstrumentation.MessageAdvice.onEnter("handleNode",
                null, null);
        Exception exception = new RuntimeException("test error");
        PushAtmosphereHandlerInstrumentation.MessageAdvice.onExit(exception,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        this.assertSpanHasException(span, exception);
    }
}