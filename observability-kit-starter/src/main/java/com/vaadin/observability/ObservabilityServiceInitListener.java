package com.vaadin.observability;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dependency.NpmPackage;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;

@NpmPackage(value = "lit", version = "")
@NpmPackage(value = "@opentelemetry/sdk-trace-web", version = "1.8.0")
@NpmPackage(value = "@opentelemetry/instrumentation", version = "0.35.0")
@NpmPackage(value = "@opentelemetry/instrumentation-document-load",
        version = "0.31.0")
@NpmPackage(value = "@opentelemetry/instrumentation-user-interaction",
        version = "0.32.0")
@NpmPackage(value = "@opentelemetry/instrumentation-xml-http-request",
        version = "0.34.0")
@NpmPackage(value = "@opentelemetry/instrumentation-long-task",
        version = "0.32.0")
@NpmPackage(value = "@opentelemetry/exporter-trace-otlp-http",
        version = "0.35.0")
public class ObservabilityServiceInitListener
        implements VaadinServiceInitListener {
    private static Logger getLogger() {
        return LoggerFactory.getLogger(ObservabilityServiceInitListener.class);
    }

    public void serviceInit(ServiceInitEvent serviceInitEvent) {
        getLogger().info("Initializing Observability Kit");

        serviceInitEvent.getSource().addUIInitListener(event -> {
            UI ui = event.getUI();

            ObservabilityHandler handler =
                    ObservabilityHandler.ensureInstalled(ui);

            Optional<Component> existingClient = ui.getChildren()
                    .filter(child -> (child instanceof ObservabilityClient))
                    .findFirst();
            if (existingClient.isPresent()) {
                ui.remove(existingClient.get());
            } else {
                ObservabilityClient client = new ObservabilityClient();
                client.getElement().setProperty("instanceId", handler.getId());
                ui.add(client);
            }
        });
    }
}
