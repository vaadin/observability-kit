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
import java.util.function.Function;

import org.mockito.Mockito;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.internal.NavigationRouteTarget;
import com.vaadin.flow.router.internal.RouteTarget;
import com.vaadin.flow.server.RouteRegistry;

/**
 * Mocked UIs whose router resolves locations to views, so a navigation binder
 * resolves the requested route the way it does in a running application.
 */
final class RoutedUis {

    private RoutedUis() {
    }

    /**
     * A UI whose router resolves each path in {@code views} to its view and
     * every other path to none.
     */
    static UI routedUi(Map<String, Class<? extends Component>> views) {
        return routedUi(path -> routeTarget(path, views.get(path), Map.of()));
    }

    /** A UI whose router resolves a path through {@code resolver}. */
    static UI routedUi(Function<String, NavigationRouteTarget> resolver) {
        UI routed = Mockito.mock(UI.class, Mockito.RETURNS_DEEP_STUBS);
        RouteRegistry routes = Mockito.mock(RouteRegistry.class);
        Mockito.when(routes.getNavigationRouteTarget(Mockito.anyString()))
                .thenAnswer(invocation -> resolver
                        .apply(invocation.getArgument(0)));
        Mockito.when(routed.getInternals().getRouter().getRegistry())
                .thenReturn(routes);
        Mockito.when(routed.getInternals().getRouterStateSignal().peek())
                .thenReturn(null);
        return routed;
    }

    /**
     * What the router resolves {@code path} to: {@code view} with the given
     * route parameters, or no view when {@code view} is {@code null}.
     */
    static NavigationRouteTarget routeTarget(String path,
            Class<? extends Component> view, Map<String, String> parameters) {
        return new NavigationRouteTarget(path,
                view == null ? null : new RouteTarget(view), parameters);
    }
}
