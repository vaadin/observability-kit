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
 * Drives the Spring Boot + {@code observability-kit-starter} app and verifies
 * the {@code vaadin.resync} counter is exported via Prometheus for each kind of
 * client message recovery Flow reports on the service event bus.
 *
 * <p>
 * Flow only fires the events for messages it accepts: the UI must exist and the
 * CSRF token must match. So the test records the UIDL messages the real client
 * sends, stops the client from sending more once a click has been answered, and
 * replays that last message from the page, as is, and with the message id
 * changed:
 * <ul>
 * <li>the same message again is a resend,</li>
 * <li>a message id the server does not expect is out of sync,</li>
 * <li>the next message id with the resynchronize flag is a resync.</li>
 * </ul>
 *
 * <p>
 * The Micrometer counter {@code vaadin.resync} is exposed by Prometheus as
 * {@code vaadin_resync_total} with a {@code type} label.
 */
public class ResyncMetricsIT extends AbstractIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /**
     * Records every UIDL message the client sends, and drops the ones sent
     * after {@code blocked} is set so the server's last processed message id
     * stays put while the test replays.
     */
    private static final String RECORD_UIDL = """
            window.__uidl = { sent: [], blocked: false };
            const open = XMLHttpRequest.prototype.open;
            const send = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function (method, url) {
              this.__url = String(url);
              return open.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function (body) {
              if (this.__url && this.__url.indexOf('v-r=uidl') >= 0) {
                if (window.__uidl.blocked) {
                  return;
                }
                const entry = { url: this.__url, body: body, done: false };
                window.__uidl.sent.push(entry);
                this.addEventListener('loadend', () => entry.done = true);
              }
              return send.apply(this, arguments);
            };
            """;

    /**
     * Replays the last recorded message as is (a resend), with a message id the
     * server does not expect (out of sync), and with the next message id and
     * the resynchronize flag (a resync).
     */
    private static final String REPLAY_UIDL = """
            const done = arguments[arguments.length - 1];
            const last = window.__uidl.sent[window.__uidl.sent.length - 1];
            const message = JSON.parse(last.body);
            const post = body => fetch(last.url, { method: 'POST',
                headers: { 'Content-Type': 'application/json; charset=UTF-8' },
                body: body });
            post(last.body)
              .then(() => post(JSON.stringify(
                  Object.assign({}, message, { clientId: 99999 }))))
              .then(() => post(JSON.stringify(Object.assign({}, message,
                  { clientId: message.clientId + 1, rpc: [],
                    resynchronize: true }))))
              .then(() => done(true), () => done(false));
            """;

    @Override
    protected String getTestPath() {
        return "/";
    }

    @BrowserTest
    public void resendResyncAndOutOfSyncAreCountedAndExported()
            throws IOException {
        SpanElement greeting = $(SpanElement.class).id("greeting");
        assertThat(greeting.getText()).isEqualTo("Hello micrometer boot");

        JavascriptExecutor js = (JavascriptExecutor) getDriver();
        js.executeScript(RECORD_UIDL);

        $(NativeButtonElement.class).id("bump").click();
        waitUntil(driver -> "1"
                .equals($(SpanElement.class).id("clicks").getText()));

        // A dropped message never completes, so Flow never reports itself
        // idle again: stop waiting for it before blocking the client.
        testBench().disableWaitForVaadin();
        js.executeScript("window.__uidl.blocked = true;");
        waitUntil(driver -> Boolean.TRUE.equals(js.executeScript(
                "const s = window.__uidl.sent; return s.length > 0 && s.every(e => e.done);")));

        assertThat(js.executeAsyncScript(REPLAY_UIDL))
                .as("the replayed UIDL requests should complete")
                .isEqualTo(Boolean.TRUE);

        String prometheus = awaitPrometheus(
                body -> prometheusValue(body, "vaadin_resync_total",
                        "type=\"resend\"") >= 1.0
                        && prometheusValue(body, "vaadin_resync_total",
                                "type=\"out_of_sync\"") >= 1.0
                        && prometheusValue(body, "vaadin_resync_total",
                                "type=\"resync\"") >= 1.0,
                "a resend, an out-of-sync message and a resync", TIMEOUT);

        assertThat(prometheusValue(prometheus, "vaadin_resync_total",
                "type=\"resend\"")).as("vaadin_resync_total{type=\"resend\"}")
                .isGreaterThanOrEqualTo(1.0);
        assertThat(prometheusValue(prometheus, "vaadin_resync_total",
                "type=\"out_of_sync\""))
                .as("vaadin_resync_total{type=\"out_of_sync\"}")
                .isGreaterThanOrEqualTo(1.0);
        assertThat(prometheusValue(prometheus, "vaadin_resync_total",
                "type=\"resync\"")).as("vaadin_resync_total{type=\"resync\"}")
                .isGreaterThanOrEqualTo(1.0);
    }
}
