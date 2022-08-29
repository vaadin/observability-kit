package com.vaadin.extension;

/**
 * The detail level of traces. The global trace level can be configured in the
 * {@link Configuration}, and individual instrumentations can check the trace
 * level to determine whether to add something to a trace or not.
 */
public enum TraceLevel {
    MINIMUM(0), DEFAULT(1), MAXIMUM(2),;

    private final int level;

    private TraceLevel(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }

    public boolean includes(TraceLevel level) {
        return this.level >= level.level;
    }
}
