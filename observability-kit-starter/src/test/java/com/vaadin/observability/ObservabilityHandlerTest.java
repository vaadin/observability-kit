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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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

        verify(session).addRequestHandler(any(ObservabilityHandler.class));
    }

    @Test
    public void ensureInstalled_handlerExists_returnsExistingHandler() {
        UI ui = mock(UI.class);
        VaadinSession session = mock(VaadinSession.class);
        when(ui.getSession()).thenReturn(session);

        ObservabilityHandler handler = ObservabilityHandler.ensureInstalled(ui);

        verify(session).addRequestHandler(any(ObservabilityHandler.class));

        ObservabilityHandler handler1 = ObservabilityHandler
                .ensureInstalled(ui);

        verifyNoMoreInteractions(session);
        assertSame(handler, handler1);
    }

    @Test
    public void canHandleRequest_emptyPath_returnsFalse() {
        VaadinRequest request = mock(VaadinRequest.class);
        when(request.getPathInfo()).thenReturn("");

        ObservabilityHandler handler = new ObservabilityHandler();
        assertFalse(handler.canHandleRequest(request));
    }

    @Test
    public void canHandleRequest_invalidPath_returnsFalse() {
        VaadinRequest request = mock(VaadinRequest.class);
        when(request.getPathInfo()).thenReturn("/foo");

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
            InputStream targetStream = new ByteArrayInputStream("""
                    {
                        "resourceSpans": [
                            {
                                "resource": {
                                    "attributes": [ ],
                                    "droppedAttributesCount": 0
                                },
                                "scopeSpans": [
                                    {
                                        "scope":
                                            {
                                                "name": "example-basic-tracer-node",
                                                "version": "1.0"
                                             },
                                        "spans": [
                                            {
                                                "traceId": "b7e726b6155ac52912322123d2f31a2c",
                                                "spanId": "67279b5c43e6874c",
                                                "name": "documentLoad",
                                                "kind": 1,
                                                "startTimeUnixNano": 1674542404352000000,
                                                "endTimeUnixNano": 1674542405301000200,
                                                "attributes": [
                                                    {
                                                        "key": "stringAttribute",
                                                        "value": { "stringValue": "some text" }
                                                    },
                                                    {
                                                        "key": "intAttribute",
                                                        "value": { "intValue": 123 }
                                                    },
                                                    {
                                                        "key": "doubleAttribute",
                                                        "value": { "doubleValue": 12.3 }
                                                    },
                                                    {
                                                        "key": "booleanAttribute",
                                                        "value": { "booleanValue": true }
                                                    }
                                                 ],
                                                "droppedAttributesCount": 0,
                                                "events": [],
                                                "droppedEventsCount": 0,
                                                "status": { "code": 0 },
                                                "links": [],
                                                "droppedLinksCount": 0
                                            }
                                        ]
                                    }
                                ]
                            }
                        ]
                    }""".getBytes());
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
