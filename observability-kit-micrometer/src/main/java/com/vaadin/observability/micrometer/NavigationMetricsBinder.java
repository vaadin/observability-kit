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

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationListener;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterListener;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinRequestInterceptor;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.observability.micrometer.trace.ObservationNames;

/**
 * Times each navigation from {@code beforeEnter} to {@code afterNavigation}.
 * <p>
 * When an {@link ObservationRegistry} is supplied and
 * {@link ObservabilitySettings#isTraces()} is on, the navigation is observed
 * (producing both a span and, through a registered
 * {@code DefaultMeterObservationHandler}, the Timer). Otherwise the binder
 * falls back to direct Timer recording. Per-UI state is stored as a UI
 * attribute so concurrent UIs are tracked independently.
 * <p>
 * Not every navigation reaches {@code afterNavigation}: a {@code rerouteTo} or
 * {@code forwardTo} restarts the chain (so {@code beforeEnter} fires again),
 * and an exception thrown while instantiating the view abandons it altogether.
 * Such a navigation is closed out either by the {@code beforeEnter} that
 * supersedes it or, as a backstop, by {@code requestEnd}, and is recorded with
 * the {@link Outcome} its own redirect state implies. Without that, its span
 * would never be stopped and its {@link Observation.Scope} would stay open on
 * the request thread.
 */
