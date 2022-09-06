package com.vaadin.extension.instrumentation.communication.rpc;

import static org.junit.jupiter.api.Assertions.*;

import com.vaadin.extension.conf.TraceLevel;
import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;

class ReturnChannelHandlerInstrumentationTest
        extends AbstractInstrumentationTest {

    @Test
    public void handleNode_createsSpan() {
        ReturnChannelHandlerInstrumentation.MethodAdvice.onEnter(null, null);
        ReturnChannelHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Handle return channel", span.getName());
    }

    @Test
    public void handleNodeWithException_setsErrorStatus() {
        ReturnChannelHandlerInstrumentation.MethodAdvice.onEnter(null, null);
        Exception exception = new RuntimeException("test error");
        ReturnChannelHandlerInstrumentation.MethodAdvice.onExit(exception,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        this.assertSpanHasException(span, exception);
    }

    @Test
    public void handleNode_respectsTraceLevel() {
        configureTraceLevel(TraceLevel.MINIMUM);
        ReturnChannelHandlerInstrumentation.MethodAdvice.onEnter(null, null);
        ReturnChannelHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpanOrNull(0), null);

        assertEquals(0, getExportedSpanCount());

        configureTraceLevel(TraceLevel.DEFAULT);
        resetSpans();
        ReturnChannelHandlerInstrumentation.MethodAdvice.onEnter(null, null);
        ReturnChannelHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpanOrNull(0), null);

        assertEquals(1, getExportedSpanCount());

        configureTraceLevel(TraceLevel.MAXIMUM);
        resetSpans();
        ReturnChannelHandlerInstrumentation.MethodAdvice.onEnter(null, null);
        ReturnChannelHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpanOrNull(0), null);

        assertEquals(1, getExportedSpanCount());
    }
}