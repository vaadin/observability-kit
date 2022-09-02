package com.vaadin.extension.instrumentation.server;

import static org.junit.jupiter.api.Assertions.*;

import com.vaadin.extension.Metrics;
import com.vaadin.extension.instrumentation.server.VaadinSessionInstrumentation;

import org.junit.jupiter.api.Test;

class VaadinSessionInstrumentationTest {

    @Test
    public void increaseAndDecreaseSessionCount() {
        VaadinSessionInstrumentation.CreateSessionAdvice.onEnter();
        VaadinSessionInstrumentation.CreateSessionAdvice.onEnter();
        VaadinSessionInstrumentation.CreateSessionAdvice.onEnter();

        assertEquals(3, Metrics.getSessionCount());

        VaadinSessionInstrumentation.CloseSessionAdvice.onEnter();
        VaadinSessionInstrumentation.CloseSessionAdvice.onEnter();

        assertEquals(1, Metrics.getSessionCount());

        // Should not go below 0
        VaadinSessionInstrumentation.CloseSessionAdvice.onEnter();
        VaadinSessionInstrumentation.CloseSessionAdvice.onEnter();
        VaadinSessionInstrumentation.CloseSessionAdvice.onEnter();

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
}