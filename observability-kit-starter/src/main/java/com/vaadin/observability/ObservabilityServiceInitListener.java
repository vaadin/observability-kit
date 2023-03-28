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
            if (configurer != null) {
                configurer.configure(client);
            }
            if (client.isEnabled()) {
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
        if (configurer != null) {
            getLogger().info(
                    "Applying custom front-end observability configuration");
        }
        return configurer;
    }

}
