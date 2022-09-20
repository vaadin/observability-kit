package com.vaadin.extension.instrumentation.server;

import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.extension.conf.TraceLevel;
import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.flow.server.HandlerHelper;
import com.vaadin.flow.server.HttpStatusCode;
import com.vaadin.flow.shared.ApplicationConstants;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class VaadinServletInstrumentationTest extends AbstractInstrumentationTest {

    HttpServletRequest servletRequest;
    HttpServletResponse servletResponse;

    @BeforeEach
    void init() {
        servletRequest = Mockito.mock(HttpServletRequest.class);
        Mockito.when(servletRequest.getScheme()).thenReturn("https");
        Mockito.when(servletRequest.getMethod()).thenReturn("POST");
        Mockito.when(servletRequest.getRemoteHost()).thenReturn("example.com");
        Mockito.when(servletRequest.getContextPath()).thenReturn("/app");
        Mockito.when(servletRequest.getPathInfo()).thenReturn("/route");
        Mockito.when(servletRequest.getQueryString()).thenReturn("foo=bar");
        Mockito.when(servletRequest
                .getParameter(ApplicationConstants.REQUEST_TYPE_PARAMETER))
                .thenReturn("uidl");

        servletResponse = Mockito.mock(HttpServletResponse.class);
        Mockito.when(servletResponse.getStatus()).thenReturn(200);
    }

    @Test
    public void service_createsSpan() {
        VaadinServletInstrumentation.MethodAdvice.onEnter(servletRequest, null,
                null);
        VaadinServletInstrumentation.MethodAdvice.onExit(null, servletResponse,
                currentContext(), currentContext().makeCurrent());

        SpanData span = getExportedSpan(0);
        assertEquals("/route", span.getName());
        assertEquals(SpanKind.SERVER, span.getKind());

        assertEquals("https",
                span.getAttributes().get(SemanticAttributes.HTTP_SCHEME));
        assertEquals("POST",
                span.getAttributes().get(SemanticAttributes.HTTP_METHOD));
        assertEquals("example.com",
                span.getAttributes().get(SemanticAttributes.HTTP_HOST));
        assertEquals("/app/route?foo=bar",
                span.getAttributes().get(SemanticAttributes.HTTP_TARGET));
        assertEquals("/route",
                span.getAttributes().get(SemanticAttributes.HTTP_ROUTE));
    }

    @Test
    public void handleRequest_existingRootSpan_doesNotCreateSpan() {
        try (var ignored = withRootContext()) {
            VaadinServletInstrumentation.MethodAdvice.onEnter(servletRequest,
                    null, null);
            VaadinServletInstrumentation.MethodAdvice.onExit(null,
                    servletResponse, currentContext(),
                    currentContext().makeCurrent());
        }

        assertEquals(1, getExportedSpanCount());
    }

    @Test
    public void heartbeatRequest_byDefaultNoSpan() {
        Mockito.when(servletRequest
                .getParameter(ApplicationConstants.REQUEST_TYPE_PARAMETER))
                .thenReturn(
                        HandlerHelper.RequestType.HEARTBEAT.getIdentifier());

        VaadinServletInstrumentation.MethodAdvice.onEnter(servletRequest, null,
                null);
        VaadinServletInstrumentation.MethodAdvice.onExit(null, servletResponse,
                currentContext(), currentContext().makeCurrent());

        assertEquals(0, getExportedSpanCount(),
                "No span should be made for heartbeat");
    }

    @Test
    public void heartbeatRequest_maxTrace_spanCreated() {
        Mockito.when(servletRequest
                .getParameter(ApplicationConstants.REQUEST_TYPE_PARAMETER))
                .thenReturn(
                        HandlerHelper.RequestType.HEARTBEAT.getIdentifier());
        configureTraceLevel(TraceLevel.MAXIMUM);

        VaadinServletInstrumentation.MethodAdvice.onEnter(servletRequest, null,
                null);
        VaadinServletInstrumentation.MethodAdvice.onExit(null, servletResponse,
                currentContext(), currentContext().makeCurrent());

        assertEquals(1, getExportedSpanCount(),
                "Maximum trace should generate heartbeat");
    }

    @Test
    public void handleRequestWithNotFound_setsErrorStatus() {
        Mockito.when(servletResponse.getStatus())
                .thenReturn(HttpStatusCode.NOT_FOUND.getCode());
        VaadinServletInstrumentation.MethodAdvice.onEnter(servletRequest, null,
                null);
        VaadinServletInstrumentation.MethodAdvice.onExit(null, servletResponse,
                currentContext(), currentContext().makeCurrent());

        SpanData span = getExportedSpan(0);
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals("Request was not handled",
                span.getStatus().getDescription());
    }

    @Test
    public void handleRequestWithException_setsErrorStatus() {
        VaadinServletInstrumentation.MethodAdvice.onEnter(servletRequest, null,
                null);
        VaadinServletInstrumentation.MethodAdvice.onExit(
                new IllegalStateException("exception"), servletResponse,
                currentContext(), currentContext().makeCurrent());

        SpanData span = getExportedSpan(0);
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
    }
}