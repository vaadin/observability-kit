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

import com.vaadin.flow.component.html.testbench.NativeButtonElement;
import com.vaadin.flow.component.html.testbench.SpanElement;
import com.vaadin.observability.tests.common.AbstractIT;
import com.vaadin.testbench.BrowserTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the {@link DatabaseView} in Chrome, triggering a small and a large
 * JDBC fetch, and asserts the {@code observability-kit-starter}'s DataSource
 * proxy recorded {@code vaadin.db.fetch.rows} into the Prometheus registry,
 * tagged with the {@code db} route that issued the queries.
 */
public class DatabaseFetchMetricsIT extends AbstractIT {

    @Override
    protected String getTestPath() {
        return "/db";
    }

    @BrowserTest
    public void smallAndLargeFetchRecordedPerRoute() throws IOException {
        $(NativeButtonElement.class).id("small-fetch").click();
        waitUntilResult("rows: " + DatabaseView.SMALL);

        $(NativeButtonElement.class).id("large-fetch").click();
        waitUntilResult("rows: " + NumbersInitializer.TOTAL);

        String body = fetchPrometheus();

        assertThat(body).withFailMessage(
                "expected a vaadin_db_fetch_rows sample in the Prometheus scrape")
                .contains("vaadin_db_fetch_rows");
        // Both fetches were issued from the "db" view, so the summary must be
        // tagged with that route and have observed at least the two fetches.
        assertThat(labeledValue(body, "vaadin_db_fetch_rows_count", "db"))
                .as("vaadin_db_fetch_rows_count{route=\"db\"}")
                .isGreaterThanOrEqualTo(2.0);
        // The large fetch read every seeded row, so the recorded maximum must
        // reach the full table size.
        assertThat(labeledValue(body, "vaadin_db_fetch_rows_max", "db"))
                .as("vaadin_db_fetch_rows_max{route=\"db\"}")
                .isGreaterThanOrEqualTo(NumbersInitializer.TOTAL);
    }

    private void waitUntilResult(String expected) {
        waitUntil(driver -> expected
                .equals($(SpanElement.class).id("fetch-result").getText()));
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
     * Returns the value of the first Prometheus sample of {@code meterName}
     * whose label set contains {@code route="<route>"}, or {@code -1.0} if none
     * is found.
     */
    private static double labeledValue(String prometheusBody, String meterName,
            String route) {
        Pattern pattern = Pattern.compile(
                "^" + Pattern.quote(meterName) + "\\{[^}]*route=\""
                        + Pattern.quote(route) + "\"[^}]*\\}\\s+"
                        + "([0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?)",
                Pattern.MULTILINE);
        Matcher m = pattern.matcher(prometheusBody);
        return m.find() ? Double.parseDouble(m.group(1)) : -1.0;
    }
}
