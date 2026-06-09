/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring.tests;

import java.util.Collection;
import java.util.Collections;

import com.vaadin.flow.spring.VaadinMVCWebAppInitializer;

/**
 * Bootstraps the Spring MVC context with {@link AppConfiguration}.
 */
public class AppWebAppInitializer extends VaadinMVCWebAppInitializer {

    @Override
    protected Collection<Class<?>> getConfigurationClasses() {
        return Collections.singletonList(AppConfiguration.class);
    }
}
