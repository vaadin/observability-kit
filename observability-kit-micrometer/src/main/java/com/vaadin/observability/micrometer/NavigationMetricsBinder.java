/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

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
import com.vaadin.flow.router.ListenerPriority;
import com.vaadin.flow.router.NavigationEndedEvent;
import com.vaadin.flow.router.NavigationStartedEvent;
import com.vaadin.flow.router.Router;
import com.vaadin.flow.router.internal.NavigationRouteTarget;
import com.vaadin.flow.server.VaadinServiceEventBus;
import com.vaadin.flow.shared.Registration;
import com.vaadin.observability.micrometer.trace.ObservationNames;

/**
 * Times each navigation from the {@link NavigationStartedEvent} to the
 * {@link NavigationEndedEvent} that Flow fires on the
 * {@link com.vaadin.flow.server.VaadinService#getEventBus() service event bus}.
 * <p>
 * When an {@link ObservationRegistry} is supplied and
 * {@link ObservabilitySettings#isTraces()} is on, the navigation is observed
 * (producing both a span and, through a registered
 * {@code DefaultMeterObservationHandler}, the Timer). Otherwise the binder
 * falls back to direct Timer recording.
 * <p>
 * Flow fires both events on the same thread, and the ended event also when the
 * navigation throws, so the navigations in flight are kept on a thread-local
 * stack and the observation scope is always closed on the thread that opened
 * it. A forward, a reroute or an error view is part of the navigation that
 * caused it and fires no events of its own, so each navigation is recorded
 * once.
 * <p>
 * The {@code route} tag is the view the requested location resolves to, so a
 * navigation that was redirected elsewhere is recorded under the view that was
 * asked for. A location no view resolves to, such as an unknown URL, is
 * recorded under the view the navigation showed, e.g. the "not found" error
 * view, or as {@link MeterNames#ROUTE_UNKNOWN} when it showed none. Such a
 * navigation is tagged {@link MeterNames#ROUTE_UNKNOWN} until it ends, so that
 * meters registered when it starts, like the
 * {@code DefaultMeterObservationHandler}'s long task timer, carry the same tag
 * keys as for any other navigation. The {@code outcome} tag maps Flow's
 * {@link NavigationEndedEvent.Outcome}:
 * <ul>
 * <li>{@code success}: the requested view was shown;</li>
 * <li>{@code rerouted}: another view, or the same view with other route
 * parameters, was shown after a {@code rerouteTo} or {@code forwardTo};</li>
 * <li>{@code forwarded}: no view was shown, e.g. after a {@code forwardToUrl}
 * or a hand-off to a client-side route;</li>
 * <li>{@code error}: the navigation failed;</li>
 * <li>{@code unknown}: the navigation was postponed by a
 * {@code BeforeLeaveEvent} listener.</li>
 * </ul>
 * <p>
 * {@link VaadinTelemetryContext} names the view whose code runs: the view being
 * left while its {@code BeforeLeaveEvent} listeners run, then each view the
 * navigation enters, from its first {@code BeforeEnterEvent} listener on; see
 * {@link RouteTracker}.
 * <p>
 * Both paths publish {@link MeterNames#NAVIGATION} with the same tag keys:
 * {@code route}, {@code outcome} and {@code error}. A failed navigation is
 * reported through {@code outcome} rather than as an errored observation, so
 * {@code error} is always {@link MeterNames#ERROR_NONE} — the key is still
 * emitted because {@code DefaultMeterObservationHandler} emits it on the
 * Observation path, and a metrics backend such as Prometheus rejects same-named
 * meters whose tag-key sets differ.
 */
final class NavigationMetricsBinder {

    private static final String ROUTE_TRACKER_KEY = NavigationMetricsBinder.class
            .getName() + ".routeTracker";

    /**
     * Transient state of a navigation in flight on this thread.
     *
     * @param requested
     *            the view the requested location resolves to and its route
     *            parameters, or {@code null} if no view does
     * @param route
     *            the route tag of the requested view, or {@code null} if there
     *            is none
     * @param previousRoute
     *            the UI's current route when the navigation started, or
     *            {@code null} if it had none
     */
    private record Pending(UI ui, NavigationRouteTarget requested, String route,
            String previousRoute, Timer.Sample sample, Observation observation,
            Observation.Scope scope) {
    }

