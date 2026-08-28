/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.client;

import java.util.Locale;
import java.util.Set;

import com.vaadin.observability.micrometer.MeterNames;

/**
 * Allowlist of client-emitted meter names. Samples whose names are not in this
 * set are dropped at ingest time, capping cardinality from malicious or buggy
 * clients.
 *
 * <p>
 * Note: {@link MeterNames#CLIENT_RPC_DURATION} is intentionally excluded from
 * {@link #ALLOWED} because RPC timing is measured server-side only.
 */
final class ClientMetricNames {

    static final Set<String> ALLOWED = Set.of(
            MeterNames.CLIENT_BOOTSTRAP_DURATION,
            MeterNames.CLIENT_NAVIGATION_DURATION,
            MeterNames.CLIENT_WEB_VITALS_LCP, MeterNames.CLIENT_WEB_VITALS_FCP,
            MeterNames.CLIENT_ERRORS, MeterNames.CLIENT_CONNECTION,
            MeterNames.CLIENT_CONNECTION_DOWNTIME);

    static final Set<String> COUNTER_NAMES = Set.of(MeterNames.CLIENT_ERRORS,
            MeterNames.CLIENT_CONNECTION);

    /**
     * The states Flow's client connection store can hold, minus {@code loading}
     * — the state Flow enters around every UIDL request to drive the loading
     * indicator, which the collector normalizes away before reporting. A
     * reported state outside this set is bucketed as
     * {@link MeterNames#STATE_UNKNOWN} rather than admitted, so the tag cannot
     * grow past four series however the payload is crafted.
     */
    static final Set<String> CONNECTION_STATES = Set.of(
            MeterNames.STATE_CONNECTED, MeterNames.STATE_CONNECTION_LOST,
            MeterNames.STATE_RECONNECTING);

    /**
     * The subset of {@link #CONNECTION_STATES} in which the browser cannot
     * reach the server, and so the only ones a downtime segment can be
     * attributed to.
     */
    static final Set<String> OFFLINE_STATES = Set.of(
            MeterNames.STATE_CONNECTION_LOST, MeterNames.STATE_RECONNECTING);

    static boolean isAllowed(String name) {
        return name != null && ALLOWED.contains(name);
    }

    static boolean isCounter(String name) {
        return COUNTER_NAMES.contains(name);
    }

    /**
     * Maps a browser-reported connection state onto the bounded set of
     * {@link MeterNames#TAG_STATE} values.
     *
     * @param reported
     *            the state as the browser named it, may be {@code null}
     * @return the state itself when it is one the store can hold, otherwise
     *         {@link MeterNames#STATE_UNKNOWN}
     */
    static String connectionState(String reported) {
        if (reported == null) {
            return MeterNames.STATE_UNKNOWN;
        }
        String value = reported.toLowerCase(Locale.ROOT);
        return CONNECTION_STATES.contains(value) ? value
                : MeterNames.STATE_UNKNOWN;
    }

    /**
     * Maps the state a downtime segment is attributed to onto the bounded set
     * of {@link MeterNames#TAG_STATE} values it may carry.
     * <p>
     * Narrower than {@link #connectionState(String)}: a browser only spends
     * time being unreachable, so {@link MeterNames#STATE_CONNECTED} on this
     * meter would be a contradiction and is bucketed with the rest.
     *
     * @param reported
     *            the state as the browser named it, may be {@code null}
     * @return the state itself when it is one the browser can be unreachable
     *         in, otherwise {@link MeterNames#STATE_UNKNOWN}
     */
    static String downtimeState(String reported) {
        if (reported == null) {
            return MeterNames.STATE_UNKNOWN;
        }
        String value = reported.toLowerCase(Locale.ROOT);
        return OFFLINE_STATES.contains(value) ? value
                : MeterNames.STATE_UNKNOWN;
    }

    private ClientMetricNames() {
    }
}
