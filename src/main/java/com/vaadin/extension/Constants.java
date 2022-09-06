package com.vaadin.extension;

/**
 * Constants used by the Vaadin observability extension, for example for
 * configuration options
 */
public class Constants {
    public static final String CONFIG_TRACE_LEVEL = "otel.instrumentation.vaadin.trace-level";

    // Vaadin attribute names
    public static final String SESSION_ID = "vaadin.session.id";
    public static final String REQUEST_TYPE = "vaadin.request.type";
    public static final String VIEW = "vaadin.view";
}
