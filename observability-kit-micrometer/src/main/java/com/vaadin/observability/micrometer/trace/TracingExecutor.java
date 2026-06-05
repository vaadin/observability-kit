/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.trace;

import java.util.Objects;
import java.util.concurrent.Executor;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Wraps a Vaadin service {@link Executor} so that
 * <ol>
 * <li>the trace context (and any other
 * {@link io.micrometer.context.ThreadLocalAccessor}-backed state) active when a
 * task is <em>submitted</em> is restored when the task <em>runs</em>; and</li>
 * <li>each task gets its own {@code vaadin.ui.access} span when an
 * {@link ObservationRegistry} is supplied. The span is parented to the
 * propagated trace, so a click that schedules a {@code UI.access(...)} ends up
 * with a continuous trace tree across thread hops.</li>
 * </ol>
 */
public final class TracingExecutor implements Executor {

    private final Executor delegate;
    private final ObservationRegistry observationRegistry;
    private final ContextSnapshotFactory snapshotFactory;

    public TracingExecutor(Executor delegate) {
        this(delegate, null);
    }

    public TracingExecutor(Executor delegate,
            ObservationRegistry observationRegistry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.observationRegistry = observationRegistry;
        this.snapshotFactory = ContextSnapshotFactory.builder().build();
    }

    @Override
    public void execute(Runnable command) {
        ContextSnapshot snapshot = snapshotFactory.captureAll();
        delegate.execute(snapshot.wrap(() -> {
            if (observationRegistry == null) {
                command.run();
                return;
            }
            Observation observation = Observation
                    .createNotStarted(ObservationNames.UI_ACCESS,
                            observationRegistry)
                    .contextualName(ObservationNames.UI_ACCESS).start();
            try (Observation.Scope ignored = observation.openScope()) {
                command.run();
            } catch (Throwable t) {
                observation.error(t);
                throw t;
            } finally {
                observation.stop();
            }
        }));
    }

    /** Exposed for tests. */
    Executor delegate() {
        return delegate;
    }
}
