/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.dom.ElementFactory;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationListener;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLayout;
import com.vaadin.flow.server.RouteRegistry;
import com.vaadin.flow.server.SessionDestroyEvent;
import com.vaadin.flow.server.UIInitEvent;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.communication.RpcInvocationEndedEvent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link UiStateMetricsBinder} measures the state each UI holds
 * and publishes it as the {@code vaadin.ui.state.*} aggregates.
 * <p>
 * A UI is mocked so that the listeners the binder registers can be captured and
 * fired, but its element and internals come from a real {@link UI} — the point
 * of the measurement is Flow's actual state tree, which a mock has none of.
 */
class UiStateMetricsBinderTest {

    /** A route target, so views can be told from plain components. */
    @Route("state-test")
    private static class TestView extends Component {
        TestView() {
            super(ElementFactory.createDiv());
        }
    }

    /** A layout, which is a view whether or not it is routable itself. */
    private static class TestLayout extends Component implements RouterLayout {
        TestLayout() {
            super(ElementFactory.createDiv());
        }
    }

    /** A UI whose listeners are observable and whose tree is real. */
    private record Tab(UI ui, UI real) {

        Element root() {
            return real.getElement();
        }

        /** Grows this tab's tree, the way opening a heavy view would. */
        void grow(int elements) {
            for (int i = 0; i < elements; i++) {
                root().appendChild(ElementFactory.createDiv());
            }
        }

        /** Attaches a component and makes it the UI's active route target. */
        void navigateTo(Component target) {
            root().appendChild(target.getElement());
            if (target instanceof RouterLayout layout) {
                real.getInternals().setRouterTargetChain(List.of(layout));
            }
        }
    }

    private SimpleMeterRegistry registry;
    private UiStateMetricsBinder binder;

    @BeforeEach
    void setUp() {
        // Sampling unthrottled by default here, so a test can grow a tree and
        // read the effect in the next line; the throttle has its own test.
        useBinder(ObservabilitySettings.builder().uiState(true)
                .uiStateSampleInterval(0));
    }

    /**
     * Replaces the binder under test, on a registry of its own: a gauge name
     * registered twice on one registry keeps the first binding, so a second
     * binder sharing the registry would publish through the first one's state.
     * <p>
     * The aggregate cache is switched off, so a gauge read right after a
     * measurement sees it; that the cache holds otherwise has its own test.
     */
    private void useBinder(ObservabilitySettings.Builder builder) {
        useBinder(builder, 0);
    }

    private void useBinder(ObservabilitySettings.Builder builder,
            long totalsCacheNanos) {
        registry = new SimpleMeterRegistry();
        binder = new UiStateMetricsBinder(registry, builder.build(),
                totalsCacheNanos);
    }

    /**
     * A session whose service knows one route, so that a retained
     * {@link TestView} is recognized the way Flow's own registry would
     * recognize it — by lookup, not by reading the {@code @Route} annotation.
     */
    private static VaadinSession sessionWithRoutes() {
        VaadinSession session = mock(VaadinSession.class, RETURNS_DEEP_STUBS);
        RouteRegistry routes = session.getService().getRouter().getRegistry();
        when(routes.getTemplate(TestView.class))
                .thenReturn(Optional.of("state-test"));
        return session;
    }

    private static Tab tab(VaadinSession session) {
        UI real = new UI();
        UI ui = mock(UI.class);
        when(ui.getElement()).thenReturn(real.getElement());
        when(ui.getInternals()).thenReturn(real.getInternals());
        when(ui.getSession()).thenReturn(session);
        return new Tab(ui, real);
    }

    /** Runs the UI-init path the service would run for a new browser tab. */
    private Tab openTab(VaadinSession session) {
        Tab tab = tab(session);
        binder.uiInit(new UIInitEvent(tab.ui(), mock(VaadinService.class)));
        return tab;
    }

    private double gauge(String name) {
        return registry.find(name).gauge().value();
    }

