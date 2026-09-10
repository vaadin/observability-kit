/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.tests.starter;

import java.io.IOException;
import java.time.Duration;

import org.openqa.selenium.JavascriptExecutor;

import com.vaadin.flow.component.html.testbench.SpanElement;
import com.vaadin.observability.tests.common.AbstractIT;
import com.vaadin.testbench.BrowserTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The browser half of connection and client-side problem monitoring, which only
 * a real browser can exercise: the collector's subscription to
 * {@code window.Vaadin.connectionState}, its buffering across an outage, and
 * the detail it sends alongside a browser error.
 *
 * <p>
 * The outage is simulated by driving Flow's own connection store, which is the
 * same store Flow's reconnect logic drives and fires exactly the listeners a
 * real outage fires. While it reads {@code connection-lost} the collector
 * refuses to send — a report needs the connection it is about — so the samples
 * that prove the outage only arrive once it is over, which is the behaviour
 * under test.
 */
public class ClientProblemsIT extends AbstractIT {

    /** How long to wait for the collector's next flush to reach the server. */
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Override
    protected String getTestPath() {
        return "/";
    }

    @BrowserTest
    public void outageAndBrowserErrorAreRecordedOnceTheBrowserIsBack()
            throws IOException {
        SpanElement greeting = $(SpanElement.class).id("greeting");
        assertThat(greeting.getText()).isEqualTo("Hello micrometer boot");

        JavascriptExecutor js = (JavascriptExecutor) getDriver();

        // A script fails in the tab, uncaught. The server handles nothing and
        // no server-side stack trace exists.
        js.executeScript(
                "setTimeout(function () { throw new Error('IT: rendering the "
                        + "sales chart failed'); }, 0);");

        // The browser loses the server, then gets it back.
        js.executeScript(
                "window.Vaadin.connectionState.state = 'connection-lost';");
        assertThat(js.executeScript(
                "return window.__vaadinMicrometer.bufferSize() > 0;"))
                .as("samples should be held while the browser is offline")
                .isEqualTo(Boolean.TRUE);
        js.executeScript("window.Vaadin.connectionState.state = 'connected';");

        // The simulated outage goes straight to connection-lost, so that is
        // the state the downtime is attributed to.
        String prometheus = awaitPrometheus(
                body -> prometheusValue(body,
                        "vaadin_client_connection_downtime_seconds_count",
                        "state=\"connection-lost\"") >= 1.0,
                "vaadin_client_connection_downtime_seconds_count"
                        + "{state=\"connection-lost\"}",
                TIMEOUT);

        assertThat(prometheusValue(prometheus, "vaadin_client_connection_total",
                "state=\"connection-lost\""))
                .as("a transition into connection-lost should be counted")
                .isGreaterThanOrEqualTo(1.0);
        assertThat(prometheusValue(prometheus, "vaadin_client_connection_total",
                "state=\"connected\"")).as("the recovery should be counted too")
                .isGreaterThanOrEqualTo(1.0);
        assertThat(prometheusValue(prometheus, "vaadin_client_errors_total",
                "kind=\"uncaught\"")).as("the browser error should be counted")
                .isGreaterThanOrEqualTo(1.0);

        // The counter says an error happened; the insight says what it was.
        String insights = fetch("/actuator/vaadin/observability");
        assertThat(insights).as("a client-error insight should be reported")
                .contains("\"type\":\"client-error\"");
        assertThat(insights)
                .as("the insight should name where the error came from")
                .contains("\"kind\":\"uncaught\"");
    }
}
