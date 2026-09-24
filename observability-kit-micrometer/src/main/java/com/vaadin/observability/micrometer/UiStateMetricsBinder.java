/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationListener;
import com.vaadin.flow.server.SessionDestroyEvent;
import com.vaadin.flow.server.SessionDestroyListener;
import com.vaadin.flow.server.UIInitEvent;
import com.vaadin.flow.server.UIInitListener;
import com.vaadin.flow.server.VaadinServiceEventBus;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.communication.RpcInvocationEndedEvent;
import com.vaadin.flow.shared.Registration;
import com.vaadin.observability.micrometer.insights.GrowingViewState;

/**
 * Publishes how much UI state the server is holding for live users, not just
 * how many of them there are.
 * <p>
 * {@code vaadin.sessions.active} and {@code vaadin.ui.active} count users and
 * browser tabs; this binder measures their <em>size</em>, which is the signal
 * that predicts when a server-driven application has to scale, because Flow
 * keeps each tab's component tree in server memory. The measurement itself is
 * {@link UiStateSampler}; this class decides when it runs, and folds the
 * results into the gauges.
 * <p>
 * <strong>How UIs get measured.</strong> A state tree may only be read under
 * its own session lock, so this binder never walks into another user's session.
 * Every UI reports itself, from a thread that already holds the right lock:
 * <ul>
 * <li>at UI init, so a new browser tab is accounted for immediately,</li>
 * <li>after each navigation, because that is when a tree changes shape,</li>
 * <li>when an RPC invocation ends — any interaction, in any session — throttled
 * to at most one walk per UI per
 * {@link ObservabilitySettings#getUiStateSampleInterval()} milliseconds, so a
 * burst of events costs one tree walk rather than one per event.</li>
 * </ul>
 * A consequence worth knowing rather than hiding: the aggregate contains an
 * idle user's UI as it was at their last interaction. The
 * {@code vaadin.ui.state.sample.age.max} gauge publishes how stale the oldest
 * sample in it is, so a reading can be judged instead of trusted.
 * <p>
 * <strong>What is tracked.</strong> Every UI whose walk succeeded, for as long
 * as it belongs to a session. An entry is dropped by the UI's own detach
 * listener, or by its session being destroyed; the second of those catches a UI
 * this binder never saw initialize — one restored from a serialized session, or
 * one whose first walk threw — so an interaction is enough to start tracking a
 * UI and nothing is held past the life of its session.
 * <p>
 * <strong>State outside the tree.</strong> Unless
 * {@link ObservabilitySettings#getUiStateGrowthSamples()} is zero, each walk
 * also reads the collections views hold in their own fields, and this binder
 * follows each one — the same field of the same view instance — from one
 * measurement of its UI to the next. A field that has grown at that many
 * measurements without shrinking in between is counted in
 * {@code vaadin.ui.state.retained.growing}, and reported to the insights
 * endpoint with its class and field name through {@link #growing()}: the view
 * that keeps appending what it loads, which heap grows by while every tree
 * gauge stays flat.
 * <p>
 * <strong>Cardinality.</strong> The gauges are aggregates — totals and maxima
 * across all UIs — never one series per session or per UI, which would grow
 * unbounded with traffic. That is the same reason the kit keeps component-level
 * attribution off meter tags.
 * <p>
 * <strong>Serialization.</strong> This binder belongs to the service, not to
 * any one session: it tracks live state spanning every session, and its gauges
 * are bound to a registry it does not itself hold. The per-UI listeners
 * therefore reach it through {@link UiListeners}, whose reference to it is
 * {@code transient}, so a serialized session carries an inert stub rather than
 * a copy of the binder — a restored UI does no tree walks whose results nothing
 * reads, and the live binder picks it up again on its next interaction.
 */
