package com.vaadin.extension.instrumentation.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

import java.util.Optional;

public class TestMetricReader {
    private final InMemoryMetricReader metricReader = InMemoryMetricReader.create();

    public InMemoryMetricReader getMetricReader() {
        return metricReader;
    }

    public void collectAllMetrics() {
        // This method triggers metric collection
        metricReader.collectAllMetrics();
    }

    public MetricData getMetric(String name) {
        final var metricData = metricReader.collectAllMetrics();
        Optional<MetricData> metric = metricData.stream()
                .filter(m -> m.getName().equals(name)).findFirst();

        assertTrue(metric.isPresent(), "Metric does not exist: " + name);

        return metric.get();
    }
}
