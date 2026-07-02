/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;

/**
 * Exposes the Vaadin view context of the request currently being handled on
 * this thread, so instrumentation living outside the Vaadin runtime (for
 * example a JDBC {@code DataSource} proxy) can attribute its measurements to
 * the view that triggered them.
 * <p>
 * The route is the template resolved by {@link RouteTagResolver} during
 * navigation and is stored as a UI attribute that survives past the navigation
 * event (unlike the transient timing state in {@link NavigationMetricsBinder}).
 * Because Vaadin binds {@link UI#getCurrent()} to the request-handling thread,
 * code running on that thread can read it back here.
 */
public final class VaadinTelemetryContext {

    static final String CURRENT_ROUTE_KEY = VaadinTelemetryContext.class
            .getName() + ".currentRoute";

    private VaadinTelemetryContext() {
    }

    /**
     * Records the route template the current UI last navigated to. Called from
     * {@link NavigationMetricsBinder} after navigation completes.
     */
    static void setCurrentRoute(UI ui, String route) {
        if (ui != null) {
            ComponentUtil.setData(ui, CURRENT_ROUTE_KEY, route);
        }
    }

    /**
     * Returns the route template of the view bound to the current thread, or
     * {@link MeterNames#ROUTE_UNKNOWN} when there is no current UI or no
     * navigation has been recorded yet.
     *
     * @return the current route tag value, never {@code null}
     */
    public static String currentRoute() {
        UI ui = UI.getCurrent();
        if (ui == null) {
            return MeterNames.ROUTE_UNKNOWN;
        }
        Object route = ComponentUtil.getData(ui, CURRENT_ROUTE_KEY);
        return route instanceof String r ? r : MeterNames.ROUTE_UNKNOWN;
    }
}