    /**
     * Keeps {@link VaadinTelemetryContext} on the view a UI's navigation is
     * entering. Flow fires the UI's {@code BeforeEnterEvent} listeners for
     * every view a navigation enters, before building it: the requested view,
     * the target of a {@code rerouteTo} or {@code forwardTo}, and an error
     * view, including the "not found" view of an unknown location. So work done
     * while building any of them, like a query in a constructor, is attributed
     * to that view, while the {@code BeforeLeaveEvent} listeners that run
     * before, like an autosave, are still attributed to the view being left.
     * The {@code AfterNavigationEvent} listener settles on the view shown.
     * <p>
     * It is annotated with the highest {@link ListenerPriority}, so Flow runs
     * it before every other {@code BeforeEnterEvent} listener of the UI, in
     * whatever order they were added. A listener such as an access check that
     * looks up the user is then attributed to the view being entered, while the
     * view being left keeps its {@code BeforeLeaveEvent} listeners, which run
     * before any of them.
     * <p>
     * One per UI, added when the UI first navigates and kept for the life of
     * the UI. So it also follows a postponed navigation resumed with
     * {@code proceed()}, which fires no navigation events, and any navigation
     * started from inside another one.
     * <p>
     * Static and {@link java.io.Serializable}, as the UI listeners it is
     * registered as are, so it is serialized with the session without the
     * binder. The resolver is not carried along: a deserialized tracker does
     * nothing, and is replaced when its UI next navigates.
     */
    @ListenerPriority(Integer.MAX_VALUE)
    private static final class RouteTracker
            implements BeforeEnterListener, AfterNavigationListener {

        private static final long serialVersionUID = 1L;

        private final UI ui;
        private final transient RouteTagResolver routes;
        private Registration beforeEnter;
        private Registration afterNavigation;

        RouteTracker(UI ui, RouteTagResolver routes) {
            this.ui = ui;
            this.routes = routes;
        }

        /**
         * Whether this tracker can resolve routes, i.e. was not deserialized.
         */
        boolean isWorking() {
            return routes != null;
        }

        @Override
        public void beforeEnter(BeforeEnterEvent event) {
            if (routes != null) {
                VaadinTelemetryContext.setCurrentRoute(ui,
                        routes.tagFor(event.getNavigationTarget()));
            }
        }

        @Override
        public void afterNavigation(AfterNavigationEvent event) {
            String shown = routes == null ? null : shownRoute(routes, ui);
            if (shown != null) {
                VaadinTelemetryContext.setCurrentRoute(ui, shown);
            }
        }

        /**
         * Detaches the tracker from its UI. The UI forgets it first, so a
         * failure below leaves nothing that looks like a working tracker.
         */
        void remove() {
            if (ComponentUtil.getData(ui, ROUTE_TRACKER_KEY) == this) {
                ComponentUtil.setData(ui, ROUTE_TRACKER_KEY, null);
            }
            try {
                if (beforeEnter != null) {
                    beforeEnter.remove();
                }
            } finally {
                if (afterNavigation != null) {
                    afterNavigation.remove();
                }
            }
        }
    }

    private final MeterRegistry registry;
    private final ObservationRegistry observationRegistry;
    private final ObservabilitySettings config;
    private final RouteTagResolver routes;

    /**
     * The navigations in flight on this thread, innermost last. Navigations of
     * different UIs can nest, e.g. one UI navigating another from an
     * {@code AfterNavigationEvent} listener, and Flow ends them in reverse
     * order. Removed once empty, so a pooled thread keeps no UI reachable.
     */
    private final ThreadLocal<Deque<Pending>> pending = new ThreadLocal<>();

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

    /**
     * Subscribes to the navigation events on the given bus.
     *
     * @param eventBus
     *            the service event bus to listen on
     * @return a handle removing every subscription made here
     */
    Registration register(VaadinServiceEventBus eventBus) {
        return Registration.combine(
                eventBus.addListener(NavigationStartedEvent.class,
                        this::navigationStarted),
                eventBus.addListener(NavigationEndedEvent.class,
                        this::navigationEnded));
    }

    private boolean useObservation() {
        return config.isTraces() && observationRegistry != null;
    }

