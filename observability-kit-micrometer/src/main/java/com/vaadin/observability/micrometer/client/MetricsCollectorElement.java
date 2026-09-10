/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.client;

import java.util.List;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.UI;
import com.vaadin.observability.micrometer.ClientResourceLoader;
import com.vaadin.observability.micrometer.ObservabilitySettings;

/**
 * Hidden helper component attached to each UI when client metrics are enabled.
 * Exposes a {@link ClientCallable} that receives batches of
 * {@link ClientSample} from the in-browser collector. Loads the client JS on
 * first attach and re-attaches itself to its UI if removed.
 */
@Tag("vaadin-metrics-collector")
public final class MetricsCollectorElement extends Component {

    private static final String CLIENT_INIT_KEY = "vaadinMetricsClientInitialized";
    private static final String CLIENT_RESOURCE = "META-INF/frontend/VaadinMetricsClient.js";

    /**
     * Tells the in-browser collector whether to gather the message of an error
     * it reports. Decided on the server rather than left to its retention rule:
     * a message nothing would keep should not be buffered in the tab and posted
     * either, since the browser holds its buffer in {@code sessionStorage}
     * across a reload and an outage.
     */
    private static final String DETAILS_PRELUDE = "window.__vaadinMicrometerDetails=%s;";

    private final transient ClientMetricsBinder binder;
    private final transient ClientRateLimiter limiter;
    private final boolean collectErrorMessages;

    /**
     * @deprecated use
     *             {@link #MetricsCollectorElement(ClientMetricsBinder, ObservabilitySettings, boolean)},
     *             which says whether the browser should gather an error's
     *             message and function name. This overload never gathers
     *             either, so an application wiring the element up by hand
     *             silently loses the detail {@code insights-details} asks for.
     */
    @Deprecated
    public MetricsCollectorElement(ClientMetricsBinder binder,
            ObservabilitySettings settings) {
        this(binder, settings, false);
    }

    /**
     * @param collectErrorMessages
     *            whether the in-browser collector should gather the message of
     *            an error it reports — the {@code insights-details} setting
     *            <em>and</em> something on the server willing to retain one
     */
    public MetricsCollectorElement(ClientMetricsBinder binder,
            ObservabilitySettings settings, boolean collectErrorMessages) {
        this.binder = binder;
        this.collectErrorMessages = collectErrorMessages;
        this.limiter = new ClientRateLimiter(
                settings.getClientRatePerSession());
        getElement().getStyle().set("display", "none");
        addDetachListener(event -> {
            UI ui = event.getUI();
            if (ui != null && !ui.isClosing()) {
                ui.access(() -> ui.add(this));
            }
        });
    }

    @Override
    protected void onAttach(AttachEvent event) {
        ClientResourceLoader.loadOnce(event.getUI(), CLIENT_INIT_KEY,
                CLIENT_RESOURCE, MetricsCollectorElement.class,
                DETAILS_PRELUDE.formatted(collectErrorMessages));
    }

    @ClientCallable
    public void recordSamples(List<ClientSample> samples) {
        if (binder == null || samples == null || samples.isEmpty()) {
            return;
        }
        int granted = limiter.tryAcquire(samples.size());
        if (granted < samples.size()) {
            binder.recordThrottled(samples.size() - granted);
            if (granted == 0) {
                return;
            }
            binder.ingest(samples.subList(0, granted));
        } else {
            binder.ingest(samples);
        }
    }
}
