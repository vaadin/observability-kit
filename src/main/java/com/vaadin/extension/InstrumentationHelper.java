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
        return GlobalOpenTelemetry.getTracer("com.vaadin.observability.instrumentation", "1.0-alpha");
    }

    public static void updateHttpRoute(UI ui) {
        Span localRootSpan = LocalRootSpan.fromContextOrNull(Context.current());

        if (localRootSpan == null) {
            return;
        }

        // Update root span name and http.route attribute to contain route template
        List<HasElement> activeRouterTargetsChain = ui.getInternals().getActiveRouterTargetsChain();
        HasElement routerTarget = activeRouterTargetsChain.size() > 0 ? activeRouterTargetsChain.get(0) : null;

        Optional<String> routeTemplate = Optional.ofNullable(routerTarget)
                .flatMap(component -> RouteConfiguration.forSessionScope().getTemplate(((Component)component).getClass()));

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
        String locationPath = "/" + ui.getInternals().getActiveViewLocation().getPath();
        localRootSpan.setAttribute(SemanticAttributes.HTTP_TARGET, locationPath);
    }
}
