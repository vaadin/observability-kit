/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.internal.StringUtil;
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

    private final transient ClientMetricsBinder binder;
    private final transient ClientRateLimiter limiter;

    public MetricsCollectorElement(ClientMetricsBinder binder,
            ObservabilitySettings settings) {
        this.binder = binder;
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
        ensureClientLoaded(event.getUI());
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

    private static void ensureClientLoaded(UI ui) {
        if (ComponentUtil.getData(ui, CLIENT_INIT_KEY) != null) {
            return;
        }
        ComponentUtil.setData(ui, CLIENT_INIT_KEY, Boolean.TRUE);
        ClassLoader loader = MetricsCollectorElement.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(CLIENT_RESOURCE)) {
            if (in == null) {
                LoggerFactory.getLogger(MetricsCollectorElement.class).warn(
                        "observability-kit client resource not found: {}",
                        CLIENT_RESOURCE);
                return;
            }
            String js = StringUtil.removeComments(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    true);
            ui.getPage().executeJs(js);
        } catch (IOException e) {
            LoggerFactory.getLogger(MetricsCollectorElement.class)
                    .warn("Could not load observability-kit client code", e);
        }
    }
}
