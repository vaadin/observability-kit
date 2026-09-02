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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.vaadin.flow.internal.CurrentInstance;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.observability.micrometer.trace.ObservationNames;

class RequestMetricsBinderObservationTest {

    private static final class RecordingHandler
            implements ObservationHandler<Observation.Context> {

        final List<String> names = new ArrayList<>();
        final List<String> contextualNames = new ArrayList<>();
        final List<Map<String, String>> tags = new ArrayList<>();
        final List<ObservationView> parents = new ArrayList<>();
        final List<Map<String, String>> highCardinalityTags = new ArrayList<>();
        final List<String> scopesClosed = new ArrayList<>();
        final AtomicBoolean errored = new AtomicBoolean();

        @Override
        public void onScopeClosed(Observation.Context ctx) {
            scopesClosed.add(ctx.getName());
        }

        @Override
        public void onStop(Observation.Context ctx) {
            names.add(ctx.getName());
            parents.add(ctx.getParentObservation());
            contextualNames.add(ctx.getContextualName());
            Map<String, String> snap = new HashMap<>();
            for (KeyValue kv : ctx.getLowCardinalityKeyValues()) {
                snap.put(kv.getKey(), kv.getValue());
            }
            tags.add(snap);
            Map<String, String> highSnap = new HashMap<>();
            for (KeyValue kv : ctx.getHighCardinalityKeyValues()) {
                highSnap.put(kv.getKey(), kv.getValue());
            }
            highCardinalityTags.add(highSnap);
            if (ctx.getError() != null) {
                errored.set(true);
            }
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }

    /**
     * Micrometer keeps the current scope in a {@code static} thread-local, so
     * it is shared by every registry on this thread. A scope a test leaves open
     * on purpose would otherwise become the parent of the next test's
     * observations.
     */
    @AfterEach
    void clearCurrentScope() {
        ObservationRegistry.create().setCurrentObservationScope(null);
        RequestInteraction.clear();
    }

    @Test
    void observationProducesExpectedNameAndTagsOnSuccess() {
        ObservationRegistry obs = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        obs.observationConfig().observationHandler(recorder);

        RequestMetricsBinder binder = new RequestMetricsBinder(
                new SimpleMeterRegistry(), obs,
                ObservabilitySettings.builder().build());

        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        Mockito.when(req.getParameter("v-r")).thenReturn("uidl");
        VaadinResponse resp = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        binder.requestStart(req, resp);
        binder.requestEnd(req, resp, session);

        Assertions.assertEquals(1, recorder.names.size());
        Assertions.assertEquals(MeterNames.REQUEST_DURATION,
                recorder.names.get(0));
        // No poll/navigation marked, so a plain UIDL request is labelled as a
        // generic "rpc" interaction rather than the opaque "uidl".
        Assertions.assertEquals(
                ObservationNames.REQUEST + "."
                        + ObservationNames.INTERACTION_RPC,
                recorder.contextualNames.get(0));
        Assertions.assertEquals("uidl",
                recorder.tags.get(0).get(ObservationNames.KEY_REQUEST_TYPE));
        Assertions.assertEquals(ObservationNames.INTERACTION_RPC,
                recorder.tags.get(0).get(ObservationNames.KEY_INTERACTION));
        Assertions.assertEquals(ObservationNames.OUTCOME_SUCCESS,
                recorder.tags.get(0).get(ObservationNames.KEY_OUTCOME));
        Assertions.assertFalse(recorder.errored.get());
    }

    @Test
    void pollMarkerLabelsRequestAsPoll() {
        ObservationRegistry obs = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        obs.observationConfig().observationHandler(recorder);

        RequestMetricsBinder binder = new RequestMetricsBinder(
                new SimpleMeterRegistry(), obs,
                ObservabilitySettings.builder().build());

        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        Mockito.when(req.getParameter("v-r")).thenReturn("uidl");
        VaadinResponse resp = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        binder.requestStart(req, resp);
        // Simulate a poll listener firing during request handling.
        RequestInteraction.mark(ObservationNames.INTERACTION_POLL);
        binder.requestEnd(req, resp, session);

        Assertions.assertEquals(
                ObservationNames.REQUEST + "."
                        + ObservationNames.INTERACTION_POLL,
                recorder.contextualNames.get(0));
        Assertions.assertEquals(ObservationNames.INTERACTION_POLL,
                recorder.tags.get(0).get(ObservationNames.KEY_INTERACTION));
    }

    @Test
    void staleMarkerIsClearedAtRequestStart() {
        ObservationRegistry obs = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        obs.observationConfig().observationHandler(recorder);

        RequestMetricsBinder binder = new RequestMetricsBinder(
                new SimpleMeterRegistry(), obs,
                ObservabilitySettings.builder().build());

        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        Mockito.when(req.getParameter("v-r")).thenReturn("uidl");
        VaadinResponse resp = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        // Leftover marker from a prior request on this thread.
        RequestInteraction.mark(ObservationNames.INTERACTION_POLL);
        binder.requestStart(req, resp);
        binder.requestEnd(req, resp, session);

        Assertions.assertEquals(ObservationNames.INTERACTION_RPC,
                recorder.tags.get(0).get(ObservationNames.KEY_INTERACTION));
    }

    @Test
    void leakedScopeIsClosedSoNextRequestIsNotParentedOnStaleObservation() {
        ObservationRegistry obs = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        obs.observationConfig().observationHandler(recorder);

        RequestMetricsBinder binder = new RequestMetricsBinder(
                new SimpleMeterRegistry(), obs,
                ObservabilitySettings.builder().build());

        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        Mockito.when(req.getParameter("v-r")).thenReturn("uidl");
        VaadinResponse resp = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        Assertions.assertNull(obs.getCurrentObservation(),
                "precondition: no observation is current on this thread");

        // A request whose requestEnd never ran (e.g. mid-request shutdown)
        // leaves its scope open on this thread.
        binder.requestStart(req, resp);
        Assertions.assertNotNull(obs.getCurrentObservation(),
                "precondition: the first request opened a scope");

        // The pooled thread now serves the next request.
        binder.requestStart(req, resp);
        binder.requestEnd(req, resp, session);

        Assertions.assertEquals(1, recorder.parents.size());
        Assertions.assertNull(recorder.parents.get(0),
                "new request span must not be parented on the stale observation");
        Assertions.assertNull(obs.getCurrentObservation(),
                "no scope should remain current once the request ended");
    }

    @Test
    void leakedScopeIsOnlyDroppedWhileAnEnclosingScopeIsCurrent() {
        ObservationRegistry obs = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        obs.observationConfig().observationHandler(recorder);

        RequestMetricsBinder binder = new RequestMetricsBinder(
                new SimpleMeterRegistry(), obs,
                ObservabilitySettings.builder().build());

        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        Mockito.when(req.getParameter("v-r")).thenReturn("uidl");
        VaadinResponse resp = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        // A request whose requestEnd never ran leaves its scope open.
        binder.requestStart(req, resp);

        // Something live then becomes current on the pooled thread, the way a
        // Spring/Boot HTTP observation wraps the next request.
        Observation enclosing = Observation.start("http.server.requests", obs);
        Observation.Scope enclosingScope = enclosing.openScope();

        // Cleaning up the leaked scope must not close it: close() would
        // reinstate its previous scope and evict the enclosing one.
        binder.requestStart(req, resp);
        binder.requestEnd(req, resp, session);

        Assertions.assertEquals(1, recorder.parents.size());
        Assertions.assertSame(enclosing, recorder.parents.get(0),
                "the request span must be parented on the enclosing live "
                        + "observation, not on the stale one below it");
        Assertions.assertSame(enclosingScope, obs.getCurrentObservationScope(),
                "the enclosing scope must still be current after the request");

        enclosingScope.close();
        enclosing.stop();
    }

    @Test
    void requestEndUnwindsAScopeLeakedOnTopOfTheRequestScope() {
        ObservationRegistry obs = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        obs.observationConfig().observationHandler(recorder);

        RequestMetricsBinder binder = new RequestMetricsBinder(
                new SimpleMeterRegistry(), obs,
                ObservabilitySettings.builder().build());

        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        Mockito.when(req.getParameter("v-r")).thenReturn("uidl");
        VaadinResponse resp = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        binder.requestStart(req, resp);
        // Nested instrumentation whose end callback never ran, e.g. an RPC
        // invocation interrupted mid-request.
        Observation nested = Observation.start("nested.rpc", obs);
        nested.openScope();

        binder.requestEnd(req, resp, session);

        // Waiting for the next requestStart to clean this up would leave a
        // dead observation current for anything running on this pooled thread
        // in between, so the leaked scope must be unwound here, innermost
        // first, and its handlers notified.
        Assertions.assertEquals(
                List.of("nested.rpc", MeterNames.REQUEST_DURATION),
                recorder.scopesClosed,
                "the leaked nested scope must be closed before ours");
        Assertions.assertNull(obs.getCurrentObservationScope(),
                "no scope should remain current once the request ended");

        nested.stop();
    }

    @Test
    void observationCarriesErrorAndOutcomeOnException() {
        ObservationRegistry obs = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        obs.observationConfig().observationHandler(recorder);

        RequestMetricsBinder binder = new RequestMetricsBinder(
                new SimpleMeterRegistry(), obs,
                ObservabilitySettings.builder().build());

        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        VaadinResponse resp = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        binder.requestStart(req, resp);
        binder.handleException(req, resp, session,
                new IllegalStateException("boom"));
        binder.requestEnd(req, resp, session);

        Assertions.assertEquals(ObservationNames.OUTCOME_ERROR,
                recorder.tags.get(0).get(ObservationNames.KEY_OUTCOME));
        Assertions.assertTrue(recorder.errored.get());
    }

    @Test
    void handledListenerFailureMarksTheRequestSpanAsErrored() {
        // The exception a component listener throws is caught by Flow and
        // routed to the session error handler, so it never reaches
        // handleException. The request span must still report the failure
        // rather than claiming outcome=success.
        ObservationRegistry obs = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        obs.observationConfig().observationHandler(recorder);

        RequestMetricsBinder binder = new RequestMetricsBinder(
                new SimpleMeterRegistry(), obs,
                ObservabilitySettings.builder().build());

        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        VaadinResponse resp = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);
        CurrentInstance.set(VaadinRequest.class, req);
        try {
            binder.requestStart(req, resp);
            RequestError.markHandled(new IllegalStateException("boom"));
            binder.requestEnd(req, resp, session);
        } finally {
            CurrentInstance.clearAll();
        }

        Assertions.assertEquals(ObservationNames.OUTCOME_ERROR,
                recorder.tags.get(0).get(ObservationNames.KEY_OUTCOME));
        Assertions.assertTrue(recorder.errored.get(),
                "the span should carry the handled exception");
    }

