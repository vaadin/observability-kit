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
import java.util.Optional;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.LocationChangeEvent;
import com.vaadin.flow.router.NavigationHandler;
import com.vaadin.flow.router.NavigationState;
import com.vaadin.flow.router.NavigationTrigger;
import com.vaadin.flow.router.NotFoundException;
import com.vaadin.flow.router.Router;
import com.vaadin.flow.router.internal.ErrorTargetEntry;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.observability.micrometer.trace.ObservationNames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers how {@link NavigationMetricsBinder} closes out navigations, in
 * particular the ones that never reach {@code afterNavigation} because they
 * were rerouted away or aborted.
 */
class NavigationMetricsBinderTest {

    @Tag("first-view")
    private static class FirstView extends Component {
    }

    @Tag("second-view")
    private static class SecondView extends Component {
    }

    private static final String FIRST = FirstView.class.getSimpleName();
    private static final String SECOND = SecondView.class.getSimpleName();

    private static final class RecordingHandler
            implements ObservationHandler<Observation.Context> {

        final List<String> names = new ArrayList<>();
        final List<Map<String, String>> tags = new ArrayList<>();

        @Override
        public void onStop(Observation.Context ctx) {
            names.add(ctx.getName());
            Map<String, String> snap = new HashMap<>();
            for (KeyValue kv : ctx.getLowCardinalityKeyValues()) {
                snap.put(kv.getKey(), kv.getValue());
            }
            tags.add(snap);
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }

    private SimpleMeterRegistry registry;
    private Router router;
    private UI ui;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        router = Mockito.mock(Router.class);
        ui = new UI();
    }

    private NavigationMetricsBinder binder() {
        return new NavigationMetricsBinder(registry, new RouteTagResolver(100));
    }

    private NavigationMetricsBinder tracingBinder(ObservationRegistry obs) {
        return new NavigationMetricsBinder(registry, obs,
                ObservabilitySettings.builder().traces(true).build(),
                new RouteTagResolver(100));
    }

    private BeforeEnterEvent beforeEnter(Class<? extends Component> target) {
        return beforeEnter(target, ui);
    }

    private BeforeEnterEvent beforeEnter(Class<? extends Component> target,
            UI targetUi) {
        return new BeforeEnterEvent(router, NavigationTrigger.UI_NAVIGATE,
                new Location("view"), target, targetUi, List.of());
    }

    /**
     * Marks the event as rerouted the way Flow does once a listener called
     * {@code rerouteTo}, without needing a live route registry.
     */
    private void rerouteAway(BeforeEnterEvent event) {
        event.rerouteTo(Mockito.mock(NavigationHandler.class),
                Mockito.mock(NavigationState.class));
    }

    private void forwardAway(BeforeEnterEvent event) {
        event.forwardTo(Mockito.mock(NavigationHandler.class),
                Mockito.mock(NavigationState.class));
    }

    private AfterNavigationEvent afterNavigation() {
        return new AfterNavigationEvent(new LocationChangeEvent(router, ui,
                NavigationTrigger.UI_NAVIGATE, new Location("view"),
                List.of()));
    }

    private double timerCount(String route, String outcome) {
        Timer timer = registry.find(MeterNames.NAVIGATION)
                .tags(MeterNames.TAG_ROUTE, route, MeterNames.TAG_OUTCOME,
                        outcome)
                .timer();
        return timer == null ? 0 : timer.count();
    }

    private void requestEnd(NavigationMetricsBinder binder) {
        binder.requestEnd(Mockito.mock(VaadinRequest.class),
                Mockito.mock(VaadinResponse.class),
                Mockito.mock(VaadinSession.class));
    }

    @Test
    void completedNavigationRecordsSuccess() {
        NavigationMetricsBinder binder = binder();

        binder.beforeEnter(beforeEnter(FirstView.class));
        binder.afterNavigation(afterNavigation());

        assertEquals(1, timerCount(FIRST, MeterNames.OUTCOME_SUCCESS));
    }

    @Test
    void rerouteRecordsSupersededNavigationAsRerouted() {
        NavigationMetricsBinder binder = binder();

        // rerouteTo re-runs the chain: beforeEnter fires twice and only the
        // second navigation reaches afterNavigation.
        BeforeEnterEvent first = beforeEnter(FirstView.class);
        binder.beforeEnter(first);
        rerouteAway(first);
        binder.beforeEnter(beforeEnter(SecondView.class));
        binder.afterNavigation(afterNavigation());

        assertEquals(1, timerCount(FIRST, MeterNames.OUTCOME_REROUTED));
        assertEquals(1, timerCount(SECOND, MeterNames.OUTCOME_SUCCESS));
        assertEquals(0, timerCount(FIRST, MeterNames.OUTCOME_ERROR));
        assertEquals(0, timerCount(FIRST, MeterNames.OUTCOME_SUCCESS));
    }

    @Test
    void forwardRecordsSupersededNavigationAsForwarded() {
        NavigationMetricsBinder binder = binder();

        BeforeEnterEvent first = beforeEnter(FirstView.class);
        binder.beforeEnter(first);
        forwardAway(first);
        binder.beforeEnter(beforeEnter(SecondView.class));
        binder.afterNavigation(afterNavigation());

        assertEquals(1, timerCount(FIRST, MeterNames.OUTCOME_FORWARDED));
        assertEquals(1, timerCount(SECOND, MeterNames.OUTCOME_SUCCESS));
        assertEquals(0, timerCount(FIRST, MeterNames.OUTCOME_ERROR));
    }

