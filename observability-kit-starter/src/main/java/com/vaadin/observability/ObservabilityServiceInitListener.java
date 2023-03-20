package com.vaadin.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.di.Lookup;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;

public class ObservabilityServiceInitListener
        implements VaadinServiceInitListener {
    private static Logger getLogger() {
        return LoggerFactory.getLogger(ObservabilityServiceInitListener.class);
    }

    public void serviceInit(ServiceInitEvent serviceInitEvent) {
        getLogger().info("Initializing Observability Kit");

        ObservabilityClientConfigurer configurer = getObservabilityClientConfigurer(
                serviceInitEvent);

        serviceInitEvent.getSource().addUIInitListener(event -> {

            UI ui = event.getUI();
            ObservabilityHandler handler = ObservabilityHandler
                    .ensureInstalled(ui);

            ui.getChildren()
                    .filter(child -> (child instanceof ObservabilityClient))
                    .forEach(ui::remove);

            ObservabilityClient client = new ObservabilityClient(
                    handler.getId());
            configurer.configure(client);
            if (client.active) {
                ui.add(client);
            } else {
                getLogger().debug(
                        "Observability Client disabled for UI {} in Vaadin Session {}",
                        ui.getUIId(), ui.getSession().getSession().getId());
            }
        });
    }

    private static ObservabilityClientConfigurer getObservabilityClientConfigurer(
            ServiceInitEvent serviceInitEvent) {
        ObservabilityClientConfigurer configurer = serviceInitEvent.getSource()
                .getContext().getAttribute(Lookup.class)
                .lookup(ObservabilityClientConfigurer.class);
        if (configurer == null) {
            // TODO: get defaults from ApplicationConfiguration or DeploymentConfiguration
            configurer = ObservabilityServiceInitListener::defaultInstrumentation;
            getLogger().info("Using default front-end observability configuration");
        } else {
            getLogger().info("Using custom front-end observability configuration");
        }
        return configurer;
    }

    private static void defaultInstrumentation(
            ObservabilityClientConfiguration config) {
        config.active(true);
        config.traceDocumentLoad(true);
        config.traceUserInteraction(true);
        config.traceXMLHttpRequests(true);
        config.traceLongTask(true);
        config.traceErrors(true);
    }
}
