/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        return afterNavigationOn(ui);
    }

    private AfterNavigationEvent afterNavigationOn(UI targetUi) {
        return new AfterNavigationEvent(new LocationChangeEvent(router,
                targetUi, NavigationTrigger.UI_NAVIGATE, new Location("view"),
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

    @Test
    void reEntrantNavigationRecordsSupersededNavigationAsUnknown() {
        NavigationMetricsBinder binder = binder();

        // UI.navigate() from a view's beforeEnter or onAttach nests a second
        // navigation inside the first, which sets no redirect flag: the first
        // was superseded, not failed, so it must not be tagged as an error.
        binder.beforeEnter(beforeEnter(FirstView.class));
        binder.beforeEnter(beforeEnter(SecondView.class));
        binder.afterNavigation(afterNavigation());

        assertEquals(1, timerCount(FIRST, MeterNames.OUTCOME_UNKNOWN));
        assertEquals(0, timerCount(FIRST, MeterNames.OUTCOME_ERROR));
        assertEquals(1, timerCount(SECOND, MeterNames.OUTCOME_SUCCESS));
    }

    @Test
    void afterNavigationOnAnotherUiKeepsThisUisBackstop() {
        NavigationMetricsBinder binder = binder();
        UI other = new UI();

        // One request touching two UIs: an afterNavigation for a UI with
        // nothing pending must not drop the marker of the UI that does have a
        // navigation open, or requestEnd can no longer close it out.
        binder.beforeEnter(beforeEnter(FirstView.class));
        binder.afterNavigation(afterNavigationOn(other));
        requestEnd(binder);

        assertEquals(1, timerCount(FIRST, MeterNames.OUTCOME_ERROR));
    }

    @Test
    void requestEndClosesOutEveryUiItLeftOpen() {
        NavigationMetricsBinder binder = binder();
        UI other = new UI();

        // Access tasks queued for several UIs of a session run on the request
        // thread, so two UIs can have a navigation open at the same time. Both
        // have to be closed out, not just the one marked last.
        binder.beforeEnter(beforeEnter(FirstView.class));
        binder.beforeEnter(beforeEnter(SecondView.class, other));
        requestEnd(binder);

        assertEquals(1, timerCount(FIRST, MeterNames.OUTCOME_ERROR));
        assertEquals(1, timerCount(SECOND, MeterNames.OUTCOME_ERROR));
    }

    @Test
    void detachClosesOutANavigationLeftOpenOffRequest() {
        NavigationMetricsBinder binder = binder();

        // A navigation started from UI.access() never reaches requestEnd, so
        // detach is what keeps the entry from outliving the UI. Nothing about
        // a detached UI says the navigation failed, hence unknown.
        binder.beforeEnter(beforeEnter(FirstView.class));
        binder.uiDetached(ui);

        assertEquals(1, timerCount(FIRST, MeterNames.OUTCOME_UNKNOWN));
        assertEquals(0, timerCount(FIRST, MeterNames.OUTCOME_ERROR));
    }

    @Test
    void detachRecordsANavigationOnlyOnce() {
        NavigationMetricsBinder binder = binder();

        binder.beforeEnter(beforeEnter(FirstView.class));
        binder.afterNavigation(afterNavigation());
        binder.uiDetached(ui);

        assertEquals(1, timerCount(FIRST, MeterNames.OUTCOME_SUCCESS));
        assertEquals(0, timerCount(FIRST, MeterNames.OUTCOME_UNKNOWN));
    }

    @Test
    void requestEndClosesNavigationScopeInsideTheEnclosingRequestScope() {
        ObservationRegistry obs = ObservationRegistry.create();
        obs.observationConfig().observationHandler(new RecordingHandler());
        NavigationMetricsBinder binder = tracingBinder(obs);

        // The request scope RequestMetricsBinder opens at requestStart. Vaadin
        // reverses the interceptor list, so the navigation binder — registered
        // last — is the one that runs first at requestEnd, while this scope is
        // still open.
        Observation request = Observation.start(ObservationNames.REQUEST, obs);
        Observation.Scope requestScope = request.openScope();

        binder.beforeEnter(beforeEnter(FirstView.class));
        requestEnd(binder);

        // Closing the navigation scope has to restore the enclosing request
        // observation. Were the two closed in the opposite order, the stopped
        // request observation would be put back as current here and every
        // later request on this pooled thread would be parented under it.
        assertSame(request, obs.getCurrentObservation());

        requestScope.close();
        request.stop();
        assertNull(obs.getCurrentObservation());
    }

    @Test
    void aNavigationAbandonedOffRequestDoesNotPinItsUiToThatThread()
            throws Exception {
        NavigationMetricsBinder binder = binder();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        // The UI is reached through a holder so that the task handed to the
        // executor captures no reference of its own.
        UI[] offRequest = { new UI() };
        try {
            // A navigation started from UI.access() on a background thread
            // marks that thread, and no requestEnd ever runs there to drain
            // the marker.
            executor.submit(() -> binder
                    .beforeEnter(beforeEnter(FirstView.class, offRequest[0])))
                    .get(10, TimeUnit.SECONDS);
            // Detach runs on whichever thread drops the UI, so it closes the
            // navigation out but cannot reach the executor thread's marker.
            binder.uiDetached(offRequest[0]);

            WeakReference<UI> ref = new WeakReference<>(offRequest[0]);
            offRequest[0] = null;

            assertTrue(collected(ref),
                    "a marker left on a pooled thread must not keep the UI alive");
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Whether {@code ref} has been cleared, giving the collector a bounded
     * number of chances to get to it.
     */
    private static boolean collected(WeakReference<?> ref)
            throws InterruptedException {
        for (int i = 0; i < 50 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        return ref.get() == null;
    }
}
