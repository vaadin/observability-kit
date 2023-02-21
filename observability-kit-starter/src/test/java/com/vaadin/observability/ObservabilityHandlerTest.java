package com.vaadin.observability;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.shared.ApplicationConstants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class ObservabilityHandlerTest {
    @Test
    public void ensureInstalled_handlerIsInstalled() {
        UI ui = mock(UI.class);
        VaadinSession session = mock(VaadinSession.class);
        when(ui.getSession()).thenReturn(session);

        ObservabilityHandler.ensureInstalled(ui);

        verify(session).addRequestHandler(any());
    }

    @Test
    public void ensureInstalled_handlerExists_returnsExistingHandler() {
        UI ui = mock(UI.class);
        VaadinSession session = mock(VaadinSession.class);
        when(ui.getSession()).thenReturn(session);

        ObservabilityHandler handler = ObservabilityHandler.ensureInstalled(ui);

        verify(session).addRequestHandler(any());

        ObservabilityHandler handler1 = ObservabilityHandler
                .ensureInstalled(ui);

        verifyNoMoreInteractions(session);
        assertEquals(handler, handler1);
    }

    @Test
    public void canHandleRequest_invalidPath_returnsFalse() {
        VaadinRequest request = mock(VaadinRequest.class);
        when(request.getPathInfo()).thenReturn("");

        ObservabilityHandler handler = new ObservabilityHandler();
        assertFalse(handler.canHandleRequest(request));
    }

    @Test
    public void canHandleRequest_invalidRequestType_returnsFalse() {
        VaadinRequest request = mock(VaadinRequest.class);
        when(request.getPathInfo()).thenReturn("/");
        when(request.getParameter(ApplicationConstants.REQUEST_TYPE_PARAMETER))
                .thenReturn("beacon");

        ObservabilityHandler handler = new ObservabilityHandler();
        assertFalse(handler.canHandleRequest(request));
    }

    @Test
    public void canHandleRequest_invalidId_returnsFalse() {
        VaadinRequest request = mock(VaadinRequest.class);
        when(request.getPathInfo()).thenReturn("/");
        when(request.getParameter(ApplicationConstants.REQUEST_TYPE_PARAMETER))
                .thenReturn("o11y");
        when(request.getParameter("id"))
                .thenReturn(UUID.randomUUID().toString());

        ObservabilityHandler handler = new ObservabilityHandler();
        assertFalse(handler.canHandleRequest(request));
    }

    @Test
    public void canHandleRequest_returnsTrue() {
        ObservabilityHandler handler = new ObservabilityHandler();

        VaadinRequest request = mock(VaadinRequest.class);
        when(request.getPathInfo()).thenReturn("/");
        when(request.getParameter(ApplicationConstants.REQUEST_TYPE_PARAMETER))
                .thenReturn("o11y");
        when(request.getParameter("id")).thenReturn(handler.getId());

        assertTrue(handler.canHandleRequest(request));
    }

    @Test
    public void synchronizedHandleRequest_invalidMethod_methodNotAllowed() {
        VaadinSession session = mock(VaadinSession.class);
        VaadinRequest request = mock(VaadinRequest.class);
        when(request.getMethod()).thenReturn("GET");
        VaadinResponse response = mock(VaadinResponse.class);

        ObservabilityHandler handler = new ObservabilityHandler();
        boolean handled = handler.synchronizedHandleRequest(session, request,
                response);

        assertTrue(handled);
        verify(response).setStatus(405);
    }

    @Test
    public void synchronizedHandleRequest_invalidContentType_unsupportedType() {
        VaadinSession session = mock(VaadinSession.class);
        VaadinRequest request = mock(VaadinRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentType()).thenReturn("text/html");
        VaadinResponse response = mock(VaadinResponse.class);

        ObservabilityHandler handler = new ObservabilityHandler();
        boolean handled = handler.synchronizedHandleRequest(session, request,
                response);

        assertTrue(handled);
        verify(response).setStatus(415);
    }

    @Test
    public void synchronizedHandleRequest_invalidBody_badRequest() {
        VaadinSession session = mock(VaadinSession.class);
        VaadinRequest request = mock(VaadinRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentType()).thenReturn("application/json");
        try {
            InputStream targetStream = new ByteArrayInputStream(
                    "body".getBytes());
            when(request.getInputStream()).thenReturn(targetStream);
        } catch (IOException e) {
            fail(e);
        }
        VaadinResponse response = mock(VaadinResponse.class);

        ObservabilityHandler handler = new ObservabilityHandler();
        boolean handled = handler.synchronizedHandleRequest(session, request,
                response);

        assertTrue(handled);
        verify(response).setStatus(400);
    }

    @Test
    public void synchronizedHandleRequest_invalidJson_badRequest() {
        VaadinSession session = mock(VaadinSession.class);
        VaadinRequest request = mock(VaadinRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentType()).thenReturn("application/json");
        try {
            InputStream targetStream = new ByteArrayInputStream(
                    "{}".getBytes());
            when(request.getInputStream()).thenReturn(targetStream);
        } catch (IOException e) {
            fail(e);
        }
        VaadinResponse response = mock(VaadinResponse.class);

        ObservabilityHandler handler = new ObservabilityHandler();
        boolean handled = handler.synchronizedHandleRequest(session, request,
                response);

        assertTrue(handled);
        verify(response).setStatus(400);
    }

    @Test
    public void synchronizedHandleRequest_tracesHandled() {
        VaadinSession session = mock(VaadinSession.class);
        VaadinRequest request = mock(VaadinRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentType()).thenReturn("application/json");
        try {
            // @formatter:off
            InputStream targetStream = new ByteArrayInputStream("{\"resourceSpans\":[{\"resource\":{\"attributes\":[{\"key\":\"service.name\",\"value\":{\"stringValue\":\"unknown_service\"}},{\"key\":\"telemetry.sdk.language\",\"value\":{\"stringValue\":\"webjs\"}},{\"key\":\"telemetry.sdk.name\",\"value\":{\"stringValue\":\"opentelemetry\"}},{\"key\":\"telemetry.sdk.version\",\"value\":{\"stringValue\":\"1.9.0\"}}],\"droppedAttributesCount\":0},\"scopeSpans\":[{\"scope\":{\"name\":\"@opentelemetry/instrumentation-document-load\",\"version\":\"0.31.0\"},\"spans\":[{\"traceId\":\"b7e726b6155ac52912322123d2f31a2c\",\"spanId\":\"67279b5c43e6874c\",\"name\":\"documentLoad\",\"kind\":1,\"startTimeUnixNano\":1674542404352000000,\"endTimeUnixNano\":1674542405301000200,\"attributes\":[{\"key\":\"component\",\"value\":{\"stringValue\":\"document-load\"}},{\"key\":\"http.url\",\"value\":{\"stringValue\":\"http://localhost:8080/login\"}},{\"key\":\"http.user_agent\",\"value\":{\"stringValue\":\"Mozilla/5.0(WindowsNT10.0;Win64;x64)AppleWebKit/537.36(KHTML,likeGecko)Chrome/109.0.0.0Safari/537.36\"}}],\"droppedAttributesCount\":0,\"events\":[{\"attributes\":[],\"name\":\"fetchStart\",\"timeUnixNano\":1674542404352000000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"unloadEventStart\",\"timeUnixNano\":1674542404385100000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"unloadEventEnd\",\"timeUnixNano\":1674542404385700000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"domInteractive\",\"timeUnixNano\":1674542404468400000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"domContentLoadedEventStart\",\"timeUnixNano\":1674542405284400000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"domContentLoadedEventEnd\",\"timeUnixNano\":1674542405287600000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"domComplete\",\"timeUnixNano\":1674542405293900000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"loadEventStart\",\"timeUnixNano\":1674542405300900000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"loadEventEnd\",\"timeUnixNano\":1674542405301000200,\"droppedAttributesCount\":0}],\"droppedEventsCount\":0,\"status\":{\"code\":0},\"links\":[],\"droppedLinksCount\":0}]}]}]}".getBytes());
            // @formatter:on
            when(request.getInputStream()).thenReturn(targetStream);
        } catch (IOException e) {
            fail(e);
        }
        VaadinResponse response = mock(VaadinResponse.class);

        ObservabilityHandler handler = new ObservabilityHandler();
        boolean handled = handler.synchronizedHandleRequest(session, request,
                response);

        assertTrue(handled);
        verify(response).setStatus(200);
    }
}
