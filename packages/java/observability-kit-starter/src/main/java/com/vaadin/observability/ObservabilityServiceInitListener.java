/*-
 * Copyright (C) 2022 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.di.Lookup;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;

/**
 * This is a service initialization listener that installs the Frontend
 * Observability module into a Vaadin UI.
 */
public class ObservabilityServiceInitListener
        implements VaadinServiceInitListener {

    static final String CONFIG_PROPERTY_PREFIX = "otel.instrumentation.vaadin.frontend.";

    private static Logger getLogger() {
        return LoggerFactory.getLogger(ObservabilityServiceInitListener.class);
    }

    /**
     * Adds the current parent trace and span IDs into a meta tag on the page
     * and ensures that an ObservabilityHandler is installed on the UI.
     *
     * @param serviceInitEvent the service initialization event
     */
    public void serviceInit(ServiceInitEvent serviceInitEvent) {
        getLogger().info("Initializing Observability Kit");

        serviceInitEvent.addIndexHtmlRequestListener((response) -> {
            Span span = Span.current();
            SpanContext spanContext = span.getSpanContext();
            String traceParent =
                    "00-" + spanContext.getTraceId() + "-" + spanContext.getSpanId() + "-01";

            response.getDocument().head().prependElement("meta")
                    .attr("name", "traceparent")
                    .attr("content", traceParent);
        });

        ObservabilityClientConfigurer configurer =
                getObservabilityClientConfigurer(serviceInitEvent);

        serviceInitEvent.getSource().addUIInitListener(event -> {

            UI ui = event.getUI();
            ObservabilityHandler handler = ObservabilityHandler
                    .ensureInstalled(ui);

            ui.getChildren()
                    .filter(child -> (child instanceof ObservabilityClient))
                    .forEach(ui::remove);

            ObservabilityClient client = new ObservabilityClient(
                    handler.getId());
            if (configurer != null) {
                configurer.configure(client);
            } else {
                applyDefaultConfiguration(handler, client);
            }
            if (client.isEnabled()) {
                client.getElement().setProperty("instanceId", handler.getId());
                client.getElement().setProperty("serviceName",
                        handler.getConfigProperty("otel.service.name"));
                client.getElement().setProperty("serviceVersion",
                        handler.getConfigProperty("otel.service.version"));
                ui.add(client);
            } else {
                getLogger().debug(
                        "Observability Client disabled for UI {} in Vaadin Session {}",
                        ui.getUIId(), ui.getSession().getSession().getId());
            }
        });
    }



    private static void applyDefaultConfiguration(ObservabilityHandler handler,
            ObservabilityClient client) {
        if (isConfigurationFlagEnabled(handler, "enabled", true)) {
            client.setDocumentLoadEnabled(
                    isConfigurationFlagEnabled(handler, "document-load", true));
            client.setUserInteractionEnabled(
                    isConfigurationFlagEnabled(handler, "user-interaction", true));
            client.setXMLHttpRequestEnabled(
                    isConfigurationFlagEnabled(handler, "xml-http-request", true));
            client.setLongTaskEnabled(
                    isConfigurationFlagEnabled(handler, "long-task", true));
            client.setFrontendErrorEnabled(
                    isConfigurationFlagEnabled(handler, "frontend-error", true));
            // ObservabilityClient should not be added if all instrumentation
            // are disabled
            client.setEnabled(client.isDocumentLoadEnabled()
                    || client.isUserInteractionEnabled()
                    || client.isXMLHttpRequestEnabled()
                    || client.isLongTaskEnabled()
                    || client.isFrontendErrorEnabled());
        } else {
            client.setEnabled(false);
        }
    }

    private static boolean isConfigurationFlagEnabled(ObservabilityHandler handler,
                                                      String key, boolean defaultValue) {
        String value = handler.getConfigProperty(CONFIG_PROPERTY_PREFIX + key);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return defaultValue;
    }

    private static ObservabilityClientConfigurer getObservabilityClientConfigurer(
            ServiceInitEvent serviceInitEvent) {
        ObservabilityClientConfigurer configurer = serviceInitEvent.getSource()
                .getContext().getAttribute(Lookup.class)
                .lookup(ObservabilityClientConfigurer.class);
        if (configurer != null) {
            getLogger().info(
                    "Applying custom front-end observability configuration");
        }
        return configurer;
    }

}
