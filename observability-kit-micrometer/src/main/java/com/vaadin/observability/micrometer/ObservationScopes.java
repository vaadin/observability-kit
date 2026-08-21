/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import io.micrometer.observation.Observation;
import org.slf4j.LoggerFactory;

/**
 * Helpers for the {@link Observation.Scope} thread-locals held by the binders.
 */
final class ObservationScopes {

    private ObservationScopes() {
    }

    /**
     * Closes and clears a scope left behind by a previous request or invocation
     * whose end callback never ran (e.g. mid-request server shutdown).
     * <p>
     * Dropping the binder's own reference is not enough: the scope also lives
     * in the {@code ObservationRegistry}'s own thread-local, so on a pooled
     * thread the stale observation would stay "current" and the next
     * observation started on that thread would be parented onto it. Closing
     * restores whatever scope was current before it was opened.
     *
     * @param holder
     *            the thread-local holding the possibly stale scope
     */
    static void closeStale(ThreadLocal<Observation.Scope> holder) {
        Observation.Scope stale = holder.get();
        holder.remove();
        if (stale == null) {
            return;
        }
        try {
            stale.close();
        } catch (RuntimeException e) {
            // A handler failing while unwinding a scope we are only cleaning
            // up must not fail the request that is just starting. The
            // thread-local is already cleared at this point.
            LoggerFactory.getLogger(ObservationScopes.class)
                    .debug("Failed to close a stale observation scope", e);
        }
    }
}