    @Test
    void externalForwardIsRecordedAsForwardedAtRequestEnd() {
        NavigationMetricsBinder binder = binder();

        // forwardToUrl redirects the browser instead of re-running the chain,
        // so nothing supersedes this navigation within the request.
        BeforeEnterEvent event = beforeEnter(FirstView.class);
        binder.beforeEnter(event);
        event.forwardToUrl("https://example.com/elsewhere");
        requestEnd(binder);

        assertEquals(1, timerCount(FIRST, MeterNames.OUTCOME_FORWARDED));
    }

    @Test
    void rerouteToErrorIsRecordedAsError() {
        NavigationMetricsBinder binder = binder();
        // rerouteToError resolves the error view through the router, so this
        // event needs a UI whose internals hand one back.
        UI mockUi = Mockito.mock(UI.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(mockUi.getInternals().getRouter()).thenReturn(router);
        Mockito.when(router.getErrorNavigationTarget(Mockito.any()))
                .thenReturn(Optional.of(new ErrorTargetEntry(SecondView.class,
                        NotFoundException.class)));
        BeforeEnterEvent event = beforeEnter(FirstView.class, mockUi);

        binder.beforeEnter(event);
        event.rerouteToError(NotFoundException.class);
        binder.beforeEnter(beforeEnter(SecondView.class, mockUi));

        // The error view is a reroute target, but the navigation genuinely
        // failed, so it must not be filed under the routing outcomes.
        assertEquals(1, timerCount(FIRST, MeterNames.OUTCOME_ERROR));
        assertEquals(0, timerCount(FIRST, MeterNames.OUTCOME_REROUTED));
    }

    @Test
    void abandonedNavigationIsRecordedAsErrorAtRequestEnd() {
        NavigationMetricsBinder binder = binder();

        // No afterNavigation: the view blew up while being instantiated.
        binder.beforeEnter(beforeEnter(FirstView.class));
        requestEnd(binder);

        assertEquals(1, timerCount(FIRST, MeterNames.OUTCOME_ERROR));
    }

    @Test
    void requestEndWithoutNavigationRecordsNothing() {
        NavigationMetricsBinder binder = binder();

        requestEnd(binder);

        assertNull(registry.find(MeterNames.NAVIGATION).timer());
    }

    @Test
    void completedNavigationIsNotRecordedTwiceAtRequestEnd() {
        NavigationMetricsBinder binder = binder();

        binder.beforeEnter(beforeEnter(FirstView.class));
        binder.afterNavigation(afterNavigation());
        requestEnd(binder);

        assertEquals(1, timerCount(FIRST, MeterNames.OUTCOME_SUCCESS));
        assertEquals(0, timerCount(FIRST, MeterNames.OUTCOME_ERROR));
    }

    @Test
    void rerouteStopsSupersededObservationAndClosesItsScope() {
        ObservationRegistry obs = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        obs.observationConfig().observationHandler(recorder);
        NavigationMetricsBinder binder = tracingBinder(obs);

        BeforeEnterEvent first = beforeEnter(FirstView.class);
        binder.beforeEnter(first);
        rerouteAway(first);
        binder.beforeEnter(beforeEnter(SecondView.class));
        binder.afterNavigation(afterNavigation());

        assertEquals(List.of(MeterNames.NAVIGATION, MeterNames.NAVIGATION),
                recorder.names);
        assertEquals(FIRST,
                recorder.tags.get(0).get(ObservationNames.KEY_ROUTE));
        assertEquals(ObservationNames.OUTCOME_REROUTED,
                recorder.tags.get(0).get(ObservationNames.KEY_OUTCOME));
        assertEquals(SECOND,
                recorder.tags.get(1).get(ObservationNames.KEY_ROUTE));
        assertEquals(ObservationNames.OUTCOME_SUCCESS,
                recorder.tags.get(1).get(ObservationNames.KEY_OUTCOME));
        // Both scopes were closed, so nothing dangles on the request thread.
        assertNull(obs.getCurrentObservation());
    }

    @Test
    void abandonedObservationIsStoppedAndUnscopedAtRequestEnd() {
        ObservationRegistry obs = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        obs.observationConfig().observationHandler(recorder);
        NavigationMetricsBinder binder = tracingBinder(obs);

        binder.beforeEnter(beforeEnter(FirstView.class));
        requestEnd(binder);

        assertEquals(1, recorder.names.size());
        assertEquals(ObservationNames.OUTCOME_ERROR,
                recorder.tags.get(0).get(ObservationNames.KEY_OUTCOME));
        assertNull(obs.getCurrentObservation());
    }

    @Test
    void requestStartDropsStaleMarkerFromPreviousRequest() {
        NavigationMetricsBinder binder = binder();

        binder.beforeEnter(beforeEnter(FirstView.class));
        // A new request on the same (pooled) thread must not close out a
        // navigation left behind by the previous one.
        binder.requestStart(Mockito.mock(VaadinRequest.class),
                Mockito.mock(VaadinResponse.class));
        requestEnd(binder);

        assertNull(registry.find(MeterNames.NAVIGATION).timer());
    }
}
