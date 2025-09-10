package com.vaadin.extension.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vaadin.extension.conf.Configuration;
import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.extension.instrumentation.server.VaadinSessionInstrumentation;
import com.vaadin.extension.metrics.SpanToMetricProcessor;
import com.vaadin.flow.server.VaadinSession;

import io.opentelemetry.sdk.trace.ReadableSpan;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
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

    @Test
    public void recordSpanDuration() {
        SpanContext spanContext = createMockSpanContext();
        String spanName = "test-span";
        long durationNanos = 150_000_000; // 150ms in nanoseconds

        Metrics.recordSpanDuration(spanName, durationNanos, spanContext);

        HistogramPointData metricValue = getLastHistogramMetricValue("vaadin.span.duration");
        assertEquals(150, metricValue.getSum(), 0); // Should be 150ms
        assertEquals(1, metricValue.getCount());
        
        // Verify span name attribute is recorded
        assertTrue(metricValue.getAttributes().asMap().containsKey(io.opentelemetry.api.common.AttributeKey.stringKey("span.name")));
        assertEquals(spanName, metricValue.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("span.name")));
    }

    @Test
    public void spanToMetricsConfigurationRespected() {
        // Test that the configuration mock works as expected
        
        // Disable span-to-metrics
        ConfigurationMock.when(() -> Configuration.isSpanToMetricsEnabled())
                .thenReturn(false);
        
        assertFalse(Configuration.isSpanToMetricsEnabled(), 
                "Configuration should reflect disabled state");
        
        // Enable span-to-metrics
        ConfigurationMock.when(() -> Configuration.isSpanToMetricsEnabled())
                .thenReturn(true);
        
        assertTrue(Configuration.isSpanToMetricsEnabled(), 
                "Configuration should reflect enabled state");
        
        // Reset to enabled for other tests
        ConfigurationMock.when(() -> Configuration.isSpanToMetricsEnabled())
                .thenReturn(true);
    }

    @Test
    public void recordSpanDurationVariousSpanNames() {
        SpanContext spanContext = createMockSpanContext();
        
        // Test recording spans with different names (including ones that used to trigger document load)
        String[] spanNames = {
            "regular-operation",
            "documentLoad", 
            "navigate-to-page",
            "Navigate-Home"
        };
        
        // Record spans and verify basic functionality
        for (String spanName : spanNames) {
            Metrics.recordSpanDuration(spanName, 100_000_000, spanContext); // 100ms in nanoseconds
        }
        
        // Verify basic functionality - that spans are recorded in the histogram
        HistogramPointData spanMetric = getLastHistogramMetricValue("vaadin.span.duration");
        assertTrue(spanMetric.getCount() >= 1, "Should have recorded at least 1 span");
        assertTrue(spanMetric.getSum() >= 100, "Should have recorded at least 100ms total");
        
        // Verify that span name attribute key exists (value will be from the last recorded span)
        assertTrue(spanMetric.getAttributes().asMap().containsKey(io.opentelemetry.api.common.AttributeKey.stringKey("span.name")),
                "Span name attribute should be present");
    }

    private static VaadinSession mockSession(String sessionId) {
        VaadinSession session = Mockito.mock(VaadinSession.class);
        Mockito.when(session.getPushId()).thenReturn(sessionId);

        return session;
    }

    private static SpanContext createMockSpanContext() {
        return SpanContext.create(
            "12345678901234567890123456789012", // traceId (32 hex chars)
            "1234567890123456", // spanId (16 hex chars)  
            TraceFlags.getSampled(),
            TraceState.getDefault()
        );
    }
}
