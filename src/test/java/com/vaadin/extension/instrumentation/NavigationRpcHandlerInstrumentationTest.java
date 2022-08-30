package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.*;

import com.vaadin.extension.conf.TraceLevel;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;

class NavigationRpcHandlerInstrumentationTest
        extends AbstractInstrumentationTest {

    @Test
    public void handle_createsSpan() {
        configureTraceLevel(TraceLevel.MAXIMUM);

        NavigationRpcHandlerInstrumentation.MethodAdvice.onEnter(null, null);
        NavigationRpcHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Handle navigation", span.getName());
    }

    @Test
    public void handle_respectsTraceLevel() {
        configureTraceLevel(TraceLevel.MINIMUM);
        NavigationRpcHandlerInstrumentation.MethodAdvice.onEnter(null, null);
        NavigationRpcHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpanOrNull(0), null);

        assertEquals(0, getExportedSpanCount());

        configureTraceLevel(TraceLevel.DEFAULT);
        NavigationRpcHandlerInstrumentation.MethodAdvice.onEnter(null, null);
        NavigationRpcHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpanOrNull(0), null);

        assertEquals(0, getExportedSpanCount());

        configureTraceLevel(TraceLevel.MAXIMUM);
        NavigationRpcHandlerInstrumentation.MethodAdvice.onEnter(null, null);
        NavigationRpcHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpanOrNull(0), null);

        assertEquals(1, getExportedSpanCount());

    }
}