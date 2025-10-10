/*
 * Copyright (C) 2025 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 *
 */

package com.vaadin.hilla.observability;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
class ObservabilityKitConfiguration {

    @Bean
    ObservabilityEndpoint observabilityEndpoint() {
        return new ObservabilityEndpoint();
    }
}
