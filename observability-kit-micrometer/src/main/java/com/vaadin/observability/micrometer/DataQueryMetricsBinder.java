/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.time.Duration;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import com.vaadin.flow.server.VaadinServiceEventBus;
import com.vaadin.flow.server.data.AbstractDataCountEvent;
import com.vaadin.flow.server.data.AbstractDataFetchEvent;
import com.vaadin.flow.server.data.DataCountEndedEvent;
import com.vaadin.flow.server.data.DataCountFailedEvent;
import com.vaadin.flow.server.data.DataCountStartedEvent;
import com.vaadin.flow.server.data.DataFetchEndedEvent;
import com.vaadin.flow.server.data.DataFetchFailedEvent;
import com.vaadin.flow.server.data.DataFetchStartedEvent;
import com.vaadin.flow.shared.Registration;
import com.vaadin.observability.micrometer.trace.ObservationNames;

/**
 * Measures the data provider queries a lazy-loading component makes, and
 * records spans for them.
 * <p>
 * These queries run while the response is being built, after RPC handling, so
 * they are invisible to {@link RpcMetricsBinder}: the {@code setViewportRange}
 * invocation that triggers a load only registers a flush, and returns long
 * before the data provider is asked anything. Without this binder the time a
 * {@code Grid}, {@code ComboBox} or {@code TreeGrid} spends loading items is
 * attributable only to the request as a whole.
 * <p>
 * Count and fetch queries are measured separately because they have different
 * cost profiles and different causes: a count is one query for a whole level, a
 * fetch loads one page. A hierarchical component issues one count per expanded
 * parent, so many counts within one request is the signature of an expensive
 * hierarchy.
 * <p>
 * Timer tags are kept low cardinality: {@code outcome} and {@code filtered}
 * only. The component class goes on the span, never on a tag, exactly as
 * {@link RpcMetricsBinder} treats {@code vaadin.rpc.component}. The row
 * summaries are tagged by route, whose cardinality is already bounded by
 * {@link RouteTagResolver}.
 * <p>
 * <b>Threading.</b> The events of one query arrive on the same thread, so the
 * in-flight timing state is kept in thread locals. That thread is the request
 * thread for count queries, but a component configured for asynchronous updates
 * runs its fetches on its own executor, so fetch state is kept separately from
 * count state rather than sharing one slot.
 */
final class DataQueryMetricsBinder {

    private final MeterRegistry registry;
    private final ObservationRegistry observationRegistry;
    private final boolean useObservation;
    private final RouteTagResolver routes;

    private final ThreadLocal<Timer.Sample> countSample = new ThreadLocal<>();
    private final ThreadLocal<Observation> countObservation = new ThreadLocal<>();
    private final ThreadLocal<Observation.Scope> countScope = new ThreadLocal<>();
    private final ThreadLocal<Long> countStart = new ThreadLocal<>();

    private final ThreadLocal<Timer.Sample> fetchSample = new ThreadLocal<>();
    private final ThreadLocal<Observation> fetchObservation = new ThreadLocal<>();
    private final ThreadLocal<Observation.Scope> fetchScope = new ThreadLocal<>();
    private final ThreadLocal<Long> fetchStart = new ThreadLocal<>();

    DataQueryMetricsBinder(MeterRegistry registry,
            ObservationRegistry observationRegistry,
            ObservabilitySettings settings) {
        this.registry = registry;
        this.observationRegistry = observationRegistry;
        this.useObservation = observationRegistry != null
                && settings.isTraces();
        this.routes = new RouteTagResolver(settings.getRouteCardinalityLimit());
    }

    /**
     * Subscribes to the data query events on the given bus.
     *
     * @param eventBus
     *            the service event bus to listen on
     * @return a handle removing every subscription made here
     */
    Registration register(VaadinServiceEventBus eventBus) {
        return Registration.combine(
                eventBus.addListener(DataCountStartedEvent.class,
                        this::countStarted),
                eventBus.addListener(DataCountFailedEvent.class,
                        this::countFailed),
                eventBus.addListener(DataCountEndedEvent.class,
                        this::countEnded),
                eventBus.addListener(DataFetchStartedEvent.class,
                        this::fetchStarted),
                eventBus.addListener(DataFetchFailedEvent.class,
                        this::fetchFailed),
                eventBus.addListener(DataFetchEndedEvent.class,
                        this::fetchEnded));
    }

    // ---------- count ----------

    void countStarted(DataCountStartedEvent event) {
        // Drop anything a previous query left behind, e.g. if its ended event
        // never arrived because the server was shutting down.
        clearCount();
        if (useObservation) {
            Observation obs = Observation
                    .createNotStarted(MeterNames.DATA_COUNT_DURATION,
                            observationRegistry)
                    .contextualName(ObservationNames.DATA_COUNT)
                    .lowCardinalityKeyValue(MeterNames.TAG_FILTERED,
                            Boolean.toString(event.isFiltered()));
            component(event).ifPresent(c -> obs.highCardinalityKeyValue(
                    ObservationNames.KEY_DATA_COMPONENT, c));
            obs.start();
            countObservation.set(obs);
            countScope.set(obs.openScope());
        } else {
            countSample.set(Timer.start(registry));
        }
        countStart.set(System.nanoTime());
    }

    void countFailed(DataCountFailedEvent event) {
        Observation obs = countObservation.get();
        if (obs != null && event.getError() != null) {
            obs.error(event.getError());
        }
    }