final class UiStateMetricsBinder
        implements UIInitListener, SessionDestroyListener {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory
            .getLogger(UiStateMetricsBinder.class);

    /**
     * How long an aggregate is reused. A scrape reads every gauge in turn, and
     * without this each of them would fold the whole tracked map again.
     */
    private static final long TOTALS_CACHE_NANOS = 100_000_000L;

    /**
     * One tracked UI: its session, its last measurement, and how each
     * collection its views hold has moved across measurements.
     *
     * @param growth
     *            by {@link RetainedCollection#key()}, holding only the
     *            collections of the last measurement, so a view that left the
     *            tree takes its history with it
     * @param sessionId
     *            the session as insights report it, or {@code null} when
     *            nothing in this UI is being followed
     */
    private record Tracked(VaadinSession session, UiStateSample sample,
            Map<String, Growth> growth, String sessionId, int uiId) {
    }

    /**
     * How one view collection has moved since its run of growth began.
     *
     * @param latest
     *            the latest measurement of it
     * @param firstElements
     *            its size when the run began
     * @param growthSamples
     *            measurements that found it larger than the one before
     * @param firstSeen
     *            when the run began
     * @param lastGrew
     *            the latest measurement that found it larger, or
     *            {@code firstSeen} when none has yet
     */
    private record Growth(RetainedCollection latest, int firstElements,
            int growthSamples, Instant firstSeen, Instant lastGrew) {

        static Growth start(RetainedCollection collection, Instant now) {
            return new Growth(collection, collection.elements(), 0, now, now);
        }

        /**
         * This collection's history after one more measurement. Growth counts,
         * a shrink starts the run over, and an unchanged size leaves it as it
         * was: measurements follow interactions, not whatever adds to the
         * field, so most of them see no change either way.
         */
        Growth next(RetainedCollection collection, Instant now) {
            int elements = collection.elements();
            if (elements > latest.elements()) {
                return new Growth(collection, firstElements, growthSamples + 1,
                        firstSeen, now);
            }
            if (elements < latest.elements()) {
                return start(collection, now);
            }
            return new Growth(collection, firstElements, growthSamples,
                    firstSeen, lastGrew);
        }
    }

    /**
     * The aggregate the gauges publish.
     *
     * @param nodes
     *            state-tree nodes across all tracked UIs
     * @param components
     *            server-side component instances retained
     * @param views
     *            route targets and router layouts retained
     * @param staleViews
     *            how many of those are no longer part of their UI's active
     *            navigation
     * @param maxUiNodes
     *            nodes held by the largest single UI
     * @param maxSessionNodes
     *            nodes held by the largest single session
     * @param maxUisPerSession
     *            most UIs held open by one session
     * @param oldestSampleAtNanos
     *            {@link System#nanoTime()} reading of the stalest sample in the
     *            aggregate, or {@link Long#MAX_VALUE} when nothing is tracked —
     *            a sentinel rather than {@code 0}, which is a reading
     *            {@code nanoTime} may legitimately return
     * @param retainedElements
     *            elements held in view collection fields across all UIs
     * @param maxRetainedElements
     *            elements held by the largest single view field
     * @param growingFields
     *            view fields currently reported as growing
     */
    private record Totals(int nodes, int components, int views, int staleViews,
            int maxUiNodes, int maxSessionNodes, int maxUisPerSession,
            long oldestSampleAtNanos, long retainedElements,
            int maxRetainedElements, int growingFields) {

        static final Totals EMPTY = new Totals(0, 0, 0, 0, 0, 0, 0,
                Long.MAX_VALUE, 0, 0, 0);
    }

    private final long sampleIntervalNanos;
    private final int bytesPerNode;
    private final int growthSamples;
    private final boolean insightsDetails;
    private final long totalsCacheNanos;
    private transient Map<UI, Tracked> tracked = new ConcurrentHashMap<>();

    /**
     * Last aggregate and when it was computed. Deliberately not invalidated by
     * a new sample: on a busy server samples never stop arriving, and a cache
     * cleared by each of them would leave every gauge of a scrape folding the
     * whole map again — the cost this cache exists to avoid. Held for at most
     * {@link #totalsCacheNanos}, which is nothing next to the sampling interval
     * the figures are already limited by, and it keeps the gauges of one scrape
     * consistent with each other.
     */
    private transient volatile Totals cached;
    private transient volatile long cachedAtNanos;

    UiStateMetricsBinder(MeterRegistry registry,
            ObservabilitySettings settings) {
        this(registry, settings, TOTALS_CACHE_NANOS);
    }

    /**
     * @param totalsCacheNanos
     *            how long an aggregate is reused; {@code 0} in tests, so a
     *            gauge read right after a measurement sees it
     */
    UiStateMetricsBinder(MeterRegistry registry, ObservabilitySettings settings,
            long totalsCacheNanos) {
        this.sampleIntervalNanos = TimeUnit.MILLISECONDS
                .toNanos(settings.getUiStateSampleInterval());
        this.bytesPerNode = settings.getUiStateBytesPerNode();
        this.growthSamples = settings.getUiStateGrowthSamples();
        this.insightsDetails = settings.isInsightsDetails();
        this.totalsCacheNanos = totalsCacheNanos;

        // No base unit on the count gauges: Micrometer's Prometheus convention
        // appends it to the meter name, which would export
        // vaadin.ui.state.nodes as vaadin_ui_state_nodes_nodes.
        gauge(registry, MeterNames.UI_STATE_NODES,
                "State-tree nodes retained across all UIs — the UI state the "
                        + "server currently holds for live users",
                Totals::nodes);
        gauge(registry, MeterNames.UI_STATE_NODES_MAX,
                "State-tree nodes held by the largest single UI",
                Totals::maxUiNodes);
        gauge(registry, MeterNames.UI_STATE_COMPONENTS,
                "Server-side component instances retained across all UIs",
                Totals::components);
        gauge(registry, MeterNames.UI_STATE_VIEWS,
                "Route-target and router-layout instances retained across all "
                        + "UIs; one navigation into a nested layout retains "
                        + "one per level",
                Totals::views);
        gauge(registry, MeterNames.UI_STATE_VIEWS_STALE,
                "Retained views that are no longer part of their UI's active "
                        + "navigation, i.e. views that outlived it — normally "
                        + "zero",
                Totals::staleViews);
        gauge(registry, MeterNames.SESSION_STATE_NODES_MAX,
                "State-tree nodes held by the largest single session",
                Totals::maxSessionNodes);
        gauge(registry, MeterNames.SESSION_UIS_MAX,
                "Most UIs (browser tabs) held open by one session",
                Totals::maxUisPerSession);
        Gauge.builder(MeterNames.UI_STATE_SAMPLE_AGE_MAX, this,
                UiStateMetricsBinder::oldestSampleAgeSeconds)
                .description("Age of the stalest per-UI measurement in the "
                        + "aggregate: a UI is measured on its own session's "
                        + "thread, so an idle user's state is as old as their "
                        + "last interaction")
                .baseUnit("seconds").register(registry);
        if (bytesPerNode > 0) {
            Gauge.builder(MeterNames.UI_STATE_SIZE, this,
                    self -> (double) self.totals().nodes() * self.bytesPerNode)
                    .description(
                            "Retained UI state in bytes: node count times the "
                                    + "configured cost per node")
                    .baseUnit("bytes").register(registry);
        }
        if (growthSamples > 0) {
            gauge(registry, MeterNames.UI_STATE_RETAINED_ELEMENTS,
                    "Elements held in the collection fields of views across "
                            + "all UIs — state the state tree does not contain",
                    totals -> (double) totals.retainedElements());
            gauge(registry, MeterNames.UI_STATE_RETAINED_ELEMENTS_MAX,
                    "Elements held by the largest single view field",
                    Totals::maxRetainedElements);
            gauge(registry, MeterNames.UI_STATE_RETAINED_GROWING,
                    "View fields whose collection grew at " + growthSamples
                            + " measurements without shrinking in between — "
                            + "normally zero",
                    Totals::growingFields);
        }
    }

    /** Whether a collection has grown for long enough to be reported. */
    private boolean isGrowing(Growth growth) {
        return growth.growthSamples() >= growthSamples;
    }

    /**
     * The view collections currently growing, for the insights endpoint. Read
     * from the latest measurement of each UI, so this describes the UIs still
     * open, as of their last interaction.
     *
     * @return the growing view fields, in no particular order
     */
    List<GrowingViewState> growing() {
        if (growthSamples == 0) {
            return List.of();
        }
        List<GrowingViewState> growing = new ArrayList<>();
        for (Tracked entry : tracked.values()) {
            for (Growth growth : entry.growth().values()) {
                if (isGrowing(growth)) {
                    RetainedCollection latest = growth.latest();
                    growing.add(new GrowingViewState(latest.viewClass(),
                            latest.field(), latest.route(), latest.elements(),
                            growth.firstElements(), growth.growthSamples(),
                            growth.firstSeen(), growth.lastGrew(),
                            entry.sessionId(), entry.uiId()));
                }
            }
        }
        return growing;
    }

    private void gauge(MeterRegistry registry, String name, String description,
            ToDoubleFunction<Totals> value) {
        Gauge.builder(name, this, self -> value.applyAsDouble(self.totals()))
                .description(description).register(registry);
    }

    /**
     * Subscribes to the RPC invocation events on the given bus, so that any
     * interaction refreshes the UI it touched.
     *
     * @param eventBus
     *            the service event bus to listen on
     * @return a handle removing every subscription made here
     */
    Registration register(VaadinServiceEventBus eventBus) {
        return eventBus.addListener(RpcInvocationEndedEvent.class,
                this::invocationEnded);
    }

    /**
     * Starts tracking a UI and keeps its measurement fresh for the rest of its
     * life.
     */
    @Override
    public void uiInit(UIInitEvent event) {
        UI ui = event.getUI();
        sample(ui);
        UiListeners listeners = new UiListeners(this, ui);
        ui.addDetachListener(listeners);
        ui.addAfterNavigationListener(listeners);
    }

    /**
     * Re-measures the UI an interaction just touched, unless it was measured
     * within the sampling interval. A UI this binder never saw initialize — one
     * restored from a serialized session, or one whose first walk threw — is
     * picked up here rather than staying invisible for its whole life; its
     * session being destroyed drops it even without a detach listener of ours.
     * A UI whose walk keeps failing is retried on each interaction instead of
     * written off, because the reason is usually transient and a failed walk
     * gives up early.
     */
    void invocationEnded(RpcInvocationEndedEvent event) {
        UI ui = event.getUI();
        if (ui == null) {
            return;
        }
        Tracked current = tracked.get(ui);
        if (current != null && System.nanoTime()
                - current.sample().sampledAtNanos() < sampleIntervalNanos) {
            return;
        }
        sample(ui);
    }

    /**
     * Drops every UI of a destroyed session. UI detach normally does this one
     * tab at a time; a session that expires without its UIs being detached
     * would otherwise keep them in the aggregate.
     */
    @Override
    public void sessionDestroy(SessionDestroyEvent event) {
        VaadinSession session = event.getSession();
        tracked.entrySet()
                .removeIf(entry -> entry.getValue().session() == session);
    }

    /**
     * Measures a UI and records the result. Called only from threads holding
     * that UI's session lock — a request, navigation or RPC of its own session.
     * Instrumentation must not break the interaction it observes, so a failed
     * walk drops the sample instead of propagating.
     */
    private void sample(UI ui) {
        try {
            VaadinSession session = ui.getSession();
            // A UI with no session is either not attached yet or already gone.
            // Nothing would ever drop it from the map — session destroy matches
            // on session identity — so it is left out rather than retained for
            // the life of the service.
            if (session == null) {
                return;
            }
            UiStateSample sample = UiStateSampler.sample(ui, growthSamples > 0);
            Tracked previous = tracked.get(ui);
            Map<String, Growth> growth = follow(sample,
                    previous == null ? Map.of() : previous.growth());
            // Hashed once per UI rather than per measurement, and only once
            // there is something an insight could report.
            String sessionId = previous != null && previous.sessionId() != null
                    ? previous.sessionId()
                    : growth.isEmpty() ? null
                            : GrowingViewState.sessionIdOf(ui, insightsDetails);
            tracked.put(ui, new Tracked(session, sample, growth, sessionId,
                    ui.getUIId()));
        } catch (RuntimeException e) {
            LOGGER.debug(
                    "Could not measure the state tree of UI {}, "
                            + "its state is left out of the UI state metrics",
                    ui.getUIId(), e);
        }
    }

    /**
     * Carries each view collection's history over to a new measurement. Only
     * the collections the new measurement found are kept: a view that left the
     * tree, or was replaced by a fresh instance on navigation, starts over.
     */
    private static Map<String, Growth> follow(UiStateSample sample,
            Map<String, Growth> previous) {
        if (sample.retained().isEmpty()) {
            return Map.of();
        }
        Instant now = Instant.now();
        Map<String, Growth> next = new HashMap<>();
        for (RetainedCollection collection : sample.retained()) {
            Growth before = previous.get(collection.key());
            next.put(collection.key(),
                    before == null ? Growth.start(collection, now)
                            : before.next(collection, now));
        }
        return Map.copyOf(next);
    }

    private void forget(UI ui) {
        tracked.remove(ui);
    }

    /**
     * The per-UI hooks: a detach drops the UI from the aggregate, a navigation
     * re-measures it.
     * <p>
     * The binder is held {@code transient} on purpose. These listeners live on
     * the UI, so they are written out with a serialized session, and the binder
     * is service-wide state that has no business there — see the class javadoc.
     * A restored stub therefore does nothing, and the live binder picks the UI
     * up again on its next interaction.
     */
    private static final class UiListeners
            implements ComponentEventListener<DetachEvent>,
            AfterNavigationListener, Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private final transient UiStateMetricsBinder binder;
        private final transient UI ui;

        UiListeners(UiStateMetricsBinder binder, UI ui) {
            this.binder = binder;
            this.ui = ui;
        }

        @Override
        public void onComponentEvent(DetachEvent event) {
            if (binder != null) {
                binder.forget(ui);
            }
        }

        @Override
        public void afterNavigation(AfterNavigationEvent event) {
            if (binder != null) {
                binder.sample(ui);
            }
        }
    }

    /**
     * The aggregate, recomputed at most once per {@link #totalsCacheNanos}.
     */
    private Totals totals() {
        long now = System.nanoTime();
        Totals snapshot = cached;
        if (snapshot != null && now - cachedAtNanos < totalsCacheNanos) {
            return snapshot;
        }
        snapshot = computeTotals();
        cached = snapshot;
        cachedAtNanos = now;
        return snapshot;
    }

    private Totals computeTotals() {
        if (tracked.isEmpty()) {
            return Totals.EMPTY;
        }
        int nodes = 0;
        int components = 0;
        int views = 0;
        int staleViews = 0;
        int maxUiNodes = 0;
        long oldest = Long.MAX_VALUE;
        long retainedElements = 0;
        int maxRetainedElements = 0;
        int growingFields = 0;
        // Per session, so the two "largest single X" gauges can distinguish a
        // heavy tab from a user holding many of them.
        Map<VaadinSession, int[]> perSession = new HashMap<>();
        for (Tracked entry : tracked.values()) {
            UiStateSample sample = entry.sample();
            for (Growth growth : entry.growth().values()) {
                int elements = growth.latest().elements();
                retainedElements += elements;
                maxRetainedElements = Math.max(maxRetainedElements, elements);
                if (isGrowing(growth)) {
                    growingFields++;
                }
            }
            nodes += sample.nodes();
            components += sample.components();
            views += sample.views();
            staleViews += sample.staleViews();
            maxUiNodes = Math.max(maxUiNodes, sample.nodes());
            oldest = Math.min(oldest, sample.sampledAtNanos());
            int[] session = perSession.computeIfAbsent(entry.session(),
                    key -> new int[2]);
            session[0] += sample.nodes();
            session[1]++;
        }
        int maxSessionNodes = 0;
        int maxUisPerSession = 0;
        for (int[] session : perSession.values()) {
            maxSessionNodes = Math.max(maxSessionNodes, session[0]);
            maxUisPerSession = Math.max(maxUisPerSession, session[1]);
        }
        return new Totals(nodes, components, views, staleViews, maxUiNodes,
                maxSessionNodes, maxUisPerSession, oldest, retainedElements,
                maxRetainedElements, growingFields);
    }

    /** How stale the oldest measurement in the aggregate is, in seconds. */
    private double oldestSampleAgeSeconds() {
        long oldest = totals().oldestSampleAtNanos();
        if (oldest == Long.MAX_VALUE) {
            return 0;
        }
        return Math.max(0, System.nanoTime() - oldest) / 1_000_000_000.0;
    }

    @Serial
    private void readObject(ObjectInputStream in)
            throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        tracked = new ConcurrentHashMap<>();
    }

    /** Tracked UI count, for tests. */
    int trackedUis() {
        return tracked.size();
    }
}
