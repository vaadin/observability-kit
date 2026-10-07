/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.time.Duration;

import org.mockito.Mockito;

import com.vaadin.flow.server.RequestEndedEvent;
import com.vaadin.flow.server.RequestHandler;
import com.vaadin.flow.server.RequestStartedEvent;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;

/**
 * Drives a {@link RequestMetricsBinder} through the request events Flow fires
 * at the start and end of a request.
 */
final class RequestEvents {

    /** What every ended event reports as the request's duration. */
    static final Duration DURATION = Duration.ofMillis(42);

    private static final VaadinService SERVICE = Mockito
            .mock(VaadinService.class);

    private RequestEvents() {
    }

    static void start(RequestMetricsBinder binder, VaadinRequest request,
            VaadinResponse response) {
        binder.requestStarted(
                new RequestStartedEvent(SERVICE, request, response));
    }

    static void end(RequestMetricsBinder binder, VaadinRequest request,
            VaadinResponse response, VaadinSession session) {
        end(binder, request, response, session, null, null);
    }

    static void end(RequestMetricsBinder binder, VaadinRequest request,
            VaadinResponse response, VaadinSession session,
            RequestHandler handler, Exception failure) {
        binder.requestEnded(new RequestEndedEvent(SERVICE, request, response,
                session, handler, failure, DURATION));
    }
}
