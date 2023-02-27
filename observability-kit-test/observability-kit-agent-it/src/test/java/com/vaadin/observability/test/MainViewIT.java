package com.vaadin.observability.test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.bonigarcia.wdm.WebDriverManager;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.trace.v1.Span;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerExtension;
import org.mockserver.junit.jupiter.MockServerSettings;
import org.mockserver.model.Body;
import org.mockserver.model.HttpRequest;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import com.vaadin.flow.component.html.testbench.H1Element;
import com.vaadin.testbench.TestBench;
import com.vaadin.testbench.TestBenchTestCase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

@ExtendWith(MockServerExtension.class)
@MockServerSettings(ports = { MainViewIT.EXPORTER_ENDPOINT_PORT })
public class MainViewIT extends TestBenchTestCase {

    static final int EXPORTER_ENDPOINT_PORT = 4318;

    private ClientAndServer collector;

    @BeforeEach
    public void navigateToView(ClientAndServer collector) {
        this.collector = collector;
        this.collector.when(request()).respond(response().withStatusCode(200));

        WebDriverManager.chromedriver().setup();
        setDriver(createHeadlessChromeDriver());
        getDriver().get("http://localhost:8080/");

        // Wait for the view to be rendered
        waitUntil(driver -> $(H1Element.class).exists());
    }

    @Test
    public void verifyExportedTraces() {
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var requests = collector.retrieveRecordedRequests(request());
            var spans = extractSpansFromRequests(requests);
            assertThat(spans).extracting(Span::getName)
                    .contains("SessionRequestHandler.handleRequest");
        });
    }

    @Test
    public void verifyExportedMetrics() {
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var requests = collector.retrieveRecordedRequests(request());
            var metrics = extractMetricsFromRequests(requests);
            assertThat(metrics).extracting(Metric::getName)
                    .contains("vaadin.ui.count");
        });
    }

    private List<Span> extractSpansFromRequests(HttpRequest[] requests) {
        return Arrays.stream(requests).map(HttpRequest::getBody)
                .flatMap(body -> getExportTraceServiceRequest(body).stream())
                .flatMap(r -> r.getResourceSpansList().stream())
                .flatMap(r -> r.getInstrumentationLibrarySpansList().stream())
                .flatMap(r -> r.getSpansList().stream())
                .collect(Collectors.toList());
    }

    private List<Metric> extractMetricsFromRequests(HttpRequest[] requests) {
        return Arrays.stream(requests).map(HttpRequest::getBody)
                .flatMap(body -> getExportMetricsServiceRequest(body).stream())
                .flatMap(r -> r.getResourceMetricsList().stream())
                .flatMap(r -> r.getInstrumentationLibraryMetricsList().stream())
                .flatMap(r -> r.getMetricsList().stream())
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

    private Optional<ExportMetricsServiceRequest> getExportMetricsServiceRequest(
            Body<?> body) {
        try {
            var req = ExportMetricsServiceRequest.parseFrom(body.getRawBytes());
            return Optional.ofNullable(req);
        } catch (InvalidProtocolBufferException e) {
            return Optional.empty();
        }
    }

    private WebDriver createHeadlessChromeDriver() {
        final var options = new ChromeOptions();
        options.addArguments("--no-sandbox", "--disable-dev-shm-usage");
        if (!Boolean.getBoolean("noHeadless")) {
            options.addArguments("--headless");
        }
        return TestBench.createDriver(new ChromeDriver(options));
    }
}