    @Test
    void uiInitPublishesNodesComponentsAndTheActiveView() {
        Tab tab = tab(mock(VaadinSession.class));
        tab.navigateTo(new TestLayout());
        tab.grow(5);

        binder.uiInit(new UIInitEvent(tab.ui(), mock(VaadinService.class)));

        // The UI element, the layout and the five divs are all state-tree
        // nodes; the exact total is Flow's business, that it counts them is
        // ours.
        assertTrue(gauge(MeterNames.UI_STATE_NODES) >= 7,
                "the whole tree should be counted, got "
                        + gauge(MeterNames.UI_STATE_NODES));
        assertEquals(2.0, gauge(MeterNames.UI_STATE_COMPONENTS), 0.0,
                "the UI and the layout are the only components attached");
        assertEquals(1.0, gauge(MeterNames.UI_STATE_VIEWS), 0.0);
        assertEquals(0.0, gauge(MeterNames.UI_STATE_VIEWS_STALE), 0.0,
                "the only view is the one the UI is showing");
        assertEquals(gauge(MeterNames.UI_STATE_NODES),
                gauge(MeterNames.UI_STATE_NODES_MAX), 0.0,
                "with one UI, the largest UI holds everything");
        assertEquals(1.0, gauge(MeterNames.SESSION_UIS_MAX), 0.0);
    }

    @Test
    void aNestedLayoutIsNotMistakenForAViewThatOutlivedItsNavigation() {
        // One navigation into a nested layout legitimately retains a view per
        // level, and Flow reports the whole chain as active. Reading "more than
        // one view" as a leak is exactly the misreading this gauge pair exists
        // to avoid.
        Tab tab = tab(mock(VaadinSession.class));
        TestLayout parent = new TestLayout();
        TestLayout child = new TestLayout();
        tab.root().appendChild(parent.getElement());
        parent.getElement().appendChild(child.getElement());
        tab.real().getInternals().setRouterTargetChain(List.of(child, parent));

        binder.uiInit(new UIInitEvent(tab.ui(), mock(VaadinService.class)));

        assertEquals(2.0, gauge(MeterNames.UI_STATE_VIEWS), 0.0,
                "both levels of the chain are retained views");
        assertEquals(0.0, gauge(MeterNames.UI_STATE_VIEWS_STALE), 0.0,
                "neither has outlived the navigation that put it there");
    }

    @Test
    void aViewOutsideTheActiveChainIsReportedAsStale() {
        Tab tab = tab(mock(VaadinSession.class));
        tab.navigateTo(new TestLayout());
        // A second layout still hanging in the tree: the navigation that put
        // it there is over, but something is still holding it.
        tab.root().appendChild(new TestLayout().getElement());

        binder.uiInit(new UIInitEvent(tab.ui(), mock(VaadinService.class)));

        assertEquals(2.0, gauge(MeterNames.UI_STATE_VIEWS), 0.0);
        assertEquals(1.0, gauge(MeterNames.UI_STATE_VIEWS_STALE), 0.0,
                "the layout outside the active chain outlived its navigation");
    }

    @Test
    void aRetainedRouteIsFoundThroughTheRegistryNotTheAnnotation() {
        // TestView carries @Route, but the registry is what is asked: that is
        // what also finds routes added through RouteConfiguration.setRoute.
        Tab tab = tab(sessionWithRoutes());
        tab.root().appendChild(new TestView().getElement());

        binder.uiInit(new UIInitEvent(tab.ui(), mock(VaadinService.class)));

        assertEquals(1.0, gauge(MeterNames.UI_STATE_VIEWS), 0.0);
        assertEquals(1.0, gauge(MeterNames.UI_STATE_VIEWS_STALE), 0.0);
    }

    @Test
    void aSubclassOfARouteIsStillARoute() {
        // @Route is not inherited and Flow registers the annotated class, so a
        // proxy or a specialized subclass is only found by climbing.
        class SpecializedView extends TestView {
        }
        Tab tab = tab(sessionWithRoutes());
        tab.root().appendChild(new SpecializedView().getElement());

        binder.uiInit(new UIInitEvent(tab.ui(), mock(VaadinService.class)));

        assertEquals(1.0, gauge(MeterNames.UI_STATE_VIEWS), 0.0);
    }

    @Test
    void gaugesAreZeroWhileNothingIsTracked() {
        assertEquals(0.0, gauge(MeterNames.UI_STATE_NODES), 0.0);
        assertEquals(0.0, gauge(MeterNames.UI_STATE_NODES_MAX), 0.0);
        assertEquals(0.0, gauge(MeterNames.UI_STATE_VIEWS), 0.0);
        assertEquals(0.0, gauge(MeterNames.UI_STATE_VIEWS_STALE), 0.0);
        assertEquals(0.0, gauge(MeterNames.SESSION_STATE_NODES_MAX), 0.0);
        assertEquals(0.0, gauge(MeterNames.SESSION_UIS_MAX), 0.0);
        assertEquals(0.0, gauge(MeterNames.UI_STATE_SAMPLE_AGE_MAX), 0.0);
    }

