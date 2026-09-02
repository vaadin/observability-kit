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

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinSession;

class RequestMetricsBinderTest {

    @Test
    void successfulRequestRecordsDurationWithSuccessOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RequestMetricsBinder binder = new RequestMetricsBinder(registry,
                ObservabilitySettings.builder().traces(false).build());
        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        VaadinResponse res = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        binder.requestStart(req, res);
        binder.requestEnd(req, res, session);

        Timer timer = registry.find(MeterNames.REQUEST_DURATION)
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .timer();
        Assertions.assertNotNull(timer);
        Assertions.assertEquals(1L, timer.count());
    }

    @Test
    void errorStateDoesNotBleedIntoSubsequentRequest() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RequestMetricsBinder binder = new RequestMetricsBinder(registry,
                ObservabilitySettings.builder().traces(false).build());
        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        VaadinResponse res = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        // Request 1: ends with an error.
        binder.requestStart(req, res);
        binder.handleException(req, res, session,
                new IllegalStateException("boom"));
        binder.requestEnd(req, res, session);

        Timer errorTimer = registry.find(MeterNames.REQUEST_DURATION)
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_ERROR).timer();
        Assertions.assertNotNull(errorTimer, "request 1 should be error");
        Assertions.assertEquals(1L, errorTimer.count());

        // Request 2 on the same binder/thread: no exception.
        binder.requestStart(req, res);
        binder.requestEnd(req, res, session);

        // The success timer must have exactly one sample (from request 2).
        // Without F1 (clearing errored at requestStart), the errored flag left
        // by request 1's handleException—when requestEnd is skipped—would bleed
        // here. This test exercises the safe-guard by running both requests
        // sequentially on the same binder instance.
        Timer successTimer = registry.find(MeterNames.REQUEST_DURATION)
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .timer();
        Assertions.assertNotNull(successTimer,
                "request 2 should record a success sample");
        Assertions.assertEquals(1L, successTimer.count(),
                "request 2 must be outcome=success, not bleed error from request 1");
    }

    @Test
    void exceptionRecordsErrorOutcomeAndExceptionCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RequestMetricsBinder binder = new RequestMetricsBinder(registry,
                ObservabilitySettings.builder().traces(false).build());
        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        VaadinResponse res = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        binder.requestStart(req, res);
        binder.handleException(req, res, session,
                new IllegalStateException("boom"));
        binder.requestEnd(req, res, session);

        Timer timer = registry.find(MeterNames.REQUEST_DURATION)
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_ERROR).timer();
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
        binder.requestStart(req, res);
        binder.handleException(req, res, session, failure);
        binder.requestEnd(req, res, session);

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

        binder.requestStart(req, res);
        binder.requestEnd(req, res, session);

        Assertions.assertTrue(marked.isEmpty(),
                "no exception, nothing to mark");
    }

    @Test
    void errorMarkerRunsEvenWhenErrorCountingIsDisabled() {
        // The binder does not gate the marker on the errors setting: it
        // corrects the status of an observation the framework emits anyway.
        // End to end there is still a registration gate — the interceptor is
        // only registered under isRequests() || isErrors(), so with both off
        // the marker never runs.
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

        binder.requestStart(req, res);
        binder.handleException(req, res, session,
                new IllegalStateException("boom"));
        binder.requestEnd(req, res, session);

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

        binder.requestStart(req, res);
        binder.requestEnd(req, res, Mockito.mock(VaadinSession.class));

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

        binder.requestStart(req, res);
        RequestUi.mark(new UI());
        binder.requestEnd(req, res, Mockito.mock(VaadinSession.class));

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

        binder.requestStart(req, res);
        binder.requestEnd(req, res, Mockito.mock(VaadinSession.class));

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

            binder.requestStart(req, res);
            binder.requestEnd(req, res, Mockito.mock(VaadinSession.class));

            Assertions.assertNotNull(
                    registry.find(MeterNames.REQUEST_DURATION)
                            .tag("vaadin.request.type", "static").timer(),
                    path + " should classify as a static resource");
        }
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

        binder.requestStart(req, res);
        RequestUi.mark(ui);
        binder.requestEnd(req, res, Mockito.mock(VaadinSession.class));

        Assertions.assertEquals(List.of("RoutedView"), hooks.routes,
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

        binder.requestStart(req, res);
        binder.requestEnd(req, res, Mockito.mock(VaadinSession.class));

        Assertions.assertEquals(List.of("uidl"), types,
                "request type enrichment must run with traces off");
    }
}
