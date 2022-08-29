package com.vaadin.extension.instrumentation;

import static com.vaadin.extension.instrumentation.WebcomponentBootstrapHandlerInstrumentation.REQ_PARAM_URL;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.flow.server.VaadinRequest;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WebcomponentBootstrapHandlerInstrumentationTest
        extends AbstractInstrumentationTest {

    @Test
    public void webcomponentBootsrapRequest_spanCreated() {
        VaadinRequest request = Mockito.mock(VaadinRequest.class);
        Mockito.when(request.getPathInfo())
                .thenReturn("vaadin/web-component/web-component-bootstrap.js");
        final String url = "http://localhost:8888/vaadin/web-component/web-component-bootstrap.js";
        Mockito.when(request.getParameter(REQ_PARAM_URL)).thenReturn(url);

        try (var ignored = withRootContext()) {
            WebcomponentBootstrapHandlerInstrumentation.SynchronizedHandleRequestAdvice
                    .onEnter(request, null, null);
            WebcomponentBootstrapHandlerInstrumentation.SynchronizedHandleRequestAdvice
                    .onExit(null, getCapturedSpan(0), null);
        }

        assertEquals("WebComponentBootstrapHandler",
                getExportedSpan(0).getName());
        SpanData rootSpan = getExportedSpan(1);
        assertEquals("/ : WebComponentBootstrap", rootSpan.getName());
        assertEquals(url, rootSpan.getAttributes()
                .get(AttributeKey.stringKey("vaadin.webcomponent.url")));
    }
}