    @Test
    void twoTabsOfOneSessionAggregateIntoThatSession() {
        VaadinSession session = mock(VaadinSession.class);
        Tab first = openTab(session);
        first.grow(20);
        Tab second = openTab(session);
        second.grow(5);
        // Re-measure both now that they have grown.
        resample(first);
        resample(second);

        double total = gauge(MeterNames.UI_STATE_NODES);
        assertEquals(total, gauge(MeterNames.SESSION_STATE_NODES_MAX), 0.0,
                "one session holds every tab, so it is the largest session");
        assertEquals(2.0, gauge(MeterNames.SESSION_UIS_MAX), 0.0);
        assertTrue(gauge(MeterNames.UI_STATE_NODES_MAX) < total,
                "the larger tab is only part of the session's state");
    }

    @Test
    void sessionsAreMeasuredApart() {
        openTab(mock(VaadinSession.class));
        Tab heavy = openTab(mock(VaadinSession.class));
        heavy.grow(30);
        resample(heavy);

        assertEquals(1.0, gauge(MeterNames.SESSION_UIS_MAX), 0.0,
                "neither user has a second tab open");
        assertEquals(gauge(MeterNames.UI_STATE_NODES_MAX),
                gauge(MeterNames.SESSION_STATE_NODES_MAX), 0.0,
                "the heavy user's only tab is their whole footprint");
        assertTrue(
                gauge(MeterNames.UI_STATE_NODES) > gauge(
                        MeterNames.SESSION_STATE_NODES_MAX),
                "the total also holds the quiet user's tab");
    }

    @Test
    void interactionReMeasuresTheTabItTouched() {
        Tab tab = openTab(mock(VaadinSession.class));
        double before = gauge(MeterNames.UI_STATE_NODES);

        tab.grow(10);
        resample(tab);

        assertEquals(before + 10, gauge(MeterNames.UI_STATE_NODES), 0.0);
    }

    @Test
    void repeatedInteractionsWithinTheIntervalCostOneWalk() {
        useBinder(ObservabilitySettings.builder().uiState(true));
        Tab tab = openTab(mock(VaadinSession.class));
        double before = gauge(MeterNames.UI_STATE_NODES);

        tab.grow(10);
        for (int i = 0; i < 5; i++) {
            resample(tab);
        }

        assertEquals(before, gauge(MeterNames.UI_STATE_NODES), 0.0,
                "the default ten-second interval should throttle the walk");
    }

    @Test
    void navigationReMeasuresRegardlessOfTheInterval() {
        Tab tab = openTab(mock(VaadinSession.class));
        double before = gauge(MeterNames.UI_STATE_NODES);
        tab.grow(10);

        ArgumentCaptor<AfterNavigationListener> captor = ArgumentCaptor
                .forClass(AfterNavigationListener.class);
        verify(tab.ui()).addAfterNavigationListener(captor.capture());
        captor.getValue().afterNavigation(mock(AfterNavigationEvent.class));

        assertEquals(before + 10, gauge(MeterNames.UI_STATE_NODES), 0.0,
                "a navigation is exactly when a tree changes shape");
    }

    @Test
    @SuppressWarnings("unchecked")
    void closingATabDropsItsState() {
        Tab tab = openTab(mock(VaadinSession.class));
        assertTrue(gauge(MeterNames.UI_STATE_NODES) > 0);

        ArgumentCaptor<ComponentEventListener<DetachEvent>> captor = ArgumentCaptor
                .forClass(ComponentEventListener.class);
        verify(tab.ui()).addDetachListener(captor.capture());
        captor.getValue().onComponentEvent(mock(DetachEvent.class));

        assertEquals(0, binder.trackedUis());
        assertEquals(0.0, gauge(MeterNames.UI_STATE_NODES), 0.0);
    }

