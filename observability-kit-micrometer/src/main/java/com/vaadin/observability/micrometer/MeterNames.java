/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

/**
 * Names of the meters published by Observability Kit. These form the public
 * telemetry contract scraped by metrics backends, so treat changes as breaking.
 */
public final class MeterNames {

    /** Gauge: number of currently active Vaadin sessions. */
    public static final String SESSIONS_ACTIVE = "vaadin.sessions.active";

    public static final String SESSIONS_CREATED = "vaadin.sessions.created";
    public static final String SESSIONS_DURATION = "vaadin.sessions.duration";

    public static final String SESSION_LOCK_WAIT = "vaadin.session.lock.wait";
    public static final String SESSION_LOCK_HOLD = "vaadin.session.lock.hold";

    public static final String UI_ACTIVE = "vaadin.ui.active";
    public static final String UI_CREATED = "vaadin.ui.created";

    /**
     * Gauge: state-tree nodes retained across all tracked UIs — how much UI
     * state the server currently holds for live users. Recorded only when UI
     * state metrics are enabled.
     */
    public static final String UI_STATE_NODES = "vaadin.ui.state.nodes";

    /** Gauge: state-tree nodes held by the largest single UI. */
    public static final String UI_STATE_NODES_MAX = "vaadin.ui.state.nodes.max";

    /** Gauge: server-side component instances retained across all UIs. */
    public static final String UI_STATE_COMPONENTS = "vaadin.ui.state.components";

    /**
     * Gauge: route-target and router-layout instances retained across all UIs.
     * One navigation into a nested layout retains one per level, so this is a
     * capacity figure; {@link #UI_STATE_VIEWS_STALE} is the leak signal.
     */
    public static final String UI_STATE_VIEWS = "vaadin.ui.state.views";

    /**
     * Gauge: retained views that are no longer part of their UI's active
     * navigation, i.e. views that outlived it. Normally zero.
     */
    public static final String UI_STATE_VIEWS_STALE = "vaadin.ui.state.views.stale";

    /**
     * Gauge: retained UI state in bytes, node count times the configured cost
     * per node. Registered only when
     * {@link ObservabilitySettings#getUiStateBytesPerNode()} is greater than
     * zero, because an unmeasured byte figure would be a guess.
     */
    public static final String UI_STATE_SIZE = "vaadin.ui.state.size";

    /**
     * Gauge: age in seconds of the stalest per-UI measurement in the aggregate.
     * A UI is measured on its own session's thread, so an idle user's state is
     * as old as their last interaction.
     */
    public static final String UI_STATE_SAMPLE_AGE_MAX = "vaadin.ui.state.sample.age.max";

    /** Gauge: state-tree nodes held by the largest single session. */
    public static final String SESSION_STATE_NODES_MAX = "vaadin.session.state.nodes.max";

    /** Gauge: most UIs (browser tabs) held open by one session. */
    public static final String SESSION_UIS_MAX = "vaadin.session.uis.max";

    public static final String NAVIGATION = "vaadin.navigation";

    public static final String REQUEST_DURATION = "vaadin.request.duration";

    public static final String ERRORS = "vaadin.errors";

    public static final String CLIENT_BOOTSTRAP_DURATION = "vaadin.client.bootstrap.duration";
    public static final String CLIENT_NAVIGATION_DURATION = "vaadin.client.navigation.duration";
    public static final String CLIENT_RPC_DURATION = "vaadin.client.rpc.duration";
    public static final String CLIENT_WEB_VITALS_LCP = "vaadin.client.web_vitals.lcp";
    public static final String CLIENT_WEB_VITALS_FCP = "vaadin.client.web_vitals.fcp";
    public static final String CLIENT_ERRORS = "vaadin.client.errors";
    public static final String CLIENT_DROPPED = "vaadin.client.dropped";
    public static final String CLIENT_THROTTLED = "vaadin.client.throttled";

    public static final String TAG_ROUTE = "route";

    /**
     * Tag key: {@link #OUTCOME_SUCCESS} or {@link #OUTCOME_ERROR}. Also the
     * low-cardinality key the Observation path uses (aliased there as
     * {@code ObservationNames.KEY_OUTCOME}), so both paths tag identically.
     */
    public static final String TAG_OUTCOME = "outcome";

