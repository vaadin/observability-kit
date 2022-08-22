package com.vaadin.extension;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.RouteConfiguration;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.instrumenter.LocalRootSpan;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;

import java.util.List;
import java.util.Optional;

public class InstrumentationHelper {

    public static Tracer getTracer() {
        return GlobalOpenTelemetry.getTracer(
                "com.vaadin.observability.instrumentation", "1.0-alpha");
    }

    public static void updateHttpRoute(UI ui) {
        Span localRootSpan = LocalRootSpan.fromContextOrNull(Context.current());

        if (localRootSpan == null) {
            return;
        }

        Optional<String> routeTemplate = getActiveRouteTemplate(ui);

        if (routeTemplate.isPresent()) {
            // Update root span name to contain the route.
            // Not using HttpRouteHolder.updateHttpRoute here, as that uses
            // additional logic that might prevent an update, for example
            // when the route was already updated by a previous instrumentation.
            // Updating the root span directly allows us to cover the case
            // where a request is made against the current route, but the
            // request actually navigates to a new route, in which case the
            // root span should show the new route rather than the previous one.
            String route = "/" + routeTemplate.get();
            localRootSpan.updateName(route);
            localRootSpan.setAttribute(SemanticAttributes.HTTP_ROUTE, route);
        }
        // Update http.target to contain actual path with params
        String locationPath = "/"
                + ui.getInternals().getActiveViewLocation().getPath();
        localRootSpan.setAttribute(SemanticAttributes.HTTP_TARGET,
                locationPath);
    }

    /**
     * Get the route template for the currently active view.
     *
     * @param ui
     *            Current UI to get active view path for.
     * @return view template if available, else {@link Optional#empty()}
     */
    public static Optional<String> getActiveRouteTemplate(UI ui) {
        // Update root span name and http.route attribute to contain route
        // template
        List<HasElement> activeRouterTargetsChain = ui.getInternals()
                .getActiveRouterTargetsChain();
        if (activeRouterTargetsChain.isEmpty()) {
            return Optional.empty();
        }

        return RouteConfiguration.forSessionScope().getTemplate(
                ((Component) activeRouterTargetsChain.get(0)).getClass());
    }

    public static void handleException(Span span, Throwable throwable) {
        if (throwable != null) {
            // This will actually mark the span as having an exception which
            // shows on the dataUI
            span.setStatus(StatusCode.ERROR, throwable.getMessage());
            // Add log trace of exception.
            span.recordException(throwable);
        }
    }
}
