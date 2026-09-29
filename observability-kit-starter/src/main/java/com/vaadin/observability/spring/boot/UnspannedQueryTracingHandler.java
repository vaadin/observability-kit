/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring.boot;

import io.micrometer.observation.Observation;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;

/**
 * Keeps queries past the span limit out of the trace. Spring Boot registers its
 * tracing handlers as one first-matching group, so this handler, ordered ahead
 * of the default one, takes every
 * {@link DatabaseQuerySpans.UnspannedQueryContext} and does nothing with it:
 * the tracer never sees the observation and no span is made. The meter handlers
 * are a group of their own and still time it.
 * <p>
 * It leaves an empty tracing context behind, since Spring Boot's tracing-aware
 * meter handler requires one on every observation it stops.
 */
final class UnspannedQueryTracingHandler
        implements TracingObservationHandler<Observation.Context> {

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof DatabaseQuerySpans.UnspannedQueryContext;
    }

    @Override
    public void onStart(Observation.Context context) {
        getTracingContext(context);
    }

    @Override
    public void onScopeOpened(Observation.Context context) {
    }

    @Override
    public void onScopeClosed(Observation.Context context) {
    }

    @Override
    public void onScopeReset(Observation.Context context) {
    }

    @Override
    public void onEvent(Observation.Event event, Observation.Context context) {
    }

    @Override
    public void onError(Observation.Context context) {
    }

    @Override
    public Tracer getTracer() {
        return Tracer.NOOP;
    }
}
