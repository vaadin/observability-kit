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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    private final int limit;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    public RouteTagResolver(int limit) {
        this.limit = limit;
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
        if (seen.contains(template)) {
            return template;
        }
        if (seen.size() < limit) {
            seen.add(template);
            return template;
        }
        return MeterNames.ROUTE_OTHER;
    }

    int trackedCount() {
        return seen.size();
    }
}
