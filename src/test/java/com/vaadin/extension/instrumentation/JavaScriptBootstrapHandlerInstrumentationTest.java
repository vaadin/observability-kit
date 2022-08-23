package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.*;

import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.shared.ApplicationConstants;

import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;
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
    public void createAndInitUI_createsSpan() {
        Mockito.when(request
                .getParameter(ApplicationConstants.REQUEST_LOCATION_PARAMETER))
                .thenReturn("test-route");

            JavaScriptBootstrapHandlerInstrumentation.CreateAndInitUiAdvice
                    .onEnter(null);
            JavaScriptBootstrapHandlerInstrumentation.CreateAndInitUiAdvice
                    .onExit(request, getCapturedSpan(0));

        SpanData span = getExportedSpan(0);
        assertEquals("JavaScript Bootstrap", span.getName());
    }

    @Test
    public void createAndInitUI_updatesRootSpan() {
        Mockito.when(request
                .getParameter(ApplicationConstants.REQUEST_LOCATION_PARAMETER))
                .thenReturn("test-route");

        try (var ignored = withRootContext()) {
            JavaScriptBootstrapHandlerInstrumentation.CreateAndInitUiAdvice
                    .onEnter(null);
            JavaScriptBootstrapHandlerInstrumentation.CreateAndInitUiAdvice
                    .onExit(request, getCapturedSpan(0));
        }

        SpanData rootSpan = getExportedSpan(1);
        assertEquals("/test-route : JavaScript Bootstrap", rootSpan.getName());
        assertEquals("/test-route",
                rootSpan.getAttributes().get(SemanticAttributes.HTTP_ROUTE));
    }
}
