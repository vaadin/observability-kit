/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring.boot;

import java.util.ArrayList;
import java.util.List;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingAwareMeterObservationHandler;
import io.micrometer.tracing.handler.TracingObservationHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.observability.micrometer.trace.ObservationNames;

/**
 * The span limit: an N+1 load under one parent span gets a bounded number of
 * query spans, while the timer and the parent still account for every query.
 */
class DatabaseQuerySpansTest {

    /**
     * Records every started observation's name, like a tracer would, and leaves
     * the tracing context a tracer leaves.
     */
    private static final class StartedSpans
            implements ObservationHandler<Observation.Context> {

        final List<String> started = new ArrayList<>();

        @Override
        public void onStart(Observation.Context ctx) {
            started.add(ctx.getName());
            ctx.computeIfAbsent(TracingObservationHandler.TracingContext.class,
                    key -> new TracingObservationHandler.TracingContext());
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        long queries() {
            return started.stream().filter(ObservationNames.DB_QUERY::equals)
                    .count();
        }
    }

    private SimpleMeterRegistry meters;
    private ObservationRegistry observations;
    private StartedSpans spans;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        observations = ObservationRegistry.create();
        spans = new StartedSpans();
        // What Spring Boot installs with tracing on: the tracing handlers as
        // one first-matching group, the query handler ordered first, and the
        // meter handler wrapped to be tracing-aware.
        observations.observationConfig().observationHandler(
                new ObservationHandler.FirstMatchingCompositeObservationHandler(
                        new UnspannedQueryTracingHandler(), spans))
                .observationHandler(new TracingAwareMeterObservationHandler<>(
                        new DefaultMeterObservationHandler(meters),
                        Tracer.NOOP));
    }

    private DatabaseQuerySpans querySpans(int limit) {
        return new DatabaseQuerySpans(observations, false, limit);
    }

    private static void runQueries(DatabaseQuerySpans querySpans, int count) {
        for (int i = 0; i < count; i++) {
            querySpans.start("select " + i).stop(1);
        }
    }

    private void runQueriesUnderParent(DatabaseQuerySpans querySpans,
            int count) {
        Observation parent = Observation.start("vaadin.data.fetch",
                observations);
        try (Observation.Scope scope = parent.openScope()) {
            runQueries(querySpans, count);
        }
        parent.stop();
    }

    private static String unspanned(Observation parent) {
        var value = parent.getContext().getHighCardinalityKeyValue(
                ObservationNames.KEY_DB_QUERIES_UNSPANNED);
        return value == null ? null : value.getValue();
    }

    @Test
    void queriesPastTheLimit_getNoSpan_andTheParentSaysHowMany() {
        DatabaseQuerySpans querySpans = querySpans(2);
        Observation parent = Observation.start("vaadin.data.fetch",
                observations);
        try (Observation.Scope scope = parent.openScope()) {
            runQueries(querySpans, 5);
        }
        parent.stop();

        Assertions.assertEquals(2, spans.queries());
        Assertions.assertEquals("3", unspanned(parent));
    }

    @Test
    void everyQueryIsStillTimed_intoTheSameTimer() {
        runQueriesUnderParent(querySpans(2), 5);

        List<Timer> timers = new ArrayList<>(
                meters.find(ObservationNames.DB_QUERY).timers());
        Assertions.assertEquals(1, timers.size());
        Assertions.assertEquals(5, timers.get(0).count());
    }

    @Test
    void keysAddedByAFilter_tagTheUnspannedQueriesToo() {
        // E.g. management.observations.key-values.region=eu: the unspanned
        // queries' timer must carry it as well, or the two same-named
        // timers differ in tag keys and Prometheus drops one.
        observations.observationConfig().observationFilter(ctx -> ctx
                .addLowCardinalityKeyValue(KeyValue.of("region", "eu")));

        runQueriesUnderParent(querySpans(2), 5);

        List<Timer> timers = new ArrayList<>(
                meters.find(ObservationNames.DB_QUERY).timers());
        Assertions.assertEquals(1, timers.size());
        Assertions.assertEquals(5, timers.get(0).count());
        Assertions.assertEquals("eu", timers.get(0).getId().getTag("region"));
    }

    @Test
    void aDisabledQueryObservation_timesNoUnspannedQueryEither() {
        observations.observationConfig().observationPredicate(
                (name, ctx) -> !ObservationNames.DB_QUERY.equals(name));

        runQueriesUnderParent(querySpans(2), 5);

        Assertions.assertEquals(0, spans.queries());
        Assertions.assertNull(meters.find(ObservationNames.DB_QUERY).timer());
    }

    @Test
    void underTheLimit_theParentCarriesNoCount() {
        DatabaseQuerySpans querySpans = querySpans(10);
        Observation parent = Observation.start("vaadin.rpc", observations);
        try (Observation.Scope scope = parent.openScope()) {
            runQueries(querySpans, 3);
        }
        parent.stop();

        Assertions.assertEquals(3, spans.queries());
        Assertions.assertNull(unspanned(parent));
    }

    @Test
    void eachParentHasItsOwnLimit() {
        DatabaseQuerySpans querySpans = querySpans(2);
        for (int i = 0; i < 2; i++) {
            Observation parent = Observation.start("vaadin.data.fetch",
                    observations);
            try (Observation.Scope scope = parent.openScope()) {
                runQueries(querySpans, 3);
            }
            parent.stop();
            Assertions.assertEquals("1", unspanned(parent));
        }

        Assertions.assertEquals(4, spans.queries());
    }

    @Test
    void zeroLimit_spansNoQueryUnderAParent() {
        DatabaseQuerySpans querySpans = querySpans(0);
        Observation parent = Observation.start("vaadin.rpc", observations);
        try (Observation.Scope scope = parent.openScope()) {
            runQueries(querySpans, 3);
        }
        parent.stop();

        Assertions.assertEquals(0, spans.queries());
        Assertions.assertEquals("3", unspanned(parent));
    }

    @Test
    void withoutAParent_queriesAreNotLimited() {
        // Nothing to count against: each is a root span of its own.
        runQueries(querySpans(1), 3);

        Assertions.assertEquals(3, spans.queries());
    }

    @Test
    void stoppingAnUnspannedQueryTwice_timesItOnce() {
        DatabaseQuerySpans querySpans = querySpans(0);
        Observation parent = Observation.start("vaadin.rpc", observations);
        try (Observation.Scope scope = parent.openScope()) {
            DatabaseQuerySpans.QuerySpan query = querySpans.start("select 1");
            query.stop(1);
            query.stop(-1);
        }
        parent.stop();

        Assertions.assertEquals(1,
                meters.get(ObservationNames.DB_QUERY).timer().count());
    }
}
