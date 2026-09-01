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

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.RouteConfiguration;
import com.vaadin.flow.server.RouteRegistry;

/**
 * Maps a Flow navigation target to a low-cardinality tag value suitable for
 * Micrometer. Resolves the route template (e.g. {@code users/:id}) rather than
 * the resolved URL, then enforces an upper bound on the number of distinct
 * values it will admit; further values are bucketed under
 * {@link MeterNames#ROUTE_OTHER}.
 */
public final class RouteTagResolver {

    private final BoundedTagValues values;

    public RouteTagResolver(int limit) {
        this.values = new BoundedTagValues(limit, MeterNames.ROUTE_OTHER);
    }

    /**
     * Resolves the tag value for a navigation target. {@code null} input is
     * treated as an unknown route.
     */
    public String tagFor(Class<? extends Component> navigationTarget) {
        if (navigationTarget == null) {
            return MeterNames.ROUTE_UNKNOWN;
        }
        return tagForTemplate(resolveTemplate(navigationTarget)
                .orElseGet(navigationTarget::getSimpleName));
    }

    /**
     * Resolves the tag value for a navigation target against an explicit route
     * registry.
     * <p>
     * Use this when the caller may run off the request thread. The
     * session-scoped lookup that {@link #tagFor(Class)} falls back on needs
     * {@code VaadinSession.getCurrent()}, which is unset there, so such a
     * caller would silently get the class simple name instead of the template.
     * <p>
     * The trade is that a UI's router carries the <em>application</em>
     * registry, while the session registry layers over it. A route registered
     * with {@code RouteConfiguration.forSessionScope().setRoute(...)} is
     * therefore not resolvable here and falls back to the class simple name,
     * even though {@link #tagFor(Class)} would find it. Reading the session
     * registry instead is not an option for those callers: it needs the session
     * lock, which the executor thread a fetch runs on does not hold.
     *
     * @param navigationTarget
     *            the navigation target, may be {@code null}
     * @param registry
     *            the registry to resolve the template from, may be {@code null}
     * @return the tag value, never {@code null}
     */
    public String tagFor(Class<? extends Component> navigationTarget,
            RouteRegistry registry) {
        if (navigationTarget == null) {
            return MeterNames.ROUTE_UNKNOWN;
        }
        if (registry == null) {
            return tagFor(navigationTarget);
        }
        return tagForTemplate(RouteConfiguration.forRegistry(registry)
                .getTemplate(navigationTarget)
                .orElseGet(navigationTarget::getSimpleName));
    }

    /**
     * Resolves the tag value for the view a UI currently shows.
     *
     * @param ui
     *            the UI, may be {@code null}
     * @param fallback
     *            returned when no navigation target can be resolved
     * @return the tag value, or {@code fallback}
     */
    public String tagForUi(UI ui, String fallback) {
        if (ui == null) {
            return fallback;
        }
        var internals = ui.getInternals();
        RouteRegistry registry = null;
        try {
            var router = internals.getRouter();
            registry = router == null ? null : router.getRegistry();
        } catch (RuntimeException ignored) {
            // UIInternals#getRouter reaches through the session, which a
            // detached UI no longer has. Leaving the registry null falls back
            // to the session-scoped lookup, which is itself guarded.
        }
        // Copied before iterating: the chain is an unmodifiable view over a
        // live list that navigation mutates in place, and this may run on a
        // different thread than the one navigating.
        for (HasElement target : List
                .copyOf(internals.getActiveRouterTargetsChain())) {
            if (target instanceof Component component) {
                return tagFor(component.getClass(), registry);
            }
        }
        return fallback;
    }

    /**
     * Resolves the tag value for the view a UI currently shows, preferring the
     * route <em>template</em> of the innermost active navigation target so that
     * {@code orders/17} and {@code orders/18} share one tag value. Falls back
     * to the concrete location when no navigation target can be resolved, and
     * to {@link MeterNames#ROUTE_UNKNOWN} when there is no UI or its state
     * cannot be read (for example because it has been detached).
     *
     * @param ui
     *            the UI to resolve the active route of, may be {@code null}
     * @return the route tag value, never {@code null}
     */
    public String tagForActiveRoute(UI ui) {
        if (ui == null) {
            return MeterNames.ROUTE_UNKNOWN;
        }
        try {
            String tag = tagForUi(ui, null);
            return tag != null ? tag
                    : tagForTemplate(ui.getInternals().getActiveViewLocation()
                            .getPath());
        } catch (RuntimeException e) {
            // Resolution is best-effort enrichment of a measurement; never let
            // it break the caller.
            return MeterNames.ROUTE_UNKNOWN;
        }
    }

    /**
     * Like {@link #tagForActiveRoute(UI)} but without the concrete-location
     * fallback: the result is always a route template (or the class-simple-name
     * stand-in), never a literal path with its parameter values. For a consumer
     * whose tag values must stay bounded — such as the {@code uri} tag on
     * {@code http.server.requests} — the location fallback would be the one
     * genuinely unbounded source ({@code orders/17}, {@code orders/18}, …).
     *
     * @param ui
     *            the UI to resolve the active route of, may be {@code null}
     * @return the route template tag value, or {@link MeterNames#ROUTE_UNKNOWN}
     *         when no navigation target can be resolved
     */
    public String templateForActiveRoute(UI ui) {
        if (ui == null) {
            return MeterNames.ROUTE_UNKNOWN;
        }
        try {
            return tagForUi(ui, MeterNames.ROUTE_UNKNOWN);
        } catch (RuntimeException e) {
            // Resolution is best-effort enrichment of a measurement; never let
            // it break the caller.
            return MeterNames.ROUTE_UNKNOWN;
        }
    }

    private Optional<String> resolveTemplate(
            Class<? extends Component> navigationTarget) {
        try {
            return RouteConfiguration.forSessionScope()
                    .getTemplate(navigationTarget);
        } catch (RuntimeException ignored) {
            // No current session bound; fall back to simple name.
            return Optional.empty();
        }
    }

    /**
     * Bucketizes an already-resolved route template against the cardinality
     * limit. Useful when the template is already known and we only need the
     * overflow behavior.
     */
    public String tagForTemplate(String template) {
        if (template == null) {
            return MeterNames.ROUTE_UNKNOWN;
        }
        return values.admit(template);
    }

    int trackedCount() {
        return values.trackedCount();
    }
}
