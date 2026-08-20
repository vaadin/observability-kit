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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.SessionDestroyEvent;
import com.vaadin.flow.server.SessionDestroyListener;
import com.vaadin.flow.server.UIInitEvent;
import com.vaadin.flow.server.UIInitListener;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.communication.RpcInvocationEvent;
import com.vaadin.flow.server.communication.RpcInvocationListener;

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
 * <strong>What is tracked.</strong> Only a UI that reported itself at init or
 * navigation, and only while it belongs to a session. Those are the two
 * conditions under which an entry can also be removed again — by the UI's own
 * detach listener, or by its session being destroyed — so tracking anything
 * else would retain a component tree that nothing ever drops. An interaction
 * with a UI that is not tracked is therefore ignored rather than used to start
 * tracking it.
 * <p>
 * <strong>Cardinality.</strong> The gauges are aggregates — totals and maxima
 * across all UIs — never one series per session or per UI, which would grow
 * unbounded with traffic. That is the same reason the kit keeps component-level
 * attribution off meter tags.
 * <p>
 * <strong>Serialization.</strong> The per-UI listeners registered below capture
 * this binder, so a serialized session would drag it along. What it tracks is
 * live server state spanning every session, none of which belongs in one
 * session's serialized form, so the map and its cache are {@code transient} and
 * a restored binder starts empty.
 */
final class UiStateMetricsBinder implements UIInitListener,
        SessionDestroyListener, RpcInvocationListener {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory
            .getLogger(UiStateMetricsBinder.class);

    /**
     * How long an aggregate is reused. A scrape reads every gauge in turn, and
     * without this each of them would fold the whole tracked map again.
     */
    private static final long TOTALS_CACHE_NANOS = 100_000_000L;

    /** One tracked UI: its session, and its last measurement. */
    private record Tracked(VaadinSession session, UiStateSample sample) {
    }

    /**
     * The aggregate the gauges publish.
     *
     * @param nodes
     *            state-tree nodes across all tracked UIs
     * @param components
     *            server-side component instances retained
     * @param viewInstances
     *            retained route targets
     * @param maxUiNodes
     *            nodes held by the largest single UI
     * @param maxSessionNodes
     *            nodes held by the largest single session
     * @param maxUisPerSession
     *            most UIs held open by one session
     * @param oldestSampleAtMillis
     *            when the stalest sample in the aggregate was taken, or
     *            {@code 0} when nothing is tracked
     */
    private record Totals(int nodes, int components, int viewInstances,
            int maxUiNodes, int maxSessionNodes, int maxUisPerSession,
            long oldestSampleAtMillis) {

        static final Totals EMPTY = new Totals(0, 0, 0, 0, 0, 0, 0);
    }

    private final long sampleIntervalMillis;
    private final int bytesPerNode;
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
        this.sampleIntervalMillis = settings.getUiStateSampleInterval();
        this.bytesPerNode = settings.getUiStateBytesPerNode();
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
                "Route-target instances retained across all UIs",
                Totals::viewInstances);
        gauge(registry, MeterNames.SESSION_STATE_NODES_MAX,
                "State-tree nodes held by the largest single session",
                Totals::maxSessionNodes);
        gauge(registry, MeterNames.SESSION_UIS_MAX,
                "Most UIs (browser tabs) held open by one session",
                Totals::maxUisPerSession);
        Gauge.builder(MeterNames.UI_STATE_SAMPLE_AGE_MAX, this,
                self -> self.oldestSampleAgeSeconds())
                .description("Age of the stalest per-UI measurement in the "
                        + "aggregate: a UI is measured on its own session's "
                        + "thread, so an idle user's state is as old as their "
                        + "last interaction")
                .baseUnit("seconds").register(registry);
        if (bytesPerNode > 0) {
            Gauge.builder(MeterNames.UI_STATE_SIZE, this,
                    self -> (double) self.totals().nodes() * bytesPerNode)
                    .description(
                            "Retained UI state in bytes: node count times the "
                                    + "configured cost per node")
                    .baseUnit("bytes").register(registry);
        }
    }

    private void gauge(MeterRegistry registry, String name, String description,
            ToDoubleFunction<Totals> value) {
        Gauge.builder(name, this, self -> value.applyAsDouble(self.totals()))
                .description(description).register(registry);
    }

    /**
     * Starts tracking a UI and keeps its measurement fresh for the rest of its
     * life.
     */
    @Override
    public void uiInit(UIInitEvent event) {
        UI ui = event.getUI();
        sample(ui);
        ui.addDetachListener(detach -> forget(ui));
        ui.addAfterNavigationListener(navigation -> sample(ui));
    }

    /**
     * Re-measures the UI an interaction just touched, unless it was measured
     * within the sampling interval. A UI that is not tracked is left alone:
     * tracking it here would add an entry that has no detach listener to remove
     * it again.
     */
    @Override
    public void invocationEnded(RpcInvocationEvent event) {
        UI ui = event.getUI();
        if (ui == null) {
            return;
        }
        Tracked current = tracked.get(ui);
        if (current == null || System.currentTimeMillis()
                - current.sample().sampledAtMillis() < sampleIntervalMillis) {
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
            tracked.put(ui, new Tracked(session, UiStateSampler.sample(ui)));
        } catch (RuntimeException e) {
            LOGGER.debug(
                    "Could not measure the state tree of UI {}, "
                            + "its state is left out of the UI state metrics",
                    ui.getUIId(), e);
        }
    }

    private void forget(UI ui) {
        tracked.remove(ui);
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
        int viewInstances = 0;
        int maxUiNodes = 0;
        long oldest = Long.MAX_VALUE;
        // Per session, so the two "largest single X" gauges can distinguish a
        // heavy tab from a user holding many of them.
        Map<VaadinSession, int[]> perSession = new HashMap<>();
        for (Tracked entry : tracked.values()) {
            UiStateSample sample = entry.sample();
            nodes += sample.nodes();
            components += sample.components();
            viewInstances += sample.viewInstances();
            maxUiNodes = Math.max(maxUiNodes, sample.nodes());
            oldest = Math.min(oldest, sample.sampledAtMillis());
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
        return new Totals(nodes, components, viewInstances, maxUiNodes,
                maxSessionNodes, maxUisPerSession,
                oldest == Long.MAX_VALUE ? 0 : oldest);
    }

    /** How stale the oldest measurement in the aggregate is, in seconds. */
    private double oldestSampleAgeSeconds() {
        long oldest = totals().oldestSampleAtMillis();
        if (oldest == 0) {
            return 0;
        }
        return Math.max(0, System.currentTimeMillis() - oldest) / 1000.0;
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
