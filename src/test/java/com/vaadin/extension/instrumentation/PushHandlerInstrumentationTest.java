package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;

class PushHandlerInstrumentationTest extends AbstractInstrumentationTest {

    @Test
    public void onMessage_createsSpan() {
        PushHandlerInstrumentation.MessageAdvice.onEnter("onMessage", null,
                null);
        PushHandlerInstrumentation.MessageAdvice.onExit(null,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Push : onMessage", span.getName());
    }

    @Test
    public void onConnect_createsSpan() {
        PushHandlerInstrumentation.MessageAdvice.onEnter("onConnect", null,
                null);
        PushHandlerInstrumentation.MessageAdvice.onExit(null,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Push : onConnect", span.getName());
    }

    @Test
    public void handleWithException_setsErrorStatus() {
        PushHandlerInstrumentation.MessageAdvice.onEnter("handleNode", null,
                null);
        Exception exception = new RuntimeException("test error");
        PushHandlerInstrumentation.MessageAdvice.onExit(exception,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        this.assertSpanHasException(span, exception);
    }
}