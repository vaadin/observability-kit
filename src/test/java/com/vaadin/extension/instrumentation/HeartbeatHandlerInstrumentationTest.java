package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;

class HeartbeatHandlerInstrumentationTest extends AbstractInstrumentationTest {
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
