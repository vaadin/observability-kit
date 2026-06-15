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
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Wraps every {@link DataSource} bean in a {@link RowCountingDataSource} so the
 * kit records {@code vaadin.db.fetch.rows} without any application code.
 * <p>
 * The {@link MeterRegistry} is resolved lazily through an
 * {@link ObjectProvider} rather than injected, so this post-processor can be
 * created early (before the {@code DataSource} bean) without dragging registry
 * creation forward and short-circuiting other post-processors.
 */
class DataSourceFetchMetricsBeanPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<MeterRegistry> meterRegistry;
    private volatile DatabaseFetchMetrics metrics;

    DataSourceFetchMetricsBeanPostProcessor(
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName)
            throws BeansException {
        if (bean instanceof DataSource dataSource
                && !(bean instanceof RowCountingDataSource)) {
            DatabaseFetchMetrics m = metrics();
            if (m != null) {
                return new RowCountingDataSource(dataSource, m);
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
}
