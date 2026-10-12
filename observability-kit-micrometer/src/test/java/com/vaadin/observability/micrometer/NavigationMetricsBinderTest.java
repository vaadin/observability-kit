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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationListener;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterListener;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.NavigationEndedEvent;
import com.vaadin.flow.router.NavigationStartedEvent;
import com.vaadin.flow.router.NavigationTrigger;
import com.vaadin.flow.router.NotFoundException;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterState;
import com.vaadin.flow.router.internal.NavigationRouteTarget;
import com.vaadin.flow.router.internal.RouteTarget;
import com.vaadin.flow.server.RouteRegistry;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServiceEventBus;
import com.vaadin.flow.shared.Registration;
import com.vaadin.observability.micrometer.trace.ObservationNames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Covers how {@link NavigationMetricsBinder} times navigations from Flow's
 * navigation events and maps their outcome.
 */
class NavigationMetricsBinderTest {

    @Tag("first-view")
    private static class FirstView extends Component {
    }

    @Tag("second-view")
    private static class SecondView extends Component {
    }

    @Tag("not-found-view")
    private static class NotFoundView extends Component {
    }

    private static final String FIRST = FirstView.class.getSimpleName();
    private static final String SECOND = SecondView.class.getSimpleName();
    private static final String NOT_FOUND = NotFoundView.class.getSimpleName();

