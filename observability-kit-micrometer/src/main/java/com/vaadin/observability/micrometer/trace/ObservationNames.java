/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.trace;

/**
 * Span name + attribute-key constants for Observability Kit Observations.
 * <p>
 * Span names follow Micrometer/OpenTelemetry conventions (lowercase
 * dot-separated). They are intentionally distinct from the parallel Meter names
 * (e.g. {@code vaadin.request} span vs. {@code vaadin.request.duration} timer)
 * so that auto-Timer producers like {@code DefaultMeterObservationHandler} do
 * not collide with the existing manual timers emitted by the binders.
 */
public final class ObservationNames {

    public static final String REQUEST = "vaadin.request";
    public static final String NAVIGATION = "vaadin.navigation";
    public static final String UI_ACCESS = "vaadin.ui.access";

    public static final String KEY_OUTCOME = "outcome";
    public static final String KEY_REQUEST_TYPE = "vaadin.request.type";
    public static final String KEY_INTERACTION = "vaadin.interaction";
    public static final String KEY_ROUTE = "route";
    public static final String KEY_HTTP_METHOD = "http.method";
    public static final String KEY_SESSION_ID = "vaadin.session.id";
    public static final String KEY_UI_ID = "ui.id";
    public static final String KEY_CLIENT_LOCATION = "vaadin.client.location";

    /** A poll request triggered by a configured poll interval. */
    public static final String INTERACTION_POLL = "poll";

    /** The request performed a server-side navigation. */
    public static final String INTERACTION_NAVIGATION = "navigation";

    /**
     * A real client-to-server RPC (DOM event, {@code @ClientCallable}, property
     * sync, return channel) that we cannot break down further without parsing
     * the UIDL body.
     */
    public static final String INTERACTION_RPC = "rpc";

    public static final String UI_ID_UNKNOWN = "_unknown";
    public static final String LOCATION_UNKNOWN = "_unknown";

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_ERROR = "error";

    public static final String REQUEST_TYPE_UIDL = "uidl";
    public static final String REQUEST_TYPE_HEARTBEAT = "heartbeat";
    public static final String REQUEST_TYPE_PUSH = "push";
    public static final String REQUEST_TYPE_STATIC = "static";
    public static final String REQUEST_TYPE_OTHER = "other";

    /** Observation/span name for a server-side RPC invocation. */
    public static final String RPC = "vaadin.rpc";

    /**
     * High-cardinality span attribute: the human-readable invocation name (DOM
     * event name, invoked method name, navigation location, ...). Span-only; it
     * is never added as a Timer tag because of its cardinality.
     */
    public static final String KEY_EVENT_NAME = "vaadin.rpc.event_name";

    /**
     * High-cardinality span attribute: the class name of the Vaadin
     * {@code Component} that the invocation targets, when it can be resolved
     * from the target node. Span-only; it is never added as a Timer tag because
     * of its cardinality.
     */
    public static final String KEY_COMPONENT = "vaadin.rpc.component";

    private ObservationNames() {
    }
}
