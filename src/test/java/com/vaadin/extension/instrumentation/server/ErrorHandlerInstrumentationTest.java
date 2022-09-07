package com.vaadin.extension.instrumentation.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.flow.server.ErrorEvent;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ErrorHandlerInstrumentationTest extends AbstractInstrumentationTest {

    private IllegalAccessError error = new IllegalAccessError("not allowed");
    private ErrorEvent event;

    @BeforeEach
    void init() {
        event = Mockito.mock(ErrorEvent.class);
        Mockito.when(event.getThrowable()).thenReturn(error);
    }

    @Test
    public void errorThrown_rootSpanExists_rootSpanGetsException() {
        try (var ignored = withRootContext()) {
            ErrorHandlerInstrumentation.ErrorAdvice.onEnter(event, null, null);
        }

        assertEquals(1, getExportedSpanCount());
        SpanData rootSpan = getExportedSpan(0);

        assertEquals(StatusCode.ERROR, rootSpan.getStatus().getStatusCode());
        assertEquals("java.lang.IllegalAccessError: not allowed",
                rootSpan.getStatus().getDescription());
        assertEquals(1, rootSpan.getEvents().size(),
                "Root span should contain the 'exception' event");
        assertEquals("exception", rootSpan.getEvents().get(0).getName());
    }

}