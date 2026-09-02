/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import com.vaadin.flow.internal.UsageStatistics;

/**
 * Registers Vaadin usage-statistics entries for the kit and its enabled
 * features.
 * <p>
 * Entries are exported to the page and reported by Vaadin dev tools in
 * development mode only; in production mode Flow never exports them, so
 * marking is unconditional here and costs a map insertion.
 */
final class ObservabilityUsage {

    private ObservabilityUsage() {
    }

    /**
     * Marks the kit and each enabled feature as used, one entry per feature
     * named {@code vaadin-observability-kit/<feature>}.
     *
     * @param settings
     *            the settings the instrumentation was registered with
     */
    static void markAsUsed(ObservabilitySettings settings) {
        mark("");
        markIf(settings.isSessions(), "sessions");
        markIf(settings.isUis(), "uis");
        markIf(settings.isUiState(), "ui-state");
        markIf(settings.isNavigation(), "navigation");
        markIf(settings.isRequests(), "requests");
        markIf(settings.isData(), "data");
        markIf(settings.isErrors(), "errors");
        markIf(settings.isClient(), "client");
        markIf(settings.isTraces(), "traces");
        markIf(settings.isDatabase(), "database");
        markIf(settings.isInsights(), "insights");
    }

    private static void markIf(boolean enabled, String feature) {
        if (enabled) {
            mark("/" + feature);
        }
    }

    private static void mark(String suffix) {
        UsageStatistics.markAsUsed(ObservabilityLicense.PRODUCT_NAME + suffix,
                ObservabilityLicense.PRODUCT_VERSION);
    }
}
