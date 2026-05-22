package com.vaadin.extension.conf;

import java.util.function.Function;

/**
 * Provides the effective configuration for the Vaadin observability extension.
 * Initialized by {@code ConfigurationDefaults} from the agent classloader
 * during auto-configuration, before any instrumentation is applied.
 */
public class Configuration {

    private static TraceLevel traceLevel = TraceLevel.DEFAULT;
    private static Function<String, String> propertyLookup = key -> null;

    /**
     * Called by {@code ConfigurationDefaults} during agent initialization to
     * provide resolved configuration values. Uses only standard Java types to
     * avoid references to OTel SDK classes in this helper class.
     */
    static void initialize(String traceLevelValue,
            Function<String, String> lookup) {
        try {
            traceLevel = TraceLevel.valueOf(traceLevelValue.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            traceLevel = TraceLevel.DEFAULT;
        }
        propertyLookup = lookup;
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
        return Configuration.traceLevel.includes(traceLevel);
    }

    /**
     * Looks up a configuration property by key.
     *
     * @param key
     *            the property key
     * @return the property value, or null if not set
     */
    public static String getProperty(String key) {
        return propertyLookup.apply(key);
    }
}
