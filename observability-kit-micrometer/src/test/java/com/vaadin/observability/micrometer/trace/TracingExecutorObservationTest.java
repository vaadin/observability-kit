/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TracingExecutorObservationTest {

    private static final class NameRecorder
            implements ObservationHandler<Observation.Context> {

        final List<String> names = new ArrayList<>();
        final List<String> contextualNames = new ArrayList<>();
        final AtomicBoolean errored = new AtomicBoolean();

        @Override
        public void onStop(Observation.Context ctx) {
            names.add(ctx.getName());
            contextualNames.add(ctx.getContextualName());
            if (ctx.getError() != null) {
                errored.set(true);
            }
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }

    @Test
    void everyTaskEmitsUiAccessObservation() {
        ObservationRegistry obs = ObservationRegistry.create();
        NameRecorder recorder = new NameRecorder();
        obs.observationConfig().observationHandler(recorder);

        Executor inline = Runnable::run;
        TracingExecutor te = new TracingExecutor(inline, obs);

        te.execute(() -> {
        });
        te.execute(() -> {
        });

        Assertions.assertEquals(2, recorder.names.size());
        Assertions.assertEquals(ObservationNames.UI_ACCESS,
                recorder.names.get(0));
        Assertions.assertEquals(ObservationNames.UI_ACCESS,
                recorder.contextualNames.get(0));
    }

    @Test
    void exceptionsArePropagatedAndRecordedOnObservation() {
        ObservationRegistry obs = ObservationRegistry.create();
        NameRecorder recorder = new NameRecorder();
        obs.observationConfig().observationHandler(recorder);

        TracingExecutor te = new TracingExecutor(Runnable::run, obs);

        Assertions.assertThrows(IllegalStateException.class,
                () -> te.execute(() -> {
                    throw new IllegalStateException("boom");
                }));
        Assertions.assertTrue(recorder.errored.get());
    }

    @Test
    void noObservationWhenRegistryAbsent() {
        ObservationRegistry obs = ObservationRegistry.create();
        NameRecorder recorder = new NameRecorder();
        obs.observationConfig().observationHandler(recorder);

        TracingExecutor te = new TracingExecutor(Runnable::run);
        AtomicBoolean ran = new AtomicBoolean();

        te.execute(() -> ran.set(true));

        Assertions.assertTrue(ran.get());
        Assertions.assertTrue(recorder.names.isEmpty(),
                "no observation should be emitted when registry is null");
    }
}
