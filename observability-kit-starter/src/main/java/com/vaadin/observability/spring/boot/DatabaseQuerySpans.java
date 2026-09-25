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
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
 * <p>
 * <b>Span limit.</b> An N+1 load issues one query per row, and a span per query
 * turns one click into tens of thousands of spans: the tracing exporter's queue
 * overflows and drops spans from every request, not only the one at fault. So
 * at most {@code spanLimit} query spans are started under any one parent span.
 * The count lives on the parent's observation context, so it covers exactly
 * that span's lifetime and needs no reset. A query past the limit is still
 * timed into the same {@code vaadin.db.query} timer, so the metric does not
 * under-report exactly the load that is slow, and the parent carries the number
 * left out as {@link ObservationNames#KEY_DB_QUERIES_UNSPANNED}. A query with
 * no current observation has no parent to count against and is not limited.
 */
final class DatabaseQuerySpans {

    /** Context key of the parent's {@link Budget}. */
    private static final Object BUDGET_KEY = Budget.class;

    /**
     * The tag the meter observation handler adds to every timer it makes; the
     * timer recorded for an unspanned query must carry the same tag keys, or
     * Prometheus rejects it as a same-named meter with different keys. A query
     * span is never stopped with an error, so the value is always this one.
     */
    private static final String ERROR_TAG = "error";
    private static final String ERROR_NONE = "none";

    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final boolean captureStatement;
    private final int spanLimit;

    /**
     * @param observationRegistry
     *            the registry query observations are started in
     * @param meterRegistry
     *            where an unspanned query's duration is recorded, or
     *            {@code null} to not time those
     * @param captureStatement
     *            whether to attach the SQL to the span
     * @param spanLimit
     *            the most query spans under one parent span
     */
    DatabaseQuerySpans(ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry, boolean captureStatement,
            int spanLimit) {
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
        this.captureStatement = captureStatement;
        this.spanLimit = spanLimit;
    }

    /** Query spans started, and left out, under one parent span. */
    private static final class Budget {
        private final AtomicLong started = new AtomicLong();
        private final AtomicLong unspanned = new AtomicLong();
    }

    /**
     * Starts a query span, tagging the current route and (when enabled) the SQL
     * — or, when the parent span has used up its limit, only a timer. The
     * caller must {@link QuerySpan#stop(long) stop} it when the result set
     * closes.
     *
     * @param sql
     *            the SQL being executed, may be {@code null}
     * @return the in-flight span handle
     */
    QuerySpan start(String sql) {
        String route = VaadinTelemetryContext.currentRoute();
        Observation parent = observationRegistry.getCurrentObservation();
        if (parent != null) {
            Budget budget = parent.getContext().computeIfAbsent(BUDGET_KEY,
                    key -> new Budget());
            if (budget.started.incrementAndGet() > spanLimit) {
                // Written on every query past the limit rather than once when
                // the parent stops, which is not something to hook: the
                // tracing handler reads it off the context at that point, so
                // the last write is the one exported.
                parent.highCardinalityKeyValue(
                        ObservationNames.KEY_DB_QUERIES_UNSPANNED,
                        Long.toString(budget.unspanned.incrementAndGet()));
                return new QuerySpan(null, route, meterRegistry == null ? null
                        : Timer.start(meterRegistry), meterRegistry);
            }
        }
        Observation observation = Observation
                .createNotStarted(ObservationNames.DB_QUERY,
                        observationRegistry)
                .contextualName(ObservationNames.DB_QUERY)
                .lowCardinalityKeyValue(ObservationNames.KEY_ROUTE, route);
        if (captureStatement && sql != null) {
            observation.highCardinalityKeyValue(
                    ObservationNames.KEY_DB_STATEMENT, sql);
        }
        return new QuerySpan(observation.start(), route, null, null);
    }

    /**
     * Handle for an in-flight query: a span, or for a query past the span limit
     * a timer sample alone. {@link #stop(long)} is idempotent so the result-set
     * close path and the statement-close leak guard can both call it without
     * double-stopping.
     */
    static final class QuerySpan {
        private final Observation observation;
        private final String route;
        private final Timer.Sample sample;
        private final MeterRegistry meterRegistry;
        private final AtomicBoolean stopped = new AtomicBoolean();

        private QuerySpan(Observation observation, String route,
                Timer.Sample sample, MeterRegistry meterRegistry) {
            this.observation = observation;
            this.route = route;
            this.sample = sample;
            this.meterRegistry = meterRegistry;
        }

        /**
         * Stops the span, recording the row count when known.
         *
         * @param rows
         *            rows read, or a negative value when unknown (e.g. the
         *            result set was never closed)
         */
        void stop(long rows) {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            if (observation != null) {
                if (rows >= 0) {
                    observation.highCardinalityKeyValue(
                            ObservationNames.KEY_DB_ROWS, Long.toString(rows));
                }
                observation.stop();
            } else if (sample != null) {
                sample.stop(Timer.builder(ObservationNames.DB_QUERY)
                        .tag(ObservationNames.KEY_ROUTE, route)
                        .tag(ERROR_TAG, ERROR_NONE).register(meterRegistry));
            }
        }
    }
}
