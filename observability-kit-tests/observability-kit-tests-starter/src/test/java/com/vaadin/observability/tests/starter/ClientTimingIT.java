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

import com.vaadin.flow.component.html.testbench.NativeButtonElement;
import com.vaadin.flow.component.html.testbench.SpanElement;
import com.vaadin.observability.tests.common.AbstractIT;
import com.vaadin.testbench.BrowserTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The browser's side of an interaction, which only a real browser can measure:
 * the round trip of a UIDL request as the Resource Timing API reports it, and
 * the time Flow's client spent applying the response as Flow's own profiling
 * data reports it.
 *
 * <p>
 * The test app runs in production mode with {@code vaadin.requestTiming=true},
 * the switch a production deployment flips to get the render figure, so both
 * meters are expected. The collector's own request that carries the samples is
 * a UIDL request too and must not be reported, which is why the test also
 * checks that an idle tab stops producing samples once what it had is sent.
 */
public class ClientTimingIT extends AbstractIT {

    /** How long to wait for the collector's next flush to reach the server. */
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final String REQUEST_COUNT = "vaadin_client_request_duration_seconds_count";
    private static final String RENDER_COUNT = "vaadin_client_render_duration_seconds_count";

    @Override
    protected String getTestPath() {
        return "/";
    }

    @BrowserTest
    public void aClickIsTimedOnTheBrowserSideAndTheCollectorDoesNotTimeItself()
            throws IOException {
        SpanElement clicks = $(SpanElement.class).id("clicks");
        assertThat(clicks.getText()).isEqualTo("0");

        JavascriptExecutor js = (JavascriptExecutor) getDriver();
        assertThat(js.executeScript("return Object.values(window.Vaadin.Flow"
                + ".clients).some(function (c) { return typeof "
                + "c.getProfilingData === 'function'; });"))
                .as("the test app sets vaadin.requestTiming=true, so Flow "
                        + "should publish its profiling data")
                .isEqualTo(Boolean.TRUE);

        $(NativeButtonElement.class).id("bump").click();
        assertThat(clicks.getText()).isEqualTo("1");

        String prometheus = awaitPrometheus(
                body -> prometheusValue(body, REQUEST_COUNT, null) >= 1.0
                        && prometheusValue(body, RENDER_COUNT, null) >= 1.0,
                REQUEST_COUNT + " and " + RENDER_COUNT, TIMEOUT);

        double requests = prometheusValue(prometheus, REQUEST_COUNT, null);
        double renders = prometheusValue(prometheus, RENDER_COUNT, null);
        assertThat(requests).as("the click's round trip should be timed")
                .isGreaterThanOrEqualTo(1.0);
        assertThat(renders).as("applying the click's response should be timed")
                .isGreaterThanOrEqualTo(1.0);
        assertThat(prometheusValue(prometheus,
                "vaadin_client_request_duration_seconds_sum", null))
                .as("a round trip takes time").isGreaterThan(0.0);

        // The flush that delivered those samples was a UIDL request of its own.
        // Left unfiltered, it would be timed, reported on the next flush, and
        // so on for as long as the tab is open. Two flush intervals of an idle
        // tab must therefore add nothing.
        sleep(Duration.ofSeconds(11));
        String later = fetch("/actuator/prometheus");
        assertThat(prometheusValue(later, REQUEST_COUNT, null))
                .as("an idle tab must not time the collector's own requests")
                .isEqualTo(requests);
        assertThat(prometheusValue(later, RENDER_COUNT, null))
                .as("an idle tab must not time the collector's own responses")
                .isEqualTo(renders);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
