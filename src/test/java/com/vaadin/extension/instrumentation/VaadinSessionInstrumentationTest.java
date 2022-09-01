package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.*;

import com.vaadin.extension.Metrics;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WrappedSession;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VaadinSessionInstrumentationTest {

    @Test
    public void increaseAndDecreaseSessionCount() {
        VaadinSession session1 = mockSession("session1");
        VaadinSession session2 = mockSession("session2");
        VaadinSession session3 = mockSession("session3");

        VaadinSessionInstrumentation.CreateSessionAdvice.onEnter(session1);
        VaadinSessionInstrumentation.CreateSessionAdvice.onEnter(session2);
        VaadinSessionInstrumentation.CreateSessionAdvice.onEnter(session3);

        assertEquals(3, Metrics.getSessionCount());

        VaadinSessionInstrumentation.CloseSessionAdvice.onEnter(session1);
        VaadinSessionInstrumentation.CloseSessionAdvice.onEnter(session2);

        assertEquals(1, Metrics.getSessionCount());

        // Should not go below 0
        VaadinSessionInstrumentation.CloseSessionAdvice.onEnter(session3);
        VaadinSessionInstrumentation.CloseSessionAdvice.onEnter(session3);
        VaadinSessionInstrumentation.CloseSessionAdvice.onEnter(session3);

        assertEquals(0, Metrics.getSessionCount());
    }

    @Test
    public void increaseAndDecreaseUiCount() {
        VaadinSessionInstrumentation.AddUiAdvice.onExit();
        VaadinSessionInstrumentation.AddUiAdvice.onExit();
        VaadinSessionInstrumentation.AddUiAdvice.onExit();

        assertEquals(3, Metrics.getUiCount());

        VaadinSessionInstrumentation.RemoveUiAdvice.onExit();
        VaadinSessionInstrumentation.RemoveUiAdvice.onExit();

        assertEquals(1, Metrics.getUiCount());

        // Should not go below 0
        VaadinSessionInstrumentation.RemoveUiAdvice.onExit();
        VaadinSessionInstrumentation.RemoveUiAdvice.onExit();
        VaadinSessionInstrumentation.RemoveUiAdvice.onExit();

        assertEquals(0, Metrics.getUiCount());
    }

    private static VaadinSession mockSession(String sessionId) {
        WrappedSession wrappedSession = Mockito.mock(WrappedSession.class);
        Mockito.when(wrappedSession.getId()).thenReturn(sessionId);

        VaadinSession session = Mockito.mock(VaadinSession.class);
        Mockito.when(session.getSession()).thenReturn(wrappedSession);

        return session;
    }
}