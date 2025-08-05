/*-
 * Copyright (C) 2022 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.extension;

/**
 * Constants used by the Vaadin observability extension, for example for
 * configuration options
 */
public class Constants {
    public static final String CONFIG_TRACE_LEVEL = "otel.instrumentation.vaadin.trace-level";
    public static final String CONFIG_SPAN_TO_METRICS_ENABLED = "otel.instrumentation.vaadin.span-to-metrics.enabled";

    // Vaadin attribute names
    public static final String SESSION_ID = "vaadin.session.id";
    public static final String REQUEST_TYPE = "vaadin.request.type";
    public static final String FLOW_VERSION = "vaadin.flow.version";

    public static final String REQUEST_LOCATION_PARAMETER = "location";
    public static final String FRONTEND_ID = "vaadin.frontend.id";
    public static final String REQUEST_TYPE_OBSERVABILITY = "o11y";
    public static final String REQUEST_TYPE_HEARTBEAT = "heartbeat";

}
