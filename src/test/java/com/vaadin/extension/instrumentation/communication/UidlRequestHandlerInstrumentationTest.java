package com.vaadin.extension.instrumentation.communication;

import static org.junit.jupiter.api.Assertions.*;

import com.vaadin.extension.conf.TraceLevel;
import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;

class UidlRequestHandlerInstrumentationTest
        extends AbstractInstrumentationTest {

    @Test
    public void synchronizedHandleRequest_createsSpan() {
        UidlRequestHandlerInstrumentation.SynchronizedHandleRequestAdvice
                .onEnter(null, null);
        UidlRequestHandlerInstrumentation.SynchronizedHandleRequestAdvice
                .onExit(null, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Handle Client Request", span.getName());
    }

    @Test
    public void synchronizedHandleRequest_traceLevels() {
        configureTraceLevel(TraceLevel.MINIMUM);
        UidlRequestHandlerInstrumentation.SynchronizedHandleRequestAdvice
                .onEnter(null, null);
        assertEquals(0, getCapturedSpanCount());

        configureTraceLevel(TraceLevel.DEFAULT);
        resetSpans();
        UidlRequestHandlerInstrumentation.SynchronizedHandleRequestAdvice
                .onEnter(null, null);
        assertEquals(1, getCapturedSpanCount());

        configureTraceLevel(TraceLevel.MAXIMUM);
        resetSpans();
        UidlRequestHandlerInstrumentation.SynchronizedHandleRequestAdvice
                .onEnter(null, null);
        assertEquals(1, getCapturedSpanCount());
    }
}
