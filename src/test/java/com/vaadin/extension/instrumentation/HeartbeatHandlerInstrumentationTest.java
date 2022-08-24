package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.*;

import com.vaadin.flow.server.VaadinRequest;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class HeartbeatHandlerInstrumentationTest extends AbstractInstrumentationTest {
    @Test
    public void synchronizedHandleRequest_createsSpan() {
        VaadinRequest request = Mockito.mock(VaadinRequest.class);

        HeartbeatHandlerInstrumentation.SynchronizedHandleRequestAdvice
                .onEnter(getMockSession(), request, null, null);
        HeartbeatHandlerInstrumentation.SynchronizedHandleRequestAdvice
                .onExit(null, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Heartbeat", span.getName());
    }

    @Test
    public void synchronizedHandleRequest_updatesRootSpan() {
        try (var ignored = withRootContext()) {
            VaadinRequest request = Mockito.mock(VaadinRequest.class);

            HeartbeatHandlerInstrumentation.SynchronizedHandleRequestAdvice
                    .onEnter(getMockSession(), request, null, null);
            HeartbeatHandlerInstrumentation.SynchronizedHandleRequestAdvice
                    .onExit(null, getCapturedSpan(0), null);
        }

        SpanData rootSpan = getExportedSpan(1);
        assertEquals("/test-route : Heartbeat", rootSpan.getName());
    }

}