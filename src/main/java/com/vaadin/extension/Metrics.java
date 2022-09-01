package com.vaadin.extension;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.Meter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class Metrics {
    private static final AtomicBoolean registered = new AtomicBoolean();
    private static final AtomicInteger sessionCount = new AtomicInteger();

    public static void ensureMetricsRegistered() {
        // Ensure meters are only created once
        if (registered.compareAndSet(false, true)) {
            Meter meter = GlobalOpenTelemetry
                    .meterBuilder("com.vaadin.observability.instrumentation")
                    .setInstrumentationVersion("1.0-alpha").build();

            meter.gaugeBuilder("vaadin.session.count").ofLongs()
                    .setDescription("Vaadin Session Count").setUnit("count")
                    .buildWithCallback(measurement -> {
                        measurement.record(sessionCount.get());
                    });
        }
    }

    public static int getSessionCount() {
        return sessionCount.get();
    }

    public static void incrementSessionCount() {
        Metrics.ensureMetricsRegistered();
        sessionCount.incrementAndGet();
    }

    public static void decrementSessionCount() {
        Metrics.ensureMetricsRegistered();
        sessionCount.updateAndGet(value -> Math.max(0, value - 1));
    }
}
