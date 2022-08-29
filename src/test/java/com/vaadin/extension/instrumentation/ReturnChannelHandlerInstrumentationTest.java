package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.*;

import com.vaadin.flow.server.communication.ReturnChannelHandler;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ReturnChannelHandlerInstrumentationTest
        extends AbstractInstrumentationTest {

    ReturnChannelHandler returnChannelHandler;

    @BeforeEach
    public void setup() {
        returnChannelHandler = Mockito.mock(ReturnChannelHandler.class);
    }

    @Test
    public void handleNode_createsSpan() {
        ReturnChannelHandlerInstrumentation.MethodAdvice
                .onEnter(returnChannelHandler, "handleNode", null, null);
        ReturnChannelHandlerInstrumentation.MethodAdvice.onExit(null,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("ReturnChannelHandler.handleNode", span.getName());
    }

    @Test
    public void handleNodeWithException_setsErrorStatus() {
        ReturnChannelHandlerInstrumentation.MethodAdvice
                .onEnter(returnChannelHandler, "handleNode", null, null);
        Exception exception = new RuntimeException("test error");
        ReturnChannelHandlerInstrumentation.MethodAdvice.onExit(exception,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        this.assertSpanHasException(span, exception);
    }
}