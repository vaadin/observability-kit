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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.router.RouteConfiguration;

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
        String template = resolveTemplate(navigationTarget)
                .orElseGet(navigationTarget::getSimpleName);
        if (seen.contains(template)) {
            return template;
        }
        if (seen.size() < limit) {
            seen.add(template);
            return template;
        }
        return MeterNames.ROUTE_OTHER;
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
