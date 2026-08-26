/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.Map;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.communication.RpcInvocationEndedEvent;
import com.vaadin.flow.server.communication.RpcInvocationFailedEvent;
import com.vaadin.flow.server.communication.RpcInvocationStartedEvent;
import com.vaadin.observability.micrometer.trace.ObservationNames;

/**
 * Every binder can publish its Timer two ways: through the Observation API
 * (where {@code DefaultMeterObservationHandler} builds the Timer) or by
 * recording it directly. A metrics backend such as Prometheus rejects
 * same-named meters whose tag-key sets differ, so the two paths must agree —
 * and since the choice is fixed at binder construction, a difference between
 * them is invisible in any single application and only shows up as an
 * unqueryable dashboard elsewhere. These tests drive both paths over the same
 * input and require the resulting tags to be identical, keys and values alike.
 */
class MeterTagParityTest {

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        // beforeEnter marks the enclosing request as a navigation; drop the
        // marker so it cannot leak into another test on this thread.
        RequestInteraction.clear();
    }

    private static ObservationRegistry meterProducingObservations(
            SimpleMeterRegistry registry) {
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(
                new DefaultMeterObservationHandler(registry));
        return observations;
    }

    private static Map<String, String> tags(SimpleMeterRegistry registry,
            String meter) {
        Timer timer = registry.find(meter).timer();
        Assertions.assertNotNull(timer, meter + " should have been recorded");
        return timer.getId().getTags().stream()
                .collect(Collectors.toMap(Tag::getKey, Tag::getValue));
    }

    private static VaadinRequest uidlRequest() {
        VaadinRequest request = Mockito.mock(VaadinRequest.class);
        Mockito.when(request.getParameter("v-r")).thenReturn("uidl");
        Mockito.when(request.getMethod()).thenReturn("POST");
        return request;
    }

    /**
     * Runs one request through the binder and returns the tags of the Timer it
     * produced. {@code traces} picks the recording path.
     */
    private static Map<String, String> recordRequest(boolean traces,
            Exception failure) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RequestMetricsBinder binder = traces
                ? new RequestMetricsBinder(registry,
                        meterProducingObservations(registry),
                        ObservabilitySettings.builder().build())
                : new RequestMetricsBinder(registry,
                        ObservabilitySettings.builder().traces(false).build());

        VaadinRequest request = uidlRequest();
        VaadinResponse response = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        binder.requestStart(request, response);
        if (failure != null) {
            binder.handleException(request, response, session, failure);
        }
        binder.requestEnd(request, response, session);

        return tags(registry, MeterNames.REQUEST_DURATION);
    }

    @Test
    void requestDurationTagsMatchAcrossPaths() {
        Assertions.assertEquals(Map.of(ObservationNames.KEY_REQUEST_TYPE,
                ObservationNames.REQUEST_TYPE_UIDL,
                ObservationNames.KEY_HTTP_METHOD, "POST",
                ObservationNames.KEY_INTERACTION,
                ObservationNames.INTERACTION_RPC, ObservationNames.KEY_OUTCOME,
                MeterNames.OUTCOME_SUCCESS, MeterNames.TAG_ERROR,
                MeterNames.ERROR_NONE), recordRequest(true, null),
                "the Observation path defines the vaadin.request.duration tag set");
        Assertions.assertEquals(recordRequest(true, null),
                recordRequest(false, null),
                "recording the Timer directly must produce the same tags");
    }

    @Test
    void requestDurationErrorTagsMatchAcrossPaths() {
        Map<String, String> traced = recordRequest(true,
                new IllegalStateException("boom"));
        Map<String, String> direct = recordRequest(false,
                new IllegalStateException("boom"));

        Assertions.assertEquals(MeterNames.OUTCOME_ERROR,
                traced.get(ObservationNames.KEY_OUTCOME));
        Assertions.assertEquals("IllegalStateException",
                traced.get(MeterNames.TAG_ERROR),
                "DefaultMeterObservationHandler tags with the exception's simple name");
        Assertions.assertEquals(traced, direct,
                "the direct path must name the failing exception the same way");
    }

    /**
     * Runs one RPC invocation through the binder and returns the tags of the
     * Timer it produced. {@code traces} picks the recording path.
     */
    private static Map<String, String> recordRpc(boolean traces,
            Throwable failure) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RpcMetricsBinder binder = new RpcMetricsBinder(registry,
                traces ? meterProducingObservations(registry) : null,
                ObservabilitySettings.builder().traces(traces).build());

        RpcInvocationStartedEvent started = Mockito
                .mock(RpcInvocationStartedEvent.class);
        Mockito.when(started.getType()).thenReturn("event");
        Mockito.when(started.getNodeId()).thenReturn(-1);
        RpcInvocationEndedEvent ended = Mockito
                .mock(RpcInvocationEndedEvent.class);
        Mockito.when(ended.getType()).thenReturn("event");

        binder.invocationStarted(started);
        if (failure != null) {
            RpcInvocationFailedEvent failed = Mockito
                    .mock(RpcInvocationFailedEvent.class);
            Mockito.when(failed.getError()).thenReturn(failure);
            binder.invocationFailed(failed);
        }
        binder.invocationEnded(ended);

        return tags(registry, MeterNames.RPC_DURATION);
    }

    @Test
    void rpcDurationTagsMatchAcrossPaths() {
        Assertions.assertEquals(
                Map.of(MeterNames.TAG_TYPE, "event", MeterNames.TAG_OUTCOME,
                        MeterNames.OUTCOME_SUCCESS, MeterNames.TAG_ERROR,
                        MeterNames.ERROR_NONE),
                recordRpc(true, null),
                "the Observation path defines the vaadin.rpc.duration tag set");
        Assertions.assertEquals(recordRpc(true, null), recordRpc(false, null),
                "recording the Timer directly must produce the same tags");
    }

    @Test
    void rpcDurationErrorTagsMatchAcrossPaths() {
        Map<String, String> traced = recordRpc(true,
                new IllegalArgumentException("boom"));
        Map<String, String> direct = recordRpc(false,
                new IllegalArgumentException("boom"));

        Assertions.assertEquals(MeterNames.OUTCOME_ERROR,
                traced.get(MeterNames.TAG_OUTCOME));
        Assertions.assertEquals("IllegalArgumentException",
                traced.get(MeterNames.TAG_ERROR));
        Assertions.assertEquals(traced, direct,
                "the direct path must name the failing exception the same way");
    }

    private static final class ParityView extends Component {
    }

    /**
     * Runs one navigation through the binder and returns the tags of the Timer
     * it produced. {@code traces} picks the recording path.
     */
    private static Map<String, String> recordNavigation(boolean traces) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NavigationMetricsBinder binder = new NavigationMetricsBinder(registry,
                traces ? meterProducingObservations(registry) : null,
                ObservabilitySettings.builder().traces(traces).build(),
                new RouteTagResolver(10));

        UI ui = Mockito.mock(UI.class);
        UI.setCurrent(ui);
        BeforeEnterEvent enter = Mockito.mock(BeforeEnterEvent.class);
        Mockito.when(enter.getUI()).thenReturn(ui);
        // doReturn: the wildcard in Class<? extends Component> makes the
        // type-safe when(...).thenReturn(...) form uncompilable here.
        Mockito.doReturn(ParityView.class).when(enter).getNavigationTarget();

        binder.beforeEnter(enter);
        binder.afterNavigation(Mockito.mock(AfterNavigationEvent.class));

        return tags(registry, MeterNames.NAVIGATION);
    }

    @Test
    void navigationTagsMatchAcrossPaths() {
        Assertions.assertEquals(
                Map.of(MeterNames.TAG_ROUTE, "ParityView",
                        MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS,
                        MeterNames.TAG_ERROR, MeterNames.ERROR_NONE),
                recordNavigation(true),
                "the Observation path defines the vaadin.navigation tag set");
        Assertions.assertEquals(recordNavigation(true), recordNavigation(false),
                "recording the Timer directly must produce the same tags");
    }
}