    void navigationStarted(NavigationStartedEvent event) {
        UI ui = event.getUI();
        NavigationRouteTarget requested = requestedTarget(event);
        String route = requested == null ? null
                : routes.tagFor(requested.getRouteTarget().getTarget());
        // The context keeps naming the view being left: its BeforeLeaveEvent
        // listeners run first, and may postpone the navigation altogether.
        // The tracker moves it on as the navigation enters a view.
        track(ui);
        String previousRoute = VaadinTelemetryContext.currentRoute(ui);
        // Relay the UI for route resolution at request end. Unconditional:
        // the HTTP route enrichment works without tracing.
        RequestUi.mark(ui);
        Timer.Sample sample = null;
        Observation obs = null;
        Observation.Scope scope = null;
        if (useObservation()) {
            // Tell the enclosing request span this UIDL request navigated.
            RequestInteraction.mark(ObservationNames.INTERACTION_NAVIGATION);
            // Always tagged with a route: the meters registered on start must
            // have the same tag keys as the ones registered on stop.
            obs = nameAfterRoute(
                    Observation.createNotStarted(MeterNames.NAVIGATION,
                            observationRegistry),
                    route != null ? route : MeterNames.ROUTE_UNKNOWN).start();
            scope = obs.openScope();
        } else {
            sample = Timer.start(registry);
        }
        // Nothing that can fail between opening the scope and storing the
        // navigation that closes it.
        Pending started = new Pending(ui, requested, route, previousRoute,
                sample, obs, scope);
        Deque<Pending> inFlight = pending.get();
        if (inFlight == null) {
            inFlight = new ArrayDeque<>();
            pending.set(inFlight);
        }
        inFlight.addLast(started);
    }

    void navigationEnded(NavigationEndedEvent event) {
        Pending ended = take(event.getUI());
        if (ended == null) {
            // Started before this binder was subscribed.
            return;
        }
        NavigationEndedEvent.Outcome outcome = event.getOutcome();
        String route = ended.route() != null ? ended.route()
                : MeterNames.ROUTE_UNKNOWN;
        String resolved = MeterNames.OUTCOME_UNKNOWN;
        try {
            String shown = outcome instanceof NavigationEndedEvent.Completed completed
                    ? routes.tagFor(completed.navigationTarget())
                    : shownRoute(routes, ended.ui());
            // The view the UI shows now: the requested one, the one it was
            // redirected to, an error view, or the previous view when nothing
            // new was shown. When that cannot be told, the route from before
            // the navigation, rather than a view it entered and did not show.
            VaadinTelemetryContext.setCurrentRoute(ended.ui(),
                    shown != null ? shown : ended.previousRoute());
            // A location without a view falls back to the view this
            // navigation showed: the redirected one or the error view. A
            // navigation that showed nothing new, or threw instead of showing
            // an error view (no status code), leaves the previous view, which
            // says nothing about it.
            boolean showedView = outcome instanceof NavigationEndedEvent.Completed
                    || outcome instanceof NavigationEndedEvent.Failed
                            && event.getStatusCode() != -1;
            route = ended.route() != null ? ended.route()
                    : showedView && shown != null ? shown
                            : MeterNames.ROUTE_UNKNOWN;
            resolved = outcomeOf(outcome, ended.requested());
        } finally {
            // Whatever failed above, the scope must not stay open on this
            // thread, where it would parent every later span.
            if (ended.sample() != null) {
                ended.sample().stop(registry.timer(MeterNames.NAVIGATION,
                        MeterNames.TAG_ROUTE, route, MeterNames.TAG_OUTCOME,
                        resolved, MeterNames.TAG_ERROR, MeterNames.ERROR_NONE));
            }
            if (ended.observation() != null) {
                ended.scope().close();
                nameAfterRoute(ended.observation(), route)
                        .lowCardinalityKeyValue(ObservationNames.KEY_OUTCOME,
                                resolved)
                        .stop();
            }
        }
    }

