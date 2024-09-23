package com.vaadin.extension.instrumentation.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.metrics.export.MetricProducer;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.resources.Resource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public class TestMetricReader implements MetricReader, MetricProducer {
    private final List<MetricData> metricData = new ArrayList<>();

    public CompletableResultCode flush() {
        // Implement flush logic if needed
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public void register(CollectionRegistration collectionRegistration) {

    }

    @Override
    public CompletableResultCode forceFlush() {
        return null;
    }

    @Override
    public CompletableResultCode shutdown() {
        // Implement shutdown logic if needed
        return CompletableResultCode.ofSuccess();
    }

    public Collection<MetricData> collectAllMetrics() {
        // Return the collected metrics
        return new ArrayList<>(metricData);
    }

    public void addMetric(MetricData metric) {
        metricData.add(metric);
    }

    public MetricData getMetric(String name) {
        Optional<MetricData> metric = metricData.stream()
            .filter(m -> m.getName().equals(name))
            .findFirst();

        assertTrue(metric.isPresent(), "Metric does not exist: " + name);
        return metric.get();
    }

    @Override
    public Collection<MetricData> produce(Resource resource) {
        return List.of();
    }

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
        return null;
    }
}
