/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import com.vaadin.flow.server.communication.RpcInvocationEvent;
import com.vaadin.flow.server.communication.RpcInvocationListener;
import com.vaadin.observability.micrometer.trace.ObservationNames;

/**
 * Measures server-side RPC invocation duration and records spans.
 * <p>
 * Two modes:
 * <ul>
 * <li>If {@code settings.isTraces()} and an {@link ObservationRegistry} is
 * supplied, each RPC invocation is driven through the Observation API. The
 * Observation name matches the Timer name ({@link MeterNames#RPC_DURATION}) so
 * a {@code DefaultMeterObservationHandler} produces the same Timer that the
 * direct-recording path would. The Observation's {@code contextualName} carries
 * the span-friendly name ({@code vaadin.rpc.<type>}) used by tracing
 * handlers.</li>
 * <li>Otherwise (no obs registry / traces disabled / observation handler
 * unavailable), the binder falls back to recording the Timer directly.</li>
 * </ul>
 * <p>
 * Tags: {@code type} (RPC invocation type) and {@code outcome}
 * ({@code success}/{@code error}). The invocation name and node ID are
 * deliberately omitted because they are high-cardinality.
 */
final class RpcMetricsBinder implements RpcInvocationListener {

    private final MeterRegistry registry;
    private final ObservationRegistry observationRegistry;
    private final boolean useObservation;

    private final ThreadLocal<Boolean> errored = ThreadLocal
            .withInitial(() -> Boolean.FALSE);
    private final ThreadLocal<Timer.Sample> sample = new ThreadLocal<>();
    private final ThreadLocal<Observation> observation = new ThreadLocal<>();
    private final ThreadLocal<Observation.Scope> observationScope = new ThreadLocal<>();

    RpcMetricsBinder(MeterRegistry registry,
            ObservationRegistry observationRegistry,
            ObservabilitySettings settings) {
        this.registry = registry;
        this.observationRegistry = observationRegistry;
        this.useObservation = observationRegistry != null
                && settings.isTraces();
    }

    @Override
    public void invocationStarted(RpcInvocationEvent event) {
        // Defensively clear any stale thread-local state left by a previous
        // invocation whose invocationEnded was skipped (e.g. mid-request
        // server shutdown). Without this, a pooled thread could carry
        // errored=TRUE into the next invocation and misreport it.
        errored.remove();
        sample.remove();
        observation.remove();
        observationScope.remove();

        // Mark the enclosing UIDL request span as an RPC interaction so the
        // RequestMetricsBinder labels the parent span appropriately.
        RequestInteraction.mark(ObservationNames.INTERACTION_RPC);

        String type = event.getType();
        if (useObservation) {
            Observation obs = Observation
                    .createNotStarted(MeterNames.RPC_DURATION,
                            observationRegistry)
                    .contextualName(ObservationNames.RPC + "." + type)
                    .lowCardinalityKeyValue(MeterNames.TAG_TYPE, type).start();
            observation.set(obs);
            observationScope.set(obs.openScope());
        } else {
            sample.set(Timer.start(registry));
        }
    }

    @Override
    public void invocationFailed(RpcInvocationEvent event, Throwable error) {
        errored.set(Boolean.TRUE);
        Observation obs = observation.get();
        if (obs != null && error != null) {
            obs.error(error);
        }
    }

    @Override
    public void invocationEnded(RpcInvocationEvent event) {
        boolean wasError = errored.get();
        String outcome = wasError ? MeterNames.OUTCOME_ERROR
                : MeterNames.OUTCOME_SUCCESS;
        String type = event.getType();

        Timer.Sample s = sample.get();
        Observation.Scope scope = observationScope.get();
        Observation obs = observation.get();

        // Clear all thread-locals before any calls that could throw.
        errored.remove();
        sample.remove();
        observationScope.remove();
        observation.remove();

        if (obs != null) {
            obs.lowCardinalityKeyValue(MeterNames.TAG_OUTCOME, outcome);
            if (scope != null) {
                scope.close();
            }
            obs.stop();
        } else if (s != null) {
            s.stop(Timer.builder(MeterNames.RPC_DURATION)
                    .tag(MeterNames.TAG_TYPE, type)
                    .tag(MeterNames.TAG_OUTCOME, outcome).register(registry));
        }
    }
}
