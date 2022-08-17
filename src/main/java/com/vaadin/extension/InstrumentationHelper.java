package com.vaadin.extension;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.RouteConfiguration;

import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.instrumenter.http.HttpRouteHolder;
import io.opentelemetry.instrumentation.api.instrumenter.http.HttpRouteSource;

import java.util.List;
import java.util.Optional;

public class InstrumentationHelper {

    public static void updateHttpRoute(UI ui) {
        List<HasElement> activeRouterTargetsChain = ui.getInternals().getActiveRouterTargetsChain();
        Optional<HasElement> maybeRouterTarget = activeRouterTargetsChain.size() > 0 ? Optional.of(activeRouterTargetsChain.get(0)) : Optional.empty();
        Optional<Component> maybeComponent = maybeRouterTarget.map(routerTarget -> routerTarget instanceof Component ? (Component) routerTarget : null);
        Optional<String> routeTemplate = maybeComponent.flatMap(component -> RouteConfiguration.forRegistry(ui.getInternals().getRouter().getRegistry()).getTemplate(component.getClass()));

        if (routeTemplate.isPresent()) {
            String route = "/" + routeTemplate.get();
            Context context = Context.current();
            HttpRouteHolder.updateHttpRoute(
                    context,
                    HttpRouteSource.NESTED_CONTROLLER,
                    route);
        }
    }
}
