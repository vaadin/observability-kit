/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.client;

import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.observability.micrometer.MeterNames;
import com.vaadin.observability.micrometer.ObservabilitySettings;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link ClientMetricsBinder}.
 */
class ClientMetricsBinderTest {

    private SimpleMeterRegistry registry;
    private ClientMetricsBinder binder;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        binder = new ClientMetricsBinder(registry,
                ObservabilitySettings.builder().build());
    }

    // --- ingest: timer ---

    @Test
    void ingestBootstrapSampleRecordsTimer() {
        ClientSample sample = sample(MeterNames.CLIENT_BOOTSTRAP_DURATION,
                250.0, Map.of());
        binder.ingest(List.of(sample));

        assertEquals(1L,
                registry.timer(MeterNames.CLIENT_BOOTSTRAP_DURATION).count());
    }

    @Test
    void ingestNavigationSampleRecordsTimer() {
        ClientSample sample = sample(MeterNames.CLIENT_NAVIGATION_DURATION,
                100.0, Map.of("route", "/home", "trigger", "programmatic"));
        binder.ingest(List.of(sample));

        assertEquals(1L, registry.timer(MeterNames.CLIENT_NAVIGATION_DURATION,
                "route", "_unknown", "trigger", "programmatic").count());
    }

    @Test
    void ingestLcpSampleRecordsTimer() {
        ClientSample sample = sample(MeterNames.CLIENT_WEB_VITALS_LCP, 1200.0,
                Map.of());
        binder.ingest(List.of(sample));

        assertEquals(1L,
                registry.timer(MeterNames.CLIENT_WEB_VITALS_LCP).count());
    }

    @Test
    void ingestFcpSampleRecordsTimer() {
        ClientSample sample = sample(MeterNames.CLIENT_WEB_VITALS_FCP, 800.0,
                Map.of());
        binder.ingest(List.of(sample));

        assertEquals(1L,
                registry.timer(MeterNames.CLIENT_WEB_VITALS_FCP).count());
    }

    // --- ingest: counter ---

    @Test
    void ingestErrorSampleIncrementsCounter() {
        ClientSample sample = sample(MeterNames.CLIENT_ERRORS, 0.0,
                Map.of("kind", "uncaught"));
        binder.ingest(List.of(sample));

        assertEquals(1.0, registry
                .counter(MeterNames.CLIENT_ERRORS, "kind", "uncaught").count(),
                1e-9);
    }

    @Test
    void ingestMultipleErrorsAccumulates() {
        binder.ingest(List.of(
                sample(MeterNames.CLIENT_ERRORS, 0.0,
                        Map.of("kind", "promise")),
                sample(MeterNames.CLIENT_ERRORS, 0.0,
                        Map.of("kind", "promise"))));

        assertEquals(2.0, registry
                .counter(MeterNames.CLIENT_ERRORS, "kind", "promise").count(),
                1e-9);
    }

    // --- ingest: disallowed name drops and increments dropped counter ---

    @Test
    void ingestDisallowedNameIsDropped() {
        ClientSample sample = sample("vaadin.client.rpc.duration", 50.0,
                Map.of());
        binder.ingest(List.of(sample));

        // The disallowed sample is NOT recorded as a timer
        assertEquals(0L, registry.find("vaadin.client.rpc.duration").timers()
                .stream().mapToLong(t -> t.count()).sum());
        // No dropped counter incremented here (dropped is only for
        // unrecognized *incoming* names; recordDropped is a separate method)
        assertEquals(0.0, registry.counter(MeterNames.CLIENT_DROPPED).count(),
                1e-9);
    }

    @Test
    void ingestNullListDoesNotThrow() {
        binder.ingest(null); // must not throw
    }

    @Test
    void ingestSampleWithNegativeValueIsSkipped() {
        ClientSample sample = sample(MeterNames.CLIENT_BOOTSTRAP_DURATION, -1.0,
                Map.of());
        binder.ingest(List.of(sample));

        assertEquals(0L,
                registry.timer(MeterNames.CLIENT_BOOTSTRAP_DURATION).count());
    }

    // --- tag capping ---

    @Test
    void tagValueOverCapBecomesOther() {
        // tag value longer than 200 chars should be capped
        String longValue = "x".repeat(201);
        ClientSample sample = sample(MeterNames.CLIENT_BOOTSTRAP_DURATION,
                100.0, Map.of("custom", longValue));
        binder.ingest(List.of(sample));

        // Timer is recorded with "_other" for the capped value
        assertEquals(1L, registry.timer(MeterNames.CLIENT_BOOTSTRAP_DURATION,
                "custom", MeterNames.ROUTE_OTHER).count());
    }

    @Test
    void tagKeyOverCapIsDropped() {
        // tag key longer than 64 chars should be dropped (tag not added)
        String longKey = "k".repeat(65);
        ClientSample sample = sample(MeterNames.CLIENT_BOOTSTRAP_DURATION,
                100.0, Map.of(longKey, "value"));
        binder.ingest(List.of(sample));

        // Timer recorded without that tag
        assertEquals(1L,
                registry.timer(MeterNames.CLIENT_BOOTSTRAP_DURATION).count());
    }

    // --- recordThrottled ---

    @Test
    void recordThrottledIncrementsCounter() {
        binder.recordThrottled(3);

        assertEquals(3.0, registry.counter(MeterNames.CLIENT_THROTTLED).count(),
                1e-9);
    }

    @Test
    void recordThrottledZeroDoesNotRegisterCounter() {
        binder.recordThrottled(0);

        // Counter should not be pre-registered (find returns null when not
        // registered in SimpleMeterRegistry without prior access)
        assertEquals(0.0, registry.counter(MeterNames.CLIENT_THROTTLED).count(),
                1e-9);
    }

    // --- recordDropped ---

    @Test
    void recordDroppedIncrementsCounter() {
        binder.recordDropped(2);

        assertEquals(2.0, registry.counter(MeterNames.CLIENT_DROPPED).count(),
                1e-9);
    }

    // --- helper ---

    private static ClientSample sample(String name, double valueMs,
            Map<String, String> tags) {
        ClientSample s = new ClientSample();
        s.setName(name);
        s.setValueMs(valueMs);
        s.setTags(tags);
        return s;
    }
}
