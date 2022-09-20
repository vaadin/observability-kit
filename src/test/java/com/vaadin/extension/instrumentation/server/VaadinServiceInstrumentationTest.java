package com.vaadin.extension.instrumentation.server;

import static com.vaadin.extension.Constants.*;
import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.WrappedSession;
import com.vaadin.flow.shared.ApplicationConstants;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VaadinServiceInstrumentationTest extends AbstractInstrumentationTest {

    VaadinServletRequest vaadinRequest;

    @BeforeEach
    void init() {
        vaadinRequest = Mockito.mock(VaadinServletRequest.class);
        WrappedSession session = Mockito.mock(WrappedSession.class);
        Mockito.when(vaadinRequest.getWrappedSession()).thenReturn(session);
        Mockito.when(session.getId()).thenReturn("1234");
        Mockito.when(vaadinRequest
                .getParameter(ApplicationConstants.REQUEST_TYPE_PARAMETER))
                .thenReturn("uidl");
    }

    @Test
    public void handleRequest_enhancesRootSpan() {
        try (var ignored = withRootContext()) {
            VaadinServiceInstrumentation.MethodAdvice.onEnter(vaadinRequest,
                    null);
            VaadinServiceInstrumentation.MethodAdvice
                    .onExit(currentContext().makeCurrent());
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
}