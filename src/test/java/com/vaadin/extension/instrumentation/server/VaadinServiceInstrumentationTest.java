package com.vaadin.extension.instrumentation.server;

import static com.vaadin.extension.Constants.HTTP_TARGET;
import static com.vaadin.extension.Constants.REQUEST_TYPE;
import static com.vaadin.extension.Constants.SESSION_ID;
import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static org.junit.jupiter.api.Assertions.*;

import com.vaadin.extension.conf.TraceLevel;
import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.flow.server.HandlerHelper;
import com.vaadin.flow.server.HttpStatusCode;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinServletResponse;
import com.vaadin.flow.server.WrappedSession;
import com.vaadin.flow.shared.ApplicationConstants;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VaadinServiceInstrumentationTest extends AbstractInstrumentationTest {

    VaadinRequest vaadinRequest;
    VaadinServletResponse vaadinResponse;

    @BeforeEach
    void init() {
        vaadinRequest = Mockito.mock(VaadinRequest.class);
        WrappedSession session = Mockito.mock(WrappedSession.class);
        Mockito.when(vaadinRequest.getWrappedSession()).thenReturn(session);
        Mockito.when(session.getId()).thenReturn("1234");
        Mockito.when(vaadinRequest.getPathInfo()).thenReturn("/");
        Mockito.when(vaadinRequest
                .getParameter(ApplicationConstants.REQUEST_TYPE_PARAMETER))
                .thenReturn("uidl");

        vaadinResponse = Mockito.mock(VaadinServletResponse.class);
        Mockito.when(vaadinResponse.getStatus()).thenReturn(200);
    }

    @Test
    public void handleRequest_createsSpan() {
        VaadinServiceInstrumentation.MethodAdvice.onEnter(vaadinRequest, null,
                null);
        VaadinServiceInstrumentation.MethodAdvice.onExit(null, vaadinResponse,
                currentContext(), currentContext().makeCurrent());

        SpanData span = getExportedSpan(0);
        assertEquals("Request handle", span.getName());

        assertEquals("1234",
                span.getAttributes().get(AttributeKey.stringKey(SESSION_ID)));
        assertEquals("/",
                span.getAttributes().get(AttributeKey.stringKey(HTTP_TARGET)));
        assertEquals("uidl",
                span.getAttributes().get(AttributeKey.stringKey(REQUEST_TYPE)));
    }

    @Test
    public void heartbeatRequest_byDefaultNoSpan() {
        Mockito.when(vaadinRequest
                .getParameter(ApplicationConstants.REQUEST_TYPE_PARAMETER))
                .thenReturn(
                        HandlerHelper.RequestType.HEARTBEAT.getIdentifier());

        VaadinServiceInstrumentation.MethodAdvice.onEnter(vaadinRequest, null,
                null);
        VaadinServiceInstrumentation.MethodAdvice.onExit(null, vaadinResponse,
                currentContext(), currentContext().makeCurrent());

        assertEquals(0, getExportedSpanCount(),
                "No span should be made for heartbeat");
    }

    @Test
    public void heartbeatRequest_maxTrace_spanCreated() {
        Mockito.when(vaadinRequest
                .getParameter(ApplicationConstants.REQUEST_TYPE_PARAMETER))
                .thenReturn(
                        HandlerHelper.RequestType.HEARTBEAT.getIdentifier());
        configureTraceLevel(TraceLevel.MAXIMUM);

        VaadinServiceInstrumentation.MethodAdvice.onEnter(vaadinRequest, null,
                null);
        VaadinServiceInstrumentation.MethodAdvice.onExit(null, vaadinResponse,
                currentContext(), currentContext().makeCurrent());

        assertEquals(1, getExportedSpanCount(),
                "Maximum trace should generate heartbeat");
    }

    @Test
    public void handleRequestWithNotFound_setsErrorStatus() {
        Mockito.when(vaadinResponse.getStatus())
                .thenReturn(HttpStatusCode.NOT_FOUND.getCode());
        VaadinServiceInstrumentation.MethodAdvice.onEnter(vaadinRequest, null,
                null);
        VaadinServiceInstrumentation.MethodAdvice.onExit(null, vaadinResponse,
                currentContext(), currentContext().makeCurrent());

        SpanData span = getExportedSpan(0);
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals("Request was not handled",
                span.getStatus().getDescription());
    }

    @Test
    public void handleRequestWithException_setsErrorStatus() {
        VaadinServiceInstrumentation.MethodAdvice.onEnter(vaadinRequest, null,
                null);
        VaadinServiceInstrumentation.MethodAdvice.onExit(
                new IllegalStateException("exception"), vaadinResponse,
                currentContext(), currentContext().makeCurrent());

        SpanData span = getExportedSpan(0);
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
    }
}