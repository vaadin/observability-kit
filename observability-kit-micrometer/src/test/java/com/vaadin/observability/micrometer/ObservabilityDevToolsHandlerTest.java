/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.base.devserver.DevToolsInterface;
import com.vaadin.observability.micrometer.devtools.ObservabilityDevToolsHandler;
import com.vaadin.observability.micrometer.insights.CapturedClientError;
import com.vaadin.observability.micrometer.insights.CapturedInteraction;
import com.vaadin.observability.micrometer.insights.RecentClientErrors;
import com.vaadin.observability.micrometer.insights.RecentInteractions;

/**
 * What the dev-tools panel is sent. In this package rather than next to the
 * handler because the buffers are bound through {@code ObservabilityKit}'s
 * package-private setters, exactly as {@code MetricsServiceInitListener} binds
 * them at {@code serviceInit}.
 */
class ObservabilityDevToolsHandlerTest {

    private static final String COMMAND_REFRESH = "observability-kit-refresh";
    private static final String COMMAND_METRICS = "observability-kit-metrics";
    private static final String COMMAND_INSIGHTS = "observability-kit-insights";
    private static final String COMMAND_INSIGHTS_DATA = "observability-kit-insights-data";

    /**
     * Captures every message sent, so a test can read what reached the browser
     * and under which command.
     */
    private static class CapturingDevTools implements DevToolsInterface {

        private final List<String> commands = new ArrayList<>();
        private final Map<String, Map<String, Object>> payloads = new LinkedHashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public void send(String command, Object data) {
            commands.add(command);
            payloads.put(command, (Map<String, Object>) data);
        }
    }

    private ObservabilityDevToolsHandler handler;
    private CapturingDevTools devTools;

    @BeforeEach
    void setUp() {
        ObservabilityKit.reset();
        handler = new ObservabilityDevToolsHandler();
        devTools = new CapturingDevTools();
    }

    @AfterEach
    void tearDown() {
        ObservabilityKit.reset();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> insights() {
        return (List<Map<String, Object>>) devTools.payloads
                .get(COMMAND_INSIGHTS_DATA).get("insights");
    }

    private static CapturedInteraction failedClick(String component) {
        return new CapturedInteraction(Instant.now(), "orders", "orders/1",
                component, "click", "publishedEventHandler",
                CapturedInteraction.OUTCOME_ERROR, 12, 500, false,
                "java.lang.NullPointerException", null,
                "com.example.OrderView.save(OrderView.java:42)", null, "abc123",
                1);
    }

    @Test
    void connect_sendsBothAMeterSnapshotAndTheInsights() {
        ObservabilityKit.setActiveMeterRegistry(new SimpleMeterRegistry());
        RecentInteractions interactions = new RecentInteractions(10);
        interactions.add(failedClick("com.example.SaveButton"));
        ObservabilityKit.setRecentInteractions(interactions);

        handler.handleConnect(devTools);

        Assertions.assertEquals(List.of(COMMAND_METRICS, COMMAND_INSIGHTS_DATA),
                devTools.commands);
        Assertions.assertNotNull(
                devTools.payloads.get(COMMAND_METRICS).get("meters"));
        Assertions.assertEquals(1, insights().size());
        Assertions.assertEquals("user-interaction-error",
                insights().get(0).get("type"));
    }

    @Test
    void refresh_sendsOnlyTheMeters() {
        ObservabilityKit.setActiveMeterRegistry(new SimpleMeterRegistry());

        Assertions.assertTrue(
                handler.handleMessage(COMMAND_REFRESH, null, devTools));

        // The panel polls this one only while it is open, and polls the
        // insights whether or not it is: answering with both would put the
        // whole registry on the wire for a watch that never reads it.
        Assertions.assertEquals(List.of(COMMAND_METRICS), devTools.commands);
    }

    @Test
    void insights_sendTheEndpointPayloadFromEveryBuffer() {
        RecentInteractions interactions = new RecentInteractions(10);
        interactions.add(failedClick("com.example.SaveButton"));
        ObservabilityKit.setRecentInteractions(interactions);
        RecentClientErrors clientErrors = new RecentClientErrors(10);
        clientErrors.add(new CapturedClientError(Instant.now(), "dashboard",
                "uncaught", null, "/VAADIN/chart.js", "/VAADIN/chart.js:12:9",
                null, 0, false, "abc123", 1));
        ObservabilityKit.setRecentClientErrors(clientErrors);

        Assertions.assertTrue(
                handler.handleMessage(COMMAND_INSIGHTS, null, devTools));

        Map<String, Object> payload = devTools.payloads
                .get(COMMAND_INSIGHTS_DATA);
        // The actuator payload as it stands, not a projection of it: same
        // schema version, same instrumentation answer, same order.
        Assertions.assertEquals(1, payload.get("schemaVersion"));
        Assertions.assertEquals("active", payload.get("instrumentation"));
        Assertions.assertNotNull(payload.get("generated"));
        List<String> types = insights().stream()
                .map(insight -> (String) insight.get("type")).toList();
        Assertions.assertEquals(
                List.of("user-interaction-error", "client-error"), types);
    }

    @Test
    void noBuffersBound_saysInstrumentationIsInactive() {
        ObservabilityKit.setActiveMeterRegistry(new SimpleMeterRegistry());

        handler.handleConnect(devTools);

        // Not the same answer as "no findings": the panel says the setting is
        // off rather than implying nothing is wrong.
        Assertions.assertEquals("inactive", devTools.payloads
                .get(COMMAND_INSIGHTS_DATA).get("instrumentation"));
        Assertions.assertEquals(List.of(), insights());
        Assertions.assertNotNull(
                devTools.payloads.get(COMMAND_METRICS).get("meters"));
    }

    @Test
    void unknownCommand_isNotClaimed() {
        Assertions.assertFalse(
                handler.handleMessage("something-else", null, devTools));
        Assertions.assertEquals(List.of(), devTools.commands);
    }
}
