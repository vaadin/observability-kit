/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.tests.spring;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.assertj.core.api.Assertions;

import com.vaadin.flow.component.html.testbench.SpanElement;
import com.vaadin.observability.tests.common.AbstractIT;
import com.vaadin.testbench.BrowserTest;

/**
 * Drives a real plain-Spring Vaadin Flow page in Chrome and asserts that the
 * Spring-managed {@code MeterRegistry} (provided through
 * {@code ObservabilityConfiguration}) recorded session / UI / request activity.
 */
public class SpringMetricsIT extends AbstractIT {

    @Override
    protected String getTestPath() {
        return "/";
    }

    @BrowserTest
    public void viewLoadDrivesSessionAndUiMetrics() throws IOException {
        SpanElement greeting = $(SpanElement.class).id("greeting");
        Assertions.assertThat(greeting.getText())
                .isEqualTo("Hello micrometer spring");

        String metrics = fetchMetrics();

        Assertions
                .assertThat(
                        meterValue(metrics, "vaadin.sessions.created", "count"))
                .as("vaadin.sessions.created count")
                .isGreaterThanOrEqualTo(1.0);
        Assertions.assertThat(meterValue(metrics, "vaadin.ui.created", "count"))
                .as("vaadin.ui.created count").isGreaterThanOrEqualTo(1.0);
        Assertions
                .assertThat(
                        meterValue(metrics, "vaadin.sessions.active", "value"))
                .as("vaadin.sessions.active value").isGreaterThanOrEqualTo(1.0);
        Assertions.assertThat(metrics).as("vaadin.request.duration present")
                .contains("vaadin.request.duration");
    }

    private String fetchMetrics() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI
                .create(getRootURL() + "/metrics").toURL().openConnection();
        conn.setRequestMethod("GET");
        Assertions.assertThat(conn.getResponseCode()).isEqualTo(200);
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
