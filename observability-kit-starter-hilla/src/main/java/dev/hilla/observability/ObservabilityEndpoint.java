/*-
 * Copyright (C) 2022 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package dev.hilla.observability;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.server.auth.AnonymousAllowed;
import dev.hilla.Endpoint;
import dev.hilla.exception.EndpointException;

@Endpoint
@AnonymousAllowed
public class ObservabilityEndpoint {
    private static Logger getLogger() {
        return LoggerFactory.getLogger(ObservabilityEndpoint.class);
    }

    public ObservabilityEndpoint() {}

    String id = null;

    transient BiConsumer<String, Map<String, Object>> exporter = (id, map) -> getLogger().error(
        "Observability agent is not running");

    public void export(String jsonString) {
        try {
            Map<String, Object> objectMap =
                new ObjectMapper().readerForMapOf(Object.class)
                    .readValue(jsonString);
            if (!objectMap.containsKey("resourceSpans")) {
                getLogger().error("Malformed traces message.");
                throw new EndpointException("Malformed traces message.");
            }
            getLogger().info(String.format("EXPORTER: %s", exporter));
            exporter.accept(id, objectMap);
        } catch (IOException e) {
            throw new EndpointException("Unexpected I/O error.");
        }
    }
}
