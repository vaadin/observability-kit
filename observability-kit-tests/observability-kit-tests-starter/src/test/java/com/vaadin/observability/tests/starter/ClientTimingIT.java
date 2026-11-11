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
 * the round trip of a UIDL request and the time Flow's client spent applying
 * the response, both as the request and message events of Flow's client report
 * them.
 *
 * <p>
 * The test app runs in production mode with Flow's defaults, so both meters are
 * expected without any setting. The collector's own request that carries the
 * samples is a UIDL request too and must not be reported, which is why the test
 * also checks that an idle tab stops producing samples once what it had is
 * sent.
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
        recordFlushes(js);

        // Page load is timed too: the navigation it ends with is a UIDL
        // request. Sent now, so the counts the click is measured against are
        // settled. Waiting for the periodic flush instead left it to chance
        // which samples it carried, and a slow browser that clicked after it
        // had its snapshot taken before the click was reported at all.
        // The navigation must have been timed first: Flow idle, which it is
        // only once the response has been applied and both samples taken.
        waitUntil(driver -> Boolean.TRUE.equals(js.executeScript(
                "return Object.values(window.Vaadin.Flow.clients).every("
                        + "function (c) { return !c.isActive(); });")));
        js.executeAsyncScript("var done = arguments[arguments.length - 1];"
                + "setTimeout(function () {"
                + "  window.__vaadinMicrometer.flush(); done(); }, 100);");
        waitUntil(driver -> Boolean.TRUE.equals(js.executeScript(
                "return window.__vaadinMicrometer.bufferSize() === 0"
                        + " && window.__itFlushes.every("
                        + "function (f) { return f.answered; });")));
        String before = fetch("/actuator/prometheus");
        double requestsBefore = prometheusValue(before, REQUEST_COUNT, null);
        double rendersBefore = prometheusValue(before, RENDER_COUNT, null);

        $(NativeButtonElement.class).id("bump").click();
        assertThat(clicks.getText()).isEqualTo("1");

        // The click's round trip and its render are both timed, and reported
        // in the same flush, so once the request sample is in, the render
        // counts are final too.
        String prometheus = awaitPrometheus(
                body -> prometheusValue(body, REQUEST_COUNT,
                        null) >= requestsBefore + 1.0,
                "the click's " + REQUEST_COUNT, TIMEOUT);

        double requests = prometheusValue(prometheus, REQUEST_COUNT, null);
        double renders = prometheusValue(prometheus, RENDER_COUNT, null);
        assertThat(requests).as("the click's round trip should be timed once")
                .isEqualTo(requestsBefore + 1.0);
        assertThat(renders)
                .as("applying the click's response should be timed once")
                .isEqualTo(rendersBefore + 1.0);
        assertThat(prometheusValue(prometheus,
                "vaadin_client_request_duration_seconds_sum", null))
                .as("a round trip takes time").isGreaterThan(0.0);

        // The flush that delivered those samples was a UIDL request of its own.
        // Left unfiltered, it would be timed, reported on the next flush, and
        // so on for as long as the tab is open. Two flush intervals of an idle
        // tab must therefore add nothing.
        sleep(Duration.ofSeconds(11));
        String later = fetch("/actuator/prometheus");
        // When this fails, which request was timed is only knowable from the
        // browser, and a CI run is often the only one that shows it.
        String timeline = timeline(js);
        assertThat(prometheusValue(later, REQUEST_COUNT, null))
                .as("an idle tab must not time the collector's own requests; "
                        + "browser timeline: %s", timeline)
                .isEqualTo(requests);
        assertThat(prometheusValue(later, RENDER_COUNT, null))
                .as("an idle tab must not time the collector's own responses; "
                        + "browser timeline: %s", timeline)
                .isEqualTo(renders);
    }

    /**
     * Logs every batch the collector hands to the server, with when it did,
     * which samples it carried and whether the server has answered for it, so
     * the test can tell when a batch has landed and a failure can be laid
     * against the UIDL requests the browser made. The collector looks the call
     * up afresh on every flush, so wrapping it here sees them all.
     */
    private void recordFlushes(JavascriptExecutor js) {
        waitUntil(driver -> Boolean.TRUE.equals(js.executeScript(
                "var el = document.querySelector('vaadin-metrics-collector');"
                        + "return !!(el && el.$server"
                        + " && el.$server.recordSamples);")));
        js.executeScript("var server = document"
                + ".querySelector('vaadin-metrics-collector').$server;"
                + "var send = server.recordSamples;"
                + "window.__itFlushes = [];"
                + "server.recordSamples = function (batch) {"
                + "  var flush = { at: Math.round(performance.now()),"
                + "    samples: batch.map(function (s) { return s.name; }),"
                + "    answered: false };" + "  window.__itFlushes.push(flush);"
                + "  var sent = send.apply(this, arguments);"
                + "  var done = function () { flush.answered = true; };"
                + "  if (sent && sent.then) { sent.then(done, done); }"
                + "  else { done(); }" + "  return sent;" + "};");
    }

    /**
     * The UIDL requests the tab made (start and end, on the page's clock) and
     * the flushes {@link #recordFlushes} logged, as JSON.
     */
    private static String timeline(JavascriptExecutor js) {
        return String.valueOf(js.executeScript("return JSON.stringify({"
                + "uidl: performance.getEntriesByType('resource')"
                + "  .filter(function (e) {"
                + "    return /[?&]v-r=uidl(?:&|$)/.test(e.name); })"
                + "  .map(function (e) { return { start: Math.round(e.startTime),"
                + "    end: Math.round(e.responseEnd) }; }),"
                + "flushes: window.__itFlushes || [] });"));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
