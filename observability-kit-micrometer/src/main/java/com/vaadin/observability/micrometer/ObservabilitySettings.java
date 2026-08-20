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
 * Immutable settings for Observability Kit instrumentation. Build instances
 * with {@link #builder()}.
 */
public final class ObservabilitySettings {

    private final boolean sessions;
    private final boolean uis;
    private final boolean uiState;
    private final boolean navigation;
    private final boolean requests;
    private final boolean errors;
    private final boolean client;
    private final boolean resync;
    private final boolean traces;
    private final boolean tracesSessionId;
    private final boolean database;
    private final boolean databaseStatement;
    private final int routeCardinalityLimit;
    private final int clientRatePerSession;
    private final int uiStateSampleInterval;
    private final int uiStateBytesPerNode;

    private ObservabilitySettings(Builder builder) {
        this.sessions = builder.sessions;
        this.uis = builder.uis;
        this.uiState = builder.uiState;
        this.navigation = builder.navigation;
        this.requests = builder.requests;
        this.errors = builder.errors;
        this.client = builder.client;
        this.resync = builder.resync;
        this.traces = builder.traces;
        this.tracesSessionId = builder.tracesSessionId;
        this.database = builder.database;
        this.databaseStatement = builder.databaseStatement;
        this.routeCardinalityLimit = builder.routeCardinalityLimit;
        this.clientRatePerSession = builder.clientRatePerSession;
        this.uiStateSampleInterval = builder.uiStateSampleInterval;
        this.uiStateBytesPerNode = builder.uiStateBytesPerNode;
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

    /** Builder for {@link ObservabilitySettings}. */
    public static final class Builder {

        private boolean sessions = true;
        private boolean uis = true;
        private boolean uiState = false;
        private boolean navigation = true;
        private boolean requests = true;
        private boolean errors = true;
        private boolean client = true;
        private boolean resync = true;
        private boolean traces = true;
        private boolean tracesSessionId = false;
        private boolean database = false;
        private boolean databaseStatement = false;
        private int routeCardinalityLimit = 200;
        private int clientRatePerSession = 100;
        private int uiStateSampleInterval = 10000;
        private int uiStateBytesPerNode = 0;

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

        public ObservabilitySettings build() {
            return new ObservabilitySettings(this);
        }
    }
}
