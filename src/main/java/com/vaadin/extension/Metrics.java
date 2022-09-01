package com.vaadin.extension;

import com.vaadin.flow.server.VaadinSession;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class Metrics {
    private static final AtomicBoolean registered = new AtomicBoolean();
    private static final AtomicInteger sessionCount = new AtomicInteger();
    private static final AtomicInteger uiCount = new AtomicInteger();
    private static final Map<String, Instant> sessionStarts = new ConcurrentHashMap<>();

    private static LongHistogram sessionDurationMeasurement;

    private static InstantProvider instantProvider = new DefaultInstantProvider();

    // Exposed for testing
    public static void setInstantProvider(InstantProvider instantProvider) {
        Metrics.instantProvider = instantProvider;
    }

    public static void ensureMetricsRegistered() {
        // Ensure meters are only created once
        if (registered.compareAndSet(false, true)) {
            Meter meter = GlobalOpenTelemetry
                    .meterBuilder("com.vaadin.observability.instrumentation")
                    .setInstrumentationVersion("1.0-alpha").build();

            meter.gaugeBuilder("vaadin.session.count").ofLongs()
                    .setDescription("Number of open sessions").setUnit("count")
                    .buildWithCallback(measurement -> {
                        measurement.record(sessionCount.get());
                    });

            meter.gaugeBuilder("vaadin.ui.count").ofLongs()
                    .setDescription("Vaadin UI Count").setUnit("count")
                    .buildWithCallback(measurement -> {
                        measurement.record(uiCount.get());
                    });

            sessionDurationMeasurement = meter
                    .histogramBuilder("vaadin.session.duration")
                    .setDescription("Duration of sessions").setUnit("seconds")
                    .ofLongs().build();
        }
    }

    public static void recordSessionStart(VaadinSession session) {
        Metrics.ensureMetricsRegistered();
        sessionCount.incrementAndGet();

        String sessionId = getSessionIdentifier(session);
        Instant sessionStart = instantProvider.get();

        sessionStarts.put(sessionId, sessionStart);
    }

    public static void recordSessionEnd(VaadinSession session) {
        Metrics.ensureMetricsRegistered();
        sessionCount.updateAndGet(value -> Math.max(0, value - 1));

        String sessionId = getSessionIdentifier(session);
        Instant sessionStart = sessionStarts.getOrDefault(sessionId, null);

        if (sessionStart != null) {
            sessionStarts.remove(sessionId);
            Duration sessionDuration = Duration.between(sessionStart,
                    instantProvider.get());
            sessionDurationMeasurement.record(sessionDuration.toSeconds());
        }
    }

    public static void incrementUiCount() {
        Metrics.ensureMetricsRegistered();
        uiCount.incrementAndGet();
    }

    public static void decrementUiCount() {
        Metrics.ensureMetricsRegistered();
        uiCount.updateAndGet(value -> Math.max(0, value - 1));
    }

    private static String getSessionIdentifier(VaadinSession session) {
        // VaadinSession.getWrappedSession().getId is a more natural candidate,
        // however the wrapped session might not yet be set when recording the
        // session start. The push id is generated for all sessions, and should
        // contain a random UUID.
        return session.getPushId();
    }

    public interface InstantProvider {
        Instant get();
    }

    private static class DefaultInstantProvider implements InstantProvider {
        @Override
        public Instant get() {
            return Instant.now();
        }
    }
}
