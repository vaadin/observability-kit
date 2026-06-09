/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.tests;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assertions;

import com.vaadin.flow.component.html.testbench.SpanElement;
import com.vaadin.testbench.BrowserTest;

/**
 * Drives a real Vaadin Flow page in Chrome and asserts that the
 * vaadin-micrometer binders moved the corresponding meters, scraped through a
 * plain HTTP {@code GET /metrics}.
 */
public class StandaloneMetricsIT extends AbstractIT {

    @Override
    protected String getTestPath() {
        return "/";
    }

    @BrowserTest
    public void viewLoadDrivesSessionAndUiMetrics() throws IOException {
        SpanElement greeting = $(SpanElement.class).id("greeting");
        Assertions.assertEquals("Hello micrometer", greeting.getText());

        String metrics = fetchMetrics();

        Assertions.assertTrue(
                meterValue(metrics, "vaadin.sessions.created", "count") >= 1.0,
                "expected vaadin.sessions.created counter > 0, got:\n"
                        + metrics);
        Assertions.assertTrue(
                meterValue(metrics, "vaadin.ui.created", "count") >= 1.0,
                "expected vaadin.ui.created counter > 0, got:\n" + metrics);
        Assertions.assertTrue(
                meterValue(metrics, "vaadin.sessions.active", "value") >= 1.0,
                "expected vaadin.sessions.active gauge > 0, got:\n" + metrics);
        Assertions.assertTrue(metrics.contains("vaadin.request.duration"),
                "expected at least one vaadin.request.duration sample, got:\n"
                        + metrics);
    }

    @BrowserTest
    public void clientMetricsArriveViaUidlAfterFlush() throws IOException {
        // Wait until the view rendered, ensuring the collector is attached
        // and the client script has installed itself.
        $(SpanElement.class).id("greeting");

        // Force an immediate flush of whatever the client buffered so the
        // IT does not have to wait for the 5 s periodic timer. window.
        // __vaadinMicrometer.flush is exposed by VaadinMetricsClient.js
        // explicitly for tests.
        executeScript("window.__vaadinMicrometer && window."
                + "__vaadinMicrometer.flush();");

        // Poll the /metrics endpoint until at least one vaadin.client.*
        // sample appears. We give the server up to ~5 s to ingest, then
        // fail if the collector never delivered anything.
        String metrics = pollUntilClientMetrics();

        Assertions.assertTrue(metrics.contains("vaadin.client."),
                "expected at least one vaadin.client.* meter in registry, "
                        + "got:\n" + metrics);
    }

    private String pollUntilClientMetrics() throws IOException {
        long deadline = System.currentTimeMillis() + 5_000L;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            last = fetchMetrics();
            if (last.contains("vaadin.client.")) {
                return last;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return last;
    }

    private String fetchMetrics() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI
                .create(getRootURL() + "/metrics").toURL().openConnection();
        conn.setRequestMethod("GET");
        Assertions.assertEquals(200, conn.getResponseCode());
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Finds the numeric value of {@code field} on the first line that starts
     * with {@code meterName}. Returns {@code -1.0} if not present.
     */
    private static double meterValue(String metricsBody, String meterName,
            String field) {
        Pattern pattern = Pattern.compile(
                "^" + Pattern.quote(meterName) + "(?:\\s.*)?\\s"
                        + Pattern.quote(field) + "=([0-9]+(?:\\.[0-9]+)?)",
                Pattern.MULTILINE);
        Matcher m = pattern.matcher(metricsBody);
        return m.find() ? Double.parseDouble(m.group(1)) : -1.0;
    }
}
