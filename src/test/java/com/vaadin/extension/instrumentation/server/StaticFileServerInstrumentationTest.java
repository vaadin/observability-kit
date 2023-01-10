package com.vaadin.extension.instrumentation.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.extension.HttpStatusCode;
import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.server.StaticFileServer;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class StaticFileServerInstrumentationTest extends AbstractInstrumentationTest {

    private StaticFileServer fileServerInstrumentation;
    TestComponent component;
    Instant startTimestamp = Instant.ofEpochSecond(123);

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

        StaticFileServerInstrumentation.HandleRequestAdvice.onEnter(null);
        StaticFileServerInstrumentation.HandleRequestAdvice.onExit(null, true,
                request, response, startTimestamp);

        SpanData span = getExportedSpan(0);
        assertEquals("StaticFileRequest", span.getName());
    }

    @Test
    public void bundleRequest_mainSpanIsUpdated() {
        final HttpServletRequest request = Mockito
                .mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito
                .mock(HttpServletResponse.class);

        final String fileName = "/VAADIN/build/vaadin-bundle-f14b29f0d9b87d2ec1aa.cache.js";
        Mockito.when(request.getPathInfo()).thenReturn(fileName);
        Mockito.when(response.getStatus())
                .thenReturn(HttpStatusCode.BAD_REQUEST.getCode());

        try (var ignored = withRootContext()) {
            StaticFileServerInstrumentation.HandleRequestAdvice.onEnter(null);
            StaticFileServerInstrumentation.HandleRequestAdvice.onExit(null,
                    true, request, response, startTimestamp);
        }

        SpanData rootSpan = getExportedSpan(1);
        assertEquals("StaticFileRequest", getExportedSpan(0).getName());
        assertEquals("/ : Load frontend bundle", rootSpan.getName());
    }

    @Test
    public void staticFileRequest_mainSpanIsUpdatedToFileName() {
        final HttpServletRequest request = Mockito
                .mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito
                .mock(HttpServletResponse.class);

        final String fileName = "/VAADIN/static/file.png";
        Mockito.when(request.getPathInfo()).thenReturn(fileName);
        Mockito.when(response.getStatus())
                .thenReturn(HttpStatusCode.BAD_REQUEST.getCode());

        try (var ignored = withRootContext()) {
            StaticFileServerInstrumentation.HandleRequestAdvice.onEnter(null);
            StaticFileServerInstrumentation.HandleRequestAdvice.onExit(null,
                    true, request, response, startTimestamp);
        }

        SpanData rootSpan = getExportedSpan(1);
        assertEquals("StaticFileRequest", getExportedSpan(0).getName());
        assertEquals(fileName, rootSpan.getName());
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

        StaticFileServerInstrumentation.HandleRequestAdvice.onEnter(null);
        StaticFileServerInstrumentation.HandleRequestAdvice.onExit(null, true,
                request, response, startTimestamp);

        SpanData span = getExportedSpan(0);
        assertEquals("StaticFileRequest", span.getName());
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

        StaticFileServerInstrumentation.HandleRequestAdvice.onEnter(null);
        StaticFileServerInstrumentation.HandleRequestAdvice.onExit(null, true,
                request, response, startTimestamp);

        SpanData span = getExportedSpan(0);
        assertEquals("StaticFileRequest", span.getName());
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

        StaticFileServerInstrumentation.HandleRequestAdvice.onEnter(null);
        StaticFileServerInstrumentation.HandleRequestAdvice.onExit(null, false,
                request, response, startTimestamp);

        assertEquals(0, getExportedSpanCount());
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
