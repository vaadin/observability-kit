package com.vaadin.extension.conf;

/**
 * The detail level of traces. The global trace level can be configured in the
 * {@link Configuration}, and individual instrumentations can check the trace
 * level to determine whether to add something to a trace or not.
 */
public enum TraceLevel {
    MINIMUM, DEFAULT, MAXIMUM,;

    public boolean includes(TraceLevel level) {
        return this.ordinal() >= level.ordinal();
    }
}
