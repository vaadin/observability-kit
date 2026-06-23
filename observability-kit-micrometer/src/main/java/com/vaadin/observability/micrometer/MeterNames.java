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
    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_EXCEPTION = "exception";
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
