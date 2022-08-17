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
import io.opentelemetry.instrumentation.api.instrumenter.http.HttpRouteHolder;
import io.opentelemetry.instrumentation.api.instrumenter.http.HttpRouteSource;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;

import java.util.List;
import java.util.Optional;

public class InstrumentationHelper {

    public static Tracer getTracer() {
        return GlobalOpenTelemetry.getTracer("com.vaadin.observability.instrumentation", "1.0-alpha");
    }

    public static void updateHttpRoute(UI ui) {
        // Update root span name and http.route attribute to contain route template
        List<HasElement> activeRouterTargetsChain = ui.getInternals().getActiveRouterTargetsChain();

        Optional<String> routeTemplate = (activeRouterTargetsChain.size() > 0 ? Optional.of(activeRouterTargetsChain.get(0)) : Optional.empty())
                .map(routerTarget -> routerTarget instanceof Component ? (Component) routerTarget : null)
                .flatMap(component -> RouteConfiguration.forRegistry(ui.getInternals().getRouter().getRegistry()).getTemplate(component.getClass()));

        if (routeTemplate.isPresent()) {
            String route = "/" + routeTemplate.get();
            Context context = Context.current();
            HttpRouteHolder.updateHttpRoute(
                    context,
                    HttpRouteSource.NESTED_CONTROLLER,
                    route);
        }
        // Update http.target to contain actual path with params
        Span localRootSpan = LocalRootSpan.fromContextOrNull(Context.current());
        if (localRootSpan != null) {
            String locationPath = "/" + ui.getInternals().getActiveViewLocation().getPath();
            localRootSpan.setAttribute(SemanticAttributes.HTTP_TARGET, locationPath);
        }
    }
}
