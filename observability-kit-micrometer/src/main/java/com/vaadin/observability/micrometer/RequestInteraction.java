/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

/**
 * Thread-local relay that lets binders which observe a specific kind of
 * server-side activity (poll, navigation) tell the {@code RequestMetricsBinder}
 * what the in-flight UIDL request actually did.
 * <p>
 * UIDL processing is synchronous on the request thread, so a value set by a
 * poll listener or navigation listener during request handling is visible to
 * the interceptor's {@code requestEnd} on the same thread. The interceptor
 * clears the slot at {@code requestStart} and consumes it at
 * {@code requestEnd}.
 */
final class RequestInteraction {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RequestInteraction() {
    }

    /**
     * Records what the current request did. Last writer wins; a request that
     * both navigates and polls is rare and either label is acceptable.
     */
    static void mark(String kind) {
        CURRENT.set(kind);
    }

    /**
     * Returns and clears the interaction kind for the current thread, or
     * {@code null} if none was marked.
     */
    static String take() {
        String value = CURRENT.get();
        CURRENT.remove();
        return value;
    }

    /**
     * Clears any value left over from a previous request on this (pooled)
     * thread.
     */
    static void clear() {
        CURRENT.remove();
    }
}
