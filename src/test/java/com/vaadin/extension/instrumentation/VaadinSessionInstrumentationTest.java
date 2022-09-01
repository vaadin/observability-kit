package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.*;

import com.vaadin.extension.Metrics;

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
}