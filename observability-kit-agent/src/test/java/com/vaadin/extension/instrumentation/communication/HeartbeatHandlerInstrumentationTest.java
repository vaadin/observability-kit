package com.vaadin.extension.instrumentation.communication;

import static org.junit.jupiter.api.Assertions.*;

import com.vaadin.extension.conf.TraceLevel;
import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HeartbeatHandlerInstrumentationTest extends AbstractInstrumentationTest {
    @BeforeEach
    void init() {
        configureTraceLevel(TraceLevel.MAXIMUM);
    }

    @Test
    public void synchronizedHandleRequest_createsSpan() {
        HeartbeatHandlerInstrumentation.SynchronizedHandleRequestAdvice
                .onEnter(null, null);
        HeartbeatHandlerInstrumentation.SynchronizedHandleRequestAdvice
                .onExit(null, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Heartbeat", span.getName());
    }

    @Test
    public void synchronizedHandleRequest_updatesRootSpan() {
        try (var ignored = withRootContext()) {
            HeartbeatHandlerInstrumentation.SynchronizedHandleRequestAdvice
                    .onEnter(null, null);
            HeartbeatHandlerInstrumentation.SynchronizedHandleRequestAdvice
                    .onExit(null, getCapturedSpan(0), null);
        }

        SpanData rootSpan = getExportedSpan(1);
        assertEquals("/ : Heartbeat", rootSpan.getName());
    }
}
