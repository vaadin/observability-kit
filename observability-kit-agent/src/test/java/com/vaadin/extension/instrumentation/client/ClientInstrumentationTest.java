package com.vaadin.extension.instrumentation.client;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class ClientInstrumentationTest extends AbstractInstrumentationTest {
    @Test
    public void handleRequest_createsSpan() {
        try {
            // @formatter:off
            String jsonString = "{\"resourceSpans\":[{\"resource\":{\"attributes\":[{\"key\":\"service.name\",\"value\":{\"stringValue\":\"unknown_service\"}},{\"key\":\"telemetry.sdk.language\",\"value\":{\"stringValue\":\"webjs\"}},{\"key\":\"telemetry.sdk.name\",\"value\":{\"stringValue\":\"opentelemetry\"}},{\"key\":\"telemetry.sdk.version\",\"value\":{\"stringValue\":\"1.9.0\"}}],\"droppedAttributesCount\":0},\"scopeSpans\":[{\"scope\":{\"name\":\"@opentelemetry/instrumentation-document-load\",\"version\":\"0.31.0\"},\"spans\":[{\"traceId\":\"b7e726b6155ac52912322123d2f31a2c\",\"spanId\":\"67279b5c43e6874c\",\"name\":\"documentLoad\",\"kind\":1,\"startTimeUnixNano\":1674542404352000000,\"endTimeUnixNano\":1674542405301000200,\"attributes\":[{\"key\":\"component\",\"value\":{\"stringValue\":\"document-load\"}},{\"key\":\"http.url\",\"value\":{\"stringValue\":\"http://localhost:8080/login\"}},{\"key\":\"http.user_agent\",\"value\":{\"stringValue\":\"Mozilla/5.0(WindowsNT10.0;Win64;x64)AppleWebKit/537.36(KHTML,likeGecko)Chrome/109.0.0.0Safari/537.36\"}}],\"droppedAttributesCount\":0,\"events\":[{\"attributes\":[],\"name\":\"fetchStart\",\"timeUnixNano\":1674542404352000000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"unloadEventStart\",\"timeUnixNano\":1674542404385100000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"unloadEventEnd\",\"timeUnixNano\":1674542404385700000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"domInteractive\",\"timeUnixNano\":1674542404468400000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"domContentLoadedEventStart\",\"timeUnixNano\":1674542405284400000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"domContentLoadedEventEnd\",\"timeUnixNano\":1674542405287600000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"domComplete\",\"timeUnixNano\":1674542405293900000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"loadEventStart\",\"timeUnixNano\":1674542405300900000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"loadEventEnd\",\"timeUnixNano\":1674542405301000200,\"droppedAttributesCount\":0}],\"droppedEventsCount\":0,\"status\":{\"code\":0},\"links\":[],\"droppedLinksCount\":0}]}]}]}";
            // @formatter:on

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonString);

            String observabilityClientId = UUID.randomUUID().toString();
            ClientInstrumentation.MethodAdvice.onEnter(root,
                    observabilityClientId);

            SpanData span = getExportedSpan(0);
            assertEquals("Client: documentLoad", span.getName());
            assertEquals(observabilityClientId,
                    span.getAttributes().get(AttributeKey.stringKey(
                            ClientInstrumentation.MethodAdvice.FRONTEND_ID)),
                    "Missing or invalid observability client identifier on span attributes");
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    public void handleRequest_createsParentAndChildSpans() {
        try {
            // @formatter:off
            String jsonString = "{\"resourceSpans\":[{\"resource\":{\"attributes\":[{\"key\":\"service.name\",\"value\":{\"stringValue\":\"unknown_service\"}},{\"key\":\"telemetry.sdk.language\",\"value\":{\"stringValue\":\"webjs\"}},{\"key\":\"telemetry.sdk.name\",\"value\":{\"stringValue\":\"opentelemetry\"}},{\"key\":\"telemetry.sdk.version\",\"value\":{\"stringValue\":\"1.9.0\"}}],\"droppedAttributesCount\":0},\"scopeSpans\":[{\"scope\":{\"name\":\"@opentelemetry/instrumentation-document-load\",\"version\":\"0.31.0\"},\"spans\":[{\"traceId\":\"b7e726b6155ac52912322123d2f31a2c\",\"spanId\":\"52226f938f423db4\",\"parentSpanId\":\"67279b5c43e6874c\",\"name\":\"documentFetch\",\"kind\":1,\"startTimeUnixNano\":1674542404351400000,\"endTimeUnixNano\":1674542404377600000,\"attributes\":[{\"key\":\"component\",\"value\":{\"stringValue\":\"document-load\"}},{\"key\":\"http.url\",\"value\":{\"stringValue\":\"http://localhost:8080/login\"}},{\"key\":\"http.response_content_length\",\"value\":{\"intValue\":0}}],\"droppedAttributesCount\":0,\"events\":[{\"attributes\":[],\"name\":\"fetchStart\",\"timeUnixNano\":1674542404351400000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"domainLookupStart\",\"timeUnixNano\":1674542404351400000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"domainLookupEnd\",\"timeUnixNano\":1674542404351400000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"connectStart\",\"timeUnixNano\":1674542404351400000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"secureConnectionStart\",\"timeUnixNano\":1674542404339500000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"connectEnd\",\"timeUnixNano\":1674542404351400000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"requestStart\",\"timeUnixNano\":1674542404351400000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"responseStart\",\"timeUnixNano\":1674542404377400000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"responseEnd\",\"timeUnixNano\":1674542404377600000,\"droppedAttributesCount\":0}],\"droppedEventsCount\":0,\"status\":{\"code\":0},\"links\":[],\"droppedLinksCount\":0},{\"traceId\":\"b7e726b6155ac52912322123d2f31a2c\",\"spanId\":\"67279b5c43e6874c\",\"name\":\"documentLoad\",\"kind\":1,\"startTimeUnixNano\":1674542404352000000,\"endTimeUnixNano\":1674542405301000200,\"attributes\":[{\"key\":\"component\",\"value\":{\"stringValue\":\"document-load\"}},{\"key\":\"http.url\",\"value\":{\"stringValue\":\"http://localhost:8080/login\"}},{\"key\":\"http.user_agent\",\"value\":{\"stringValue\":\"Mozilla/5.0(WindowsNT10.0;Win64;x64)AppleWebKit/537.36(KHTML,likeGecko)Chrome/109.0.0.0Safari/537.36\"}}],\"droppedAttributesCount\":0,\"events\":[{\"attributes\":[],\"name\":\"fetchStart\",\"timeUnixNano\":1674542404352000000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"unloadEventStart\",\"timeUnixNano\":1674542404385100000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"unloadEventEnd\",\"timeUnixNano\":1674542404385700000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"domInteractive\",\"timeUnixNano\":1674542404468400000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"domContentLoadedEventStart\",\"timeUnixNano\":1674542405284400000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"domContentLoadedEventEnd\",\"timeUnixNano\":1674542405287600000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"domComplete\",\"timeUnixNano\":1674542405293900000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"loadEventStart\",\"timeUnixNano\":1674542405300900000,\"droppedAttributesCount\":0},{\"attributes\":[],\"name\":\"loadEventEnd\",\"timeUnixNano\":1674542405301000200,\"droppedAttributesCount\":0}],\"droppedEventsCount\":0,\"status\":{\"code\":0},\"links\":[],\"droppedLinksCount\":0}]}]}]}";
            // @formatter:on

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonString);

            String observabilityClientId = UUID.randomUUID().toString();
            ClientInstrumentation.MethodAdvice.onEnter(root,
                    observabilityClientId);

            SpanData parentSpan = getExportedSpan(1);
            SpanData childSpan = getExportedSpan(0);
            assertEquals(parentSpan.getSpanId(), childSpan.getParentSpanId());
            assertEquals(observabilityClientId,
                    parentSpan.getAttributes().get(AttributeKey.stringKey(
                            ClientInstrumentation.MethodAdvice.FRONTEND_ID)),
                    "Missing or invalid observability client identifier on parent span attributes");
            assertEquals(observabilityClientId,
                    childSpan.getAttributes().get(AttributeKey.stringKey(
                            ClientInstrumentation.MethodAdvice.FRONTEND_ID)),
                    "Missing or invalid observability client identifier on child span attributes");
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    public void handleRequest_notHandled() {
        try {
            String jsonString = "{}";

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonString);

            ClientInstrumentation.MethodAdvice.onEnter(root,
                    "observabilityClientId");

            assertEquals(0, getExportedSpanCount());
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void handleRequest_attribute_handlesSupportedPrimitiveTypes() {
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
                                    "scope": { "name": "example-basic-tracer-node" },
                                    "spans": [
                                        {
                                            "traceId": "df0d98d8e9a800608b087d9d00172e49",
                                            "spanId": "9a728acf16177e63",
                                            "name": "myerror",
                                            "kind": 1,
                                            "startTimeUnixNano": 1678691748703599900,
                                            "endTimeUnixNano": 1678691748709300000,
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
                                                    "value": { "boolValue": true }
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
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode root = mapper.readTree(jsonString);

            ClientInstrumentation.MethodAdvice.onEnter(root,
                    "observabilityClientId");

            SpanData span = getExportedSpan(0);
            Attributes attributes = span.getAttributes();

            assertNotNull(
                    attributes.get(AttributeKey.stringKey("stringAttribute")),
                    "Missing attribute with key 'stringAttribute'");
            assertEquals("some text",
                    attributes.get(AttributeKey.stringKey("stringAttribute")),
                    "Wrong value for key 'stringAttribute'");

            assertNotNull(attributes.get(AttributeKey.longKey("intAttribute")),
                    "Missing attribute with key 'intAttribute'");
            assertEquals(123,
                    attributes.get(AttributeKey.longKey("intAttribute")),
                    "Wrong value for key 'intAttribute'");

            assertNotNull(
                    attributes.get(AttributeKey.doubleKey("doubleAttribute")),
                    "Missing attribute with key 'doubleAttribute'");
            assertEquals(12.3,
                    attributes.get(AttributeKey.doubleKey("doubleAttribute")),
                    "Wrong value for key 'doubleAttribute'");

            assertNotNull(
                    attributes.get(AttributeKey.booleanKey("booleanAttribute")),
                    "Missing attribute with key 'booleanAttribute'");
            assertEquals(true,
                    attributes.get(AttributeKey.booleanKey("booleanAttribute")),
                    "Wrong value for key 'booleanAttribute'");

        } catch (Exception e) {
            fail(e);
        }

    }

    @Test
    void handleRequest_attribute_handlesSupportedArraysOfPrimitiveTypes() {
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
                                    "scope": { "name": "example-basic-tracer-node" },
                                    "spans": [
                                        {
                                            "traceId": "df0d98d8e9a800608b087d9d00172e49",
                                            "spanId": "9a728acf16177e63",
                                            "name": "myerror",
                                            "kind": 1,
                                            "startTimeUnixNano": 1678691748703599900,
                                            "endTimeUnixNano": 1678691748709300000,
                                            "attributes": [
                                                {
                                                    "key": "stringAttribute",
                                                    "value": {
                                                        "arrayValue": {
                                                            "values": [
                                                                { "stringValue": "text1" },
                                                                { "stringValue": "text2" },
                                                                { "stringValue": "text3" },
                                                                { "stringValue": "text4" }
                                                            ]
                                                        }
                                                    }
                                                },
                                                {
                                                    "key": "intAttribute",
                                                    "value": {
                                                        "arrayValue": {
                                                            "values": [
                                                                { "intValue": 10 },
                                                                { "intValue": 20 },
                                                                { "intValue": 30 },
                                                                { "intValue": 40 }
                                                            ]
                                                        }
                                                    }
                                                },
                                                {
                                                    "key": "doubleAttribute",
                                                    "value": {
                                                        "arrayValue": {
                                                            "values": [
                                                                { "doubleValue": 10.1 },
                                                                { "doubleValue": 20.2 },
                                                                { "doubleValue": 30.3 },
                                                                { "doubleValue": 40.4 }
                                                            ]
                                                        }
                                                    }
                                                },
                                                {
                                                    "key": "booleanAttribute",
                                                    "value": {
                                                        "arrayValue": {
                                                            "values": [
                                                                { "boolValue": true },
                                                                { "boolValue": false },
                                                                { "boolValue": true },
                                                                { "boolValue": false }
                                                            ]
                                                        }
                                                    }
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
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode root = mapper.readTree(jsonString);

            ClientInstrumentation.MethodAdvice.onEnter(root,
                    "observabilityClientId");

            SpanData span = getExportedSpan(0);
            Attributes attributes = span.getAttributes();

            assertNotNull(
                    attributes.get(
                            AttributeKey.stringArrayKey("stringAttribute")),
                    "Missing attribute with key 'stringAttribute'");
            assertEquals(List.of("text1", "text2", "text3", "text4"),
                    attributes.get(
                            AttributeKey.stringArrayKey("stringAttribute")),
                    "Wrong value for key 'stringAttribute'");

            assertNotNull(
                    attributes.get(AttributeKey.longArrayKey("intAttribute")),
                    "Missing attribute with key 'intAttribute'");
            assertEquals(List.of(10L, 20L, 30L, 40L),
                    attributes.get(AttributeKey.longArrayKey("intAttribute")),
                    "Wrong value for key 'intAttribute'");

            assertNotNull(
                    attributes.get(
                            AttributeKey.doubleArrayKey("doubleAttribute")),
                    "Missing attribute with key 'doubleAttribute'");
            assertEquals(List.of(10.1, 20.2, 30.3, 40.4),
                    attributes.get(
                            AttributeKey.doubleArrayKey("doubleAttribute")),
                    "Wrong value for key 'doubleAttribute'");

            assertNotNull(
                    attributes.get(
                            AttributeKey.booleanArrayKey("booleanAttribute")),
                    "Missing attribute with key 'booleanAttribute'");
            assertEquals(List.of(true, false, true, false),
                    attributes.get(
                            AttributeKey.booleanArrayKey("booleanAttribute")),
                    "Wrong value for key 'booleanAttribute'");

        } catch (Exception e) {
            fail(e);
        }

    }

    @Test
    void handleRequest_spanEventAttributesPropagated() {
        String jsonString = """
                {
                    "resourceSpans": [
                        {
                            "resource": {
                                "attributes": [
                                    {
                                        "key": "service.name",
                                        "value": {
                                            "stringValue": "unknown_service"
                                        }
                                    },
                                    {
                                        "key": "telemetry.sdk.language",
                                        "value": {
                                            "stringValue": "webjs"
                                        }
                                    },
                                    {
                                        "key": "telemetry.sdk.name",
                                        "value": {
                                            "stringValue": "opentelemetry"
                                        }
                                    },
                                    {
                                        "key": "telemetry.sdk.version",
                                        "value": {
                                            "stringValue": "1.8.0"
                                        }
                                    }
                                ],
                                "droppedAttributesCount": 0
                            },
                            "scopeSpans": [
                                {
                                    "scope": {
                                        "name": "example-basic-tracer-node"
                                    },
                                    "spans": [
                                        {
                                            "traceId": "df0d98d8e9a800608b087d9d00172e49",
                                            "spanId": "9a728acf16177e63",
                                            "name": "myerror",
                                            "kind": 1,
                                            "startTimeUnixNano": 1678691748703599900,
                                            "endTimeUnixNano": 1678691748709300000,
                                            "attributes": [
                                                {
                                                    "key": "component",
                                                    "value": {
                                                        "stringValue": "error-logger"
                                                    }
                                                }
                                            ],
                                            "droppedAttributesCount": 0,
                                            "events": [
                                                {
                                                    "attributes": [
                                                        {
                                                            "key": "exception.type",
                                                            "value": {
                                                                "stringValue": "Error"
                                                            }
                                                        },
                                                        {
                                                            "key": "exception.message",
                                                            "value": {
                                                                "stringValue": "A client side error"
                                                            }
                                                        },
                                                        {
                                                            "key": "exception.stacktrace",
                                                            "value": {
                                                                "stringValue": "Error: A client side error\\n    at ProblematicComponent._throwError (http://localhost:8080/VAADIN/src/problematic-component.ts:22:11)\\n    at EventPart.handleEvent (http://localhost:8080/VAADIN/@fs/home/marco/projects/vaadin/acceleration-kits/observability-kit/observability-kit/observability-kit-demo/node_modules/.vite/deps/chunk-VUDNLEOG.js?v=ed1576fb:866:29)\\n    at UserInteractionInstrumentation2._invokeListener (http://localhost:8080/VAADIN/@fs/home/marco/projects/vaadin/acceleration-kits/observability-kit/observability-kit/observability-kit-demo/node_modules/.vite/deps/@opentelemetry_instrumentation-user-interaction.js?v=ed1576fb:187:25)\\n    at http://localhost:8080/VAADIN/@fs/home/marco/projects/vaadin/acceleration-kits/observability-kit/observability-kit/observability-kit-demo/node_modules/.vite/deps/@opentelemetry_instrumentation-user-interaction.js?v=ed1576fb:219:37\\n    at StackContextManager2.with (http://localhost:8080/VAADIN/@fs/home/marco/projects/vaadin/acceleration-kits/observability-kit/observability-kit/observability-kit-demo/node_modules/.vite/deps/chunk-MKQOKIFJ.js?v=ed1576fb:4242:24)\\n    at ContextAPI2.with (http://localhost:8080/VAADIN/@fs/home/marco/projects/vaadin/acceleration-kits/observability-kit/observability-kit/observability-kit-demo/node_modules/.vite/deps/chunk-6RTE4G25.js?v=ed1576fb:823:52)\\n    at HTMLButtonElement.patchedListener (http://localhost:8080/VAADIN/@fs/home/marco/projects/vaadin/acceleration-kits/observability-kit/observability-kit/observability-kit-demo/node_modules/.vite/deps/@opentelemetry_instrumentation-user-interaction.js?v=ed1576fb:218:34)"
                                                            }
                                                        }
                                                    ],
                                                    "name": "exception",
                                                    "timeUnixNano": 1678691748709200000,
                                                    "droppedAttributesCount": 0
                                                }
                                            ],
                                            "droppedEventsCount": 0,
                                            "status": {
                                                "code": 0
                                            },
                                            "links": [],
                                            "droppedLinksCount": 0
                                        }
                                    ]
                                }
                            ]
                        }
                    ]
                }""";
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode root = mapper.readTree(jsonString);

            ClientInstrumentation.MethodAdvice.onEnter(root,
                    "observabilityClientId");

            SpanData span = getExportedSpan(0);
            assertEquals(1, span.getEvents().size(),
                    "Expecting exactly one span");
            Attributes attributes = span.getEvents().get(0).getAttributes();
            assertEquals(3, attributes.size(),
                    "Wrong number of span attributes");

            Map<String, Object> attributesMap = attributes.asMap().entrySet()
                    .stream().collect(Collectors.toMap(e -> e.getKey().getKey(),
                            Map.Entry::getValue));

            assertEquals(Set.of("exception.type", "exception.message",
                    "exception.stacktrace"), attributesMap.keySet());
            assertEquals("Error", attributesMap.get("exception.type"),
                    "Wrong value for exception.type attributes");
            assertEquals("A client side error",
                    attributesMap.get("exception.message"),
                    "Wrong value for exception.type attributes");
            assertThat("Wrong value for exception.type attributes",
                    (String) attributesMap.get("exception.stacktrace"),
                    CoreMatchers.containsString(
                            "ProblematicComponent._throwError"));

        } catch (Exception e) {
            fail(e);
        }
    }
}
