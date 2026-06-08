/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.client;

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
            MeterNames.CLIENT_ERRORS);

    static final Set<String> COUNTER_NAMES = Set.of(MeterNames.CLIENT_ERRORS);

    static boolean isAllowed(String name) {
        return name != null && ALLOWED.contains(name);
    }

    static boolean isCounter(String name) {
        return COUNTER_NAMES.contains(name);
    }

    private ClientMetricNames() {
    }
}