final class NavigationMetricsBinder implements BeforeEnterListener,
        AfterNavigationListener, VaadinRequestInterceptor {

    private static final String PENDING_KEY = NavigationMetricsBinder.class
            .getName() + ".pending";

    /**
     * How a navigation ended, reported as the {@code outcome} tag. Rerouting
     * and forwarding are ordinary routing decisions (an access guard sending
     * the user elsewhere), so they are kept apart from genuine failures.
     * <p>
     * The Timer tag and the span attribute carry the same values but come from
     * the two separate name registries, so both stay under their own contract.
     */
    private enum Outcome {

        SUCCESS(MeterNames.OUTCOME_SUCCESS, ObservationNames.OUTCOME_SUCCESS),
        ERROR(MeterNames.OUTCOME_ERROR, ObservationNames.OUTCOME_ERROR),
        REROUTED(MeterNames.OUTCOME_REROUTED,
                ObservationNames.OUTCOME_REROUTED),
        FORWARDED(MeterNames.OUTCOME_FORWARDED,
                ObservationNames.OUTCOME_FORWARDED);

        private final String meterTag;
        private final String spanAttribute;

        Outcome(String meterTag, String spanAttribute) {
            this.meterTag = meterTag;
            this.spanAttribute = spanAttribute;
        }
    }

    /**
     * Transient state of the navigation currently in flight on a UI. The
     * observation, its scope and the timer sample are all tied to the thread
     * that started them, which is recorded so a leftover navigation is never
     * unwound on a foreign thread. The event is kept because Flow only marks it
     * as forwarded or rerouted after the listener chain has returned, i.e.
     * after {@code beforeEnter} finished.
     */
    private record Pending(String route, BeforeEnterEvent event,
            Timer.Sample sample, Observation observation,
            Observation.Scope scope, Thread thread) {
    }

    private final MeterRegistry registry;
    private final ObservationRegistry observationRegistry;
    private final ObservabilitySettings config;
    private final RouteTagResolver routes;
    /** The UI whose navigation is unfinished on this request thread. */
    private final ThreadLocal<UI> pendingUi = new ThreadLocal<>();

    NavigationMetricsBinder(MeterRegistry registry, RouteTagResolver routes) {
        this(registry, null, ObservabilitySettings.builder().build(), routes);
    }

    NavigationMetricsBinder(MeterRegistry registry,
            ObservationRegistry observationRegistry,
            ObservabilitySettings config, RouteTagResolver routes) {
        this.registry = registry;
        this.observationRegistry = observationRegistry;
        this.config = config;
        this.routes = routes;
    }

    private boolean useObservation() {
        return config.isTraces() && observationRegistry != null;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        UI ui = event.getUI();
        // rerouteTo/forwardTo re-runs the navigation chain, so beforeEnter can
        // fire more than once per request. The superseded navigation never
        // reaches afterNavigation: close it out before overwriting the state,
        // or its span dangles and its scope stays open on this thread.
        finish(ui, null);
        String route = routes.tagFor(event.getNavigationTarget());
        // Persist the route up front (before the view renders) so
        // out-of-runtime
        // instrumentation (e.g. the DataSource fetch-size proxy) attributes
        // even
        // construction-time queries on this request thread to the target view.
        VaadinTelemetryContext.setCurrentRoute(ui, route);
        Pending pending;
        if (useObservation()) {
            // Tell the enclosing request span this UIDL request navigated.
            RequestInteraction.mark(ObservationNames.INTERACTION_NAVIGATION);
            Observation obs = Observation
                    .createNotStarted(MeterNames.NAVIGATION,
                            observationRegistry)
                    .contextualName(ObservationNames.NAVIGATION + " " + route)
                    .lowCardinalityKeyValue(ObservationNames.KEY_ROUTE, route)
                    .start();
            pending = new Pending(route, event, null, obs, obs.openScope(),
                    Thread.currentThread());
        } else {
            pending = new Pending(route, event, Timer.start(registry), null,
                    null, Thread.currentThread());
        }
        ComponentUtil.setData(ui, PENDING_KEY, pending);
        pendingUi.set(ui);
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        finish(event.getLocationChangeEvent().getUI(), Outcome.SUCCESS);
    }

    @Override
    public void requestStart(VaadinRequest request, VaadinResponse response) {
        // Drop any marker left by a previous request whose requestEnd was
        // skipped (e.g. mid-request server shutdown), so this request never
        // unwinds a navigation belonging to another one.
        pendingUi.remove();
    }

    @Override
    public void handleException(VaadinRequest request, VaadinResponse response,
            VaadinSession session, Exception t) {
        // Nothing to do: requestEnd closes the pending navigation, if any.
    }

    @Override
    public void requestEnd(VaadinRequest request, VaadinResponse response,
            VaadinSession session) {
        // Backstop for a navigation that started but never completed, e.g. one
        // aborted by an exception while the view was being instantiated, or
        // forwarded to an external URL (which redirects instead of re-running
        // the chain). It has to be unwound here, on the thread that opened the
        // scope, before the thread is recycled.
        finish(pendingUi.get(), null);
    }

    /**
     * Stops the navigation in flight on {@code ui}, if any. Safe to call when
     * nothing is pending.
     *
     * @param outcome
     *            the outcome to record, or {@code null} to derive it from the
     *            navigation's own redirect state
     */
    private void finish(UI ui, Outcome outcome) {
        if (ui == null) {
            return;
        }
        Object data = ComponentUtil.getData(ui, PENDING_KEY);
        ComponentUtil.setData(ui, PENDING_KEY, null);
        pendingUi.remove();
        if (!(data instanceof Pending pending)) {
            return;
        }
        Outcome resolved = outcome != null ? outcome
                : outcomeOf(pending.event());
        if (pending.sample() != null) {
            pending.sample()
                    .stop(registry.timer(MeterNames.NAVIGATION,
                            MeterNames.TAG_ROUTE, pending.route(),
                            MeterNames.TAG_OUTCOME, resolved.meterTag));
        }
        // A scope may only be closed on the thread that opened it; doing it
        // from another thread would restore that thread's observation onto
        // this one. Leftovers from a dead request are dropped instead.
        if (pending.scope() != null
                && pending.thread() == Thread.currentThread()) {
            pending.scope().close();
        }
        if (pending.observation() != null) {
            pending.observation()
                    .lowCardinalityKeyValue(ObservationNames.KEY_OUTCOME,
                            resolved.spanAttribute)
                    .stop();
        }
    }

    /**
     * Classifies a navigation that never reached {@code afterNavigation} by
     * what the listener chain did to it.
     */
    private static Outcome outcomeOf(BeforeEnterEvent event) {
        if (event.hasErrorParameter()) {
            // rerouteToError(...): the navigation failed and was handed to an
            // error view, so this really is an error.
            return Outcome.ERROR;
        }
        if (event.hasForwardTarget() || event.hasUnknownForward()
                || event.hasExternalForwardUrl()) {
            return Outcome.FORWARDED;
        }
        if (event.hasRerouteTarget() || event.hasUnknownReroute()) {
            return Outcome.REROUTED;
        }
        // Abandoned without a redirect, e.g. the view threw while it was being
        // instantiated.
        return Outcome.ERROR;
    }
}
