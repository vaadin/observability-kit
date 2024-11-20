package com.vaadin.extension.instrumentation.server;

import static com.vaadin.extension.Constants.*;
import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.semconv.HttpAttributes.HTTP_REQUEST_METHOD;
import static io.opentelemetry.semconv.HttpAttributes.HTTP_ROUTE;
import static io.opentelemetry.semconv.ServerAttributes.SERVER_ADDRESS;
import static io.opentelemetry.semconv.UrlAttributes.URL_PATH;
import static io.opentelemetry.semconv.UrlAttributes.URL_QUERY;
import static io.opentelemetry.semconv.UrlAttributes.URL_SCHEME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.vaadin.extension.HttpStatusCode;
import com.vaadin.extension.conf.TraceLevel;
import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.flow.shared.ApplicationConstants;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

class VaadinServletInstrumentationTest extends AbstractInstrumentationTest {

    HttpServletRequest servletRequest;
    HttpServletResponse servletResponse;

    @BeforeEach
    void init() {
        HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getId()).thenReturn("1234");

        servletRequest = Mockito.mock(HttpServletRequest.class);
        Mockito.when(servletRequest.getSession()).thenReturn(session);
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

        // VaadinServletInstrumentation creates a sub-context during its
        // execution, which can not be properly closed with our current test
        // setup. Reset the current context instead, so that they don't
        // interfere with the next test run.
        fixCurrentContext(null);
    }

    @Test
    public void service_createsSpan() {
        VaadinServletInstrumentation.MethodAdvice.onEnter(servletRequest, null,
                null, false);

        VaadinServletInstrumentation.MethodAdvice.onExit(null, servletResponse,
                currentContext(), currentContext().makeCurrent(), true);

        SpanData span = getExportedSpan(0);
        assertEquals("/route", span.getName());
        assertEquals(SpanKind.SERVER, span.getKind());

        assertEquals("https",
                span.getAttributes().get(URL_SCHEME));
        assertEquals("POST",
                span.getAttributes().get(HTTP_REQUEST_METHOD));
        assertEquals("example.com",
                span.getAttributes().get(SERVER_ADDRESS));
        assertEquals("/app/route",
                span.getAttributes().get(URL_PATH));
        assertEquals("foo=bar",
            span.getAttributes().get(URL_QUERY));
        assertEquals("/route",
                span.getAttributes().get(HTTP_ROUTE));
    }

    @Test
    public void service_existingRootSpan_doesNotCreateSpan() {
        try (var ignored = withRootContext()) {
            VaadinServletInstrumentation.MethodAdvice.onEnter(servletRequest,
                    null, null, false);
            VaadinServletInstrumentation.MethodAdvice.onExit(null,
                    servletResponse, currentContext(),
                    currentContext().makeCurrent(), true);
        }

        assertEquals(1, getExportedSpanCount());
    }

    @Test
    public void service_enhancesRootSpan() {
        try (var ignored = withRootContext()) {
            VaadinServletInstrumentation.MethodAdvice.onEnter(servletRequest,
                    null, null, false);
            VaadinServletInstrumentation.MethodAdvice.onExit(null,
                    servletResponse, currentContext(),
                    currentContext().makeCurrent(), false);
        }

        assertEquals(1, getExportedSpanCount());

        SpanData span = getExportedSpan(0);
        assertEquals("1234",
                span.getAttributes().get(AttributeKey.stringKey(SESSION_ID)));
        assertEquals("uidl",
                span.getAttributes().get(AttributeKey.stringKey(REQUEST_TYPE)));
        assertNotNull(
                span.getAttributes().get(AttributeKey.stringKey(FLOW_VERSION)),
                "Flow version should be added as attribute");
    }

    @Test
    public void heartbeatRequest_byDefaultNoSpan() {
        Mockito.when(servletRequest
                .getParameter(ApplicationConstants.REQUEST_TYPE_PARAMETER))
                .thenReturn(REQUEST_TYPE_HEARTBEAT);

        VaadinServletInstrumentation.MethodAdvice.onEnter(servletRequest, null,
                null, false);
        VaadinServletInstrumentation.MethodAdvice.onExit(null, servletResponse,
                currentContext(), currentContext().makeCurrent(), false);

        assertEquals(0, getExportedSpanCount(),
                "No span should be made for heartbeat");
    }

    @Test
    public void heartbeatRequest_maxTrace_spanCreated() {
        Mockito.when(servletRequest
                .getParameter(ApplicationConstants.REQUEST_TYPE_PARAMETER))
                .thenReturn(REQUEST_TYPE_HEARTBEAT);
        configureTraceLevel(TraceLevel.MAXIMUM);

        VaadinServletInstrumentation.MethodAdvice.onEnter(servletRequest, null,
                null, false);
        VaadinServletInstrumentation.MethodAdvice.onExit(null, servletResponse,
                currentContext(), currentContext().makeCurrent(), true);

        assertEquals(1, getExportedSpanCount(),
                "Maximum trace should generate heartbeat");
    }

    @Test
    public void observabilityRequest_noSpanCreated() {
        Mockito.when(servletRequest
                        .getParameter(ApplicationConstants.REQUEST_TYPE_PARAMETER))
                .thenReturn(REQUEST_TYPE_OBSERVABILITY);

        VaadinServletInstrumentation.MethodAdvice.onEnter(servletRequest, null,
                null, false);
        VaadinServletInstrumentation.MethodAdvice.onExit(null, servletResponse,
                currentContext(), currentContext().makeCurrent(), false);

        assertEquals(0, getExportedSpanCount(),
                "No span should be made for observability");
    }

    @Test
    public void service_notFound_setsErrorStatus() {
        Mockito.when(servletResponse.getStatus())
                .thenReturn(HttpStatusCode.NOT_FOUND.getCode());
        VaadinServletInstrumentation.MethodAdvice.onEnter(servletRequest, null,
                null, false);
        VaadinServletInstrumentation.MethodAdvice.onExit(null, servletResponse,
                currentContext(), currentContext().makeCurrent(), true);

        SpanData span = getExportedSpan(0);
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals("Request was not handled",
                span.getStatus().getDescription());
    }

    @Test
    public void service_throwsException_setsErrorStatus() {
        VaadinServletInstrumentation.MethodAdvice.onEnter(servletRequest, null,
                null, false);
        VaadinServletInstrumentation.MethodAdvice.onExit(
                new IllegalStateException("exception"), servletResponse,
                currentContext(), currentContext().makeCurrent(), true);

        SpanData span = getExportedSpan(0);
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
    }
}
