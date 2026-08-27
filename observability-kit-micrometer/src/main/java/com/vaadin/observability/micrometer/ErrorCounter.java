/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;

/**
 * Records {@link MeterNames#ERRORS}, the single counter for every server-side
 * failure the kit observes.
 * <p>
 * Errors reach the kit from two places — exceptions escaping request handling
 * ({@link RequestMetricsBinder}) and exceptions Flow routes to the session
 * error handler ({@link ErrorMetricsBinder}) — and both must produce the same
 * tag keys: Prometheus rejects same-named meters whose tag-key sets differ. The
 * counter therefore always carries {@code exception}, {@code route} and
 * {@code component}, falling back to {@code _unknown} for whatever cannot be
 * resolved at the point the failure surfaced.
 * <p>
 * All three tag values are capped at
 * {@link ObservabilitySettings#getRouteCardinalityLimit()}, and they multiply
 * with each other, so one instance is shared by both writers: a cap enforced
 * per instance would mean two budgets for one meter, and the same route or
 * exception type could be itself on one path and {@code _other} on the other.
 * The instance is created only when {@link ObservabilitySettings#isErrors()} is
 * on; there is nothing to count otherwise.
 */
final class ErrorCounter {

    private final MeterRegistry registry;
    private final RouteTagResolver routes;
    private final BoundedTagValues components;
    private final BoundedTagValues exceptions;

    ErrorCounter(MeterRegistry registry, ObservabilitySettings settings) {
        this.registry = registry;
        this.routes = new RouteTagResolver(settings.getRouteCardinalityLimit());
        // Components and exception types are bounded by the same limit as
        // routes: all three derive from application classes, and a single knob
        // is easier to reason about than three that would in practice always be
        // set together.
        this.components = new BoundedTagValues(
                settings.getRouteCardinalityLimit(),
                MeterNames.COMPONENT_OTHER);
        this.exceptions = new BoundedTagValues(
                settings.getRouteCardinalityLimit(),
                MeterNames.EXCEPTION_OTHER);
    }

    /**
     * Increments the error counter for {@code error}, attributing it to the
     * component it was thrown for and to the view that component lives in.
     *
     * @param error
     *            the failure to count, ignored when {@code null}
     * @param component
     *            the component the failure is attributed to, or {@code null}
     *            when unknown
     */
    void increment(Throwable error, Component component) {
        if (error == null) {
            return;
        }
        Counter.builder(MeterNames.ERRORS)
                .tag(MeterNames.TAG_EXCEPTION, exceptionTag(error))
                .tag(MeterNames.TAG_ROUTE,
                        routes.tagForActiveRoute(ui(component)))
                .tag(MeterNames.TAG_COMPONENT, componentTag(component))
                .register(registry).increment();
    }

    /**
     * The UI to resolve the route from: the one the failing component is
     * attached to, since that is the UI the failure actually belongs to. The UI
     * bound to this thread is only the fallback — it is ambient state that can
     * name a different UI of the session than the one the error was thrown for
     * (a state-tree execution flushed while another UI is current), and it is
     * all there is when the error event carries no component or the component
     * was already detached.
     */
    private static UI ui(Component component) {
        if (component != null) {
            UI attached = component.getUI().orElse(null);
            if (attached != null) {
                return attached;
            }
        }
        return UI.getCurrent();
    }

    private String exceptionTag(Throwable error) {
        Class<?> type = error.getClass();
        String name = type.getSimpleName();
        // Same reasoning as for components: a lambda or an anonymous Throwable
        // subclass has an empty simple name.
        return exceptions.admit(name.isEmpty() ? type.getName() : name);
    }

    private String componentTag(Component component) {
        if (component == null) {
            return MeterNames.COMPONENT_UNKNOWN;
        }
        Class<?> type = component.getClass();
        String name = type.getSimpleName();
        // Anonymous classes have an empty simple name; the binary name at
        // least says which class they were declared in.
        return components.admit(name.isEmpty() ? type.getName() : name);
    }
}
