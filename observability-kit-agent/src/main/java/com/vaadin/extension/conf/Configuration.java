package com.vaadin.extension.conf;

import com.vaadin.extension.Constants;

import io.opentelemetry.javaagent.bootstrap.internal.AgentInstrumentationConfig;

/**
 * Provides the effective configuration for the Vaadin observability extension.
 */
public class Configuration {
    public static final TraceLevel TRACE_LEVEL = determineTraceLevel();
    public static final boolean SPAN_TO_METRICS_ENABLED = determineSpanToMetricsEnabled();

    private static TraceLevel determineTraceLevel() {
        String traceLevelString = AgentInstrumentationConfig.get().getString(
                Constants.CONFIG_TRACE_LEVEL, TraceLevel.DEFAULT.name());
        try {
            return TraceLevel.valueOf(traceLevelString.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return TraceLevel.DEFAULT;
        }
    }

    private static boolean determineSpanToMetricsEnabled() {
        return AgentInstrumentationConfig.get().getBoolean(
                Constants.CONFIG_SPAN_TO_METRICS_ENABLED, false);
    }

    /**
     * Checks whether a trace level is enabled. Can be used by instrumentations
     * to check whether some detail should be added to a trace or not.
     *
     * @param traceLevel
     *            the trace level to check
     * @return true if the trace level is enabled, false if not
     */
    public static boolean isEnabled(TraceLevel traceLevel) {
        return TRACE_LEVEL.includes(traceLevel);
    }

    /**
     * Checks whether span-to-metrics recording is enabled. When enabled,
     * span duration data will be recorded as metrics.
     *
     * @return true if span-to-metrics is enabled, false if not
     */
    public static boolean isSpanToMetricsEnabled() {
        return SPAN_TO_METRICS_ENABLED;
    }
}