    /**
     * Adds the {@link RouteTracker} of {@code ui}, unless it has a working one
     * already. Called when a navigation starts, before Flow collects the UI's
     * listeners for it. Best-effort enrichment of a measurement, so it never
     * throws: a tracker that could not be added, e.g. without the session lock
     * that adding a listener requires, is tried again on the UI's next
     * navigation.
     *
     * @param ui
     *            the UI to track
     */
    private void track(UI ui) {
        try {
            if (ComponentUtil.getData(ui,
                    ROUTE_TRACKER_KEY) instanceof RouteTracker existing) {
                if (existing.isWorking()) {
                    return;
                }
                existing.remove();
            }
            RouteTracker tracker = new RouteTracker(ui, routes);
            tracker.beforeEnter = ui.addBeforeEnterListener(tracker);
            try {
                tracker.afterNavigation = ui
                        .addAfterNavigationListener(tracker);
            } catch (RuntimeException e) {
                tracker.remove();
                throw e;
            }
            ComponentUtil.setData(ui, ROUTE_TRACKER_KEY, tracker);
        } catch (RuntimeException e) {
            // Only the route attribution of this UI's work is lost.
        }
    }

    /**
     * Removes and returns the innermost navigation in flight on this thread for
     * {@code ui}, or {@code null} if there is none.
     */
    private Pending take(UI ui) {
        Deque<Pending> inFlight = pending.get();
        if (inFlight == null) {
            return null;
        }
        Pending found = null;
        for (Iterator<Pending> it = inFlight.descendingIterator(); it
                .hasNext();) {
            Pending candidate = it.next();
            if (candidate.ui() == ui) {
                it.remove();
                found = candidate;
                break;
            }
        }
        if (inFlight.isEmpty()) {
            pending.remove();
        }
        return found;
    }

    private static Observation nameAfterRoute(Observation obs, String route) {
        return obs.contextualName(ObservationNames.NAVIGATION + " " + route)
                .lowCardinalityKeyValue(ObservationNames.KEY_ROUTE, route);
    }

    /**
     * The view the requested location resolves to, with its route parameters,
     * or {@code null} when none does or it cannot be resolved. Resolved through
     * the UI's router, whose registry includes the session-scoped routes while
     * the session is bound to this thread, as it is while Flow navigates.
     */
    private static NavigationRouteTarget requestedTarget(
            NavigationStartedEvent event) {
        try {
            Router router = event.getUI().getInternals().getRouter();
            if (router == null) {
                return null;
            }
            NavigationRouteTarget target = router.getRegistry()
                    .getNavigationRouteTarget(event.getLocation().getPath());
            return target != null && target.hasTarget() ? target : null;
        } catch (RuntimeException e) {
            // Best-effort enrichment of a measurement; never break navigation.
            return null;
        }
    }

    /**
     * The route of the view a UI shows after a navigation that did not show the
     * requested view, or {@code null} when it cannot be resolved.
     */
    private static String shownRoute(RouteTagResolver routes, UI ui) {
        try {
            return routes.tagForUi(ui, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Maps Flow's outcome to the {@code outcome} tag value.
     *
     * @param requested
     *            the view the requested location resolves to, or {@code null}
     */
    private static String outcomeOf(NavigationEndedEvent.Outcome outcome,
            NavigationRouteTarget requested) {
        return switch (outcome) {
        // A target or route parameters other than the requested ones mean a
        // listener sent the navigation elsewhere: a routing decision (an
        // access guard sending the user to the login view), not a failure.
        // Flow does not tell a rerouteTo from a forwardTo here, so both are
        // rerouted.
        case NavigationEndedEvent.Completed completed ->
            requested == null || requested.getRouteTarget()
                    .getTarget() == completed.navigationTarget()
                    && completed.parameters()
                            .equals(requested.getRouteParameters())
                                    ? MeterNames.OUTCOME_SUCCESS
                                    : MeterNames.OUTCOME_REROUTED;
        // Handed off without a server-side view: forwardToUrl, a client-side
        // route, or a @PreserveOnRefresh view waiting for the window name.
        case NavigationEndedEvent.NotShown notShown ->
            MeterNames.OUTCOME_FORWARDED;
        case NavigationEndedEvent.Failed failed -> MeterNames.OUTCOME_ERROR;
        // Neither success nor failure: the navigation may still be resumed
        // later, which Flow does not report as a navigation of its own.
        case NavigationEndedEvent.Postponed postponed ->
            MeterNames.OUTCOME_UNKNOWN;
        };
    }
}
