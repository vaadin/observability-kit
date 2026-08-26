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

import com.vaadin.flow.component.html.testbench.SpanElement;
import com.vaadin.observability.tests.common.AbstractIT;
import com.vaadin.testbench.BrowserTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opens a view in Chrome and asserts that the opt-in UI state binder
 * ({@code vaadin.observability.ui-state=true}) measured that browser tab's
 * server-side state tree and exposed the aggregates through the Actuator
 * Prometheus endpoint.
 * <p>
 * The counts come from a real Flow state tree, so this is also the check that
 * the internal-API walk still works against the Flow version in use: a broken
 * walk leaves the gauges at zero rather than failing loudly.
 */
public class UiStateMetricsIT extends AbstractIT {

    @Override
    protected String getTestPath() {
        return "/";
    }

    @BrowserTest
    public void openTabIsMeasuredAndExposedViaActuator() throws IOException {
        SpanElement greeting = $(SpanElement.class).id("greeting");
        assertThat(greeting.getText()).isEqualTo("Hello micrometer boot");

        String body = fetchPrometheus();

        // The open tab holds the UI element, the route target and the view's
        // own contents, so several nodes and at least one retained view.
        assertThat(meterValue(body, "vaadin_ui_state_nodes"))
                .as("vaadin_ui_state_nodes").isGreaterThanOrEqualTo(3.0);
        assertThat(meterValue(body, "vaadin_ui_state_nodes_max"))
                .as("vaadin_ui_state_nodes_max").isGreaterThanOrEqualTo(3.0);
        assertThat(meterValue(body, "vaadin_ui_state_components"))
                .as("vaadin_ui_state_components").isGreaterThanOrEqualTo(2.0);
        assertThat(meterValue(body, "vaadin_ui_state_views"))
                .as("vaadin_ui_state_views").isGreaterThanOrEqualTo(1.0);
        // One navigation, nothing left behind by an earlier one.
        assertThat(meterValue(body, "vaadin_ui_state_views_stale"))
                .as("vaadin_ui_state_views_stale").isZero();
        assertThat(meterValue(body, "vaadin_session_state_nodes_max"))
                .as("vaadin_session_state_nodes_max")
                .isGreaterThanOrEqualTo(3.0);
        assertThat(meterValue(body, "vaadin_session_uis_max"))
                .as("vaadin_session_uis_max").isGreaterThanOrEqualTo(1.0);
        // Published so a stale aggregate can be told from a fresh one; this tab
        // was measured moments ago.
        assertThat(meterValue(body, "vaadin_ui_state_sample_age_max_seconds"))
                .as("vaadin_ui_state_sample_age_max_seconds")
                .isBetween(0.0, 600.0);
        // No cost per node is configured, so no byte figure is published: an
        // unmeasured one would be a guess.
        assertThat(body).doesNotContain("vaadin_ui_state_size_bytes");
    }

    private String fetchPrometheus() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI
                .create(getRootURL() + "/actuator/prometheus").toURL()
                .openConnection();
        conn.setRequestMethod("GET");
        assertThat(conn.getResponseCode()).isEqualTo(200);
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
