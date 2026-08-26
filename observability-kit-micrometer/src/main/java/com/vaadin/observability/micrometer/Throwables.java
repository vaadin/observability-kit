/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.HashSet;
import java.util.Set;

/**
 * Throwable helpers shared by the collectors.
 */
public final class Throwables {

    private Throwables() {
    }

    /**
     * Unwraps a throwable to its root cause, which is what identifies a
     * problem: a wrapper says how it surfaced, the cause says what happened.
     * <p>
     * Cycles are tracked rather than assumed away. A chain that loops back on
     * itself (A caused by B caused by A) would otherwise spin forever, and this
     * runs while the server is already handling a failure.
     *
     * @param error
     *            the throwable to unwrap, not {@code null}
     * @return the root cause, or the last cause before the chain looped back
     */
    public static Throwable rootCause(Throwable error) {
        Set<Throwable> visited = new HashSet<>();
        Throwable cause = error;
        visited.add(cause);
        while (cause.getCause() != null && visited.add(cause.getCause())) {
            cause = cause.getCause();
        }
        return cause;
    }
}
