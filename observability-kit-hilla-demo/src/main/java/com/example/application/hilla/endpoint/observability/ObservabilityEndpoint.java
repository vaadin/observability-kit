/*-
 * Copyright (C) 2022 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.example.application.hilla.endpoint.observability;

import java.io.IOException;
import java.util.Map;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.vaadin.flow.server.auth.AnonymousAllowed;

import dev.hilla.Endpoint;
import dev.hilla.exception.EndpointException;

@Endpoint
@AnonymousAllowed
public class ObservabilityEndpoint {
    private static Logger getLogger() {
        return LoggerFactory.getLogger(ObservabilityEndpoint.class);
    }

    String id = null;

    transient BiConsumer<String, Map<String,Object>> exporter = (id, map) -> {
        getLogger().error("Observability agent is not running");
        getLogger().info(String.format("[%s]: %s", id, map));
    };

    public void export(String jsonString) {
        try {
            Map<String, Object> objectMap =
                    new ObjectMapper().readerForMapOf(Object.class)
                            .readValue(jsonString);
            if (!objectMap.containsKey("resourceSpans")) {
                getLogger().error("Malformed traces message.");
                throw new EndpointException("Malformed traces message.");
            }
            exporter.accept(id, objectMap);
        } catch(IOException e) {
            throw new EndpointException("Unexpected I/O error.");
        }
    }
}
