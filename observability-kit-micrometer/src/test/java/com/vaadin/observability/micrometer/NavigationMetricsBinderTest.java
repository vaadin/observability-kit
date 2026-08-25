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
import org.mockito.MockedStatic;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.observability.micrometer.trace.ObservationNames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class NavigationMetricsBinderTest {

    private static final String SAMPLE_KEY = NavigationMetricsBinder.class
            .getName() + ".sample";
    private static final String ROUTE_KEY = NavigationMetricsBinder.class
            .getName() + ".route";
    private static final String OBSERVATION_KEY = NavigationMetricsBinder.class
            .getName() + ".observation";
    private static final String OBSERVATION_SCOPE_KEY = NavigationMetricsBinder.class
            .getName() + ".observation.scope";

    /**
     * Recording handler that captures observation names, low-cardinality tags,
     * and the last context for verification.
     */
    private static final class RecordingHandler
            implements ObservationHandler<Observation.Context> {

        final List<String> names = new ArrayList<>();
        final List<Map<String, String>> tags = new ArrayList<>();
        Observation.Context lastContext;

        @Override
        public void onStop(Observation.Context ctx) {
            lastContext = ctx;
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

    /** Simple navigation target for testing route resolution. */
    static class HomeView extends Component {
    }

    /** Another navigation target, for testing multiple routes. */
    static class UsersView extends Component {
    }

    private MockedStatic<UI> uiMockedStatic;
    private UI mockUi;
    private RouteTagResolver routes;

    @BeforeEach
    void setUp() {
        mockUi = mock(UI.class);
        uiMockedStatic = mockStatic(UI.class);
        uiMockedStatic.when(UI::getCurrent).thenReturn(mockUi);
        routes = new RouteTagResolver(200);
    }

    @AfterEach
    void tearDown() {
        uiMockedStatic.close();
        RequestInteraction.clear();
    }

    // -----------------------------------------------------------------
    // Direct Timer path
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
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RecordingHandler recorder = new RecordingHandler();
        ObservationRegistry observationRegistry = null;
        if (withObservationRegistry) {
            observationRegistry = ObservationRegistry.create();
            observationRegistry.observationConfig()
                    .observationHandler(recorder);
        }
        NavigationMetricsBinder binder = new NavigationMetricsBinder(registry,
                observationRegistry,
                ObservabilitySettings.builder().traces(traces).build(), routes);

        binder.beforeEnter(mockBeforeEnter(HomeView.class));
        binder.afterNavigation(mockAfterNavigation());

        Timer timer = registry.find(MeterNames.NAVIGATION)
                .tag(MeterNames.TAG_ROUTE, "HomeView")
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .timer();
        assertNotNull(timer,
                "vaadin.navigation timer with route and outcome=success should exist");
        assertEquals(1L, timer.count());
        assertTrue(recorder.names.isEmpty(),
                "no observation should fire outside the observation path");
    }

    @Test
    void directPathWithNullRouteFallsBackToUnknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NavigationMetricsBinder binder = new NavigationMetricsBinder(registry,
                null, ObservabilitySettings.builder().traces(false).build(),
                routes);

        binder.beforeEnter(mockBeforeEnter(null));
        binder.afterNavigation(mockAfterNavigation());

        Timer timer = registry.find(MeterNames.NAVIGATION)
                .tag(MeterNames.TAG_ROUTE, MeterNames.ROUTE_UNKNOWN)
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .timer();
        assertNotNull(timer,
                "null navigation target should produce ROUTE_UNKNOWN tag");
        assertEquals(1L, timer.count());
    }

    /**
     * Covers the defensive fallback in {@code afterNavigation}: the stored
     * route attribute is read back as {@code Object} and only trusted when it
     * is a {@code String}.
     */
    @Test
    void directPathWithNonStringRouteAttributeFallsBackToUnknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NavigationMetricsBinder binder = new NavigationMetricsBinder(registry,
                null, ObservabilitySettings.builder().traces(false).build(),
                routes);

        binder.beforeEnter(mockBeforeEnter(HomeView.class));
        ComponentUtil.setData(mockUi, ROUTE_KEY, Integer.valueOf(1));
        binder.afterNavigation(mockAfterNavigation());

        Timer timer = registry.find(MeterNames.NAVIGATION)
                .tag(MeterNames.TAG_ROUTE, MeterNames.ROUTE_UNKNOWN)
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .timer();
        assertNotNull(timer,
                "a non-String route attribute should fall back to unknown");
        assertEquals(1L, timer.count());
    }

    @Test
    void constructorWithNullObservationRegistryUsesDefaults() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NavigationMetricsBinder binder = new NavigationMetricsBinder(registry,
                routes);

        binder.beforeEnter(mockBeforeEnter(HomeView.class));
        binder.afterNavigation(mockAfterNavigation());

        Timer timer = registry.find(MeterNames.NAVIGATION)
                .tag(MeterNames.TAG_ROUTE, "HomeView")
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .timer();
        assertNotNull(timer,
                "two-arg constructor should fall back to direct Timer when no observation registry");
        assertEquals(1L, timer.count());
    }

    @Test
    void twoNavigationsRecordSeparately() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NavigationMetricsBinder binder = new NavigationMetricsBinder(registry,
                null, ObservabilitySettings.builder().traces(false).build(),
                routes);

        binder.beforeEnter(mockBeforeEnter(HomeView.class));
        binder.afterNavigation(mockAfterNavigation());
        binder.beforeEnter(mockBeforeEnter(UsersView.class));
        binder.afterNavigation(mockAfterNavigation());

        Timer homeTimer = registry.find(MeterNames.NAVIGATION)
                .tag(MeterNames.TAG_ROUTE, "HomeView")
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .timer();
        assertNotNull(homeTimer);
        assertEquals(1L, homeTimer.count());

        Timer usersTimer = registry.find(MeterNames.NAVIGATION)
                .tag(MeterNames.TAG_ROUTE, "UsersView")
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .timer();
        assertNotNull(usersTimer);
        assertEquals(1L, usersTimer.count());
    }

    @Test
    void twoNavigationsOnSameRouteAccumulate() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NavigationMetricsBinder binder = new NavigationMetricsBinder(registry,
                null, ObservabilitySettings.builder().traces(false).build(),
                routes);

        for (int i = 0; i < 3; i++) {
            binder.beforeEnter(mockBeforeEnter(HomeView.class));
            binder.afterNavigation(mockAfterNavigation());
        }

        Timer timer = registry.find(MeterNames.NAVIGATION)
                .tag(MeterNames.TAG_ROUTE, "HomeView")
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .timer();
        assertNotNull(timer);
        assertEquals(3L, timer.count(),
                "same route should accumulate across multiple navigations");
    }

    // -----------------------------------------------------------------
    // Observation path
    // -----------------------------------------------------------------

    @Test
    void observationPathEmitsTimerWithRouteAndOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(
                new DefaultMeterObservationHandler(registry));

        NavigationMetricsBinder binder = new NavigationMetricsBinder(registry,
                observationRegistry,
                ObservabilitySettings.builder().traces(true).build(), routes);

        binder.beforeEnter(mockBeforeEnter(HomeView.class));
        binder.afterNavigation(mockAfterNavigation());

        Timer timer = registry.find(MeterNames.NAVIGATION)
                .tag(MeterNames.TAG_ROUTE, "HomeView")
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .timer();
        assertNotNull(timer,
                "DefaultMeterObservationHandler should emit vaadin.navigation timer");
        assertEquals(1L, timer.count());
    }

    @Test
    void observationPathCarriesRouteTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        observationRegistry.observationConfig()
                .observationHandler(
                        new DefaultMeterObservationHandler(registry))
                .observationHandler(recorder);

        NavigationMetricsBinder binder = new NavigationMetricsBinder(registry,
                observationRegistry,
                ObservabilitySettings.builder().traces(true).build(), routes);

        binder.beforeEnter(mockBeforeEnter(HomeView.class));
        binder.afterNavigation(mockAfterNavigation());

        assertEquals("HomeView", recorder.tags.get(0).get(MeterNames.TAG_ROUTE),
                "observation should carry the route as a low-cardinality tag");
        assertEquals(ObservationNames.OUTCOME_SUCCESS,
                recorder.tags.get(0).get(ObservationNames.KEY_OUTCOME),
                "observation should carry outcome=success");
    }

    @Test
    void observationPathSetsContextualNameWithRoute() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        observationRegistry.observationConfig()
                .observationHandler(
                        new DefaultMeterObservationHandler(registry))
                .observationHandler(recorder);

        NavigationMetricsBinder binder = new NavigationMetricsBinder(registry,
                observationRegistry,
                ObservabilitySettings.builder().traces(true).build(), routes);

        binder.beforeEnter(mockBeforeEnter(HomeView.class));
        binder.afterNavigation(mockAfterNavigation());

        assertEquals("vaadin.navigation HomeView",
                recorder.lastContext.getContextualName(),
                "contextual name should include the route");
    }

    @Test
    void observationPathClosesScopeAndStopsObservation() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(
                new DefaultMeterObservationHandler(registry));

        NavigationMetricsBinder binder = new NavigationMetricsBinder(registry,
                observationRegistry,
                ObservabilitySettings.builder().traces(true).build(), routes);

        binder.beforeEnter(mockBeforeEnter(HomeView.class));
        binder.afterNavigation(mockAfterNavigation());

        assertNull(observationRegistry.getCurrentObservation(),
                "scope must be closed: no observation may remain current");
    }

    @Test
    void beforeEnterMarksRequestInteractionAsNavigation() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        NavigationMetricsBinder binder = new NavigationMetricsBinder(registry,
                observationRegistry,
                ObservabilitySettings.builder().traces(true).build(), routes);

        binder.beforeEnter(mockBeforeEnter(HomeView.class));

        assertEquals(ObservationNames.INTERACTION_NAVIGATION,
                RequestInteraction.take(),
                "beforeEnter should mark the request interaction as navigation");

        binder.afterNavigation(mockAfterNavigation());
    }

    // -----------------------------------------------------------------
    // Per-UI state handling
    // -----------------------------------------------------------------

    @Test
    void beforeEnterSetsCurrentRouteInTelemetryContext() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NavigationMetricsBinder binder = new NavigationMetricsBinder(registry,
                null, ObservabilitySettings.builder().traces(false).build(),
                routes);

        binder.beforeEnter(mockBeforeEnter(HomeView.class));

        assertEquals("HomeView", VaadinTelemetryContext.currentRoute(),
                "VaadinTelemetryContext should reflect the navigated route");

        binder.afterNavigation(mockAfterNavigation());
    }

    @Test
    void uiAttributesClearedAfterNavigation() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        NavigationMetricsBinder binder = new NavigationMetricsBinder(registry,
                observationRegistry,
                ObservabilitySettings.builder().traces(true).build(), routes);

        binder.beforeEnter(mockBeforeEnter(HomeView.class));

        assertNotNull(ComponentUtil.getData(mockUi, OBSERVATION_KEY),
                "beforeEnter should have stored the in-flight observation");
        assertNotNull(ComponentUtil.getData(mockUi, OBSERVATION_SCOPE_KEY),
                "beforeEnter should have stored the observation scope");

        binder.afterNavigation(mockAfterNavigation());

        assertNull(ComponentUtil.getData(mockUi, SAMPLE_KEY),
                "sample attribute should be cleared");
        assertNull(ComponentUtil.getData(mockUi, ROUTE_KEY),
                "route attribute should be cleared");
        assertNull(ComponentUtil.getData(mockUi, OBSERVATION_KEY),
                "observation attribute should be cleared");
        assertNull(ComponentUtil.getData(mockUi, OBSERVATION_SCOPE_KEY),
                "observation scope attribute should be cleared");
    }

    @Test
    void afterNavigationWithNullUiReturnsEarly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NavigationMetricsBinder binder = new NavigationMetricsBinder(registry,
                null, ObservabilitySettings.builder().traces(false).build(),
                routes);

        binder.beforeEnter(mockBeforeEnter(HomeView.class));

        uiMockedStatic.when(UI::getCurrent).thenReturn(null);
        binder.afterNavigation(mockAfterNavigation());

        assertNull(registry.find(MeterNames.NAVIGATION).timer(),
                "no timer should be recorded when UI.getCurrent() is null");
    }

    /**
     * Documents current behavior: on the observation path the same early return
     * leaves the observation unstopped and its scope open on the request
     * thread, not just an unrecorded Timer sample.
     */
    @Test
    void afterNavigationWithNullUiLeavesObservationOpen() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        observationRegistry.observationConfig()
                .observationHandler(
                        new DefaultMeterObservationHandler(registry))
                .observationHandler(recorder);

        NavigationMetricsBinder binder = new NavigationMetricsBinder(registry,
                observationRegistry,
                ObservabilitySettings.builder().traces(true).build(), routes);

        binder.beforeEnter(mockBeforeEnter(HomeView.class));

        uiMockedStatic.when(UI::getCurrent).thenReturn(null);
        binder.afterNavigation(mockAfterNavigation());

        assertTrue(recorder.names.isEmpty(),
                "the observation is never stopped when UI.getCurrent() is null");
        assertNotNull(observationRegistry.getCurrentObservation(),
                "the open scope leaks when afterNavigation returns early");
    }

    /**
     * Documents current behavior for a rerouted navigation. Flow fires
     * {@code beforeEnter} once per target but only one {@code afterNavigation}
     * follows, and the binder keeps the in-flight observation in a single UI
     * attribute, so the second {@code beforeEnter} overwrites the first. The
     * first observation is never stopped and its scope is never closed.
     */
    @Test
    void rerouteLeavesFirstObservationUnstopped() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        RecordingHandler recorder = new RecordingHandler();
        observationRegistry.observationConfig()
                .observationHandler(
                        new DefaultMeterObservationHandler(registry))
                .observationHandler(recorder);

        NavigationMetricsBinder binder = new NavigationMetricsBinder(registry,
                observationRegistry,
                ObservabilitySettings.builder().traces(true).build(), routes);

        binder.beforeEnter(mockBeforeEnter(HomeView.class));
        binder.beforeEnter(mockBeforeEnter(UsersView.class));
        binder.afterNavigation(mockAfterNavigation());

        assertEquals(1, recorder.names.size(),
                "two observations were started but only the second one stopped");
        assertNotNull(observationRegistry.getCurrentObservation(),
                "the first observation's scope leaks and stays current after "
                        + "the navigation completed");
    }

    /**
     * {@code beforeEnter} stores its state on {@code event.getUI()} while
     * {@code afterNavigation} reads it back from {@code UI.getCurrent()}. When
     * the two differ the timing state is not found and the navigation is
     * silently not recorded.
     */
    @Test
    void afterNavigationOnADifferentUiRecordsNothing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NavigationMetricsBinder binder = new NavigationMetricsBinder(registry,
                null, ObservabilitySettings.builder().traces(false).build(),
                routes);

        binder.beforeEnter(mockBeforeEnter(HomeView.class));

        UI otherUi = mock(UI.class);
        uiMockedStatic.when(UI::getCurrent).thenReturn(otherUi);
        binder.afterNavigation(mockAfterNavigation());

        assertNull(registry.find(MeterNames.NAVIGATION).timer(),
                "timing state is keyed to the event UI, so a different "
                        + "UI.getCurrent() drops the sample");
        assertNotNull(ComponentUtil.getData(mockUi, SAMPLE_KEY),
                "the sample is left behind on the originating UI");
    }

    /**
     * Creates a mocked BeforeEnterEvent for the given navigation target.
     */
    private BeforeEnterEvent mockBeforeEnter(
            Class<? extends Component> navigationTarget) {
        BeforeEnterEvent event = mock(BeforeEnterEvent.class);
        when(event.getUI()).thenReturn(mockUi);
        doReturn(navigationTarget).when(event).getNavigationTarget();
        return event;
    }

    /**
     * Creates a mocked AfterNavigationEvent.
     */
    private AfterNavigationEvent mockAfterNavigation() {
        return mock(AfterNavigationEvent.class);
    }
}
