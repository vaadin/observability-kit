/**
 * Copyright (C) 2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring;

import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.filter.ServerHttpObservationFilter;

import com.vaadin.flow.server.VaadinRequest;

/**
 * Lifts Vaadin request information into Spring's HTTP server observation (the
 * {@code http <method> <uri>} span emitted by
 * {@link ServerHttpObservationFilter}). This makes the parent HTTP span render
 * as e.g. {@code http post /vaadin/uidl} instead of the generic
 * {@code http post /**} so the request type is visible in the trace UI without
 * having to drill into the child {@code vaadin.request.<type>} span.
 * <p>
 * Lives in {@code observability-kit-spring} (rather than the framework-agnostic
 * base module) so Spring's HTTP observation classes can be imported directly
 * and we don't need reflection.
 */
final class SpringHttpObservationEnricher {

    private SpringHttpObservationEnricher() {
    }

    /**
     * Enriches the Spring HTTP observation attached to {@code request}, if any.
     * Best-effort: silently no-ops if Spring's filter didn't run (e.g.
     * non-Spring-MVC deployment) or the request isn't a servlet request.
     *
     * @param request
     *            the current Vaadin request, may be {@code null}
     * @param type
     *            the classified request type (e.g. {@code uidl}), may be
     *            {@code null}
     */
    static void enrich(VaadinRequest request, String type) {
        if (request == null || type == null) {
            return;
        }
        Object ctx = request.getAttribute(
                ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE);
        if (!(ctx instanceof ServerRequestObservationContext src)) {
            return;
        }
        try {
            src.setPathPattern("/vaadin/" + type);
            String method = request.getMethod();
            src.setContextualName(
                    "http " + (method == null ? "?" : method.toLowerCase())
                            + " vaadin " + type);
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    /**
     * Marks the Spring HTTP observation attached to {@code request} as errored,
     * if any. Vaadin handles exceptions internally, so the servlet chain never
     * throws and {@link ServerHttpObservationFilter} would otherwise report the
     * request as successful. The error lands on the observation context, which
     * the meter and tracing handlers read when the filter stops the observation
     * as the chain unwinds — so the parent HTTP span (and its
     * {@code http.server.requests} Timer) reflect the failure, which is what
     * error monitoring in APM tools watches.
     * <p>
     * Best-effort: silently no-ops if Spring's filter didn't run or the request
     * isn't a servlet request.
     *
     * @param request
     *            the current Vaadin request, may be {@code null}
     * @param failure
     *            the exception Vaadin request handling raised, may be
     *            {@code null}
     */
    static void error(VaadinRequest request, Throwable failure) {
        if (request == null || failure == null) {
            return;
        }
        Object ctx = request.getAttribute(
                ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE);
        if (!(ctx instanceof ServerRequestObservationContext src)) {
            return;
        }
        try {
            src.setError(failure);
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }
}
