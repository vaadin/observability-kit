/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.RequestEndedEvent;
import com.vaadin.flow.server.RequestHandler;
import com.vaadin.flow.server.RequestStartedEvent;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServiceEventBus;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.communication.HeartbeatHandler;
import com.vaadin.flow.server.communication.IndexHtmlRequestHandler;
import com.vaadin.flow.server.communication.JavaScriptBootstrapHandler;
import com.vaadin.flow.server.communication.StreamRequestHandler;
import com.vaadin.flow.server.communication.UidlRequestHandler;
import com.vaadin.flow.shared.Registration;
import com.vaadin.observability.micrometer.trace.ObservationNames;

class RequestMetricsBinderTest {

    @Test
    void successfulRequestRecordsDurationWithSuccessOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RequestMetricsBinder binder = new RequestMetricsBinder(registry,
                ObservabilitySettings.builder().traces(false).build());
        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        VaadinResponse res = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        RequestEvents.start(binder, req, res);
        RequestEvents.end(binder, req, res, session);

        Timer timer = registry.find(MeterNames.REQUEST_DURATION)
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .timer();
        Assertions.assertNotNull(timer);
        Assertions.assertEquals(1L, timer.count());
        Assertions.assertEquals(RequestEvents.DURATION.toNanos(),
                timer.totalTime(TimeUnit.NANOSECONDS),
                "the Timer records the duration Flow measured");
    }

    @Test
    void registrationSubscribesToTheRequestEvents() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        VaadinServiceEventBus bus = new VaadinServiceEventBus(
                Mockito.mock(VaadinService.class));
        Registration registration = new RequestMetricsBinder(registry,
                ObservabilitySettings.builder().traces(false).build())
                .register(bus);
        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        VaadinResponse res = Mockito.mock(VaadinResponse.class);

        bus.fireEvent(new RequestStartedEvent(bus.getService(), req, res));
        bus.fireEvent(new RequestEndedEvent(bus.getService(), req, res, null,
                null, null, RequestEvents.DURATION));
        registration.remove();
        bus.fireEvent(new RequestStartedEvent(bus.getService(), req, res));
        bus.fireEvent(new RequestEndedEvent(bus.getService(), req, res, null,
                null, null, RequestEvents.DURATION));

        Assertions.assertEquals(1L,
                registry.get(MeterNames.REQUEST_DURATION).timer().count(),
                "one request timed while subscribed, none after");
    }

    @Test
    void unknownHttpMethodsShareOneTimer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RequestMetricsBinder binder = new RequestMetricsBinder(registry,
                ObservabilitySettings.builder().traces(false).build());
        VaadinResponse res = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        for (String method : List.of("POST", "FOO1", "FOO2", "get")) {
            VaadinRequest req = Mockito.mock(VaadinRequest.class);
            Mockito.when(req.getMethod()).thenReturn(method);
            RequestEvents.start(binder, req, res);
            RequestEvents.end(binder, req, res, session);
        }

        List<String> methods = registry.find(MeterNames.REQUEST_DURATION)
                .timers().stream()
                .map(t -> t.getId().getTag(ObservationNames.KEY_HTTP_METHOD))
                .sorted().toList();
        Assertions.assertEquals(List.of("POST", "_other"), methods);
        Assertions.assertEquals(3L,
                registry.find(MeterNames.REQUEST_DURATION)
                        .tag(ObservationNames.KEY_HTTP_METHOD, "_other").timer()
                        .count());
    }

    @Test
    void failureRecordsErrorOutcomeAndExceptionCounter() {
        // No session error handler saw the failure (here: none is
        // instrumented), so the binder counts it itself.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RequestMetricsBinder binder = new RequestMetricsBinder(registry,
                ObservabilitySettings.builder().traces(false).build());
        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        VaadinResponse res = Mockito.mock(VaadinResponse.class);

        RequestEvents.start(binder, req, res);
        RequestEvents.end(binder, req, res, null, null,
                new IllegalStateException("boom"));

        Timer timer = registry.find(MeterNames.REQUEST_DURATION)
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_ERROR)
                .tag(MeterNames.TAG_ERROR, "IllegalStateException").timer();
        Assertions.assertNotNull(timer);
        Assertions.assertEquals(1L, timer.count());

        Assertions.assertEquals(1.0,
                registry.find(MeterNames.ERRORS)
                        .tag(MeterNames.TAG_EXCEPTION, "IllegalStateException")
                        .counter().count(),
                0.0);
    }

    @Test
    void exceptionInvokesHttpObservationErrorMarker() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<Throwable> marked = new ArrayList<>();
        RequestMetricsBinder binder = new RequestMetricsBinder(registry, null,
                ObservabilitySettings.builder().traces(false).build(),
                new HttpObservationHooks() {
                    @Override
                    public void error(VaadinRequest request,
                            Throwable failure) {
                        marked.add(failure);
                    }
                }, new ErrorCounter(registry,
                        ObservabilitySettings.builder().build()));
        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        VaadinResponse res = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        IllegalStateException failure = new IllegalStateException("boom");
        RequestEvents.start(binder, req, res);
        RequestEvents.end(binder, req, res, session, null, failure);

        Assertions.assertEquals(List.of(failure), marked,
                "the framework HTTP observation must be told about the "
                        + "exception Vaadin swallowed");
    }

    @Test
    void successfulRequestDoesNotInvokeHttpObservationErrorMarker() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<Throwable> marked = new ArrayList<>();
        RequestMetricsBinder binder = new RequestMetricsBinder(registry, null,
                ObservabilitySettings.builder().traces(false).build(),
                new HttpObservationHooks() {
                    @Override
                    public void error(VaadinRequest request,
                            Throwable failure) {
                        marked.add(failure);
                    }
                }, new ErrorCounter(registry,
                        ObservabilitySettings.builder().build()));
        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        VaadinResponse res = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        RequestEvents.start(binder, req, res);
        RequestEvents.end(binder, req, res, session);

        Assertions.assertTrue(marked.isEmpty(),
                "no exception, nothing to mark");
    }

    @Test
    void errorMarkerRunsEvenWhenErrorCountingIsDisabled() {
        // The binder does not gate the marker on the errors setting: it
        // corrects the status of an observation the framework emits anyway.
        // End to end there is still a registration gate — the binder is only
        // registered under isRequests() || isErrors(), so with both off the
        // marker never runs.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<Throwable> marked = new ArrayList<>();
        RequestMetricsBinder binder = new RequestMetricsBinder(registry, null,
                ObservabilitySettings.builder().traces(false).errors(false)
                        .build(),
                new HttpObservationHooks() {
                    @Override
                    public void error(VaadinRequest request,
                            Throwable failure) {
                        marked.add(failure);
                    }
                }, null);
        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        VaadinResponse res = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        RequestEvents.start(binder, req, res);
        RequestEvents.end(binder, req, res, session, null,
                new IllegalStateException("boom"));

        Assertions.assertEquals(1, marked.size());
        Assertions.assertNull(registry.find(MeterNames.ERRORS).counter(),
                "vaadin.errors stays gated on the errors setting");
    }

    private static final class RouteRecordingHooks
            implements HttpObservationHooks {
        final List<String> routes = new ArrayList<>();

        @Override
        public void route(VaadinRequest request, String routeTemplate) {
            routes.add(routeTemplate);
        }
    }

    private static RequestMetricsBinder observedBinder(
            SimpleMeterRegistry registry, HttpObservationHooks hooks) {
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(
                new DefaultMeterObservationHandler(registry));
        return new RequestMetricsBinder(registry, observations,
                ObservabilitySettings.builder().build(), hooks);
    }

    private static VaadinRequest uidlRequest() {
        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        Mockito.when(req.getParameter("v-r")).thenReturn("uidl");
        return req;
    }

    @Test
    void routeHookNotCalledWhenNoUiWasMarked() {
        // A UIDL request in which no RPC, navigation or poll handler ran
        // leaves the RequestUi relay empty, and there is no view to report.
        RouteRecordingHooks hooks = new RouteRecordingHooks();
        RequestMetricsBinder binder = observedBinder(new SimpleMeterRegistry(),
                hooks);
        VaadinRequest req = uidlRequest();
        VaadinResponse res = Mockito.mock(VaadinResponse.class);

        RequestEvents.start(binder, req, res);
        RequestEvents.end(binder, req, res, Mockito.mock(VaadinSession.class));

        Assertions.assertTrue(hooks.routes.isEmpty(),
                "no UI was marked during handling, so no route to report");
    }

    @Test
    void routeHookNotCalledWhenNoTemplateResolves() {
        // The hook path resolves templates only: a UI with no active
        // navigation target must not fall back to its concrete view location,
        // which would feed literal paths (orders/17, orders/18, ...) into the
        // bounded uri budget. A fresh UI has no target chain, so nothing is
        // reported.
        RouteRecordingHooks hooks = new RouteRecordingHooks();
        RequestMetricsBinder binder = observedBinder(new SimpleMeterRegistry(),
                hooks);
        VaadinRequest req = uidlRequest();
        VaadinResponse res = Mockito.mock(VaadinResponse.class);

        RequestEvents.start(binder, req, res);
        RequestUi.mark(new UI());
        RequestEvents.end(binder, req, res, Mockito.mock(VaadinSession.class));

        Assertions.assertTrue(hooks.routes.isEmpty(),
                "no resolvable template, so no uri to report");
    }

    @Test
    void routeHookNotCalledForNonUidlRequests() {
        RouteRecordingHooks hooks = new RouteRecordingHooks();
        RequestMetricsBinder binder = observedBinder(new SimpleMeterRegistry(),
                hooks);
        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        Mockito.when(req.getPathInfo()).thenReturn("/VAADIN/build/app.js");
        VaadinResponse res = Mockito.mock(VaadinResponse.class);

        RequestEvents.start(binder, req, res);
        RequestEvents.end(binder, req, res, Mockito.mock(VaadinSession.class));

        Assertions.assertTrue(hooks.routes.isEmpty(),
                "static resources have no view to attribute");
    }

    @Test
    void staticClassifierCoversThemesAndServiceWorker() {
        for (String path : new String[] { "/themes/mytheme/styles.css",
                "/sw.js" }) {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            ObservationRegistry observations = ObservationRegistry.create();
            observations.observationConfig().observationHandler(
                    new DefaultMeterObservationHandler(registry));
            RequestMetricsBinder binder = new RequestMetricsBinder(registry,
                    observations, ObservabilitySettings.builder().build());
            VaadinRequest req = Mockito.mock(VaadinRequest.class);
            Mockito.when(req.getPathInfo()).thenReturn(path);
            VaadinResponse res = Mockito.mock(VaadinResponse.class);

            RequestEvents.start(binder, req, res);
            RequestEvents.end(binder, req, res,
                    Mockito.mock(VaadinSession.class));

            Assertions.assertNotNull(
                    registry.find(MeterNames.REQUEST_DURATION)
                            .tag("vaadin.request.type", "static").timer(),
                    path + " should classify as a static resource");
        }
    }

    /**
     * Classifies one request and returns the {@code vaadin.request.type} tag
     * the Timer got, driving the Observation path so the type is read exactly
     * as the span carries it.
     */
    private static String classify(RequestCustomizer customizer) {
        return classify(null, Mockito.mock(VaadinResponse.class), customizer);
    }

    /**
     * Classifies one request the given handler handled, with the given response
     * ({@code null} for a push message).
     */
    private static String classify(RequestHandler handler, VaadinResponse res,
            RequestCustomizer customizer) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(
                new DefaultMeterObservationHandler(registry));
        RequestMetricsBinder binder = new RequestMetricsBinder(registry,
                observations, ObservabilitySettings.builder().build());
        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        customizer.accept(req);

        RequestEvents.start(binder, req, res);
        RequestEvents.end(binder, req, res, Mockito.mock(VaadinSession.class),
                handler, null);

        Timer timer = registry.find(MeterNames.REQUEST_DURATION).timer();
        Assertions.assertNotNull(timer, "the request must be timed");
        return timer.getId().getTag("vaadin.request.type");
    }

    private interface RequestCustomizer {
        void accept(VaadinRequest request);
    }

    @Test
    void theRequestHandlerDecidesTheType() {
        // A URL that says nothing, so the type can only come from the
        // handler.
        RequestCustomizer anyUrl = r -> Mockito.when(r.getPathInfo())
                .thenReturn("/anything");
        VaadinResponse res = Mockito.mock(VaadinResponse.class);
        Assertions.assertEquals("uidl",
                classify(Mockito.mock(UidlRequestHandler.class), res, anyUrl));
        Assertions.assertEquals("heartbeat",
                classify(Mockito.mock(HeartbeatHandler.class), res, anyUrl));
        Assertions.assertEquals("stream", classify(
                Mockito.mock(StreamRequestHandler.class), res, anyUrl));
        Assertions.assertEquals("bootstrap",
                classify(Mockito.mock(IndexHtmlRequestHandler.class), res,
                        anyUrl),
                "index.html is the page load, with or without browser hints");
        Assertions.assertEquals("bootstrap",
                classify(Mockito.mock(JavaScriptBootstrapHandler.class), res,
                        anyUrl),
                "the init request creates the UI for the page just loaded");
    }

    @Test
    void theUrlDecidesTheTypeForOtherHandlers() {
        Assertions.assertEquals("static",
                classify(Mockito.mock(RequestHandler.class),
                        Mockito.mock(VaadinResponse.class),
                        r -> Mockito.when(r.getPathInfo())
                                .thenReturn("/VAADIN/build/app.js")),
                "a handler the binder does not know leaves it to the URL");
        Assertions.assertEquals("push",
                classify(Mockito.mock(RequestHandler.class),
                        Mockito.mock(VaadinResponse.class), r -> {
                            Mockito.when(r.getPathInfo())
                                    .thenReturn("/VAADIN/push");
                            Mockito.when(r.getParameter("v-r"))
                                    .thenReturn("push");
                        }),
                "the push connection is push, not a static resource");
    }

    @Test
    void aPushMessageIsAPushRequest() {
        // Flow reports a message sent over a push connection without a
        // response and without a handler, on the push connection's URL.
        Assertions.assertEquals("push", classify(null, null,
                r -> Mockito.when(r.getPathInfo()).thenReturn("/VAADIN/push")));
    }

    @Test
    void streamClassifierCoversDownloadsAndUploads() {
        // Both go through Flow's StreamRequestHandler, under a path that
        // starts with /VAADIN/ — so without the stream check they would be
        // counted as cheap static assets.
        Assertions.assertEquals("stream",
                classify(r -> Mockito.when(r.getPathInfo()).thenReturn(
                        "/VAADIN/dynamic/resource/1/5298ee8b-9686-4a5a-ae1d-b38c62767d6a/report.pdf")),
                "a download must classify as a stream request");
        Assertions.assertEquals("stream",
                classify(r -> Mockito.when(r.getPathInfo()).thenReturn(
                        "/VAADIN/dynamic/resource/1/5298ee8b-9686-4a5a-ae1d-b38c62767d6a/upload")),
                "an upload must classify as a stream request");
    }

    @Test
    void bootstrapClassifierCoversThePageLoad() {
        Assertions.assertEquals("bootstrap", classify(r -> {
            Mockito.when(r.getMethod()).thenReturn("GET");
            Mockito.when(r.getPathInfo()).thenReturn("/orders");
            Mockito.when(r.getHeader("Sec-Fetch-Dest")).thenReturn("document");
        }), "the HTML document request is the page load");
        Assertions.assertEquals("bootstrap", classify(
                r -> Mockito.when(r.getParameter("v-r")).thenReturn("init")),
                "the init request creates the UI for the page just loaded");
        Assertions.assertEquals("bootstrap", classify(r -> {
            // A browser too old to send Sec-Fetch-Dest still asks for HTML.
            Mockito.when(r.getMethod()).thenReturn("GET");
            Mockito.when(r.getHeader("Accept")).thenReturn(
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        }), "an explicit HTML Accept is a page load too");
        Assertions.assertEquals("bootstrap", classify(r -> {
            // An embedded route: served the same index.html, and it builds a
            // UI of its own, so the browser-side bootstrap meter counts it too.
            Mockito.when(r.getMethod()).thenReturn("GET");
            Mockito.when(r.getPathInfo()).thenReturn("/orders");
            Mockito.when(r.getHeader("Sec-Fetch-Dest")).thenReturn("iframe");
        }), "an embedded application start is a page load");
    }

    @Test
    void anAcceptHeaderThatOnlyMentionsHtmlIsNotAPageLoad() {
        // The Accept fallback runs for every request without a Sec-Fetch-Dest,
        // scripted traffic included: only a header that asks for HTML first —
        // as a navigating browser does — may be read as a page load.
        Assertions.assertEquals("other", classify(r -> {
            Mockito.when(r.getMethod()).thenReturn("GET");
            Mockito.when(r.getPathInfo()).thenReturn("/api/orders");
            Mockito.when(r.getHeader("Accept"))
                    .thenReturn("application/json, text/html;q=0.1");
        }), "an API client listing text/html last is not navigating");
        Assertions.assertEquals("bootstrap", classify(r -> {
            Mockito.when(r.getMethod()).thenReturn("GET");
            Mockito.when(r.getHeader("Accept"))
                    .thenReturn("text/html; charset=utf-8, */*");
        }), "parameters on the first media range must not hide it");
    }

    @Test
    void applicationTrafficIsNotMistakenForAPageLoad() {
        Assertions.assertEquals("other", classify(r -> {
            // fetch() from application code: same servlet, same method, but
            // the browser reports it as a non-document destination.
            Mockito.when(r.getMethod()).thenReturn("GET");
            Mockito.when(r.getPathInfo()).thenReturn("/api/orders");
            Mockito.when(r.getHeader("Sec-Fetch-Dest")).thenReturn("empty");
            Mockito.when(r.getHeader("Accept")).thenReturn("*/*");
        }), "an XHR must not be counted as a page load");
        Assertions.assertEquals("other", classify(r -> {
            Mockito.when(r.getMethod()).thenReturn("POST");
            Mockito.when(r.getPathInfo()).thenReturn("/api/orders");
        }), "a POST to an application endpoint is not a page load");
        Assertions.assertEquals("other",
                classify(r -> Mockito.when(r.getMethod()).thenReturn("GET")),
                "a request saying nothing about what it wants stays other");
    }

    @com.vaadin.flow.component.Tag("routed-view")
    private static final class RoutedView
            extends com.vaadin.flow.component.Component {
    }

    @Test
    void routeHookRunsWithoutTracing() {
        // The uri tag the route feeds on http.server.requests is a metric,
        // so the enrichment must not depend on the traces setting or an
        // ObservationRegistry.
        RouteRecordingHooks hooks = new RouteRecordingHooks();
        RequestMetricsBinder binder = new RequestMetricsBinder(
                new SimpleMeterRegistry(), null,
                ObservabilitySettings.builder().traces(false).build(), hooks);
        VaadinRequest req = uidlRequest();
        VaadinResponse res = Mockito.mock(VaadinResponse.class);

        UI ui = Mockito.mock(UI.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(ui.getInternals().getActiveRouterTargetsChain())
                .thenReturn(List.of(new RoutedView()));
        Mockito.when(ui.getInternals().getRouter().getRegistry()
                .getTemplate(RoutedView.class))
                .thenReturn(Optional.of("routed"));

        RequestEvents.start(binder, req, res);
        RequestUi.mark(ui);
        RequestEvents.end(binder, req, res, Mockito.mock(VaadinSession.class));

        Assertions.assertEquals(List.of("routed"), hooks.routes,
                "route enrichment must run with traces off");
    }

    @Test
    void requestTypeHookRunsWithoutTracing() {
        List<String> types = new ArrayList<>();
        RequestMetricsBinder binder = new RequestMetricsBinder(
                new SimpleMeterRegistry(), null,
                ObservabilitySettings.builder().traces(false).build(),
                new HttpObservationHooks() {
                    @Override
                    public void requestType(VaadinRequest request,
                            String type) {
                        types.add(type);
                    }
                });
        VaadinRequest req = uidlRequest();
        VaadinResponse res = Mockito.mock(VaadinResponse.class);

        RequestEvents.start(binder, req, res);
        RequestEvents.end(binder, req, res, Mockito.mock(VaadinSession.class));

        Assertions.assertEquals(List.of("uidl"), types,
                "request type enrichment must run with traces off");
    }
}
