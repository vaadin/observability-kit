/*-
 * Copyright (C) 2022 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.extension.metrics;

import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.server.VaadinSession;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;

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
    private static LongHistogram spanDurationHistogram;
    private static InstantProvider instantProvider = Instant::now;

    static void setInstantProvider(InstantProvider instantProvider) {
        Metrics.instantProvider = instantProvider;  
    }

    public static void ensureMetricsRegistered() {
        // Ensure meters are only created once
        if (registered.compareAndSet(false, true)) {
            Meter meter = GlobalOpenTelemetry
                    .meterBuilder(InstrumentationHelper.INSTRUMENTATION_NAME)
                    .setInstrumentationVersion(
                            InstrumentationHelper.INSTRUMENTATION_VERSION)
                    .build();

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

            spanDurationHistogram = meter
                    .histogramBuilder("vaadin.span.duration")
                    .setDescription("Duration of spans in milliseconds")
                    .setUnit("ms")
                    .ofLongs()
                    .build();
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
        Instant sessionStart = sessionStarts.remove(sessionId);

        if (sessionStart != null) {
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

    @FunctionalInterface
    interface InstantProvider {
        Instant get();
    }

    public static void recordSpanDuration(String spanName, long durationMs, SpanContext spanContext) {
        Metrics.ensureMetricsRegistered();
        Attributes attributes = Attributes.of(
            AttributeKey.stringKey("span.name"), spanName
        );
        
        // Create a Context with span information for exemplars
        Context contextWithSpan = Context.current();
        if (spanContext != null && spanContext.isValid()) {
            contextWithSpan = Context.current().with(
                io.opentelemetry.api.trace.Span.wrap(spanContext)
            );
        }
        spanDurationHistogram.record(durationMs, attributes, contextWithSpan);
    }


}