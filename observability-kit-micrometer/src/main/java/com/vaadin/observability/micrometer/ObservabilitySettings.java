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
    private final boolean traces;
    private final boolean tracesSessionId;
    private final int routeCardinalityLimit;
    private final int clientRatePerSession;

    private ObservabilitySettings(Builder builder) {
        this.sessions = builder.sessions;
        this.uis = builder.uis;
        this.navigation = builder.navigation;
        this.requests = builder.requests;
        this.errors = builder.errors;
        this.client = builder.client;
        this.traces = builder.traces;
        this.tracesSessionId = builder.tracesSessionId;
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

    public boolean isTraces() {
        return traces;
    }

    public boolean isTracesSessionId() {
        return tracesSessionId;
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
        private boolean traces = true;
        private boolean tracesSessionId = false;
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

        public Builder traces(boolean traces) {
            this.traces = traces;
            return this;
        }

        public Builder tracesSessionId(boolean tracesSessionId) {
            this.tracesSessionId = tracesSessionId;
            return this;
        }

        public Builder routeCardinalityLimit(int routeCardinalityLimit) {
            this.routeCardinalityLimit = routeCardinalityLimit;
            return this;
        }

        public Builder clientRatePerSession(int clientRatePerSession) {
            this.clientRatePerSession = clientRatePerSession;
            return this;
        }

        public ObservabilitySettings build() {
            return new ObservabilitySettings(this);
        }
    }
}
