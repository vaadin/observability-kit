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
    private final boolean navigation;
    private final boolean requests;
    private final boolean errors;
    private final boolean client;
    private final boolean resync;
    private final boolean traces;
    private final boolean tracesSessionId;
    private final boolean database;
    private final boolean databaseStatement;
    private final boolean insightsDetails;
    private final int routeCardinalityLimit;
    private final int clientRatePerSession;

    private ObservabilitySettings(Builder builder) {
        this.sessions = builder.sessions;
        this.uis = builder.uis;
        this.navigation = builder.navigation;
        this.requests = builder.requests;
        this.errors = builder.errors;
        this.client = builder.client;
        this.resync = builder.resync;
        this.traces = builder.traces;
        this.tracesSessionId = builder.tracesSessionId;
        this.database = builder.database;
        this.databaseStatement = builder.databaseStatement;
        this.insightsDetails = builder.insightsDetails;
        this.routeCardinalityLimit = builder.routeCardinalityLimit;
        this.clientRatePerSession = builder.clientRatePerSession;
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

    /** Builder for {@link ObservabilitySettings}. */
    public static final class Builder {

        private boolean sessions = true;
        private boolean uis = true;
        private boolean navigation = true;
        private boolean requests = true;
        private boolean errors = true;
        private boolean client = true;
        private boolean resync = true;
        private boolean traces = true;
        private boolean tracesSessionId = false;
        private boolean database = false;
        private boolean databaseStatement = false;
        private boolean insightsDetails = false;
        private int routeCardinalityLimit = 200;
        private int clientRatePerSession = 100;

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

        public ObservabilitySettings build() {
            return new ObservabilitySettings(this);
        }
    }
}