    @Test
    void destroyingASessionDropsOnlyItsOwnTabs() {
        VaadinSession leaving = mock(VaadinSession.class);
        openTab(leaving);
        openTab(leaving);
        Tab staying = openTab(mock(VaadinSession.class));
        resample(staying);
        double stayingNodes = gauge(MeterNames.UI_STATE_NODES_MAX);

        binder.sessionDestroy(
                new SessionDestroyEvent(mock(VaadinService.class), leaving));

        assertEquals(1, binder.trackedUis());
        assertEquals(stayingNodes, gauge(MeterNames.UI_STATE_NODES), 0.0);
        assertEquals(1.0, gauge(MeterNames.SESSION_UIS_MAX), 0.0);
    }

    @Test
    void sampleAgeIsPublishedSoAStaleAggregateCanBeSeen() {
        openTab(mock(VaadinSession.class));

        double age = gauge(MeterNames.UI_STATE_SAMPLE_AGE_MAX);

        assertTrue(age >= 0 && age < 60,
                "a just-measured tab should report a fresh sample, got " + age);
    }

    @Test
    void noByteFigureIsPublishedWithoutAMeasuredCost() {
        openTab(mock(VaadinSession.class));

        assertNull(registry.find(MeterNames.UI_STATE_SIZE).gauge(),
                "an unmeasured byte figure would be a guess");
    }

    @Test
    void byteFigureUsesTheConfiguredCostPerNode() {
        useBinder(ObservabilitySettings.builder().uiState(true)
                .uiStateSampleInterval(0).uiStateBytesPerNode(64));
        openTab(mock(VaadinSession.class));

        assertEquals(gauge(MeterNames.UI_STATE_NODES) * 64,
                gauge(MeterNames.UI_STATE_SIZE), 0.0);
    }

    @Test
    void aFailedWalkIsDroppedRatherThanBreakingTheInteraction() {
        UI broken = mock(UI.class);
        when(broken.getSession()).thenReturn(mock(VaadinSession.class));
        when(broken.getElement())
                .thenThrow(new IllegalStateException("no tree"));

        assertDoesNotThrow(() -> binder
                .uiInit(new UIInitEvent(broken, mock(VaadinService.class))));

        assertEquals(0, binder.trackedUis());
        assertEquals(0.0, gauge(MeterNames.UI_STATE_NODES), 0.0);
    }

    @Test
    void anInteractionWithoutAUiIsIgnored() {
        RpcInvocationEndedEvent event = mock(RpcInvocationEndedEvent.class);
        when(event.getUI()).thenReturn(null);

        assertDoesNotThrow(() -> binder.invocationEnded(event));
        assertEquals(0, binder.trackedUis());
    }

    @Test
    void anInteractionPicksUpAUiThatNeverReportedItselfAtInit() {
        // A UI restored from a serialized session, or one whose first walk
        // threw, would otherwise stay invisible for its whole life. Its
        // session being destroyed drops it even with no detach listener of
        // ours, so nothing is retained past the session.
        Tab latecomer = tab(mock(VaadinSession.class));

        resample(latecomer);

        assertEquals(1, binder.trackedUis());
        assertTrue(gauge(MeterNames.UI_STATE_NODES) > 0);
    }

    @Test
    void aUiWithoutASessionIsNotRetained() {
        // Session destroy matches on session identity, so an entry keyed by a
        // sessionless UI is one nothing would ever drop.
        Tab orphan = tab(null);

        binder.uiInit(new UIInitEvent(orphan.ui(), mock(VaadinService.class)));

        assertEquals(0, binder.trackedUis());
        assertEquals(0.0, gauge(MeterNames.UI_STATE_NODES), 0.0);
    }

    @Test
    void oneScrapeFoldsTheTrackedUisOnce() {
        // The gauges of a scrape are read one after another while requests keep
        // measuring; the aggregate they share is held for its cache period so
        // that a scrape costs one fold and reports figures that agree.
        useBinder(ObservabilitySettings.builder().uiState(true)
                .uiStateSampleInterval(0), TimeUnit.MINUTES.toNanos(1));
        Tab tab = openTab(mock(VaadinSession.class));
        double before = gauge(MeterNames.UI_STATE_NODES);

        tab.grow(10);
        resample(tab);

        assertEquals(before, gauge(MeterNames.UI_STATE_NODES), 0.0,
                "a measurement mid-scrape should not re-fold the map");
    }

    /** Fires the end of an RPC invocation on the given tab. */
    private void resample(Tab tab) {
        binder.invocationEnded(
                new RpcInvocationEndedEvent(tab.ui(), "event", 1, "click"));
    }
}