    public static final String TAG_EXCEPTION = "exception";

    /**
     * Tag key: simple class name of the exception that ended the operation, or
     * {@link #ERROR_NONE} when it raised none. This mirrors the tag that
     * {@code DefaultMeterObservationHandler} adds by itself on the Observation
     * path; the binders add it explicitly on their direct-recording path so
     * both paths publish the same tag-key set. Distinct from
     * {@link #TAG_EXCEPTION}, which tags the {@link #ERRORS} counter.
     */
    public static final String TAG_ERROR = "error";

    /** {@link #TAG_ERROR} value for an operation that raised no exception. */
    public static final String ERROR_NONE = "none";

    public static final String TAG_TRIGGER = "trigger";
    public static final String TAG_KIND = "kind";
    public static final String TAG_CONTEXT = "context";

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_ERROR = "error";

    public static final String CONTEXT_REQUEST = "request";
    public static final String CONTEXT_ACCESS = "access";

    public static final String ROUTE_OTHER = "_other";
    public static final String ROUTE_UNKNOWN = "_unknown";

    /** Timer: server-side RPC invocation duration. */
    public static final String RPC_DURATION = "vaadin.rpc.duration";

    /**
     * DistributionSummary: number of rows read from a JDBC {@code ResultSet},
     * tagged by {@link #TAG_ROUTE} of the Vaadin view that triggered the fetch.
     * Recorded only when database monitoring is enabled.
     */
    public static final String DB_FETCH_ROWS = "vaadin.db.fetch.rows";

    /**
     * Timer: duration of a count query issued to a data provider, that is a
     * query asking how many items a level holds. Tagged by {@link #TAG_OUTCOME}
     * and {@link #TAG_FILTERED}.
     * <p>
     * A hierarchical component issues one count per expanded parent, so a high
     * count on this timer within few requests is the signature of an expensive
     * hierarchy.
     */
    public static final String DATA_COUNT_DURATION = "vaadin.data.count.duration";

    /**
     * Timer: duration of a fetch query issued to a data provider, that is a
     * query loading one page of items. Measured around consumption of the
     * items, so it covers the backend round-trip even for a lazily evaluated
     * stream. Tagged by {@link #TAG_OUTCOME} and {@link #TAG_FILTERED}.
     */
    public static final String DATA_FETCH_DURATION = "vaadin.data.fetch.duration";

    /**
     * DistributionSummary: number of items a fetch query actually returned,
     * tagged by {@link #TAG_ROUTE}. Compare against
     * {@link #DATA_FETCH_REQUESTED} to spot a component asking for far more
     * than it renders, or a data provider returning short pages.
     */
    public static final String DATA_FETCH_ROWS = "vaadin.data.fetch.rows";

    /**
     * DistributionSummary: number of items a fetch query asked for, tagged by
     * {@link #TAG_ROUTE}.
     */
    public static final String DATA_FETCH_REQUESTED = "vaadin.data.fetch.requested";

    /**
     * Tag key: whether the data provider query carried a filter, which
     * separates a combo box loading matches for typed text from one loading the
     * whole data set. Low cardinality: {@code true} or {@code false}.
     */
    public static final String TAG_FILTERED = "filtered";

    /** Tag key: RPC invocation type. */
    public static final String TAG_TYPE = "type";

    /**
     * Counter: UIDL message recovery events observed on incoming requests.
     * Tagged by {@link #TAG_TYPE} with {@link #RESYNC_TYPE_RESEND} or
     * {@link #RESYNC_TYPE_RESYNC}.
     */
    public static final String RESYNC = "vaadin.resync";

    /**
     * {@link #TAG_TYPE} value for a duplicate message the client re-sent
     * because it never received the previous response; the server replays its
     * cached response.
     */
    public static final String RESYNC_TYPE_RESEND = "resend";

    /**
     * {@link #TAG_TYPE} value for a full client-requested resynchronization
     * (the client gave up waiting for a missing server message and asked for a
     * full UI-state rebuild).
     */
    public static final String RESYNC_TYPE_RESYNC = "resync";

    private MeterNames() {
    }
}
