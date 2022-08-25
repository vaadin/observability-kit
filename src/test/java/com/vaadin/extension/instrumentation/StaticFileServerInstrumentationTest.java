package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.server.HttpStatusCode;
import com.vaadin.flow.server.StaticFileServer;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class StaticFileServerInstrumentationTest extends AbstractInstrumentationTest {

    private StaticFileServer fileServerInstrumentation;
    TestComponent component;

    @BeforeEach
    public void setup() {
        fileServerInstrumentation = Mockito.mock(StaticFileServer.class);
        component = new TestComponent();
        getMockUI().add(component);
    }

    @Test
    public void staticFileRequest_requestIsServed() {
        final HttpServletRequest request = Mockito
                .mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito
                .mock(HttpServletResponse.class);

        final String fileName = "/VAADIN/static/file.png";
        Mockito.when(request.getPathInfo()).thenReturn(fileName);
        Mockito.when(response.getStatus())
                .thenReturn(HttpStatusCode.BAD_REQUEST.getCode());

        StaticFileServerInstrumentation.HandleRequestAdvice
                .onEnter(fileServerInstrumentation, request, null, null);
        StaticFileServerInstrumentation.HandleRequestAdvice.onExit(null, true,
                request, response, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Static file request: " + fileName, span.getName());
    }

    @Test
    public void staticFileRequest_notModified() {
        final HttpServletRequest request = Mockito
                .mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito
                .mock(HttpServletResponse.class);

        final String fileName = "/VAADIN/static/file.png";
        Mockito.when(request.getPathInfo()).thenReturn(fileName);
        Mockito.when(response.getStatus())
                .thenReturn(HttpStatusCode.NOT_MODIFIED.getCode());

        StaticFileServerInstrumentation.HandleRequestAdvice
                .onEnter(fileServerInstrumentation, request, null, null);
        StaticFileServerInstrumentation.HandleRequestAdvice.onExit(null, true,
                request, response, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Static file request: " + fileName, span.getName());
        assertEquals("Up to date", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.resolution")));
    }

    @Test
    public void staticFileRequest_badRequest() {
        final HttpServletRequest request = Mockito
                .mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito
                .mock(HttpServletResponse.class);

        final String fileName = "/VAADIN/static/file.png";
        Mockito.when(request.getPathInfo()).thenReturn(fileName);
        Mockito.when(response.getStatus())
                .thenReturn(HttpStatusCode.BAD_REQUEST.getCode());

        StaticFileServerInstrumentation.HandleRequestAdvice
                .onEnter(fileServerInstrumentation, request, null, null);
        StaticFileServerInstrumentation.HandleRequestAdvice.onExit(null, true,
                request, response, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Static file request: " + fileName, span.getName());
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals("Bad Request", span.getStatus().getDescription());
    }

    @Test
    public void staticFileRequest_notHandled() {
        final HttpServletRequest request = Mockito
                .mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito
                .mock(HttpServletResponse.class);

        final String fileName = "/VAADIN/static/file.png";
        Mockito.when(request.getPathInfo()).thenReturn(fileName);
        Mockito.when(response.getStatus())
                .thenReturn(HttpStatusCode.OK.getCode());

        StaticFileServerInstrumentation.HandleRequestAdvice
                .onEnter(fileServerInstrumentation, request, null, null);
        StaticFileServerInstrumentation.HandleRequestAdvice.onExit(null, false,
                request, response, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Static file request: " + fileName, span.getName());
        assertEquals("unhandled file request", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.resolution")));
    }

    @Tag("test-component")
    private static class TestComponent extends Component {
        @ClientCallable
        public void clientEvent() {
        }

        @ClientCallable
        public void connectClient() {
        }
    }
}
