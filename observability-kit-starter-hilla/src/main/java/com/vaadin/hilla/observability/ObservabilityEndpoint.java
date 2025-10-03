/*-
 * Copyright (C) 2024 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.hilla.observability;

import java.util.Map;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.hilla.Endpoint;
import com.vaadin.hilla.exception.EndpointException;

@Endpoint
@AnonymousAllowed
public class ObservabilityEndpoint {
    private static BiConsumer<String, Map<String, Object>> exporter = (id,
            map) -> getLogger().error("Observability agent is not running");

    private static Logger getLogger() {
        return LoggerFactory.getLogger(ObservabilityEndpoint.class);
    }

    public void export(String jsonString) {
        var objectMap = new ObjectMapper().readerForMapOf(Object.class)
                .<Map<String, Object>> readValue(jsonString);
        if (!objectMap.containsKey("resourceSpans")) {
            getLogger().error("Malformed traces message.");
            throw new EndpointException("Malformed traces message.");
        }
        exporter.accept(null, objectMap);
    }
}
