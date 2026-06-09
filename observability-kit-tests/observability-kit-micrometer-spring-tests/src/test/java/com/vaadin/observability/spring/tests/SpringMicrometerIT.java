/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring.tests;

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
 * Drives a real plain-Spring Vaadin Flow page in Chrome and asserts the
 * Spring-managed {@code MeterRegistry} (provided through
 * {@link com.vaadin.observability.spring.ObservabilityConfiguration}) saw
 * session/UI/request activity, scraped through a plain HTTP {@code GET
 * /metrics}.
 */
public class SpringMicrometerIT extends AbstractIT {

    @Override
    protected String getTestPath() {
        return "/";
    }

    @BrowserTest
    public void viewLoadDrivesSessionAndUiMetrics() throws IOException {
        SpanElement greeting = $(SpanElement.class).id("greeting");
        Assertions.assertEquals("Hello micrometer spring", greeting.getText());

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
