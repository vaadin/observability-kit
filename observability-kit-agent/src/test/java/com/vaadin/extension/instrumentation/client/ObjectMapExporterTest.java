package com.vaadin.extension.instrumentation.client;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.extension.conf.Configuration;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.vaadin.extension.conf.ConfigurationDefaults;
import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.extension.instrumentation.util.OpenTelemetryTestTools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

public class ObjectMapExporterTest extends AbstractInstrumentationTest {
    @BeforeAll
    public static void setup() {
        ConfigurationDefaults.spanExporter =
                OpenTelemetryTestTools.getSpanExporter();
    }

    @AfterAll
    public static void teardown() {
        ConfigurationDefaults.spanExporter = null;
    }

    @Test
    public void handleRequest_createsSpan() {
        try {
            String jsonString = """
                    {
                        "resourceSpans": [
                            {
                                "resource": {
                                    "attributes": [
                                        {
                                            "key": "service.name",
                                            "value": {"stringValue": "unknown_service"}
                                        },
                                        {
                                            "key": "telemetry.sdk.language",
                                            "value": {"stringValue": "webjs"}
                                        },
                                        {
                                            "key": "telemetry.sdk.name",
                                            "value": {"stringValue": "opentelemetry"}
                                        },
                                        {
                                            "key": "telemetry.sdk.version",
                                            "value": {"stringValue": "1.9.0"}
                                        }
                                     ],
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

            Map<String, Object> objectMap =
                    new ObjectMapper().readerForMapOf(Object.class)
                            .readValue(jsonString);

            new ObjectMapExporter().accept("foo", objectMap);

            SpanData span = getExportedSpan(0);
            assertEquals("Frontend: documentLoad", span.getName());
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    public void handleRequest_createsParentAndChildSpans() {
        try {
            String jsonString = """
                    {
                        "resourceSpans": [
                            {
                                "resource": {
                                    "attributes": [
                                        {
                                            "key": "service.name",
                                            "value": {"stringValue": "unknown_service"}
                                        },
                                        {
                                            "key": "telemetry.sdk.language",
                                            "value": {"stringValue": "webjs"}
                                        },
                                        {
                                            "key": "telemetry.sdk.name",
                                            "value": {"stringValue": "opentelemetry"}
                                        },
                                        {
                                            "key": "telemetry.sdk.version",
                                            "value": {"stringValue": "1.9.0"}
                                        }
                                     ],
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
                                            },
                                            {
                                                "traceId": "b7e726b6155ac52912322123d2f31a2c",
                                                "spanId": "52226f938f423db4",
                                                "parentSpanId": "67279b5c43e6874c",
                                                "name": "documentFetch",
                                                "kind": 1,
                                                "startTimeUnixNano": 1674542404351400000,
                                                "endTimeUnixNano": 1674542404377600000,
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

            Map<String, Object> objectMap =
                    new ObjectMapper().readerForMapOf(Object.class)
                            .readValue(jsonString);

            new ObjectMapExporter().accept("foo", objectMap);

            SpanData parentSpan = getExportedSpan(0);
            SpanData childSpan = getExportedSpan(1);
            assertEquals(parentSpan.getSpanId(), childSpan.getParentSpanId());
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    public void accept_emptyJson_exceptionIsThrown() {
        try {
            String jsonString = "{}";

            Map<String, Object> objectMap =
                    new ObjectMapper().readerForMapOf(Object.class)
                            .readValue(jsonString);

            assertThrows(RuntimeException.class,
                    () -> new ObjectMapExporter().accept("foo", objectMap));
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    public void spanToMetricsRespectedWhenDisabled() {
        try {
            // First, enable span-to-metrics and create a baseline metric
            ConfigurationMock.when(() -> Configuration.isSpanToMetricsEnabled())
                    .thenReturn(true);
            
            String jsonString = """
                    {
                        "resourceSpans": [
                            {
                                "resource": {
                                    "attributes": [
                                        {
                                            "key": "service.name",
                                            "value": {"stringValue": "test_service"}
                                        }
                                    ]
                                },
                                "scopeSpans": [
                                    {
                                        "scope": {
                                            "name": "test-scope"
                                        },
                                        "spans": [
                                            {
                                                "traceId": "12345678901234567890123456789012",
                                                "spanId": "1234567890123456",
                                                "name": "baseline-span",
                                                "kind": 1,
                                                "startTimeUnixNano": 1674542404352000000,
                                                "endTimeUnixNano": 1674542405301000200,
                                                "attributes": []
                                            }
                                        ]
                                    }
                                ]
                            }
                        ]
                    }
                    """;

            ObjectMapper objectMapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> objectMap = objectMapper.readValue(jsonString, Map.class);

            // Process the spans with enabled configuration to create the metric
            new ObjectMapExporter().accept("foo", objectMap);
            
            // Now get initial metrics state (after creating the metric)
            HistogramPointData initialSpanMetric = getLastHistogramMetricValue("vaadin.span.duration");
            long initialSpanCount = initialSpanMetric.getCount();
            double initialSpanSum = initialSpanMetric.getSum();
            
            // Now disable span-to-metrics
            ConfigurationMock.when(() -> Configuration.isSpanToMetricsEnabled())
                    .thenReturn(false);
            
            String jsonString2 = """
                    {
                        "resourceSpans": [
                            {
                                "resource": {
                                    "attributes": [
                                        {
                                            "key": "service.name",
                                            "value": {"stringValue": "test_service"}
                                        }
                                    ]
                                },
                                "scopeSpans": [
                                    {
                                        "scope": {
                                            "name": "test-scope"
                                        },
                                        "spans": [
                                            {
                                                "traceId": "12345678901234567890123456789013",
                                                "spanId": "1234567890123457",
                                                "name": "test-span-disabled",
                                                "kind": 1,
                                                "startTimeUnixNano": 1674542406352000000,
                                                "endTimeUnixNano": 1674542407301000200,
                                                "attributes": []
                                            }
                                        ]
                                    }
                                ]
                            }
                        ]
                    }
                    """;

            @SuppressWarnings("unchecked")
            Map<String, Object> objectMap2 = objectMapper.readValue(jsonString2, Map.class);

            // Process the spans with disabled configuration
            new ObjectMapExporter().accept("foo", objectMap2);

            // Verify no new span metrics were recorded
            HistogramPointData finalSpanMetric = getLastHistogramMetricValue("vaadin.span.duration");
            assertEquals(initialSpanCount, finalSpanMetric.getCount(), 
                    "Span count should not increase when span-to-metrics is disabled");
            assertEquals(initialSpanSum, finalSpanMetric.getSum(), 0, 
                    "Span sum should not increase when span-to-metrics is disabled");
            
        } catch (Exception e) {
            fail(e);
        } finally {
            // Re-enable for other tests
            ConfigurationMock.when(() -> Configuration.isSpanToMetricsEnabled())
                    .thenReturn(true);
        }
    }
}
