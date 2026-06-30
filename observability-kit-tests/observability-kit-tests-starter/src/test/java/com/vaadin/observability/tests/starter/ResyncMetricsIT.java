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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openqa.selenium.Cookie;

import com.vaadin.flow.component.html.testbench.SpanElement;
import com.vaadin.observability.tests.common.AbstractIT;
import com.vaadin.testbench.BrowserTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the Spring Boot + {@code observability-kit-starter} app and verifies
 * the prototype {@code vaadin.resync} counter is exported via Prometheus when
 * the UIDL request stream contains a re-sent (duplicate) message and a
 * client-requested resynchronization.
 *
 * <p>
 * Flow's own resend/resync recovery is invisible to listener SPIs (it is caught
 * internally in {@code UidlRequestHandler}). The kit reconstructs the signal in
 * {@code ResyncDetectionFilter} by inspecting the incoming UIDL body, so this
 * test replays forged UIDL POSTs through the real servlet filter chain: the
 * filter classifies and counts them before Flow ever validates them. A real
 * browser load first establishes the HTTP session whose cookie the replayed
 * requests reuse, so the filter's per-UI {@code clientId} state persists across
 * the duplicate.
 *
 * <p>
 * The Micrometer counter {@code vaadin.resync} is exposed by Prometheus as
 * {@code vaadin_resync_total} with a {@code type} label.
 */
public class ResyncMetricsIT extends AbstractIT {

    /** A UI id unlikely to collide with the browser's own UIDL traffic. */
    private static final String UI_ID = "999";

    @Override
    protected String getTestPath() {
        return "/";
    }

    @BrowserTest
    public void resendAndResyncAreCountedAndExported() throws IOException {
        // Ensure the app has loaded and a server session exists.
        SpanElement greeting = $(SpanElement.class).id("greeting");
        assertThat(greeting.getText()).isEqualTo("Hello micrometer boot");

        String sessionCookie = jsessionId();
        assertThat(sessionCookie).as("JSESSIONID cookie from the page load")
                .isNotNull();

        String uidlUrl = getRootURL() + "/?v-r=uidl&v-uiId=" + UI_ID;

        // 1) baseline message establishes the last seen clientId for this UI
        postUidl(uidlUrl, sessionCookie, uidlBody(10, false));
        // 2) same clientId again: the client re-sent a message the server
        // already processed -> resend
        postUidl(uidlUrl, sessionCookie, uidlBody(10, false));
        // 3) advancing clientId carrying the resynchronize flag -> resync
        postUidl(uidlUrl, sessionCookie, uidlBody(11, true));

        String prometheus = fetchPrometheus();

        assertThat(meterValue(prometheus, "vaadin_resync_total", "resend"))
                .as("vaadin_resync_total{type=\"resend\"}")
                .isGreaterThanOrEqualTo(1.0);
        assertThat(meterValue(prometheus, "vaadin_resync_total", "resync"))
                .as("vaadin_resync_total{type=\"resync\"}")
                .isGreaterThanOrEqualTo(1.0);
    }

    private String jsessionId() {
        Cookie cookie = getDriver().manage().getCookieNamed("JSESSIONID");
        return cookie != null ? cookie.getValue() : null;
    }

    private static String uidlBody(int clientId, boolean resync) {
        String base = "{\"csrfToken\":\"x\",\"rpc\":[],\"syncId\":0,\"clientId\":"
                + clientId;
        return resync ? base + ",\"resynchronize\":true}" : base + "}";
    }

    private static void postUidl(String url, String jsessionId, String body)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL()
                .openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type",
                "application/json; charset=UTF-8");
        conn.setRequestProperty("Cookie", "JSESSIONID=" + jsessionId);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        // The forged UIDL is rejected by Flow (bad CSRF / no matching UI), but
        // the filter has already counted it; the response status is irrelevant.
        conn.getResponseCode();
        conn.disconnect();
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
     * Returns the value of the first Prometheus sample line for {@code name}
     * carrying the label {@code type="<type>"}, or {@code -1.0} if absent.
     */
    private static double meterValue(String prometheusBody, String name,
            String type) {
        Pattern pattern = Pattern.compile(
                "^" + Pattern.quote(name) + "\\{[^}]*type=\""
                        + Pattern.quote(type) + "\"[^}]*\\}\\s+"
                        + "([0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?)",
                Pattern.MULTILINE);
        Matcher m = pattern.matcher(prometheusBody);
        return m.find() ? Double.parseDouble(m.group(1)) : -1.0;
    }
}
