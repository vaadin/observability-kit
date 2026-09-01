/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.client;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.vaadin.observability.micrometer.MeterNames;

/**
 * Allowlist of client-emitted meter names, of the tag keys each of them
 * carries, and of the values those keys may hold. Samples whose names are not
 * in this set are dropped at ingest time, capping cardinality from malicious or
 * buggy clients.
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
     * The tag keys each client meter carries — all of them, always, whatever
     * the payload contained.
     * <p>
     * A meter's tag key set is fixed at the first registration, and Micrometer
     * rejects a later meter of the same name whose keys differ. Since the
     * payload comes from a browser, both halves of that have to be taken away
     * from it: a key it invented is dropped rather than registered, and a key
     * it left out is filled in from the bounded values below. One crafted
     * sample would otherwise poison the meter for every legitimate sample after
     * it.
     */
    private static final Map<String, List<String>> TAG_KEYS = Map.of(
            MeterNames.CLIENT_BOOTSTRAP_DURATION, List.of(MeterNames.TAG_ROUTE),
            MeterNames.CLIENT_NAVIGATION_DURATION,
            List.of(MeterNames.TAG_ROUTE, MeterNames.TAG_TRIGGER),
            MeterNames.CLIENT_WEB_VITALS_LCP, List.of(MeterNames.TAG_ROUTE),
            MeterNames.CLIENT_WEB_VITALS_FCP, List.of(MeterNames.TAG_ROUTE),
            MeterNames.CLIENT_ERRORS, List.of(MeterNames.TAG_KIND),
            MeterNames.CLIENT_CONNECTION, List.of(MeterNames.TAG_STATE),
            MeterNames.CLIENT_CONNECTION_DOWNTIME,
            List.of(MeterNames.TAG_STATE));

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

    /** The ways the collector can start a client-side navigation. */
    static final Set<String> NAVIGATION_TRIGGERS = Set
            .of(MeterNames.TRIGGER_BACK, MeterNames.TRIGGER_PROGRAMMATIC);

    /** The browser events the collector reports an error for. */
    static final Set<String> ERROR_KINDS = Set.of(MeterNames.KIND_UNCAUGHT,
            MeterNames.KIND_PROMISE);

    static boolean isAllowed(String name) {
        return name != null && ALLOWED.contains(name);
    }

    static boolean isCounter(String name) {
        return COUNTER_NAMES.contains(name);
    }

    /**
     * Returns the tag keys the named meter carries, or an empty list for a
     * meter that carries none.
     */
    static List<String> tagKeys(String name) {
        return TAG_KEYS.getOrDefault(name, List.of());
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
        return admit(reported, CONNECTION_STATES, MeterNames.STATE_UNKNOWN);
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
        return admit(reported, OFFLINE_STATES, MeterNames.STATE_UNKNOWN);
    }

    /**
     * Maps a browser-reported navigation trigger onto the bounded set of
     * {@link MeterNames#TAG_TRIGGER} values.
     *
     * @param reported
     *            the trigger as the browser named it, may be {@code null}
     * @return the trigger itself when it is one the collector can report,
     *         otherwise {@link MeterNames#TRIGGER_UNKNOWN}
     */
    static String navigationTrigger(String reported) {
        return admit(reported, NAVIGATION_TRIGGERS, MeterNames.TRIGGER_UNKNOWN);
    }

    /**
     * Maps a browser-reported error kind onto the bounded set of
     * {@link MeterNames#TAG_KIND} values.
     *
     * @param reported
     *            the kind as the browser named it, may be {@code null}
     * @return the kind itself when it is one the collector can report,
     *         otherwise {@link MeterNames#KIND_UNKNOWN}
     */
    static String errorKind(String reported) {
        return admit(reported, ERROR_KINDS, MeterNames.KIND_UNKNOWN);
    }

    /**
     * Returns {@code reported}, lowercased, when {@code allowed} holds it, and
     * {@code fallback} otherwise. Admission rather than capping: every value
     * here originates in the browser, so the series count has to follow from
     * this file rather than from what a payload happens to contain.
     */
    private static String admit(String reported, Set<String> allowed,
            String fallback) {
        if (reported == null) {
            return fallback;
        }
        String value = reported.toLowerCase(Locale.ROOT);
        return allowed.contains(value) ? value : fallback;
    }

    private ClientMetricNames() {
    }
}
