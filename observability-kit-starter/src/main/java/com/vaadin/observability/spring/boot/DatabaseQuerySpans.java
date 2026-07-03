/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring.boot;

import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import com.vaadin.observability.micrometer.VaadinTelemetryContext;
import com.vaadin.observability.micrometer.trace.ObservationNames;

/**
 * Emits a {@link ObservationNames#DB_QUERY} observation around each JDBC query.
 * Started when a query executes and stopped when its result set closes, the
 * observation produces a span (exported to the configured tracing backend, e.g.
 * Jaeger) nested under the current Vaadin request/RPC span, plus — through the
 * Observation API's meter handler — a {@code vaadin.db.query} duration timer
 * tagged by route.
 * <p>
 * The route is read from {@link VaadinTelemetryContext}; the row count is added
 * when the span stops. The SQL text is attached only when statement capture is
 * enabled, since it is higher cardinality and may be sensitive.
 */
final class DatabaseQuerySpans {

    private final ObservationRegistry observationRegistry;
    private final boolean captureStatement;

    DatabaseQuerySpans(ObservationRegistry observationRegistry,
            boolean captureStatement) {
        this.observationRegistry = observationRegistry;
        this.captureStatement = captureStatement;
    }

    /**
     * Starts a query span, tagging the current route and (when enabled) the
     * SQL. The caller must {@link QuerySpan#stop(long) stop} it when the result
     * set closes.
     *
     * @param sql
     *            the SQL being executed, may be {@code null}
     * @return the in-flight span handle
     */
    QuerySpan start(String sql) {
        Observation observation = Observation
                .createNotStarted(ObservationNames.DB_QUERY,
                        observationRegistry)
                .contextualName(ObservationNames.DB_QUERY)
                .lowCardinalityKeyValue(ObservationNames.KEY_ROUTE,
                        VaadinTelemetryContext.currentRoute());
        if (captureStatement && sql != null) {
            observation.highCardinalityKeyValue(
                    ObservationNames.KEY_DB_STATEMENT, sql);
        }
        return new QuerySpan(observation.start());
    }

    /**
     * Handle for an in-flight query span. {@link #stop(long)} is idempotent so
     * the result-set close path and the statement-close leak guard can both
     * call it without double-stopping.
     */
    static final class QuerySpan {
        private final Observation observation;
        private final AtomicBoolean stopped = new AtomicBoolean();

        private QuerySpan(Observation observation) {
            this.observation = observation;
        }

        /**
         * Stops the span, recording the row count when known.
         *
         * @param rows
         *            rows read, or a negative value when unknown (e.g. the
         *            result set was never closed)
         */
        void stop(long rows) {
            if (stopped.compareAndSet(false, true)) {
                if (rows >= 0) {
                    observation.highCardinalityKeyValue(
                            ObservationNames.KEY_DB_ROWS, Long.toString(rows));
                }
                observation.stop();
            }
        }
    }
}
