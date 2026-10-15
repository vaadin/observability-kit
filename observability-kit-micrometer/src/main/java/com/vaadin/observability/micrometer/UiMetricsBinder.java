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
import org.jspecify.annotations.Nullable;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.UIInitEvent;
import com.vaadin.flow.server.UIInitListener;
import com.vaadin.observability.micrometer.client.ClientMetricsBinder;
import com.vaadin.observability.micrometer.client.MetricsCollectorElement;
import com.vaadin.observability.micrometer.insights.ClientErrorCollector;
import com.vaadin.observability.micrometer.trace.ObservationNames;

/**
 * Tracks per-UI lifecycle metrics and attaches the per-UI client and poll
 * instrumentation to each newly initialized UI.
 */
final class UiMetricsBinder implements UIInitListener {

    private final MeterRegistry registry;
    private final ObservationRegistry observationRegistry;
    private final ObservabilitySettings settings;
    private final Counter created;
    private final AtomicLong active = new AtomicLong();
    private final ClientMetricsBinder clientBinder;

    /**
     * Whether the in-browser collector should gather an error message at all.
     * Both halves matter: the setting has to be on, and something has to be
     * there to retain it — with insights off, gathering a message would buffer
     * it in the tab and post it for nothing to read.
     */
    private final boolean collectErrorMessages;

    UiMetricsBinder(MeterRegistry registry,
            ObservationRegistry observationRegistry,
            ObservabilitySettings settings) {
        this(registry, observationRegistry, settings, null);
    }

    /**
     * @param clientErrors
     *            retains the detail of a browser error reported through the
     *            in-browser collector, or {@code null} to record only the
     *            {@code vaadin.client.errors} count
     */
    UiMetricsBinder(MeterRegistry registry,
            ObservationRegistry observationRegistry,
            ObservabilitySettings settings,
            @Nullable ClientErrorCollector clientErrors) {
        this.registry = registry;
        this.observationRegistry = observationRegistry;
        this.settings = settings;
        this.created = Counter.builder(MeterNames.UI_CREATED)
                .register(registry);
        Gauge.builder(MeterNames.UI_ACTIVE, active, AtomicLong::get)
                .register(registry);
        this.clientBinder = settings.isClient()
                ? new ClientMetricsBinder(registry, settings, clientErrors)
                : null;
        this.collectErrorMessages = clientErrors != null
                && settings.isInsightsDetails();
    }

    @Override
    public void uiInit(UIInitEvent event) {
        UI ui = event.getUI();
        if (settings.isUis()) {
            created.increment();
            active.incrementAndGet();
            ui.addDetachListener(e -> active.decrementAndGet());
        }
        if (settings.isTraces() && observationRegistry != null) {
            // Polls are the high-frequency UIDL noise; labelling them lets the
            // request span read "vaadin.request.poll" instead of an opaque
            // "vaadin.request.uidl".
            ui.addPollListener(e -> {
                RequestInteraction.mark(ObservationNames.INTERACTION_POLL);
                RequestUi.mark(e.getSource());
            });
        }
        if (clientBinder != null) {
            ui.add(new MetricsCollectorElement(clientBinder, settings,
                    collectErrorMessages));
        }
    }
}
