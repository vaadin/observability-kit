/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring.boot;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;

import com.vaadin.observability.micrometer.MeterNames;
import com.vaadin.observability.micrometer.VaadinTelemetryContext;

/**
 * Records the {@link MeterNames#DB_FETCH_ROWS} distribution summary,
 * attributing each fetch to the Vaadin route active on the current request
 * thread.
 */
final class DatabaseFetchMetrics {

    private final MeterRegistry registry;

    DatabaseFetchMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Records the number of rows read from a single result set, tagged by the
     * route resolved from {@link VaadinTelemetryContext}.
     *
     * @param rows
     *            the number of rows iterated; ignored when negative
     */
    void recordFetch(long rows) {
        if (rows < 0) {
            return;
        }
        DistributionSummary.builder(MeterNames.DB_FETCH_ROWS)
                .description("Rows read from a JDBC result set")
                .tag(MeterNames.TAG_ROUTE,
                        VaadinTelemetryContext.currentRoute())
                .publishPercentiles(0.95, 0.99).register(registry).record(rows);
    }
}
