/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import com.vaadin.flow.server.VaadinService;

/**
 * Thread-local relay between the two places a server-side failure surfaces:
 * {@code VaadinRequestInterceptor.handleException} for exceptions that escape
 * request handling, and the session {@code ErrorHandler} for the ones Flow
 * handles itself (a failing component listener, {@code UI.access} body or
 * navigation callback).
 * <p>
 * It serves two purposes:
 * <ul>
 * <li><b>Outcome.</b> An exception routed to the error handler never reaches
 * the request interceptor, so without a relay the enclosing
 * {@code vaadin.request} span would report {@code outcome=success} for a
 * request whose whole point — the user's interaction — failed.</li>
 * <li><b>Deduplication.</b> Flow's own {@code handleExceptionDuringRequest}
 * notifies the request interceptors <em>and</em> the session error handler with
 * the same throwable, so the two would otherwise count one failure twice.</li>
 * </ul>
 * Request handling is synchronous on the request thread, so a value written by
 * either party during handling is visible to the interceptor's
 * {@code requestEnd} on the same thread. The interceptor clears the slots at
 * {@code requestStart} and consumes them at {@code requestEnd}.
 */
final class RequestError {

    private static final ThreadLocal<Throwable> COUNTED = new ThreadLocal<>();
    private static final ThreadLocal<Throwable> HANDLED = new ThreadLocal<>();

    private RequestError() {
    }

    /**
     * Records that {@code error} has already been counted into
     * {@link MeterNames#ERRORS} for the request being handled on this thread.
     */
    static void markCounted(Throwable error) {
        if (error != null) {
            COUNTED.set(error);
        }
    }

    /**
     * Whether this exact throwable has already been counted while handling the
     * current request. Compared by identity: Flow passes one throwable instance
     * to both reporting paths, whereas two equal-looking failures from
     * different interactions are genuinely two errors.
     */
    static boolean isCounted(Throwable error) {
        return error != null && COUNTED.get() == error;
    }

    /**
     * Records that Flow handled {@code error} through the session error handler
     * while a request was in flight, so the request's outcome reflects the
     * failure. Ignored outside request handling, where no interceptor would
     * consume it.
     */
    static void markHandled(Throwable error) {
        if (error != null && VaadinService.getCurrentRequest() != null) {
            HANDLED.set(error);
        }
    }

    /**
     * Returns and clears the failure the session error handler saw for this
     * request, or {@code null} if there was none.
     */
    static Throwable takeHandled() {
        Throwable handled = HANDLED.get();
        clear();
        return handled;
    }

    /**
     * Drops any state left over from a previous request on this (pooled)
     * thread.
     */
    static void clear() {
        HANDLED.remove();
        COUNTED.remove();
    }
}
