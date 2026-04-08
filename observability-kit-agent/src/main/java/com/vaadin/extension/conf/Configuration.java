package com.vaadin.extension.conf;

import com.vaadin.extension.Constants;

import java.util.function.BiFunction;

/**
 * Provides the effective configuration for the Vaadin observability extension.
 */
public class Configuration {
    public static final TraceLevel TRACE_LEVEL = determineTraceLevel();

    /**
     * A function to look up configuration properties. Set by
     * ConfigurationDefaults from the agent classloader.
     */
    public static volatile BiFunction<String, String, String> configLookup;

    private static TraceLevel determineTraceLevel() {
        String traceLevelString = configLookup != null
                ? configLookup.apply(Constants.CONFIG_TRACE_LEVEL,
                        TraceLevel.DEFAULT.name())
                : TraceLevel.DEFAULT.name();
        try {
            return TraceLevel.valueOf(traceLevelString.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return TraceLevel.DEFAULT;
        }
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
}
