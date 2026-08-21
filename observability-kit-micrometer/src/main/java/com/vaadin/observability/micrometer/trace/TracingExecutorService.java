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
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.micrometer.observation.ObservationRegistry;

/**
 * {@link TracingExecutor} that keeps the {@link ExecutorService} contract of
 * its delegate.
 * <p>
 * Vaadin shuts down the executor it created itself when the service is
 * destroyed, but only when the executor still is an {@link ExecutorService}. A
 * plain {@link java.util.concurrent.Executor} wrapper would therefore leak the
 * default executor, so {@link TracingExecutor#wrap} produces this subclass
 * whenever the delegate is an {@link ExecutorService}.
 * <p>
 * Lifecycle methods are delegated untouched; submitted tasks are instrumented
 * the same way as with {@link TracingExecutor#execute(Runnable)}.
 */
final class TracingExecutorService extends TracingExecutor
        implements ExecutorService {

    private final ExecutorService delegate;

    TracingExecutorService(ExecutorService delegate,
            ObservationRegistry observationRegistry) {
        super(delegate, observationRegistry);
        this.delegate = delegate;
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(instrument(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(instrument(task), result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(instrument(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(
            Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        return delegate.invokeAll(instrumentAll(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(
            Collection<? extends Callable<T>> tasks, long timeout,
            TimeUnit unit) throws InterruptedException {
        return delegate.invokeAll(instrumentAll(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        return delegate.invokeAny(instrumentAll(tasks));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks,
            long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(instrumentAll(tasks), timeout, unit);
    }

    private <T> List<Callable<T>> instrumentAll(
            Collection<? extends Callable<T>> tasks) {
        List<Callable<T>> instrumented = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            instrumented.add(instrument(task));
        }
        return instrumented;
    }
}
