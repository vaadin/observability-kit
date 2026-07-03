/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring.boot;

import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

import com.vaadin.observability.micrometer.ObservabilitySettings;

/**
 * Wraps every {@link DataSource} bean in a {@link RowCountingDataSource} so the
 * kit records {@code vaadin.db.fetch.rows} — and, when tracing is enabled,
 * emits a {@code vaadin.db.query} span per query — without any application
 * code.
 * <p>
 * The {@link MeterRegistry} and {@link ObservationRegistry} are resolved lazily
 * through {@link ObjectProvider}s rather than injected, so this post-processor
 * can be created early (before the {@code DataSource} bean) without dragging
 * registry creation forward and short-circuiting other post-processors.
 */
class DataSourceFetchMetricsBeanPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final ObjectProvider<ObservationRegistry> observationRegistry;
    private final ObjectProvider<ObservabilitySettings> settings;
    private volatile DatabaseFetchMetrics metrics;
    private volatile DatabaseQuerySpans spans;

    DataSourceFetchMetricsBeanPostProcessor(
            ObjectProvider<MeterRegistry> meterRegistry,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<ObservabilitySettings> settings) {
        this.meterRegistry = meterRegistry;
        this.observationRegistry = observationRegistry;
        this.settings = settings;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName)
            throws BeansException {
        if (bean instanceof DataSource dataSource
                && !(bean instanceof RowCountingDataSource)) {
            DatabaseFetchMetrics m = metrics();
            if (m != null) {
                return new RowCountingDataSource(dataSource, m, spans());
            }
        }
        return bean;
    }

    private DatabaseFetchMetrics metrics() {
        DatabaseFetchMetrics m = metrics;
        if (m == null) {
            MeterRegistry registry = meterRegistry.getIfAvailable();
            if (registry != null) {
                m = new DatabaseFetchMetrics(registry);
                metrics = m;
            }
        }
        return m;
    }

    /**
     * Builds the query-span emitter, or returns {@code null} when tracing is
     * disabled or no {@link ObservationRegistry} is available — in which case
     * only the row-count metric is recorded.
     */
    private DatabaseQuerySpans spans() {
        ObservabilitySettings s = settings.getIfAvailable();
        if (s == null || !s.isTraces()) {
            return null;
        }
        DatabaseQuerySpans existing = spans;
        if (existing == null) {
            ObservationRegistry registry = observationRegistry.getIfAvailable();
            if (registry != null) {
                existing = new DatabaseQuerySpans(registry,
                        s.isDatabaseStatement());
                spans = existing;
            }
        }
        return existing;
    }
}