    private static final class RecordingHandler
            implements ObservationHandler<Observation.Context> {

        final List<String> names = new ArrayList<>();
        final List<String> contextualNames = new ArrayList<>();
        final List<Map<String, String>> tags = new ArrayList<>();

        @Override
        public void onStop(Observation.Context ctx) {
            names.add(ctx.getName());
            contextualNames.add(ctx.getContextualName());
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
    private VaadinServiceEventBus eventBus;
    private UI ui;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        eventBus = new VaadinServiceEventBus(Mockito.mock(VaadinService.class));
        ui = routedUi();
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        RequestInteraction.clear();
        RequestUi.clear();
        // Micrometer keeps the current scope in a static thread-local shared by
        // every registry; a scope a failing test leaves open would otherwise
        // become the parent of the next test's observations.
        ObservationRegistry.create().setCurrentObservationScope(null);
    }

    private NavigationMetricsBinder binder() {
        NavigationMetricsBinder binder = new NavigationMetricsBinder(registry,
                new RouteTagResolver(100));
        binder.register(eventBus);
        return binder;
    }

    private NavigationMetricsBinder tracingBinder(ObservationRegistry obs) {
        NavigationMetricsBinder binder = new NavigationMetricsBinder(registry,
                obs, ObservabilitySettings.builder().traces(true).build(),
                new RouteTagResolver(100));
        binder.register(eventBus);
        return binder;
    }

    private static ObservationRegistry recording(RecordingHandler recorder) {
        ObservationRegistry obs = ObservationRegistry.create();
        obs.observationConfig().observationHandler(recorder);
        return obs;
    }

    /**
     * A UI whose router resolves {@code first} and {@code second} to their
     * views, {@code first/<id>} to the first view with an {@code id} route
     * parameter, and nothing else, and which shows {@code shown} (may be
     * empty).
     */
    private static UI routedUi(HasElement... shown) {
        UI routed = Mockito.mock(UI.class, Mockito.RETURNS_DEEP_STUBS);
        RouteRegistry routes = Mockito.mock(RouteRegistry.class);
        Mockito.when(routes.getNavigationRouteTarget(Mockito.anyString()))
                .thenAnswer(invocation -> {
                    String path = invocation.getArgument(0);
                    if (path.equals("first")) {
                        return routeTarget(path, FirstView.class, Map.of());
                    }
                    if (path.equals("second")) {
                        return routeTarget(path, SecondView.class, Map.of());
                    }
                    if (path.startsWith("first/")) {
                        return routeTarget(path, FirstView.class,
                                Map.of("id", path.substring(6)));
                    }
                    return new NavigationRouteTarget(path, null, Map.of());
                });
        Mockito.when(routed.getInternals().getRouter().getRegistry())
                .thenReturn(routes);
        Mockito.when(routed.getInternals().getActiveRouterTargetsChain())
                .thenReturn(List.of(shown));
        Mockito.when(routed.getInternals().getRouterStateSignal().peek())
                .thenReturn(null);
        return routed;
    }

    private static NavigationRouteTarget routeTarget(String path,
            Class<? extends Component> view, Map<String, String> parameters) {
        return new NavigationRouteTarget(path, new RouteTarget(view),
                parameters);
    }

    /** Makes {@code target} report it shows {@code view} with an id. */
    private static void showing(UI target, Class<? extends Component> view,
            String id) {
        Mockito.when(target.getInternals().getRouterStateSignal().peek())
                .thenReturn(new RouterState(new Location("first/" + id),
                        new RouteParameters("id", id), List.of(), view));
    }

    /**
     * Fires the {@code BeforeEnterEvent} that Flow sends to the UI's listeners
     * before it builds {@code view}.
     */
    private static void entering(UI target, Class<? extends Component> view) {
        ArgumentCaptor<BeforeEnterListener> listener = ArgumentCaptor
                .forClass(BeforeEnterListener.class);
        verify(target, Mockito.atLeastOnce())
                .addBeforeEnterListener(listener.capture());
        BeforeEnterEvent event = Mockito.mock(BeforeEnterEvent.class);
        Mockito.doReturn(view).when(event).getNavigationTarget();
        listener.getValue().beforeEnter(event);
    }

    private void start(UI target, String path) {
        eventBus.fireEvent(new NavigationStartedEvent(target,
                new Location(path), NavigationTrigger.UI_NAVIGATE));
    }

    /** Ends a navigation the way Flow does: listeners in reverse order. */
    private void end(UI target, String path,
            NavigationEndedEvent.Outcome outcome) {
        eventBus.fireEventInReverseOrder(new NavigationEndedEvent(target,
                new Location(path), NavigationTrigger.UI_NAVIGATE, outcome,
                outcome instanceof NavigationEndedEvent.Failed ? 500 : 200));
    }

    private void navigate(UI target, String path,
            NavigationEndedEvent.Outcome outcome) {
        start(target, path);
        end(target, path, outcome);
    }

    private static NavigationEndedEvent.Completed shown(
            Class<? extends Component> view) {
        return new NavigationEndedEvent.Completed(view);
    }

    private double timerCount(String route, String outcome) {
        Timer timer = registry.find(MeterNames.NAVIGATION)
                .tags(MeterNames.TAG_ROUTE, route, MeterNames.TAG_OUTCOME,
                        outcome)
                .timer();
        return timer == null ? 0 : timer.count();
    }

    private int timerSamples() {
        return registry.find(MeterNames.NAVIGATION).timers().stream()
                .mapToInt(timer -> (int) timer.count()).sum();
    }

    @Test
    void showingTheRequestedViewRecordsSuccess() {
        binder();

        navigate(ui, "first", shown(FirstView.class));

        assertEquals(1, timerCount(FIRST, MeterNames.OUTCOME_SUCCESS));
    }

    @Test
    void showingAnotherViewRecordsTheRequestedRouteAsRerouted() {
        binder();

        // A rerouteTo or forwardTo runs inside the navigation, so Flow ends
        // it once, with the view that was shown in the end.
        navigate(ui, "first", shown(SecondView.class));

        assertEquals(1, timerCount(FIRST, MeterNames.OUTCOME_REROUTED));
        assertEquals(1, timerSamples());
    }

    @ParameterizedTest(name = "shown id={0}")
    @CsvSource({ "1, success", "2, rerouted" })
    void showingTheRequestedViewWithOtherParametersRecordsRerouted(
            String shownId, String outcome) {
        binder();

        // rerouteTo(FirstView.class, otherParameters): the same view, but not
        // the one that was asked for.
        start(ui, "first/1");
        showing(ui, FirstView.class, shownId);
        end(ui, "first/1", shown(FirstView.class));

        assertEquals(1, timerCount(FIRST, outcome));
        assertEquals(1, timerSamples());
    }

    @Test
    void showingNoViewRecordsForwarded() {
        binder();

        // forwardToUrl or a hand-off to a client-side route.
        navigate(ui, "first", new NavigationEndedEvent.NotShown());

        assertEquals(1, timerCount(FIRST, MeterNames.OUTCOME_FORWARDED));
    }

    @Test
    void failedNavigationRecordsError() {
        binder();

        navigate(ui, "first", new NavigationEndedEvent.Failed(
                new IllegalStateException("view constructor failed")));

        assertEquals(1, timerCount(FIRST, MeterNames.OUTCOME_ERROR));
        assertEquals(1, timerSamples());
    }

    @Test
    void postponedNavigationRecordsUnknown() {
        binder();

        navigate(ui, "first", new NavigationEndedEvent.Postponed());

        assertEquals(1, timerCount(FIRST, MeterNames.OUTCOME_UNKNOWN));
    }

    @Test
    void aLocationWithoutViewIsRecordedUnderTheViewShownInTheEnd() {
        binder();
        UI showingNotFound = routedUi(new NotFoundView());

        navigate(showingNotFound, "no/such/view",
                new NavigationEndedEvent.Failed(
                        new NotFoundException("no route")));

        assertEquals(1, timerCount(NOT_FOUND, MeterNames.OUTCOME_ERROR));
    }

    @Test
    void aLocationWithoutViewIsRecordedAsCompletedUnderTheViewShown() {
        binder();

        // The redirect that adds or removes a trailing slash: the requested
        // location resolves to no view, the one shown is the right one.
        navigate(ui, "second/", shown(SecondView.class));

        assertEquals(1, timerCount(SECOND, MeterNames.OUTCOME_SUCCESS));
    }

    @Test
    void aLocationWithoutViewThatShowedNothingNewIsAnUnknownRoute() {
        binder();
        // Still showing the view of an earlier navigation, which says nothing
        // about this one.
        UI showingSecond = routedUi(new SecondView());

        navigate(showingSecond, "no/such/view",
                new NavigationEndedEvent.NotShown());
        // Thrown on instead of showing an error view: no status code.
        start(showingSecond, "no/such/view");
        eventBus.fireEventInReverseOrder(
                new NavigationEndedEvent(showingSecond,
                        new Location("no/such/view"),
                        NavigationTrigger.UI_NAVIGATE,
                        new NavigationEndedEvent.Failed(
                                new IllegalStateException("no error view")),
                        -1));

        assertEquals(1, timerCount(MeterNames.ROUTE_UNKNOWN,
                MeterNames.OUTCOME_FORWARDED));
        assertEquals(1,
                timerCount(MeterNames.ROUTE_UNKNOWN, MeterNames.OUTCOME_ERROR));
        assertEquals(2, timerSamples());
    }

    @Test
    void anEndWithoutAStartRecordsNothing() {
        binder();

        // Started before the binder was subscribed.
        end(ui, "first", shown(FirstView.class));

        assertNull(registry.find(MeterNames.NAVIGATION).timer());
    }

    @Test
    void navigationsAreRecordedPerRoute() {
        binder();

        navigate(ui, "first", shown(FirstView.class));
        navigate(ui, "first", shown(FirstView.class));
        navigate(ui, "second", shown(SecondView.class));

        assertEquals(2, timerCount(FIRST, MeterNames.OUTCOME_SUCCESS));
        assertEquals(1, timerCount(SECOND, MeterNames.OUTCOME_SUCCESS));
    }

    @Test
    void removingTheRegistrationStopsRecording() {
        Registration registration = new NavigationMetricsBinder(registry,
                new RouteTagResolver(100)).register(eventBus);
        registration.remove();

        navigate(ui, "first", shown(FirstView.class));

        assertNull(registry.find(MeterNames.NAVIGATION).timer());
    }

    @Test
    void nestedNavigationsOfTwoUisAreRecordedSeparately() {
        ObservationRegistry obs = recording(new RecordingHandler());
        obs.observationConfig().observationHandler(
                new DefaultMeterObservationHandler(registry));
        tracingBinder(obs);
        UI other = routedUi();

        // One UI navigating another, e.g. from an AfterNavigationEvent
        // listener: Flow ends the inner navigation first.
        start(ui, "first");
        start(other, "second");
        end(other, "second", new NavigationEndedEvent.Failed(
                new IllegalStateException("boom")));
        end(ui, "first", shown(FirstView.class));

        assertEquals(1, timerCount(SECOND, MeterNames.OUTCOME_ERROR));
        assertEquals(1, timerCount(FIRST, MeterNames.OUTCOME_SUCCESS));
        assertNull(obs.getCurrentObservation(),
                "both scopes are closed, so nothing dangles on the thread");
    }

    @Test
    void observationIsTaggedWithRouteAndOutcomeAndItsScopeIsClosed() {
        RecordingHandler recorder = new RecordingHandler();
        ObservationRegistry obs = recording(recorder);
        tracingBinder(obs);

        start(ui, "first");
        assertNotNull(obs.getCurrentObservation(),
                "the navigation scope is open while the navigation runs");
        end(ui, "first", shown(SecondView.class));

        assertEquals(List.of(MeterNames.NAVIGATION), recorder.names);
        // The meter name alone would leave every navigation span looking
        // alike in a trace view.
        assertEquals(ObservationNames.NAVIGATION + " " + FIRST,
                recorder.contextualNames.get(0));
        assertEquals(
                Map.of(ObservationNames.KEY_ROUTE, FIRST,
                        ObservationNames.KEY_OUTCOME,
                        ObservationNames.OUTCOME_REROUTED),
                recorder.tags.get(0));
        assertNull(obs.getCurrentObservation());
    }

    @Test
    void observationOfALocationWithoutViewIsNamedAfterTheViewShown() {
        RecordingHandler recorder = new RecordingHandler();
        tracingBinder(recording(recorder));
        UI showingNotFound = routedUi(new NotFoundView());

        navigate(showingNotFound, "no/such/view",
                new NavigationEndedEvent.Failed(
                        new NotFoundException("no route")));

        assertEquals(ObservationNames.NAVIGATION + " " + NOT_FOUND,
                recorder.contextualNames.get(0));
        assertEquals(NOT_FOUND,
                recorder.tags.get(0).get(ObservationNames.KEY_ROUTE));
    }

    @Test
    void navigationScopeClosesInsideTheEnclosingRequestScope() {
        ObservationRegistry obs = recording(new RecordingHandler());
        tracingBinder(obs);

        // The request scope RequestMetricsBinder opens at requestStart.
        Observation request = Observation.start(ObservationNames.REQUEST, obs);
        Observation.Scope requestScope = request.openScope();

        navigate(ui, "first", new NavigationEndedEvent.Failed(
                new IllegalStateException("boom")));

        // Closing the navigation scope has to restore the enclosing request
        // observation, also when the navigation failed.
        assertSame(request, obs.getCurrentObservation());

        requestScope.close();
        request.stop();
        assertNull(obs.getCurrentObservation());
    }

    @Test
    void aNavigationOnABackgroundThreadDoesNotPinItsUiToThatThread()
            throws Exception {
        binder();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        // The UI is reached through a holder so that the task handed to the
        // executor captures no reference of its own.
        UI[] offRequest = { new UI() };
        try {
            // A navigation from UI.access() on a background thread: Flow
            // fires both events there, and nothing else ever runs on that
            // thread to clean up after it.
            executor.submit(() -> {
                start(offRequest[0], "first");
                end(offRequest[0], "first", shown(FirstView.class));
                RequestUi.clear();
            }).get(10, TimeUnit.SECONDS);

            WeakReference<UI> ref = new WeakReference<>(offRequest[0]);
            offRequest[0] = null;

            assertEquals(1, timerCount(FIRST, MeterNames.OUTCOME_SUCCESS));
            assertTrue(collected(ref),
                    "a pooled thread must not keep the UI alive");
        } finally {
            executor.shutdownNow();
        }
    }

    // -----------------------------------------------------------------
    // Path selection and what each path publishes
    // -----------------------------------------------------------------

    /**
     * The binder only observes when tracing is on <em>and</em> an
     * {@link ObservationRegistry} was supplied; every other combination falls
     * back to recording the Timer directly and must not start an observation.
     */
    @ParameterizedTest(name = "traces={0}, observationRegistry present={1}")
    @CsvSource({ "false, false", "false, true", "true, false" })
    void directTimerPathRecordsTimerAndSkipsObservation(boolean traces,
            boolean withObservationRegistry) {
        RecordingHandler recorder = new RecordingHandler();
        ObservationRegistry obs = withObservationRegistry ? recording(recorder)
                : null;
        new NavigationMetricsBinder(registry, obs,
                ObservabilitySettings.builder().traces(traces).build(),
                new RouteTagResolver(100)).register(eventBus);

        navigate(ui, "first", shown(FirstView.class));

        assertEquals(1, timerCount(FIRST, MeterNames.OUTCOME_SUCCESS));
        assertTrue(recorder.names.isEmpty(),
                "no observation may be started outside the observation path");
    }

    @Test
    void theShortConstructorRecordsDirectlyDespiteTracesDefaultingOn() {
        // Tracing is on by default, so the absent ObservationRegistry is the
        // only thing keeping this binder off the observation path.
        binder();

        navigate(ui, "first", shown(FirstView.class));

        assertEquals(1, timerCount(FIRST, MeterNames.OUTCOME_SUCCESS));
        assertNull(RequestInteraction.take(),
                "only the observation path has an enclosing request span to "
                        + "label, so the direct path must mark nothing");
    }

    @Test
    void startMarksTheRequestInteractionAsNavigation() {
        tracingBinder(recording(new RecordingHandler()));

        start(ui, "first");

        assertEquals(ObservationNames.INTERACTION_NAVIGATION,
                RequestInteraction.take(),
                "the enclosing request span has to learn that this UIDL "
                        + "request was a navigation");
        assertSame(ui, RequestUi.take(),
                "request end resolves the route from the navigated UI");

        end(ui, "first", shown(FirstView.class));
    }

    @Test
    void telemetryContextFollowsTheViewBeingEnteredAndShown() {
        binder();
        UI.setCurrent(ui);
        Registration tracking = Mockito.mock(Registration.class);
        Mockito.when(ui.addBeforeEnterListener(Mockito.any()))
                .thenReturn(tracking);

        // Set before each view is built, so instrumentation outside the Flow
        // runtime can attribute construction-time work to it: the requested
        // view, then the error view a failure or an unknown location sends
        // the navigation to.
        start(ui, "first");
        entering(ui, FirstView.class);
        assertEquals(FIRST, VaadinTelemetryContext.currentRoute());
        entering(ui, NotFoundView.class);
        assertEquals(NOT_FOUND, VaadinTelemetryContext.currentRoute());

        // And to the view shown for the work after the navigation.
        end(ui, "first", shown(SecondView.class));
        assertEquals(SECOND, VaadinTelemetryContext.currentRoute());
        verify(tracking).remove();
    }

    @Test
    void telemetryContextFollowsAPostponedNavigationWhenItIsResumed() {
        binder();
        UI.setCurrent(ui);
        Registration tracking = Mockito.mock(Registration.class);
        Mockito.when(ui.addBeforeEnterListener(Mockito.any()))
                .thenReturn(tracking);

        navigate(ui, "second", new NavigationEndedEvent.Postponed());
        verify(tracking, never()).remove();

        // proceed() resumes the navigation without navigation events, but
        // with the UI's own navigation listeners.
        entering(ui, SecondView.class);
        assertEquals(SECOND, VaadinTelemetryContext.currentRoute());
        ArgumentCaptor<AfterNavigationListener> resumed = ArgumentCaptor
                .forClass(AfterNavigationListener.class);
        verify(ui).addAfterNavigationListener(resumed.capture());
        Mockito.when(ui.getInternals().getActiveRouterTargetsChain())
                .thenReturn(List.of(new SecondView()));
        resumed.getValue()
                .afterNavigation(Mockito.mock(AfterNavigationEvent.class));

        assertEquals(SECOND, VaadinTelemetryContext.currentRoute());
        verify(tracking).remove();
    }

    @Test
    void aNewNavigationSupersedesAPostponedOne() {
        binder();
        Registration tracking = Mockito.mock(Registration.class);
        Mockito.when(ui.addBeforeEnterListener(Mockito.any()))
                .thenReturn(tracking);

        navigate(ui, "second", new NavigationEndedEvent.Postponed());
        start(ui, "first");

        // The listener of the postponed navigation is gone, so the UI tracks
        // one navigation at a time.
        verify(tracking).remove();
        end(ui, "first", shown(FirstView.class));
    }

    @Test
    void aFailureWhileEndingStillClosesTheScope() {
        RecordingHandler recorder = new RecordingHandler();
        ObservationRegistry obs = recording(recorder);
        tracingBinder(obs);
        Registration failing = Mockito.mock(Registration.class);
        Mockito.doThrow(new IllegalStateException("boom")).when(failing)
                .remove();
        Mockito.when(ui.addBeforeEnterListener(Mockito.any()))
                .thenReturn(failing);

        navigate(ui, "first", new NavigationEndedEvent.NotShown());

        assertNull(obs.getCurrentObservation(),
                "the scope must not stay open on the thread");
        assertEquals(List.of(MeterNames.NAVIGATION), recorder.names,
                "the observation is stopped");
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
