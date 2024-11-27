package com.vaadin.extension.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.extension.instrumentation.server.VaadinSessionInstrumentation;
import com.vaadin.flow.server.VaadinSession;

import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;

class MetricsTest extends AbstractInstrumentationTest {

    @AfterEach
    public void teardown() {
        // Restore default instant provider in Metrics
        Metrics.setInstantProvider(Instant::now);
    }

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

    @Test
    public void sessionDuration() {
        VaadinSession session = mockSession("session");

        Metrics.setInstantProvider(() -> Instant.ofEpochSecond(500));
        VaadinSessionInstrumentation.CreateSessionAdvice.onEnter(session);

        Metrics.setInstantProvider(() -> Instant.ofEpochSecond(2000));
        VaadinSessionInstrumentation.CloseSessionAdvice.onEnter(session);

        HistogramPointData metricValue = getLastHistogramMetricValue(
                "vaadin.session.duration");

        assertEquals(1500, metricValue.getSum(), 0);
    }

    private static VaadinSession mockSession(String sessionId) {
        VaadinSession session = Mockito.mock(VaadinSession.class);
        Mockito.when(session.getPushId()).thenReturn(sessionId);

        return session;
    }
}
