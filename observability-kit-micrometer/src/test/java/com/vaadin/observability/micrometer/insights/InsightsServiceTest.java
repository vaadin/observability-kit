/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InsightsServiceTest {

    private static final long OVER_BUDGET = InteractionCollector.UX_BUDGET_MS
            + 2000;
    /** The budget a captured slow interaction was measured against. */
    private static final long OVER_BUDGET_THRESHOLD = InteractionCollector.UX_BUDGET_MS;

    private final RecentInteractions buffer = new RecentInteractions(100);
    private final InsightsService service = new InsightsService(buffer);

    private static CapturedInteraction error(Instant when, String component,
            String exception, String frame) {
        return new CapturedInteraction(when, "orders", "orders/17", component,
                "click", "event", CapturedInteraction.OUTCOME_ERROR, 5, -1,
                true, exception, exception + " message", frame,
                List.of(frame, "framework.Frame.call(Frame.java:1)"), "session",
                0);
    }

    private static CapturedInteraction slow(Instant when, String component,
            long durationMs) {
        return new CapturedInteraction(when, "orders", "orders/17", component,
                "click", "event", CapturedInteraction.OUTCOME_SUCCESS,
                durationMs, OVER_BUDGET_THRESHOLD, true, null, null, null, null,
                "session", 0);
    }

    private static CapturedInteraction slowWithBudget(long durationMs,
            long thresholdMs) {
        return new CapturedInteraction(Instant.now(), "orders", "orders/17",
                "com.example.OrdersView", "click", "event",
                CapturedInteraction.OUTCOME_SUCCESS, durationMs, thresholdMs,
                true, null, null, null, null, "session", 0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> insights() {
        return (List<Map<String, Object>>) service.payload().get("insights");
    }

    private Map<String, Object> insightWithId(String id) {
        return insights().stream().filter(i -> id.equals(i.get("id")))
                .findFirst().orElseThrow(
                        () -> new AssertionError("no insight with id " + id));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> evidence(Map<String, Object> insight) {
        return (Map<String, Object>) insight.get("evidence");
    }

    @Test
    void payloadCarriesSchemaVersionAndTimestamp() {
        Map<String, Object> payload = service.payload();
        Assertions.assertEquals(1, payload.get("schemaVersion"));
        Assertions.assertNotNull(payload.get("generated"),
                "payload should carry a generation timestamp");
        Assertions.assertTrue(insights().isEmpty(),
                "no interactions should yield no insights");
    }

    @Test
    void failedInteractionProducesErrorInsight() {
        buffer.add(error(Instant.now(), "com.example.OrdersView",
                "java.lang.NullPointerException",
                "com.example.OrderService.ship(OrderService.java:30)"));

        Map<String, Object> insight = insightWithId("user-interaction-error");
        Assertions.assertEquals("error", insight.get("severity"));
        Assertions.assertEquals("reliability", insight.get("category"));

        Map<String, Object> evidence = evidence(insight);
        Assertions.assertEquals("orders", evidence.get("route"));
        Assertions.assertEquals("java.lang.NullPointerException",
                evidence.get("exception"));
        Assertions.assertEquals(
                "com.example.OrderService.ship(OrderService.java:30)",
                evidence.get("applicationFrame"));
        Assertions.assertEquals(1, evidence.get("occurrences"));

        Assertions.assertTrue(
                insight.get("summary").toString()
                        .contains("NullPointerException"),
                "summary should name the exception by simple name");
        Assertions.assertNotNull(insight.get("replay"));
        Assertions.assertNotNull(insight.get("suggestion"));
    }

    @Test
    void slowInteractionProducesPerformanceInsight() {
        buffer.add(slow(Instant.now(), "com.example.OrdersView", OVER_BUDGET));

        Map<String, Object> insight = insightWithId("slow-user-interaction");
        Assertions.assertEquals("warning", insight.get("severity"));
        Assertions.assertEquals("performance", insight.get("category"));

        Map<String, Object> evidence = evidence(insight);
        Assertions.assertEquals(OVER_BUDGET, evidence.get("maxDurationMs"));
        Assertions.assertEquals(InteractionCollector.UX_BUDGET_MS,
                evidence.get("thresholdMs"));
    }

    @Test
    void repeatedSameFailureCollapsesToOneInsightWithOccurrenceCount() {
        String frame = "com.example.OrderService.ship(OrderService.java:30)";
        buffer.add(error(Instant.parse("2026-07-07T10:00:00Z"),
                "com.example.OrdersView", "java.lang.NullPointerException",
                frame));
        buffer.add(error(Instant.parse("2026-07-07T10:05:00Z"),
                "com.example.OrdersView", "java.lang.NullPointerException",
                frame));

        List<Map<String, Object>> errors = insights().stream()
                .filter(i -> "user-interaction-error".equals(i.get("id")))
                .toList();
        Assertions.assertEquals(1, errors.size(),
                "identical failures should collapse into a single insight");
        Map<String, Object> evidence = evidence(errors.get(0));
        Assertions.assertEquals(2, evidence.get("occurrences"));
        Assertions.assertEquals("2026-07-07T10:00:00Z",
                evidence.get("firstSeen"), "firstSeen is the earliest");
        Assertions.assertEquals("2026-07-07T10:05:00Z",
                evidence.get("lastSeen"), "lastSeen is the latest");
    }

    @Test
    void differentExceptionsProduceSeparateInsights() {
        buffer.add(error(Instant.now(), "com.example.OrdersView",
                "java.lang.NullPointerException", "com.example.A.x(A.java:1)"));
        buffer.add(error(Instant.now(), "com.example.OrdersView",
                "java.lang.IllegalStateException",
                "com.example.B.y(B.java:2)"));

        long errorInsights = insights().stream()
                .filter(i -> "user-interaction-error".equals(i.get("id")))
                .count();
        Assertions.assertEquals(2, errorInsights,
                "distinct exceptions must not be grouped together");
    }

    @Test
    void slowInsightReportsMaxDurationAcrossOccurrences() {
        buffer.add(slow(Instant.now(), "com.example.OrdersView", OVER_BUDGET));
        buffer.add(slow(Instant.now(), "com.example.OrdersView",
                OVER_BUDGET + 5000));

        Map<String, Object> evidence = evidence(
                insightWithId("slow-user-interaction"));
        Assertions.assertEquals(2, evidence.get("occurrences"));
        Assertions.assertEquals(OVER_BUDGET + 5000,
                evidence.get("maxDurationMs"),
                "maxDurationMs should be the worst observed duration");
    }

    @Test
    void errorsAndSlowInteractionsCoexistAsDistinctInsightTypes() {
        buffer.add(error(Instant.now(), "com.example.OrdersView",
                "java.lang.NullPointerException",
                "com.example.OrderService.ship(OrderService.java:30)"));
        buffer.add(slow(Instant.now(), "com.example.OrdersView", OVER_BUDGET));

        List<String> ids = insights().stream().map(i -> i.get("id").toString())
                .toList();
        Assertions.assertTrue(ids.contains("user-interaction-error"));
        Assertions.assertTrue(ids.contains("slow-user-interaction"));
        Assertions.assertEquals(2, ids.size());
    }

    @Test
    void nullComponentRendersAsUnknownPlaceholder() {
        buffer.add(slow(Instant.now(), null, OVER_BUDGET));

        Map<String, Object> insight = insightWithId("slow-user-interaction");
        Assertions.assertTrue(
                insight.get("summary").toString().contains("_unknown"),
                "a null component should render as the _unknown placeholder");
    }

    @Test
    void reportsInstrumentationInactiveWhenNothingIsBound() {
        // "no insights" and "nothing was watching" are different answers; a
        // consumer cannot act on the second one unless it is told.
        Map<String, Object> payload = new InsightsService(null).payload();

        Assertions.assertEquals("inactive", payload.get("instrumentation"),
                "an unbound buffer should be reported as inactive");
        Assertions.assertTrue(((List<?>) payload.get("insights")).isEmpty(),
                "no insights should be reported when nothing is bound");
    }

    @Test
    void reportsInstrumentationActiveWhenBound() {
        Assertions.assertEquals("active",
                service.payload().get("instrumentation"),
                "a bound buffer should be reported as active");
    }

    @Test
    void keepsInteractionsCapturedUnderALowerBudget() {
        // A collector configured with a 250 ms budget retains a 300 ms
        // interaction. Re-checking it against the static 1 s default would
        // silently drop it, losing data the collector deliberately kept.
        buffer.add(slowWithBudget(300, 250));

        Map<String, Object> insight = insightWithId("slow-user-interaction");
        Map<String, Object> evidence = evidence(insight);
        Assertions.assertEquals(250L, evidence.get("thresholdMs"),
                "the insight should report the budget actually in force");
        Assertions.assertEquals(300L, evidence.get("maxDurationMs"));
    }

    @Test
    void slowInsightSaysItMeasuresServerHandlingOnly() {
        // The duration covers the RPC invocation, not lock wait, network or
        // client rendering, so the report must not imply perceived latency.
        buffer.add(slowWithBudget(1500, 1000));

        Map<String, Object> insight = insightWithId("slow-user-interaction");
        Assertions.assertTrue(
                insight.get("summary").toString().startsWith("Server handling"),
                "summary should scope the claim to server handling: "
                        + insight.get("summary"));
        Assertions.assertTrue(
                evidence(insight).get("measures").toString()
                        .contains("server-side RPC handling only"),
                "evidence should state what was measured");
    }

    @Test
    void examplesCarryTheConcreteLocation() {
        buffer.add(slowWithBudget(1500, 1000));

        Map<String, Object> insight = insightWithId("slow-user-interaction");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> examples = (List<Map<String, Object>>) insight
                .get("examples");
        Assertions.assertEquals("orders/17", examples.get(0).get("location"),
                "the concrete location belongs in the examples block");
    }

    @Test
    void payloadSaysWhenDetailWasWithheldRatherThanAbsent() {
        // An error captured with detail off carries no message and no frames.
        // The payload has to say so: a missing message would otherwise read as
        // an exception that simply had none.
        buffer.add(new CapturedInteraction(Instant.now(), "orders", "orders/17",
                "com.example.OrdersView", "click", "event",
                CapturedInteraction.OUTCOME_ERROR, 5, -1, false,
                "java.lang.IllegalStateException", null,
                "com.example.OrderService.ship(OrderService.java:30)", null,
                "9f2b1c4d5e6a", 0));

        Map<String, Object> insight = insightWithId("user-interaction-error");
        Map<String, Object> evidence = evidence(insight);

        Assertions.assertFalse(evidence.containsKey("message"),
                "a withheld message should not be rendered as a null value");
        Assertions.assertTrue(
                evidence.get("detail").toString().contains("insights-details"),
                "the payload should name the setting that collects the detail");
        // The actionable part survives.
        Assertions.assertEquals(
                "com.example.OrderService.ship(OrderService.java:30)",
                evidence.get("applicationFrame"));
        Assertions.assertEquals("java.lang.IllegalStateException",
                evidence.get("exception"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> examples = (List<Map<String, Object>>) insight
                .get("examples");
        Assertions.assertFalse(examples.get(0).containsKey("stackTop"),
                "withheld stack frames should be absent from the examples");
        Assertions.assertEquals("9f2b1c4d5e6a",
                examples.get(0).get("sessionId"),
                "the hashed session id should still correlate the examples");

        // And the replay step must not print a null message.
        Assertions.assertTrue(
                insight.get("replay").toString()
                        .contains("Expect IllegalStateException")
                        && !insight.get("replay").toString().contains("null"),
                "replay should read cleanly without a message: "
                        + insight.get("replay"));
    }
}
