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

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.NavigationEndedEvent;
import com.vaadin.flow.router.NavigationStartedEvent;
import com.vaadin.flow.router.Router;
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
 * view, or as {@link MeterNames#ROUTE_UNKNOWN} when it showed none. The
 * {@code outcome} tag maps Flow's {@link NavigationEndedEvent.Outcome}:
 * <ul>
 * <li>{@code success}: the requested view was shown;</li>
 * <li>{@code rerouted}: another view was shown, after a {@code rerouteTo} or
 * {@code forwardTo};</li>
 * <li>{@code forwarded}: no view was shown, e.g. after a {@code forwardToUrl}
 * or a hand-off to a client-side route;</li>
 * <li>{@code error}: the navigation failed;</li>
 * <li>{@code unknown}: the navigation was postponed by a
 * {@code BeforeLeaveEvent} listener.</li>
 * </ul>
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

    /**
     * Transient state of a navigation in flight on this thread.
     *
     * @param target
     *            the view the requested location resolves to, or {@code null}
     *            if none does
     * @param route
     *            the route tag of {@code target}, or {@code null} if there is
     *            none
     */
    private record Pending(UI ui, Class<? extends Component> target,
            String route, Timer.Sample sample, Observation observation,
            Observation.Scope scope) {
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
        Class<? extends Component> target = requestedTarget(event);
        String route = target == null ? null : routes.tagFor(target);
        if (route != null) {
            // Persist the route up front (before the view renders) so
            // out-of-runtime instrumentation (e.g. the DataSource fetch-size
            // proxy) attributes even construction-time queries on this
            // request thread to the target view.
            VaadinTelemetryContext.setCurrentRoute(ui, route);
        }
        // Relay the UI for route resolution at request end. Unconditional:
        // the HTTP route enrichment works without tracing.
        RequestUi.mark(ui);
        Pending started;
        if (useObservation()) {
            // Tell the enclosing request span this UIDL request navigated.
            RequestInteraction.mark(ObservationNames.INTERACTION_NAVIGATION);
            Observation obs = Observation.createNotStarted(
                    MeterNames.NAVIGATION, observationRegistry);
            if (route != null) {
                nameAfterRoute(obs, route);
            }
            obs.start();
            started = new Pending(ui, target, route, null, obs,
                    obs.openScope());
        } else {
            started = new Pending(ui, target, route, Timer.start(registry),
                    null, null);
        }
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
        String shown = outcome instanceof NavigationEndedEvent.Completed completed
                ? routes.tagFor(completed.navigationTarget())
                : shownRoute(ended.ui());
        if (shown != null) {
            // The view the UI shows now: the requested one, the one it was
            // redirected to, an error view, or the previous view when nothing
            // new was shown.
            VaadinTelemetryContext.setCurrentRoute(ended.ui(), shown);
        }
        // A location without a view falls back to the view this navigation
        // showed: the redirected one or the error view. A navigation that
        // showed nothing new, or threw instead of showing an error view (no
        // status code), leaves the previous view, which says nothing about it.
        boolean showedView = outcome instanceof NavigationEndedEvent.Completed
                || outcome instanceof NavigationEndedEvent.Failed
                        && event.getStatusCode() != -1;
        String route = ended.route() != null ? ended.route()
                : showedView && shown != null ? shown
                        : MeterNames.ROUTE_UNKNOWN;
        String resolved = outcomeOf(outcome, ended.target());
        if (ended.sample() != null) {
            ended.sample().stop(registry.timer(MeterNames.NAVIGATION,
                    MeterNames.TAG_ROUTE, route, MeterNames.TAG_OUTCOME,
                    resolved, MeterNames.TAG_ERROR, MeterNames.ERROR_NONE));
        }
        if (ended.observation() != null) {
            ended.scope().close();
            if (ended.route() == null) {
                nameAfterRoute(ended.observation(), route);
            }
            ended.observation().lowCardinalityKeyValue(
                    ObservationNames.KEY_OUTCOME, resolved).stop();
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

    private static void nameAfterRoute(Observation obs, String route) {
        obs.contextualName(ObservationNames.NAVIGATION + " " + route)
                .lowCardinalityKeyValue(ObservationNames.KEY_ROUTE, route);
    }

    /**
     * The view the requested location resolves to, or {@code null} when none
     * does or it cannot be resolved. Resolved through the UI's router, whose
     * registry includes the session-scoped routes while the session is bound to
     * this thread, as it is while Flow navigates.
     */
    private static Class<? extends Component> requestedTarget(
            NavigationStartedEvent event) {
        try {
            Router router = event.getUI().getInternals().getRouter();
            if (router == null) {
                return null;
            }
            return router.getRegistry()
                    .getNavigationTarget(event.getLocation().getPath())
                    .orElse(null);
        } catch (RuntimeException e) {
            // Best-effort enrichment of a measurement; never break navigation.
            return null;
        }
    }

    /**
     * The route of the view a UI shows after a navigation that did not show the
     * requested view, or {@code null} when it cannot be resolved.
     */
    private String shownRoute(UI ui) {
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
            Class<? extends Component> requested) {
        return switch (outcome) {
        // A target other than the requested one means a listener sent the
        // navigation elsewhere: a routing decision (an access guard sending
        // the user to the login view), not a failure. Flow does not tell a
        // rerouteTo from a forwardTo here, so both are rerouted.
        case NavigationEndedEvent.Completed completed ->
            requested == null || requested == completed.navigationTarget()
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
