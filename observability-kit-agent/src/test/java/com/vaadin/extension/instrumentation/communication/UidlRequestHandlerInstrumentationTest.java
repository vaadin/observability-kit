package com.vaadin.extension.instrumentation.communication;

import static io.opentelemetry.semconv.HttpAttributes.HTTP_ROUTE;
import static io.opentelemetry.semconv.UrlAttributes.URL_PATH;
import static org.junit.jupiter.api.Assertions.*;

import com.vaadin.extension.conf.TraceLevel;
import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.flow.server.VaadinRequest;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class UidlRequestHandlerInstrumentationTest
        extends AbstractInstrumentationTest {

    VaadinRequest request;

    @BeforeEach
    public void setup() {
        request = Mockito.mock(VaadinRequest.class);
    }

    @Test
    public void synchronizedHandleRequest_createsSpan() {
        UidlRequestHandlerInstrumentation.SynchronizedHandleRequestAdvice
                .onEnter(null, null);
        UidlRequestHandlerInstrumentation.SynchronizedHandleRequestAdvice
                .onExit(getMockSession(), request, null, getCapturedSpan(0),
                        null);

        SpanData span = getExportedSpan(0);
        assertEquals("Handle Client Request", span.getName());
    }

    @Test
    public void synchronizedHandleRequest_updatesRootSpan() {
        try (var ignored = withRootContext()) {
            UidlRequestHandlerInstrumentation.SynchronizedHandleRequestAdvice
                    .onEnter(null, null);
            UidlRequestHandlerInstrumentation.SynchronizedHandleRequestAdvice
                    .onExit(getMockSession(), request, null, getCapturedSpan(0),
                            null);
        }

        SpanData rootSpan = getExportedSpan(1);
        assertEquals("/test-route", rootSpan.getName());
        assertEquals("/test-route",
                rootSpan.getAttributes().get(HTTP_ROUTE));
        assertEquals("/test-route",
                rootSpan.getAttributes().get(URL_PATH));
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
