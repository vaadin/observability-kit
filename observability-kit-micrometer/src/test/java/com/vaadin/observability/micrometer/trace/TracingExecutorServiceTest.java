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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TracingExecutorServiceTest {

    private static final class NameRecorder
            implements ObservationHandler<Observation.Context> {

        final List<String> names = new ArrayList<>();

        @Override
        public void onStop(Observation.Context ctx) {
            names.add(ctx.getName());
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }

    private final ExecutorService delegate = Executors
            .newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        delegate.shutdownNow();
    }

    @Test
    void wrapKeepsExecutorServiceContract() {
        Executor wrapped = TracingExecutor.wrap(delegate,
                ObservationRegistry.create());
        Assertions.assertInstanceOf(ExecutorService.class, wrapped);
    }

    @Test
    void wrapReturnsPlainExecutorForPlainDelegate() {
        Executor wrapped = TracingExecutor.wrap(Runnable::run,
                ObservationRegistry.create());
        Assertions.assertInstanceOf(TracingExecutor.class, wrapped);
        Assertions.assertFalse(wrapped instanceof ExecutorService);
    }

    @Test
    void wrapIsIdempotent() {
        Executor once = TracingExecutor.wrap(delegate,
                ObservationRegistry.create());
        Assertions.assertSame(once,
                TracingExecutor.wrap(once, ObservationRegistry.create()));
    }

    @Test
    void submittedTasksAreObserved()
            throws ExecutionException, InterruptedException {
        ObservationRegistry obs = ObservationRegistry.create();
        NameRecorder recorder = new NameRecorder();
        obs.observationConfig().observationHandler(recorder);

        ExecutorService wrapped = (ExecutorService) TracingExecutor
                .wrap(delegate, obs);

        wrapped.submit(() -> {
        }).get();
        Future<String> result = wrapped.submit(() -> "done");
        Assertions.assertEquals("done", result.get());

        List<Callable<String>> tasks = List.of(() -> "a", () -> "b");
        Assertions.assertEquals(2, wrapped.invokeAll(tasks).size());

        Assertions.assertEquals(4, recorder.names.size());
        Assertions.assertTrue(recorder.names.stream()
                .allMatch(ObservationNames.UI_ACCESS::equals));
    }

    @Test
    void lifecycleMethodsAreDelegated() throws InterruptedException {
        ExecutorService wrapped = (ExecutorService) TracingExecutor
                .wrap(delegate, ObservationRegistry.create());

        Assertions.assertFalse(wrapped.isShutdown());
        wrapped.shutdown();
        Assertions.assertTrue(delegate.isShutdown());
        Assertions.assertTrue(wrapped.isShutdown());
        Assertions.assertTrue(wrapped.awaitTermination(5, TimeUnit.SECONDS));
        Assertions.assertTrue(wrapped.isTerminated());
    }
}
