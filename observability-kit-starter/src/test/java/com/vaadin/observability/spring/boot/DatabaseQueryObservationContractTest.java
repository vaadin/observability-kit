/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring.boot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Pins the span surface of the one Observation this module emits,
 * {@code vaadin.db.query}. The wider surface of the core module is pinned by
 * {@code ObservationContractTest} in {@code observability-kit-micrometer}
 * against a golden file; a single observation is pinned here with direct
 * assertions instead. A failure means the public span contract changed, which
 * is breaking for dashboards and trace queries, so update deliberately and call
 * the change out in the release notes.
 */
class DatabaseQueryObservationContractTest {

    private static final class RecordingHandler
            implements ObservationHandler<Observation.Context> {

        Observation.Context stopped;

        @Override
        public void onStop(Observation.Context ctx) {
            stopped = ctx;
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }

    @Test
    void dbQuerySpanSurfaceIsStable() {
        RecordingHandler recorder = new RecordingHandler();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(recorder);

        // Statement capture on, rows known: the maximal attribute surface.
        DatabaseQuerySpans spans = new DatabaseQuerySpans(observations, true);
        spans.start("select 1").stop(5);

        Observation.Context ctx = recorder.stopped;
        Assertions.assertNotNull(ctx, "the query span should have stopped");
        Assertions.assertEquals("vaadin.db.query", ctx.getName());
        Assertions.assertEquals("vaadin.db.query", ctx.getContextualName());
        Assertions.assertEquals(Set.of("route"),
                keys(ctx.getLowCardinalityKeyValues()),
                "low-cardinality keys become Timer tags; adding one changes "
                        + "the vaadin.db.query meter for every dashboard");
        Assertions.assertEquals(Set.of("db.rows", "db.statement"),
                keys(ctx.getHighCardinalityKeyValues()),
                "high-cardinality keys are span-only attributes");
    }

    /**
     * Fails when a class in this module starts emitting Observations without
     * being pinned here, mirroring the tripwire in the core module's
     * {@code ObservationContractTest}.
     */
    @Test
    void everyObservationEmitterIsPinned() throws IOException {
        Set<String> actual;
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            actual = sources.filter(p -> p.toString().endsWith(".java")).filter(
                    DatabaseQueryObservationContractTest::createsObservations)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        Assertions.assertEquals(Set.of("DatabaseQuerySpans.java"), actual,
                "the set of observation-emitting classes changed: pin the new "
                        + "emitter's span surface in a contract test");
    }

    private static boolean createsObservations(Path source) {
        try {
            return Files.readString(source).contains("createNotStarted");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> keys(Iterable<KeyValue> keyValues) {
        Set<String> keys = new TreeSet<>();
        for (KeyValue kv : keyValues) {
            keys.add(kv.getKey());
        }
        return keys;
    }
}