    @Test
    void uiIdAndClientLocationAreSpanOnlyAndNeverTimerTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservationRegistry obs = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        obs.observationConfig()
                .observationHandler(
                        new DefaultMeterObservationHandler(registry))
                .observationHandler(recorder);

        RequestMetricsBinder binder = new RequestMetricsBinder(registry, obs,
                ObservabilitySettings.builder().build());

        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        Mockito.when(req.getParameter("v-r")).thenReturn("uidl");
        Mockito.when(req.getParameter("v-uiId")).thenReturn("42");
        Mockito.when(req.getHeader("Referer"))
                .thenReturn("https://example.com/orders/17");
        VaadinResponse resp = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        binder.requestStart(req, resp);
        binder.requestEnd(req, resp, session);

        // Both values reach the span...
        Assertions.assertEquals("42", recorder.highCardinalityTags.get(0)
                .get(ObservationNames.KEY_UI_ID));
        Assertions.assertEquals("/orders/17", recorder.highCardinalityTags
                .get(0).get(ObservationNames.KEY_CLIENT_LOCATION));

        // ...but neither becomes a Timer tag: a UI id is unbounded and the
        // client location is un-templated, so tagging with them would multiply
        // the time series of vaadin.request.duration without limit.
        Assertions.assertFalse(
                recorder.tags.get(0).containsKey(ObservationNames.KEY_UI_ID),
                "ui.id must not be a low-cardinality Timer tag");
        Assertions.assertFalse(
                recorder.tags.get(0)
                        .containsKey(ObservationNames.KEY_CLIENT_LOCATION),
                "vaadin.client.location must not be a low-cardinality Timer tag");

