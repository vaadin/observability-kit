/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import com.vaadin.flow.server.VaadinRequest;

/**
 * Callbacks into the framework-level HTTP observation (e.g. Spring's
 * {@code ServerHttpObservationFilter} span) for the request currently being
 * handled. Implemented by DI integrations; every method defaults to a no-op so
 * standalone deployments need nothing.
 * <p>
 * These hooks correct or enrich an observation the framework emits anyway —
 * they never emit telemetry of their own, which is why the callers in
 * {@link RequestMetricsBinder} do not gate them on the {@code requests} or
 * {@code errors} settings.
 */
interface HttpObservationHooks {

    /** The no-op instance for deployments without a framework observation. */
    HttpObservationHooks NONE = new HttpObservationHooks() {
    };

    /**
     * Called at request start, once the Vaadin request type is classified, so
     * the HTTP span can render as e.g. {@code http post /vaadin/uidl} instead
     * of the generic {@code http post /**}.
     *
     * @param request
     *            the current Vaadin request
     * @param requestType
     *            the classified request type (e.g. {@code uidl},
     *            {@code heartbeat}, {@code push}, {@code static},
     *            {@code other})
     */
    default void requestType(VaadinRequest request, String requestType) {
    }

    /**
     * Called at request end when the active Vaadin route template resolved, so
     * the HTTP observation's path pattern (the {@code uri} tag on
     * {@code http.server.requests}, and the span name) can carry the view
     * instead of the protocol-level {@code /vaadin/uidl}.
     *
     * @param request
     *            the current Vaadin request
     * @param routeTemplate
     *            the resolved route template (e.g. {@code orders/:id}), already
     *            run through the route cardinality cap; blank means the root
     *            route
     */
    default void route(VaadinRequest request, String routeTemplate) {
    }

    /**
     * Called when Vaadin request handling raises an exception. For a UIDL
     * request Vaadin swallows the exception and responds 200, so the framework
     * would otherwise report the request as successful; for other request types
     * Vaadin rethrows and the framework records the failure itself, and this
     * call merely front-runs it with the root cause.
     *
     * @param request
     *            the current Vaadin request
     * @param failure
     *            the exception Vaadin request handling raised, never
     *            {@code null}
     */
    default void error(VaadinRequest request, Exception failure) {
    }
}
