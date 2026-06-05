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
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
        final AtomicBoolean errored = new AtomicBoolean();

        @Override
        public void onStop(Observation.Context ctx) {
            names.add(ctx.getName());
            contextualNames.add(ctx.getContextualName());
            Map<String, String> snap = new HashMap<>();
            for (KeyValue kv : ctx.getLowCardinalityKeyValues()) {
                snap.put(kv.getKey(), kv.getValue());
            }
            tags.add(snap);
            if (ctx.getError() != null) {
                errored.set(true);
            }
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
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
}
