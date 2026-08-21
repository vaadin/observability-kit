/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.Optional;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.RouteConfiguration;

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
        return values.admit(resolveTemplate(navigationTarget)
                .orElseGet(navigationTarget::getSimpleName));
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
            for (HasElement target : ui.getInternals()
                    .getActiveRouterTargetsChain()) {
                if (target instanceof Component component) {
                    return tagFor(component.getClass());
                }
            }
            return tagForTemplate(
                    ui.getInternals().getActiveViewLocation().getPath());
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