        Timer timer = registry.find(MeterNames.REQUEST_DURATION).timer();
        Assertions.assertNotNull(timer,
                "the observation should have produced the request timer");
        Assertions.assertEquals(Set.of(ObservationNames.KEY_REQUEST_TYPE,
                ObservationNames.KEY_INTERACTION,
                ObservationNames.KEY_HTTP_METHOD, ObservationNames.KEY_OUTCOME,
                // Added by DefaultMeterObservationHandler itself; the
                // exception class name, or "none".
                "error"),
                timer.getId().getTags().stream().map(Tag::getKey)
                        .collect(Collectors.toSet()),
                "vaadin.request.duration should carry only bounded tags");
    }

    @Test
    void noObservationWhenTracesDisabled() {
        ObservationRegistry obs = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        obs.observationConfig().observationHandler(recorder);

        RequestMetricsBinder binder = new RequestMetricsBinder(
                new SimpleMeterRegistry(), obs,
                ObservabilitySettings.builder().traces(false).build());

        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        VaadinResponse resp = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        binder.requestStart(req, resp);
        binder.requestEnd(req, resp, session);

        Assertions.assertTrue(recorder.names.isEmpty(),
                "no observation should fire when traces are disabled");
    }

    @Test
    void requestsDisabledEmitsNoObservationAndNoTimer() {
        ObservationRegistry obs = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        obs.observationConfig().observationHandler(recorder);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // The handler must write into the same registry the assertion below
        // reads, or the no-timer check would pass vacuously.
        obs.observationConfig().observationHandler(
                new DefaultMeterObservationHandler(registry));

        RequestMetricsBinder binder = new RequestMetricsBinder(registry, obs,
                ObservabilitySettings.builder().requests(false).traces(true)
                        .build());

        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        Mockito.when(req.getParameter("v-r")).thenReturn("uidl");
        VaadinResponse resp = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        binder.requestStart(req, resp);
        binder.requestEnd(req, resp, session);

        Assertions.assertTrue(recorder.names.isEmpty(),
                "requests=false must stop the request observation (the span), "
                        + "not only the Timer");
        Assertions.assertNull(
                registry.find(MeterNames.REQUEST_DURATION).timer(),
                "requests=false must stop request timing on the Observation "
                        + "path too, not only the direct path");
    }

    @Test
    void requestsDisabledStillEnrichesHttpObservationAndMarksErrors() {
        ObservationRegistry obs = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        obs.observationConfig().observationHandler(recorder);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<String> enriched = new ArrayList<>();
        List<Throwable> marked = new ArrayList<>();

        RequestMetricsBinder binder = new RequestMetricsBinder(registry, obs,
                ObservabilitySettings.builder().requests(false).traces(true)
                        .build(),
                new HttpObservationHooks() {
                    @Override
                    public void requestType(VaadinRequest request,
                            String type) {
                        enriched.add(type);
                    }

                    @Override
                    public void error(VaadinRequest request,
                            Throwable failure) {
                        marked.add(failure);
                    }
                }, new ErrorCounter(registry,
                        ObservabilitySettings.builder().build()));

        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        VaadinResponse resp = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        binder.requestStart(req, resp);
        binder.handleException(req, resp, session,
                new IllegalStateException("boom"));
        binder.requestEnd(req, resp, session);

        Assertions.assertEquals(List.of("other"), enriched,
                "the framework HTTP observation is Spring's own; enriching it "
                        + "follows traces, not requests");
        Assertions.assertEquals(1, marked.size(),
                "error marking corrects framework telemetry and is not gated "
                        + "on requests");
        Assertions.assertTrue(recorder.names.isEmpty(),
                "no request observation with requests=false");
    }

    @Test
    void handledErrorIsRelayedToHttpObservationMarker() {
        // A user-triggered failure Flow routes to the session error handler
        // never escapes request handling, so handleException never runs for
        // it; the relay in requestEnd is its only path to root-span error
        // monitoring. Exercised with requests=false, where it is also the
        // failure's only trace-side signal of any kind.
        ObservationRegistry obs = ObservationRegistry.create();
        obs.observationConfig().observationHandler(new RecordingHandler());
        List<Throwable> marked = new ArrayList<>();

        RequestMetricsBinder binder = new RequestMetricsBinder(
                new SimpleMeterRegistry(), obs, ObservabilitySettings.builder()
                        .requests(false).traces(true).build(),
                new HttpObservationHooks() {
                    @Override
                    public void error(VaadinRequest request,
                            Throwable failure) {
                        marked.add(failure);
                    }
                }, null);

        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        VaadinResponse resp = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        IllegalStateException failure = new IllegalStateException("boom");
        binder.requestStart(req, resp);
        // markHandled only records while a request is current; req stays
        // strongly referenced by this frame, so the CurrentInstance weak
        // reference cannot be collected mid-test.
        CurrentInstance.set(VaadinRequest.class, req);
        try {
            RequestError.markHandled(failure);
        } finally {
            CurrentInstance.clearAll();
        }
        binder.requestEnd(req, resp, session);

        Assertions.assertEquals(List.of(failure), marked,
                "a handled failure must reach the framework HTTP observation");
    }

    @Test
    void escapedExceptionIsMarkedOnceNotTwice() {
        // handleException marks the framework observation and sets the
        // interceptor-error flag; the relay in requestEnd must not mark the
        // same request again for a handled error arriving on top of it.
        ObservationRegistry obs = ObservationRegistry.create();
        obs.observationConfig().observationHandler(new RecordingHandler());
        List<Throwable> marked = new ArrayList<>();

        RequestMetricsBinder binder = new RequestMetricsBinder(
                new SimpleMeterRegistry(), obs,
                ObservabilitySettings.builder().build(),
                new HttpObservationHooks() {
                    @Override
                    public void error(VaadinRequest request,
                            Throwable failure) {
                        marked.add(failure);
                    }
                }, null);

        VaadinRequest req = Mockito.mock(VaadinRequest.class);
        VaadinResponse resp = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        IllegalStateException escaped = new IllegalStateException("boom");
        binder.requestStart(req, resp);
        binder.handleException(req, resp, session, escaped);
        CurrentInstance.set(VaadinRequest.class, req);
        try {
            RequestError.markHandled(new IllegalStateException("handled"));
        } finally {
            CurrentInstance.clearAll();
        }
        binder.requestEnd(req, resp, session);

        Assertions.assertEquals(List.of(escaped), marked,
                "one failed request marks the framework observation once");
    }
}
