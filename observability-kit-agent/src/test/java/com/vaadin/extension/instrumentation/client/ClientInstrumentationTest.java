package com.vaadin.extension.instrumentation.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;

import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

            ClientInstrumentation.MethodAdvice.onEnter(root);

            SpanData span = getExportedSpan(0);
            assertEquals("Client: documentLoad", span.getName());
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

            ClientInstrumentation.MethodAdvice.onEnter(root);

            SpanData parentSpan = getExportedSpan(1);
            SpanData childSpan = getExportedSpan(0);
            assertEquals(parentSpan.getSpanId(), childSpan.getParentSpanId());
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

            ClientInstrumentation.MethodAdvice.onEnter(root);

            assertEquals(0, getExportedSpanCount());
        } catch (Exception e) {
            fail(e);
        }
    }
}
