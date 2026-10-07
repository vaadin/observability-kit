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
 * Thread-local relay from the session {@code ErrorHandler} to the end of the
 * request being handled, where {@link RequestMetricsBinder} reads it.
 * <p>
 * It serves two purposes:
 * <ul>
 * <li><b>Outcome.</b> Flow's {@code RequestEndedEvent} only reports the
 * exception that made handling the request fail. The ones Flow handles itself
 * (a failing component listener, {@code UI.access} body or navigation callback)
 * only reach the error handler, so without a relay the enclosing
 * {@code vaadin.request} span would report {@code outcome=success} for a
 * request whose whole point — the user's interaction — failed.</li>
 * <li><b>Deduplication.</b> Flow hands the exception that made handling the
 * request fail to the session error handler too, before the request ends. The
 * error handler counts it, and the relay tells the request binder not to count
 * the same throwable a second time.</li>
 * </ul>
 * Request handling is synchronous on the request thread, so a value written
 * during handling is visible at the end of the request on the same thread. The
 * request binder clears the slot when a request starts and consumes it when the
 * request ends.
 */
final class RequestError {

    private static final ThreadLocal<Throwable> HANDLED = new ThreadLocal<>();

    private RequestError() {
    }

    /**
     * Records that Flow handled {@code error} through the session error handler
     * while a request was in flight, so the request's outcome reflects the
     * failure. Ignored outside request handling, where nothing would consume
     * it.
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
    }
}
