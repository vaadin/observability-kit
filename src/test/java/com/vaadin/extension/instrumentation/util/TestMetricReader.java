package com.vaadin.extension.instrumentation.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.metrics.internal.export.MetricProducer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;

public class TestMetricReader implements MetricReader {
    private MetricProducer metricProducer;
    private Collection<MetricData> metricData = new ArrayList<>();

    @Override
    public void register(CollectionRegistration registration) {
        metricProducer = MetricProducer.asMetricProducer(registration);
    }

    public void read() {
        metricData = metricProducer.collectAllMetrics();
    }

    public MetricData getMetric(String name) {
        Optional<MetricData> metric = metricData.stream()
                .filter(m -> m.getName().equals(name)).findFirst();

        assertTrue(metric.isPresent(), "Metric does not exist: " + name);

        return metric.get();
    }

    @Override
    public CompletableResultCode forceFlush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public AggregationTemporality getAggregationTemporality(
            InstrumentType instrumentType) {
        return AggregationTemporality.CUMULATIVE;
    }
}
