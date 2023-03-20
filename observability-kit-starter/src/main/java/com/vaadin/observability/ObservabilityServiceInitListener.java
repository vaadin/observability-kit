package com.vaadin.observability;

import java.io.IOException;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.internal.UsageStatistics;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.UIInitEvent;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.pro.licensechecker.BuildType;
import com.vaadin.pro.licensechecker.LicenseChecker;

public class ObservabilityServiceInitListener
        implements VaadinServiceInitListener {

    static final String PROPERTIES_RESOURCE = "observability-kit.properties";

    static final String VERSION_PROPERTY = "observability-kit.version";

    static final String PRODUCT_NAME = "vaadin-observability-kit";

    private static Logger getLogger() {
        return LoggerFactory.getLogger(ObservabilityServiceInitListener.class);
    }

    @Override
    public void serviceInit(ServiceInitEvent serviceInitEvent) {
        getLogger().info("Initializing Observability Kit");

        final var service = serviceInitEvent.getSource();

        checkLicense(service);

        service.addUIInitListener(this::injectObservabilityClient);
    }

    private void checkLicense(VaadinService service) {
        final var properties = loadAllProperties(PROPERTIES_RESOURCE);
        final var version = properties.getProperty(VERSION_PROPERTY);

        UsageStatistics.markAsUsed(PRODUCT_NAME, version);

        // Check the license at runtime if in development mode
        if (!service.getDeploymentConfiguration().isProductionMode()) {
            // Using a null BuildType to allow trial licensing builds
            // The variable is defined to avoid method signature ambiguity
            BuildType buildType = null;
            LicenseChecker.checkLicense(PRODUCT_NAME, version, buildType);
        }
    }

    private void injectObservabilityClient(UIInitEvent event) {
        UI ui = event.getUI();

        ObservabilityHandler handler = ObservabilityHandler.ensureInstalled(ui);

        ui.getChildren().filter(child -> (child instanceof ObservabilityClient))
                .forEach(ui::remove);

        ObservabilityClient client = new ObservabilityClient();
        client.getElement().setProperty("instanceId", handler.getId());
        ui.add(client);
    }

    static Properties loadAllProperties(String propertiesResource) {
        final var cl = ObservabilityServiceInitListener.class.getClassLoader();
        try (final var stream = cl.getResourceAsStream(propertiesResource)) {
            final var properties = new Properties();
            properties.load(stream);
            return properties;
        } catch (NullPointerException | IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
