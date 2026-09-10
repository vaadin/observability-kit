/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import com.vaadin.observability.micrometer.insights.RecentInteractions;

/**
 * Immutable settings for Observability Kit instrumentation. Build instances
 * with {@link #builder()}.
 */
public final class ObservabilitySettings {

    private final boolean sessions;
    private final boolean uis;
    private final boolean uiState;
    private final boolean navigation;
    private final boolean requests;
    private final boolean data;
    private final boolean errors;
    private final boolean client;
    private final boolean resync;
    private final boolean traces;
    private final boolean tracesSessionId;
    private final boolean database;
    private final boolean databaseStatement;
    private final boolean insights;
    private final boolean insightsDetails;
    private final int routeCardinalityLimit;
    private final int clientRatePerSession;
    private final int uiStateSampleInterval;
    private final int uiStateBytesPerNode;
    private final int insightsCapacity;

    private ObservabilitySettings(Builder builder) {
        this.sessions = builder.sessions;
        this.uis = builder.uis;
        this.uiState = builder.uiState;
        this.navigation = builder.navigation;
        this.requests = builder.requests;
        this.data = builder.data;
        this.errors = builder.errors;
        this.client = builder.client;
        this.resync = builder.resync;
        this.traces = builder.traces;
        this.tracesSessionId = builder.tracesSessionId;
        this.database = builder.database;
        this.databaseStatement = builder.databaseStatement;
        this.insights = builder.insights;
        this.insightsDetails = builder.insightsDetails;
        this.routeCardinalityLimit = builder.routeCardinalityLimit;
        this.clientRatePerSession = builder.clientRatePerSession;
        this.uiStateSampleInterval = builder.uiStateSampleInterval;
        this.uiStateBytesPerNode = builder.uiStateBytesPerNode;
        this.insightsCapacity = builder.insightsCapacity;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isSessions() {
        return sessions;
    }

    public boolean isUis() {
        return uis;
    }

    /**
     * Whether to measure how much state each UI holds and publish the
     * {@code vaadin.ui.state.*} aggregates. Off by default: it walks the
     * component tree of the UI an interaction touched, which costs work outside
     * the request's own path.
     *
     * @return {@code true} if UI state metrics are enabled
     */
    public boolean isUiState() {
        return uiState;
    }

    public boolean isNavigation() {
        return navigation;
    }

    /**
     * Whether the data provider queries made by lazy-loading components are
     * measured. On by default: for a data-heavy view these queries are usually
     * where a slow interaction spends its time.
     *
     * @return {@code true} if data query metrics are recorded
     */
    public boolean isData() {
        return data;
    }

    public boolean isRequests() {
        return requests;
    }

    public boolean isErrors() {
        return errors;
    }

    public boolean isClient() {
        return client;
    }

    /** Whether to observe UIDL message resends and resynchronizations. */
    public boolean isResync() {
        return resync;
    }

    public boolean isTraces() {
        return traces;
    }

    public boolean isTracesSessionId() {
        return tracesSessionId;
    }

    /**
     * Whether failed and over-budget user interactions are retained so the
     * insights endpoint can backtrack a user report to a replicable
     * interaction. On by default.
     * <p>
     * Independent of {@link #isInsightsDetails()}, which only governs how much
     * detail a retained interaction carries. Collection still requires
     * {@link #isErrors()} for failures and {@link #isRequests()} for slow
     * interactions, since those supply the respective capture paths.
     */
    public boolean isInsights() {
        return insights;
    }

    /**
     * Whether interaction insights may carry potentially sensitive detail: the
     * raw session id, the exception message and the top stack frames.
     * <p>
     * Off by default. The insights payload is meant to travel — into issue
     * trackers, AI agents and whatever a consumer forwards it to — so the
     * detail that could contain personal or secret data is withheld unless an
     * application asks for it. What remains is still actionable: the route, the
     * component, the event, the exception type and the first application stack
     * frame. With this off the session id is reduced to a short one-way hash,
     * which still correlates the examples of one insight without identifying
     * the session.
     */
    public boolean isInsightsDetails() {
        return insightsDetails;
    }

    public boolean isDatabase() {
        return database;
    }

    public boolean isDatabaseStatement() {
        return databaseStatement;
    }

    public int getRouteCardinalityLimit() {
        return routeCardinalityLimit;
    }

    public int getClientRatePerSession() {
        return clientRatePerSession;
    }

    /**
     * Minimum milliseconds between two measurements of the same UI, so a burst
     * of interactions costs one tree walk rather than one per event.
     * <p>
     * One measurement walks the whole component tree of one UI while its
     * session lock is held, so the work this setting bounds is proportional to
     * tree size times interaction rate: on a grid-heavy application with many
     * concurrent users, a short interval adds measurable time to the lock that
     * {@code vaadin.session.lock.hold} reports. The gauges describe a capacity
     * trend rather than a live value, so the default is deliberately coarse.
     *
     * @return the per-UI sampling interval in milliseconds
     */
    public int getUiStateSampleInterval() {
        return uiStateSampleInterval;
    }

    /**
     * Bytes to attribute to one state-tree node when publishing
     * {@code vaadin.ui.state.size}, or {@code 0} to publish no byte figure at
     * all.
     * <p>
     * There is no default value that would be right: nodes are a proxy for
     * retained heap, not a measurement of it, and one {@code Grid} node backed
     * by 100 000 rows counts as a single node. Measure the cost for the
     * application at hand — settle the heap, build a number of copies of a
     * representative view, keep them reachable, and read the difference — and
     * set the result here. Left at zero, the gauge is not registered, because a
     * guessed byte figure published as a metric is worse than a missing one.
     *
     * @return bytes per state-tree node, or {@code 0} when not configured
     */
    public int getUiStateBytesPerNode() {
        return uiStateBytesPerNode;
    }

    /**
     * Maximum number of records retained for the insights endpoint, applied to
     * <em>each</em> buffer rather than shared between them: interactions, data
     * provider queries and browser errors are retained separately, so with all
     * three collectors active the total is three times this value. Keeping them
     * apart means a burst of slow queries cannot evict the failed interactions,
     * and neither can the flood of buffered reports that arrives when a network
     * outage ends.
     *
     * @return the per-buffer capacity
     */
    public int getInsightsCapacity() {
        return insightsCapacity;
    }

    /** Builder for {@link ObservabilitySettings}. */
    public static final class Builder {

        private boolean sessions = true;
        private boolean uis = true;
        private boolean uiState = false;
        private boolean navigation = true;
        private boolean requests = true;
        private boolean data = true;
        private boolean errors = true;
        private boolean client = true;
        private boolean resync = true;
        private boolean traces = true;
        private boolean tracesSessionId = false;
        private boolean database = false;
        private boolean databaseStatement = false;
        private boolean insights = true;
        private boolean insightsDetails = false;
        private int routeCardinalityLimit = 200;
        private int clientRatePerSession = 100;
        private int uiStateSampleInterval = 10000;
        private int uiStateBytesPerNode = 0;
        private int insightsCapacity = RecentInteractions.DEFAULT_CAPACITY;

        private Builder() {
        }

        public Builder sessions(boolean sessions) {
            this.sessions = sessions;
            return this;
        }

        public Builder uis(boolean uis) {
            this.uis = uis;
            return this;
        }

        public Builder uiState(boolean uiState) {
            this.uiState = uiState;
            return this;
        }

        public Builder navigation(boolean navigation) {
            this.navigation = navigation;
            return this;
        }

        /**
         * Sets whether data provider queries are measured.
         *
         * @param data
         *            {@code true} to record data query metrics
         * @return this builder
         */
        public Builder data(boolean data) {
            this.data = data;
            return this;
        }

        public Builder requests(boolean requests) {
            this.requests = requests;
            return this;
        }

        public Builder errors(boolean errors) {
            this.errors = errors;
            return this;
        }

        public Builder client(boolean client) {
            this.client = client;
            return this;
        }

        public Builder resync(boolean resync) {
            this.resync = resync;
            return this;
        }

        public Builder traces(boolean traces) {
            this.traces = traces;
            return this;
        }

        public Builder tracesSessionId(boolean tracesSessionId) {
            this.tracesSessionId = tracesSessionId;
            return this;
        }

        public Builder insights(boolean insights) {
            this.insights = insights;
            return this;
        }

        public Builder insightsDetails(boolean insightsDetails) {
            this.insightsDetails = insightsDetails;
            return this;
        }

        public Builder database(boolean database) {
            this.database = database;
            return this;
        }

        public Builder databaseStatement(boolean databaseStatement) {
            this.databaseStatement = databaseStatement;
            return this;
        }

        public Builder routeCardinalityLimit(int routeCardinalityLimit) {
            if (routeCardinalityLimit < 1) {
                throw new IllegalArgumentException(
                        "routeCardinalityLimit must be >= 1, got "
                                + routeCardinalityLimit);
            }
            this.routeCardinalityLimit = routeCardinalityLimit;
            return this;
        }

        public Builder clientRatePerSession(int clientRatePerSession) {
            if (clientRatePerSession < 0) {
                throw new IllegalArgumentException(
                        "clientRatePerSession must be >= 0, got "
                                + clientRatePerSession);
            }
            this.clientRatePerSession = clientRatePerSession;
            return this;
        }

        public Builder uiStateSampleInterval(int uiStateSampleInterval) {
            if (uiStateSampleInterval < 0) {
                throw new IllegalArgumentException(
                        "uiStateSampleInterval must be >= 0, got "
                                + uiStateSampleInterval);
            }
            this.uiStateSampleInterval = uiStateSampleInterval;
            return this;
        }

        public Builder uiStateBytesPerNode(int uiStateBytesPerNode) {
            if (uiStateBytesPerNode < 0) {
                throw new IllegalArgumentException(
                        "uiStateBytesPerNode must be >= 0, got "
                                + uiStateBytesPerNode);
            }
            this.uiStateBytesPerNode = uiStateBytesPerNode;
            return this;
        }

        public Builder insightsCapacity(int insightsCapacity) {
            if (insightsCapacity < 1) {
                throw new IllegalArgumentException(
                        "insightsCapacity must be >= 1, got "
                                + insightsCapacity);
            }
            this.insightsCapacity = insightsCapacity;
            return this;
        }

        public ObservabilitySettings build() {
            return new ObservabilitySettings(this);
        }
    }
}
