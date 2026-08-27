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

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.RouteRegistry;
import com.vaadin.flow.server.VaadinSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

public class RouteTagResolverTest {

    @Tag("fake-route-a")
    private static final class FakeRouteA extends Component {
    }

    private static final class FakeRouteB extends Component {
    }

    private static final class FakeRouteC extends Component {
    }

    @Test
    public void nullTargetIsUnknown() {
        RouteTagResolver resolver = new RouteTagResolver(10);
        assertEquals(MeterNames.ROUTE_UNKNOWN, resolver.tagFor(null));
    }

    @Test
    public void firstRoutesAreAdmittedThenBucketedAsOther() {
        RouteTagResolver resolver = new RouteTagResolver(2);

        String a = resolver.tagFor(FakeRouteA.class);
        String b = resolver.tagFor(FakeRouteB.class);
        String c = resolver.tagFor(FakeRouteC.class);

        assertEquals(FakeRouteA.class.getSimpleName(), a);
        assertEquals(FakeRouteB.class.getSimpleName(), b);
        assertEquals(MeterNames.ROUTE_OTHER, c);
    }

    @Test
    public void admittedRouteRemainsAdmittedAfterCapHit() {
        RouteTagResolver resolver = new RouteTagResolver(1);
        resolver.tagFor(FakeRouteA.class);
        resolver.tagFor(FakeRouteB.class); // overflow -> _other

        assertEquals(FakeRouteA.class.getSimpleName(),
                resolver.tagFor(FakeRouteA.class));
    }

    @Test
    public void activeRouteResolvesFromTheInnermostNavigationTarget() {
        RouteTagResolver resolver = new RouteTagResolver(10);
        UI ui = Mockito.mock(UI.class, RETURNS_DEEP_STUBS);
        Mockito.when(ui.getInternals().getActiveRouterTargetsChain())
                .thenReturn(List.of(new FakeRouteA()));

        assertEquals(FakeRouteA.class.getSimpleName(),
                resolver.tagForActiveRoute(ui));
    }

    @Test
    public void activeRouteFallsBackToTheLocationWithoutANavigationTarget() {
        RouteTagResolver resolver = new RouteTagResolver(10);
        UI ui = Mockito.mock(UI.class, RETURNS_DEEP_STUBS);
        Mockito.when(ui.getInternals().getActiveRouterTargetsChain())
                .thenReturn(List.<HasElement> of());
        Mockito.when(ui.getInternals().getActiveViewLocation())
                .thenReturn(new Location("orders/17"));

        assertEquals("orders/17", resolver.tagForActiveRoute(ui));
    }

    @Test
    public void activeRouteIsUnknownWithoutAUiOrWhenItCannotBeRead() {
        RouteTagResolver resolver = new RouteTagResolver(10);
        UI broken = Mockito.mock(UI.class);
        Mockito.when(broken.getInternals())
                .thenThrow(new IllegalStateException("detached"));

        assertEquals(MeterNames.ROUTE_UNKNOWN,
                resolver.tagForActiveRoute(null));
        assertEquals(MeterNames.ROUTE_UNKNOWN,
                resolver.tagForActiveRoute(broken));
    }

    @Tag("route-test-view")
    @Route("orders")
    public static class OrdersView extends Component {
    }

    /**
     * The reason tagFor(target, registry) exists: the session-scoped lookup
     * needs VaadinSession.getCurrent(), which is unset on the executor thread a
     * component with asynchronous updates fetches on. Resolving through the
     * registry must work with no session bound at all.
     */
    @Test
    void resolvesTheTemplateWithoutACurrentSession() {
        VaadinSession.setCurrent(null);
        RouteRegistry registry = Mockito.mock(RouteRegistry.class);
        Mockito.when(registry.getTemplate(OrdersView.class))
                .thenReturn(Optional.of("orders"));

        String tag = new RouteTagResolver(10).tagFor(OrdersView.class,
                registry);

        assertEquals("orders", tag,
                "the registry path must not depend on a current session");
    }

    @Test
    void fallsBackToTheSimpleNameWhenTheRegistryHasNoTemplate() {
        RouteRegistry registry = Mockito.mock(RouteRegistry.class);
        Mockito.when(registry.getTemplate(OrdersView.class))
                .thenReturn(Optional.empty());

        assertEquals("OrdersView",
                new RouteTagResolver(10).tagFor(OrdersView.class, registry));
    }

    @Test
    void aNullRegistryFallsBackToTheSessionScopedLookup() {
        // Not a failure mode: callers that have no registry still get the old
        // behaviour rather than an exception.
        VaadinSession.setCurrent(null);

        assertEquals("OrdersView",
                new RouteTagResolver(10).tagFor(OrdersView.class, null));
    }

    @Test
    void aUiWithoutASessionResolvesRatherThanThrowing() {
        // UIInternals#getRouter reaches through the session, so a detached UI
        // would throw if the registry lookup were not guarded.
        VaadinSession.setCurrent(null);

        assertEquals("_none",
                new RouteTagResolver(10).tagForUi(new UI(), "_none"));
    }

    @Test
    void aNullUiReturnsTheFallback() {
        assertEquals("_unknown",
                new RouteTagResolver(10).tagForUi(null, "_unknown"));
    }
}
