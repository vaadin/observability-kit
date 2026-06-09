/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.tests.starter;

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
 * Drives a Spring Boot + {@code observability-kit-starter} app in Chrome and
 * asserts the auto-configured binders move Vaadin meters into the Prometheus
 * registry, scraping results via the Actuator Prometheus endpoint.
 *
 * <p>
 * Prometheus / OpenMetrics treats the {@code _created} suffix as a special
 * timestamp marker and drops it from counter names, so the Micrometer counter
 * {@code vaadin.sessions.created} is emitted as {@code vaadin_sessions_total}
 * (and {@code vaadin.ui.created} as {@code vaadin_ui_total}).
 */
public class BootMetricsIT extends AbstractIT {

    @Override
    protected String getTestPath() {
        return "/";
    }

    @BrowserTest
    public void viewLoadDrivesMetricsExposedViaActuator() throws IOException {
        SpanElement greeting = $(SpanElement.class).id("greeting");
        Assertions.assertThat(greeting.getText())
                .isEqualTo("Hello micrometer boot");

        String body = fetchPrometheus();

        Assertions.assertThat(meterValue(body, "vaadin_sessions_total"))
                .as("vaadin_sessions_total").isGreaterThanOrEqualTo(1.0);
        Assertions.assertThat(meterValue(body, "vaadin_ui_total"))
                .as("vaadin_ui_total").isGreaterThanOrEqualTo(1.0);
        Assertions.assertThat(meterValue(body, "vaadin_sessions_active"))
                .as("vaadin_sessions_active").isGreaterThanOrEqualTo(1.0);
        Assertions.assertThat(body)
                .as("vaadin_request_duration_seconds present")
                .contains("vaadin_request_duration_seconds");
    }

    private String fetchPrometheus() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI
                .create(getRootURL() + "/actuator/prometheus").toURL()
                .openConnection();
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
     * Returns the numeric value of the first Prometheus sample line whose
     * metric name matches {@code meterName}. Lines with or without labels are
     * accepted. Returns {@code -1.0} if no match is found.
     */
    private static double meterValue(String prometheusBody, String meterName) {
        Pattern pattern = Pattern.compile(
                "^" + Pattern.quote(meterName) + "(?:\\{[^}]*\\})?\\s+"
                        + "([0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?)",
                Pattern.MULTILINE);
        Matcher m = pattern.matcher(prometheusBody);
        return m.find() ? Double.parseDouble(m.group(1)) : -1.0;
    }
}
