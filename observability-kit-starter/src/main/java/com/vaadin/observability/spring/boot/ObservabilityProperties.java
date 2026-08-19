/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.vaadin.observability.micrometer.ObservabilitySettings;

/**
 * Boot-bound configuration properties under the {@code vaadin.observability}
 * prefix. Converted to a plain {@link ObservabilitySettings} via
 * {@link #toSettings()}.
 */
@ConfigurationProperties(prefix = "vaadin.observability")
public class ObservabilityProperties {

    private boolean enabled = true;
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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isSessions() {
        return sessions;
    }

    public void setSessions(boolean sessions) {
        this.sessions = sessions;
    }

    public boolean isUis() {
        return uis;
    }

    public void setUis(boolean uis) {
        this.uis = uis;
    }

    public boolean isNavigation() {
        return navigation;
    }

    public void setNavigation(boolean navigation) {
        this.navigation = navigation;
    }

    public boolean isRequests() {
        return requests;
    }

    public void setRequests(boolean requests) {
        this.requests = requests;
    }

    public boolean isErrors() {
        return errors;
    }

    public void setErrors(boolean errors) {
        this.errors = errors;
    }

    public boolean isClient() {
        return client;
    }

    public void setClient(boolean client) {
        this.client = client;
    }

    public boolean isResync() {
        return resync;
    }

    public void setResync(boolean resync) {
        this.resync = resync;
    }

    public boolean isTraces() {
        return traces;
    }

    public void setTraces(boolean traces) {
        this.traces = traces;
    }

    public boolean isTracesSessionId() {
        return tracesSessionId;
    }

    public void setTracesSessionId(boolean tracesSessionId) {
        this.tracesSessionId = tracesSessionId;
    }

    /**
     * Whether interaction insights may carry potentially sensitive detail: the
     * raw session id, the exception message and the top stack frames. Off by
     * default, since the insights payload is meant to be forwarded.
     */
    public boolean isInsightsDetails() {
        return insightsDetails;
    }

    public void setInsightsDetails(boolean insightsDetails) {
        this.insightsDetails = insightsDetails;
    }

    public boolean isDatabase() {
        return database;
    }

    public void setDatabase(boolean database) {
        this.database = database;
    }

    public boolean isDatabaseStatement() {
        return databaseStatement;
    }

    public void setDatabaseStatement(boolean databaseStatement) {
        this.databaseStatement = databaseStatement;
    }

    public int getRouteCardinalityLimit() {
        return routeCardinalityLimit;
    }

    public void setRouteCardinalityLimit(int routeCardinalityLimit) {
        this.routeCardinalityLimit = routeCardinalityLimit;
    }

    public int getClientRatePerSession() {
        return clientRatePerSession;
    }

    public void setClientRatePerSession(int clientRatePerSession) {
        this.clientRatePerSession = clientRatePerSession;
    }

    /**
     * Converts these properties to an {@link ObservabilitySettings} instance.
     * The {@code enabled} flag is not included in settings; it only gates
     * activation of the auto-configuration.
     *
     * @return a new {@link ObservabilitySettings} built from the current
     *         property values
     */
    public ObservabilitySettings toSettings() {
        return ObservabilitySettings.builder().sessions(sessions).uis(uis)
                .navigation(navigation).requests(requests).errors(errors)
                .client(client).resync(resync).traces(traces)
                .tracesSessionId(tracesSessionId).database(database)
                .databaseStatement(databaseStatement)
                .insightsDetails(insightsDetails)
                .routeCardinalityLimit(routeCardinalityLimit)
                .clientRatePerSession(clientRatePerSession).build();
    }
}
