package com.vaadin.observability;

import dev.hilla.exception.EndpointException;
import dev.hilla.observability.ObservabilityEndpoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class ObservabilityEndpointTest {
    @Test
    public void export_invalidJson_throwsEndpointException() {
        String jsonString = "{}";

        ObservabilityEndpoint endpoint = new ObservabilityEndpoint();
        assertThrows(EndpointException.class, () -> endpoint.export(jsonString));
    }

    @Test
    public void export_tracesHandled() {
        String jsonString = """
            {
                "resourceSpans": [
                    {
                        "resource": {
                            "attributes": [ ],
                            "droppedAttributesCount": 0
                        },
                        "scopeSpans": [
                            {
                                "scope":
                                    {
                                        "name": "example-basic-tracer-node",
                                        "version": "1.0"
                                     },
                                "spans": [
                                    {
                                        "traceId": "b7e726b6155ac52912322123d2f31a2c",
                                        "spanId": "67279b5c43e6874c",
                                        "name": "documentLoad",
                                        "kind": 1,
                                        "startTimeUnixNano": 1674542404352000000,
                                        "endTimeUnixNano": 1674542405301000200,
                                        "attributes": [
                                            {
                                                "key": "stringAttribute",
                                                "value": { "stringValue": "some text" }
                                            },
                                            {
                                                "key": "intAttribute",
                                                "value": { "intValue": 123 }
                                            },
                                            {
                                                "key": "doubleAttribute",
                                                "value": { "doubleValue": 12.3 }
                                            },
                                            {
                                                "key": "booleanAttribute",
                                                "value": { "booleanValue": true }
                                            }
                                         ],
                                        "droppedAttributesCount": 0,
                                        "events": [],
                                        "droppedEventsCount": 0,
                                        "status": { "code": 0 },
                                        "links": [],
                                        "droppedLinksCount": 0
                                    }
                                ]
                            }
                        ]
                    }
                ]
            }""";

        ObservabilityEndpoint endpoint = new ObservabilityEndpoint();
        endpoint.export(jsonString);
    }
}
