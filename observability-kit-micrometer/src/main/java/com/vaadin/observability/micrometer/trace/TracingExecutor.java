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
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Wraps the Vaadin service {@link Executor} so that
 * <ol>
 * <li>the trace context (and any other
 * {@link io.micrometer.context.ThreadLocalAccessor}-backed state) active when a
 * task is <em>submitted</em> is restored when the task <em>runs</em>; and</li>
 * <li>each task gets its own {@code vaadin.ui.access} span when an
 * {@link ObservationRegistry} is supplied. The span is parented to the
 * propagated trace, so work handed to the executor from a request thread ends
 * up with a continuous trace tree across the thread hop.</li>
 * </ol>
 * <p>
 * The service executor is what Vaadin uses to dispatch signal effects and
 * result notifications, and what applications are expected to use for their own
 * background tasks (typically ones that end with {@code UI.access(...)}); it is
 * not used by {@code UI.access} itself, which runs pending commands on the
 * thread that unlocks the session.
 * <p>
 * Use {@link #wrap(Executor, ObservationRegistry)} rather than the constructors
 * so that an {@link ExecutorService} delegate keeps its lifecycle methods:
 * Vaadin shuts its default executor down on service destroy, and only does so
 * when the executor still is an {@link ExecutorService}.
 */
public class TracingExecutor implements Executor {

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

    /**
     * Wraps the given executor for tracing, preserving the
     * {@link ExecutorService} contract when the delegate implements it.
     * <p>
     * Wrapping is idempotent: an executor that already traces is returned
     * as-is.
     *
     * @param delegate
     *            the executor to wrap, not {@code null}
     * @param observationRegistry
     *            the registry used to create the per-task span, may be
     *            {@code null} to only propagate the context
     * @return the tracing executor
     */
    public static Executor wrap(Executor delegate,
            ObservationRegistry observationRegistry) {
        Objects.requireNonNull(delegate, "delegate");
        if (delegate instanceof TracingExecutor) {
            return delegate;
        }
        if (delegate instanceof ExecutorService executorService) {
            return new TracingExecutorService(executorService,
                    observationRegistry);
        }
        return new TracingExecutor(delegate, observationRegistry);
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(instrument(command));
    }

    /**
     * Captures the current context and returns a task that restores it and
     * opens a {@code vaadin.ui.access} span before running {@code command}.
     * <p>
     * Must be called on the submitting thread, since that is where the context
     * to propagate lives.
     *
     * @param command
     *            the task to instrument, not {@code null}
     * @return the instrumented task
     */
    protected final Runnable instrument(Runnable command) {
        ContextSnapshot snapshot = snapshotFactory.captureAll();
        return snapshot.wrap(() -> {
            if (observationRegistry == null) {
                command.run();
                return;
            }
            Observation observation = startObservation();
            try (Observation.Scope ignored = observation.openScope()) {
                command.run();
            } catch (Throwable t) {
                observation.error(t);
                throw t;
            } finally {
                observation.stop();
            }
        });
    }

    /**
     * {@link Callable} counterpart of {@link #instrument(Runnable)}, used by
     * the {@link ExecutorService} submission methods.
     *
     * @param task
     *            the task to instrument, not {@code null}
     * @param <T>
     *            the task result type
     * @return the instrumented task
     */
    protected final <T> Callable<T> instrument(Callable<T> task) {
        ContextSnapshot snapshot = snapshotFactory.captureAll();
        return snapshot.wrap(() -> {
            if (observationRegistry == null) {
                return task.call();
            }
            Observation observation = startObservation();
            try (Observation.Scope ignored = observation.openScope()) {
                return task.call();
            } catch (Throwable t) {
                observation.error(t);
                throw t;
            } finally {
                observation.stop();
            }
        });
    }

    private Observation startObservation() {
        return Observation
                .createNotStarted(ObservationNames.UI_ACCESS,
                        observationRegistry)
                .contextualName(ObservationNames.UI_ACCESS).start();
    }

    /** Exposed for tests. */
    Executor delegate() {
        return delegate;
    }
}
