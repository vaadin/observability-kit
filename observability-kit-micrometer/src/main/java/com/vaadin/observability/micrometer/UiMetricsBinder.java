/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.UIInitEvent;
import com.vaadin.flow.server.UIInitListener;
import com.vaadin.observability.micrometer.trace.ObservationNames;

/**
 * Tracks per-UI lifecycle metrics and, when navigation metrics are enabled,
 * attaches a {@link NavigationMetricsBinder} to each newly initialized UI.
 */
final class UiMetricsBinder implements UIInitListener {

    private final MeterRegistry registry;
    private final ObservationRegistry observationRegistry;
    private final ObservabilitySettings settings;
    private final Counter created;
    private final AtomicLong active = new AtomicLong();
    private final NavigationMetricsBinder navigationBinder;

    UiMetricsBinder(MeterRegistry registry,
            ObservationRegistry observationRegistry,
            ObservabilitySettings settings) {
        this.registry = registry;
        this.observationRegistry = observationRegistry;
        this.settings = settings;
        this.created = Counter.builder(MeterNames.UI_CREATED)
                .register(registry);
        Gauge.builder(MeterNames.UI_ACTIVE, active, AtomicLong::get)
                .register(registry);
        this.navigationBinder = settings.isNavigation()
                ? new NavigationMetricsBinder(registry, observationRegistry,
                        settings,
                        new RouteTagResolver(
                                settings.getRouteCardinalityLimit()))
                : null;
    }

    @Override
    public void uiInit(UIInitEvent event) {
        UI ui = event.getUI();
        if (settings.isUis()) {
            created.increment();
            active.incrementAndGet();
            ui.addDetachListener(e -> active.decrementAndGet());
        }
        if (navigationBinder != null) {
            ui.addBeforeEnterListener(navigationBinder);
            ui.addAfterNavigationListener(navigationBinder);
        }
        if (settings.isTraces() && observationRegistry != null) {
            // Polls are the high-frequency UIDL noise; labelling them lets the
            // request span read "vaadin.request.poll" instead of an opaque
            // "vaadin.request.uidl".
            ui.addPollListener(e -> RequestInteraction
                    .mark(ObservationNames.INTERACTION_POLL));
        }
        // TODO(P2-T9): wire client metrics collector when isClient()
    }
}
