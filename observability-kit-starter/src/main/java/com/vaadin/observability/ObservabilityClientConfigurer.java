package com.vaadin.observability;

import java.io.Serializable;
import java.util.ServiceLoader;

import com.vaadin.flow.server.VaadinRequest;

/**
 * Configurator for front-end observability.
 *
 * Implementors of this class can configure the behaviour of the front-end
 * observability for a UI instance, representing a single browser window or tab.
 *
 * The configurator is executed during UI initialization, before the navigation
 * to the view happens. At this stage the
 * {@link com.vaadin.flow.server.VaadinService} is fully initialized and the
 * HTTP request is available and can be accessed through the
 * {@link VaadinRequest#getCurrent()} method.
 *
 * Configurator instances are by default discovered and instantiated using
 * {@link ServiceLoader}. This means that all implementations must have a
 * zero-argument constructor and the fully qualified name of the implementation
 * class must be listed on a separate line in a
 * META-INF/services/com.vaadin.observability.ObservabilityClientConfigurer file
 * present in the jar file containing the implementation class.
 * <p>
 * Integrations for specific runtime environments, such as OSGi or Spring, might
 * also provide other ways of discovering listeners.
 */
@FunctionalInterface
public interface ObservabilityClientConfigurer extends Serializable {
    /**
     * Configures front-end observability for a UI instance.
     *
     * By default, all instrumentations are active.
     *
     * @param config
     *            configuration instance to tune observability settings.
     */
    void configure(ObservabilityClientConfiguration config);
}