    void countEnded(DataCountEndedEvent event) {
        // A count of -1 is the contract's way of saying the query threw.
        boolean failed = event.getCount() < 0;
        String outcome = failed ? MeterNames.OUTCOME_ERROR
                : MeterNames.OUTCOME_SUCCESS;

        Timer.Sample sample = countSample.get();
        Observation obs = countObservation.get();
        Observation.Scope scope = countScope.get();
        Long started = countStart.get();
        clearCount();
        // A no-op observation can still hand back a scope that makes itself
        // current, so it is closed on every path and not only when the
        // observation is what records the timing. A scope left open outlives
        // the query on a pooled request thread.
        if (scope != null) {
            scope.close();
        }

        if (obs != null && !obs.isNoop()) {
            obs.lowCardinalityKeyValue(ObservationNames.KEY_OUTCOME, outcome);
            obs.stop();
        } else if (sample != null) {
            sample.stop(countTimer(event, outcome));
        } else if (started != null) {
            // Reached when the observation turned out to be a no-op, which
            // records nothing: time it directly rather than lose the query.
            countTimer(event, outcome)
                    .record(Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private Timer countTimer(AbstractDataCountEvent event, String outcome) {
        return Timer.builder(MeterNames.DATA_COUNT_DURATION)
                .tag(MeterNames.TAG_OUTCOME, outcome)
                .tag(MeterNames.TAG_FILTERED,
                        Boolean.toString(event.isFiltered()))
                .register(registry);
    }

    private void clearCount() {
        countSample.remove();
        countObservation.remove();
        countScope.remove();
        countStart.remove();
    }

    // ---------- fetch ----------

    void fetchStarted(DataFetchStartedEvent event) {
        clearFetch();
        if (useObservation) {
            Observation obs = Observation
                    .createNotStarted(MeterNames.DATA_FETCH_DURATION,
                            observationRegistry)
                    .contextualName(ObservationNames.DATA_FETCH)
                    .lowCardinalityKeyValue(MeterNames.TAG_FILTERED,
                            Boolean.toString(event.isFiltered()))
                    .highCardinalityKeyValue(ObservationNames.KEY_DATA_OFFSET,
                            Integer.toString(event.getOffset()))
                    .highCardinalityKeyValue(ObservationNames.KEY_DATA_LIMIT,
                            Integer.toString(event.getLimit()));
            component(event).ifPresent(c -> obs.highCardinalityKeyValue(
                    ObservationNames.KEY_DATA_COMPONENT, c));
            obs.start();
            fetchObservation.set(obs);
            fetchScope.set(obs.openScope());
        } else {
            fetchSample.set(Timer.start(registry));
        }
        fetchStart.set(System.nanoTime());
    }

    void fetchFailed(DataFetchFailedEvent event) {
        Observation obs = fetchObservation.get();
        if (obs != null && event.getError() != null) {
            obs.error(event.getError());
        }
    }

    void fetchEnded(DataFetchEndedEvent event) {
        int rows = event.getRowsReturned();
        boolean failed = rows < 0;
        String outcome = failed ? MeterNames.OUTCOME_ERROR
                : MeterNames.OUTCOME_SUCCESS;

        Timer.Sample sample = fetchSample.get();
        Observation obs = fetchObservation.get();
        Observation.Scope scope = fetchScope.get();
        Long started = fetchStart.get();
        clearFetch();
        // See countEnded: the scope is closed on every path.
        if (scope != null) {
            scope.close();
        }

        if (obs != null && !obs.isNoop()) {
            obs.lowCardinalityKeyValue(ObservationNames.KEY_OUTCOME, outcome);
            if (!failed) {
                obs.highCardinalityKeyValue(ObservationNames.KEY_DATA_ROWS,
                        Integer.toString(rows));
            }
            obs.stop();
        } else if (sample != null) {
            sample.stop(fetchTimer(event, outcome));
        } else if (started != null) {
            // See countEnded: a no-op observation records nothing.
            fetchTimer(event, outcome)
                    .record(Duration.ofNanos(System.nanoTime() - started));
        }

        if (!failed) {
            try {
                // Asked-for against came-back: a persistent gap means the
                // component is over-fetching or the provider is returning
                // short pages.
                String route = routes.tagForUi(event.getUI(),
                        MeterNames.ROUTE_UNKNOWN);
                summary(MeterNames.DATA_FETCH_REQUESTED, route)
                        .record(event.getLimit());
                summary(MeterNames.DATA_FETCH_ROWS, route).record(rows);
            } catch (RuntimeException e) {
                // Recording is best-effort enrichment. Letting this out would
                // lose both summaries and log a bus error on every fetch.
            }
        }
    }

    private Timer fetchTimer(AbstractDataFetchEvent event, String outcome) {
        return Timer.builder(MeterNames.DATA_FETCH_DURATION)
                .tag(MeterNames.TAG_OUTCOME, outcome)
                .tag(MeterNames.TAG_FILTERED,
                        Boolean.toString(event.isFiltered()))
                .register(registry);
    }

    private void clearFetch() {
        fetchSample.remove();
        fetchObservation.remove();
        fetchScope.remove();
        fetchStart.remove();
    }

    // ---------- shared ----------

    private DistributionSummary summary(String name, String route) {
        return DistributionSummary.builder(name)
                .tag(MeterNames.TAG_ROUTE, route).register(registry);
    }

    private static java.util.Optional<String> component(
            AbstractDataCountEvent event) {
        return event.getComponent().map(c -> c.getClass().getName());
    }

    private static java.util.Optional<String> component(
            AbstractDataFetchEvent event) {
        return event.getComponent().map(c -> c.getClass().getName());
    }

}
