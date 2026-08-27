/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 * Such a navigation is closed out by the {@code beforeEnter} that supersedes
 * it, by {@code requestEnd} as a request-scoped backstop, or by
 * {@code uiDetached} for one started off-request through {@code UI.access()}.
 * Without that, its span would never be stopped and its
 * {@link Observation.Scope} would stay open on the request thread.
 * <p>
 * The recorded {@link Outcome} comes from the navigation's own redirect state.
 * One carrying no redirect flag at all is classified by where it was closed out
 * from: {@code error} at {@code requestEnd}, where the view being instantiated
 * is what failed the navigation, and {@code unknown} otherwise, where it was
 * merely superseded or its UI went away.
 * <p>
 * Both paths publish {@link MeterNames#NAVIGATION} with the same tag keys:
 * {@code route}, {@code outcome} and {@code error}. A failed navigation is
 * reported through {@code outcome} rather than as an errored observation, so
 * {@code error} is always {@link MeterNames#ERROR_NONE} — the key is still
 * emitted because {@code DefaultMeterObservationHandler} emits it on the
 * Observation path, and a metrics backend such as Prometheus rejects same-named
 * meters whose tag-key sets differ.
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
     * One value per outcome, used for both the Timer tag and the span
     * attribute: {@link ObservationNames} aliases the outcome vocabulary from
     * {@link MeterNames}, so there is nothing to keep in step here.
     */
    private enum Outcome {

        SUCCESS(MeterNames.OUTCOME_SUCCESS),
        ERROR(MeterNames.OUTCOME_ERROR),
        REROUTED(MeterNames.OUTCOME_REROUTED),
        FORWARDED(MeterNames.OUTCOME_FORWARDED),
        UNKNOWN(MeterNames.OUTCOME_UNKNOWN);

        private final String value;

        Outcome(String value) {
            this.value = value;
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
    /**
     * The UIs with an unfinished navigation on this request thread. A request
     * can touch more than one UI of the same session — the access tasks queued
     * for it are run on the request thread — so this is a set rather than a
     * single slot: one UI completing must not cost another its backstop.
     */
    private final ThreadLocal<Set<UI>> pendingUis = new ThreadLocal<>();

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
        // Without a redirect flag it was superseded by a re-entrant
        // UI.navigate() rather than failed, hence UNKNOWN rather than ERROR.
        finish(ui, null, Outcome.UNKNOWN);
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
        Set<UI> marked = pendingUis.get();
        if (marked == null) {
            marked = new LinkedHashSet<>();
            pendingUis.set(marked);
        }
        marked.add(ui);
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        finish(event.getLocationChangeEvent().getUI(), Outcome.SUCCESS, null);
    }

    @Override
    public void requestStart(VaadinRequest request, VaadinResponse response) {
        // Drop any markers left by a previous request whose requestEnd was
        // skipped (e.g. mid-request server shutdown), so this request never
        // unwinds a navigation belonging to another one.
        pendingUis.remove();
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
        //
        // This interceptor is registered after RequestMetricsBinder so that
        // Flow, which runs interceptors in reverse registration order, calls
        // this method first: the navigation scope has to close while the
        // enclosing request scope is still open, or closing it would restore
        // the already stopped request observation onto the thread.
        Set<UI> marked = pendingUis.get();
        if (marked == null) {
            return;
        }
        // Over a copy: finish removes the UI it closes out from the set. The
        // most recently marked UI first, so nested scopes unwind in order.
        List<UI> uis = new ArrayList<>(marked);
        for (int i = uis.size() - 1; i >= 0; i--) {
            finish(uis.get(i), null, Outcome.ERROR);
        }
    }

    /**
     * Closes out a navigation left open on a UI that is going away, so the
     * entry cannot outlive the UI.
     * <p>
     * A navigation started from {@code UI.access()} on a background thread
     * never passes through {@code requestStart}/{@code requestEnd}, so the
     * request-scoped backstop cannot reach it. Nothing about a detached UI says
     * whether its last navigation succeeded, hence {@link Outcome#UNKNOWN}.
     *
     * @param ui
     *            the UI being detached
     */
    void uiDetached(UI ui) {
        finish(ui, null, Outcome.UNKNOWN);
    }

    /**
     * Stops the navigation in flight on {@code ui}, if any. Safe to call when
     * nothing is pending.
     *
     * @param outcome
     *            the outcome to record, or {@code null} to derive it from the
     *            navigation's own redirect state
     * @param abandoned
     *            the outcome for a navigation carrying no redirect flag at all;
     *            what that means depends on where it is closed out from, so the
     *            caller decides. Unused when {@code outcome} is given.
     */
    private void finish(UI ui, Outcome outcome, Outcome abandoned) {
        if (ui == null) {
            return;
        }
        Object data = ComponentUtil.getData(ui, PENDING_KEY);
        ComponentUtil.setData(ui, PENDING_KEY, null);
        // Only this UI's marker may be dropped: a request that touches two UIs
        // would otherwise lose the first UI's marker to the second UI's
        // afterNavigation, and the requestEnd backstop would never fire for it.
        Set<UI> marked = pendingUis.get();
        if (marked != null) {
            marked.remove(ui);
            if (marked.isEmpty()) {
                pendingUis.remove();
            }
        }
        if (!(data instanceof Pending pending)) {
            return;
        }
        Outcome resolved = outcome != null ? outcome
                : outcomeOf(pending.event(), abandoned);
        if (pending.sample() != null) {
            pending.sample()
                    .stop(registry.timer(MeterNames.NAVIGATION,
                            MeterNames.TAG_ROUTE, pending.route(),
                            MeterNames.TAG_OUTCOME, resolved.value,
                            MeterNames.TAG_ERROR, MeterNames.ERROR_NONE));
        }
        // A scope may only be closed on the thread that opened it; doing it
        // from another thread would restore that thread's observation onto
        // this one. Leftovers from a dead request are dropped instead.
        if (pending.scope() != null
                && pending.thread() == Thread.currentThread()) {
            pending.scope().close();
        }
        if (pending.observation() != null) {
            pending.observation().lowCardinalityKeyValue(
                    ObservationNames.KEY_OUTCOME, resolved.value).stop();
        }
    }

    /**
     * Classifies a navigation that never reached {@code afterNavigation} by
     * what the listener chain did to it.
     *
     * @param abandoned
     *            the outcome to fall back to when the chain left no redirect
     *            flag behind
     */
    private static Outcome outcomeOf(BeforeEnterEvent event,
            Outcome abandoned) {
        if (event.hasErrorParameter()) {
            // rerouteToError(...): the navigation failed and was handed to an
            // error view, so this really is an error.
            return Outcome.ERROR;
        }
        if (event.hasForwardTarget() || event.hasUnknownForward()
                || event.hasExternalForwardUrl()) {
            // An unknown forward target is a hand-off to a client-side route:
            // the server-side navigation genuinely ends here.
            return Outcome.FORWARDED;
        }
        if (event.hasRerouteTarget()) {
            // hasUnknownReroute() is deliberately not checked: Flow only logs
            // an unknown reroute target and carries on, so such a navigation
            // still reaches afterNavigation and is never classified here.
            return Outcome.REROUTED;
        }
        return abandoned;
    }
}
