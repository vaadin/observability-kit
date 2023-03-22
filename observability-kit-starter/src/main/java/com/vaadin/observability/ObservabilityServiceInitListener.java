package com.vaadin.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;

public class ObservabilityServiceInitListener
        implements VaadinServiceInitListener {
    private static Logger getLogger() {
        return LoggerFactory.getLogger(ObservabilityServiceInitListener.class);
    }

    public void serviceInit(ServiceInitEvent serviceInitEvent) {
        getLogger().info("Initializing Observability Kit");

        serviceInitEvent.getSource().addUIInitListener(event -> {
            UI ui = event.getUI();

            ObservabilityHandler handler = ObservabilityHandler
                    .ensureInstalled(ui);

            ui.getChildren()
                    .filter(child -> (child instanceof ObservabilityClient))
                    .forEach(ui::remove);

            ObservabilityClient client = new ObservabilityClient();
            client.getElement().setProperty("instanceId", handler.getId());
            ui.add(client);
        });
    }
}
