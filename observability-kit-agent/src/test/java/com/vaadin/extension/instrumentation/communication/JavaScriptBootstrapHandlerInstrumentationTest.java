package com.vaadin.extension.instrumentation.communication;

import static io.opentelemetry.semconv.HttpAttributes.HTTP_ROUTE;
import static org.junit.jupiter.api.Assertions.*;

import com.vaadin.extension.Constants;
import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.flow.server.VaadinRequest;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JavaScriptBootstrapHandlerInstrumentationTest
        extends AbstractInstrumentationTest {

    private VaadinRequest request;

    @BeforeEach
    public void setup() {
        request = Mockito.mock(VaadinRequest.class);
    }

    @Test
    public void synchronizedHandleRequest_createsSpan() {
        Mockito.when(request.getParameter(Constants.REQUEST_LOCATION_PARAMETER))
                .thenReturn("test-route");

        JavaScriptBootstrapHandlerInstrumentation.SynchronizedHandleRequestAdvice
                .onEnter(request, null, null);
        JavaScriptBootstrapHandlerInstrumentation.SynchronizedHandleRequestAdvice
                .onExit(null, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("JavaScript Bootstrap", span.getName());
    }

    @Test
    public void synchronizedHandleRequest_updatesRootSpan() {
        Mockito.when(request.getParameter(Constants.REQUEST_LOCATION_PARAMETER))
                .thenReturn("test-route");

        try (var ignored = withRootContext()) {
            JavaScriptBootstrapHandlerInstrumentation.SynchronizedHandleRequestAdvice
                    .onEnter(request, null, null);
            JavaScriptBootstrapHandlerInstrumentation.SynchronizedHandleRequestAdvice
                    .onExit(null, getCapturedSpan(0), null);
        }

        SpanData rootSpan = getExportedSpan(1);
        assertEquals("/test-route : JavaScript Bootstrap", rootSpan.getName());
        assertEquals("/test-route",
                rootSpan.getAttributes().get(HTTP_ROUTE));
    }

    @Test
    public void synchronizedHandleRequest_withException_setsErrorStatus() {
        Mockito.when(request.getParameter(Constants.REQUEST_LOCATION_PARAMETER))
                .thenReturn("test-route");

        JavaScriptBootstrapHandlerInstrumentation.SynchronizedHandleRequestAdvice
                .onEnter(request, null, null);
        Exception exception = new RuntimeException("test error");
        JavaScriptBootstrapHandlerInstrumentation.SynchronizedHandleRequestAdvice
                .onExit(exception, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertSpanHasException(span, exception);
    }
}
