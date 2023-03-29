package com.vaadin.observability.test;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.Span;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerExtension;
import org.mockserver.junit.jupiter.MockServerSettings;
import org.mockserver.model.Body;
import org.mockserver.model.HttpRequest;

import com.vaadin.flow.component.html.testbench.H1Element;
import com.vaadin.flow.component.html.testbench.NativeButtonElement;
import com.vaadin.testbench.BrowserTest;

import static com.vaadin.observability.test.RequestBaseClientObservabilityConfigurator.INSTRUMENTATION_DOCUMENT_LOAD;
import static com.vaadin.observability.test.RequestBaseClientObservabilityConfigurator.INSTRUMENTATION_LONG_TASK;
import static com.vaadin.observability.test.RequestBaseClientObservabilityConfigurator.OBSERVABILITY_ACTIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

@ExtendWith(MockServerExtension.class)
@MockServerSettings(ports = {
        FrontendObservabilityRuntimeConfigurationIT.EXPORTER_ENDPOINT_PORT })
public class FrontendObservabilityRuntimeConfigurationIT
        extends AbstractViewIT {

    static final int EXPORTER_ENDPOINT_PORT = 4318;

    private static final int AWAIT_TIMEOUT = 15;

    private ClientAndServer collector;

    private String observabilityClientInstanceId;

    @BeforeEach
    @Override
    public void setup() {
        // Override super so that browser can be opened by single test with
        // required query parameters

    }

    @BeforeEach
    void setupTelemetryCollector(ClientAndServer collector) {
        this.collector = collector;
        this.collector.when(request()).respond(response().withStatusCode(200));
    }

    @BrowserTest
    void defaultConfiguration_allInstrumentationsActive() {
        open();
        assertObservabilityClientExist();
        $(NativeButtonElement.class).id("clientSideError").click();
        assertTracesExported("defaultConfiguration_allInstrumentationsActive",
                Set.of("Frontend: documentLoad", "Frontend: documentFetch",
                        "Frontend: resourceFetch", "Frontend: click",
                        "Frontend: longtask", "Frontend: windowError",
                        "Frontend: unhandledRejection"),
                Set.of());

    }

    @BrowserTest
    void customConfiguration_disableObservability_componentNotAddedToUI() {
        open(params -> params.accept(OBSERVABILITY_ACTIVE, false));
        Assertions.assertFalse($(ObservabilityClientElement.class).exists(),
                "Observability client disabled by configuration but present on page");
    }

    @BrowserTest
    void customConfiguration_documentLoad_onlyActiveInstrumentationTraces() {
        open(params -> {
            params.accept(OBSERVABILITY_ACTIVE, true);
            params.accept(INSTRUMENTATION_DOCUMENT_LOAD, true);
        });
        assertObservabilityClientExist();
        $(NativeButtonElement.class).id("clientSideError").click();
        assertTracesExported(
                "customConfiguration_documentLoad_onlyActiveInstrumentationTraces",
                Set.of("Client: documentLoad", "Client: documentFetch",
                        "Client: resourceFetch"),
                Set.of("Client: click", "Client: longtask",
                        "Client: windowError", "Client: unhandledRejection"));
    }

    @BrowserTest
    void customConfiguration_longTask_onlyActiveInstrumentationTraces() {
        open(params -> {
            params.accept(OBSERVABILITY_ACTIVE, true);
            params.accept(INSTRUMENTATION_LONG_TASK, true);
        });
        assertObservabilityClientExist();
        $(NativeButtonElement.class).id("clientSideError").click();
        assertTracesExported(
                "customConfiguration_documentLoad_onlyActiveInstrumentationTraces",
                Set.of("Client: longtask"),
                Set.of("Client: documentLoad", "Client: documentFetch",
                        "Client: resourceFetch", "Client: click",
                        "Client: windowError", "Client: unhandledRejection"));
    }

    private void assertObservabilityClientExist() {
        // If observability is active, stores the instance identifier to
        // be able to fetch spans for assertions
        observabilityClientInstanceId = $(ObservabilityClientElement.class)
                .waitForFirst().getInstanceId();
    }

    private void open() {
        open(null);
    }

    private void open(Consumer<BiConsumer<String, Object>> paramAppender) {
        StringBuilder url = new StringBuilder(getRootURL() + getTestPath());
        if (paramAppender != null) {
            url.append("?test");
            paramAppender.accept((key, value) -> url.append("&").append(key)
                    .append("=").append(value));
        }
        getDriver().get(url.toString());
        // Wait for page to be loaded
        waitUntil(driver -> $(H1Element.class).exists());
    }

    private void assertTracesExported(String testName,
            Collection<String> expectedTraces,
            Collection<String> invalidTraces) {
        Map<ByteString, Span> collectedSpans = new LinkedHashMap<>();

        // We collect spans for a couple of seconds to be sure that all
        // instrumentations has time to execute at least once
        // However, Mockserver recycles log entries, so we store the spans at
        // every condition evaluation and then fulfill the wait condition to
        // proceed with the test
        AtomicBoolean stopCollection = new AtomicBoolean();
        await().atMost(25, TimeUnit.SECONDS)
                .conditionEvaluationListener(condition -> {
                    var requests = collector
                            .retrieveRecordedRequests(request());
                    var spans = extractClientSpansFromRequests(requests);
                    spans.forEach(span -> collectedSpans
                            .putIfAbsent(span.getSpanId(), span));
                    stopCollection
                            .set(condition.getRemainingTimeInMS() < (condition
                                    .getPollInterval().toMillis() * 5));
                }).untilTrue(stopCollection);
        var spans = collectedSpans.values();
        if (!expectedTraces.isEmpty()) {
            assertThat(spans).extracting(Span::getName)
                    .containsAll(expectedTraces);
        }
        if (!invalidTraces.isEmpty()) {
            assertThat(spans).extracting(Span::getName)
                    .doesNotContainAnyElementsOf(invalidTraces);
        }
    }

    private List<Span> extractClientSpansFromRequests(HttpRequest[] requests) {
        return Arrays.stream(requests).map(HttpRequest::getBody)
                .flatMap(body -> getExportTraceServiceRequest(body).stream())
                .flatMap(r -> r.getResourceSpansList().stream())
                .flatMap(r -> r.getInstrumentationLibrarySpansList().stream())
                .flatMap(r -> r.getSpansList().stream())
                .filter(span -> span
                        .getKind() == Span.SpanKind.SPAN_KIND_CLIENT)
                .filter(span -> span.getAttributesList().stream()
                        .anyMatch(kv -> "vaadin.frontend.id".equals(kv.getKey())
                                && observabilityClientInstanceId.equals(
                                        kv.getValue().getStringValue())))
                .collect(Collectors.toList());
    }

    private Optional<ExportTraceServiceRequest> getExportTraceServiceRequest(
            Body<?> body) {
        try {
            var req = ExportTraceServiceRequest.parseFrom(body.getRawBytes());
            return Optional.ofNullable(req);
        } catch (InvalidProtocolBufferException e) {
            return Optional.empty();
        }
    }

    @Override
    protected String getTestPath() {
        return "/";
    }
}
