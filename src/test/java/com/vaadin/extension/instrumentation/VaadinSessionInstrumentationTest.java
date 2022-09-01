package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.*;

import com.vaadin.flow.server.VaadinSession;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VaadinSessionInstrumentationTest extends AbstractInstrumentationTest {

    @Test
    public void increaseAndDecreaseSessionCount() {
        VaadinSession session1 = mockSession("session1");
        VaadinSession session2 = mockSession("session2");
        VaadinSession session3 = mockSession("session3");

        VaadinSessionInstrumentation.CreateSessionAdvice.onEnter(session1);
        VaadinSessionInstrumentation.CreateSessionAdvice.onEnter(session2);
        VaadinSessionInstrumentation.CreateSessionAdvice.onEnter(session3);

        assertEquals(3, getLastLongGaugeMetricValue("vaadin.session.count"));

        VaadinSessionInstrumentation.CloseSessionAdvice.onEnter(session1);
        VaadinSessionInstrumentation.CloseSessionAdvice.onEnter(session2);

        assertEquals(1, getLastLongGaugeMetricValue("vaadin.session.count"));

        // Should not go below 0
        VaadinSessionInstrumentation.CloseSessionAdvice.onEnter(session3);
        VaadinSessionInstrumentation.CloseSessionAdvice.onEnter(session3);
        VaadinSessionInstrumentation.CloseSessionAdvice.onEnter(session3);

        assertEquals(0, getLastLongGaugeMetricValue("vaadin.session.count"));
    }

    @Test
    public void increaseAndDecreaseUiCount() {
        VaadinSessionInstrumentation.AddUiAdvice.onExit();
        VaadinSessionInstrumentation.AddUiAdvice.onExit();
        VaadinSessionInstrumentation.AddUiAdvice.onExit();

        assertEquals(3, getLastLongGaugeMetricValue("vaadin.ui.count"));

        VaadinSessionInstrumentation.RemoveUiAdvice.onExit();
        VaadinSessionInstrumentation.RemoveUiAdvice.onExit();

        assertEquals(1, getLastLongGaugeMetricValue("vaadin.ui.count"));

        // Should not go below 0
        VaadinSessionInstrumentation.RemoveUiAdvice.onExit();
        VaadinSessionInstrumentation.RemoveUiAdvice.onExit();
        VaadinSessionInstrumentation.RemoveUiAdvice.onExit();

        assertEquals(0, getLastLongGaugeMetricValue("vaadin.ui.count"));
    }

    private static VaadinSession mockSession(String sessionId) {
        VaadinSession session = Mockito.mock(VaadinSession.class);
        Mockito.when(session.getPushId()).thenReturn(sessionId);

        return session;
    }
}