package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.*;

import com.vaadin.extension.ContextKeys;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.WrappedSession;

import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VaadinServiceInstrumentationTest {

    @Test
    public void handleRequest_providesSessionId() {
        WrappedSession wrappedSession = Mockito.mock(WrappedSession.class);
        Mockito.when(wrappedSession.getId()).thenReturn("foo");

        VaadinRequest request = Mockito.mock(VaadinRequest.class);
        Mockito.when(request.getWrappedSession()).thenReturn(wrappedSession);

        VaadinServiceInstrumentation.MethodAdvice.onEnter(request, null);

        assertEquals("foo", Context.current().get(ContextKeys.SESSION_ID));

        // TODO: close scope
    }
}