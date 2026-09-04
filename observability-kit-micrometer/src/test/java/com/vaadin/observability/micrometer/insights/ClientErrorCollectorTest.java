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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.vaadin.observability.micrometer.MeterNames;
import com.vaadin.observability.micrometer.ObservabilitySettings;

/**
 * The browser half of the insights contract: what a reported error is kept as,
 * and how it renders once several browsers have hit the same one.
 * <p>
 * The listeners that produce these reports are JavaScript and need a browser,
 * but the server half — the detail policy, the grouping, the arithmetic that
 * says a report waited out an outage — is reached by handing the collector the
 * batch a browser would send.
 */
class ClientErrorCollectorTest {

    /** The specifiers the insight strings use. */
    private static final Pattern FORMAT_SPECIFIER = Pattern.compile("%[sd]");

    private final RecentClientErrors buffer = new RecentClientErrors(100);

    private static ObservabilitySettings settings(boolean details) {
        return ObservabilitySettings.builder().insightsDetails(details).build();
    }

    private static Map<String, String> detail(String message, String source,
            String frame) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put(ClientErrorCollector.DETAIL_MESSAGE, message);
        detail.put(ClientErrorCollector.DETAIL_SOURCE, source);
        detail.put(ClientErrorCollector.DETAIL_FRAME, frame);
        return detail;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> clientErrorInsight() {
        List<Map<String, Object>> insights = (List<Map<String, Object>>) new InsightsService(
                null, null, buffer).payload().get("insights");
        return insights.stream()
                .filter(i -> "client-error".equals(i.get("type"))).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no client-error insight in " + insights));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> clientErrorInsights() {
        List<Map<String, Object>> insights = (List<Map<String, Object>>) new InsightsService(
                null, null, buffer).payload().get("insights");
        return insights.stream()
                .filter(i -> "client-error".equals(i.get("type"))).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> evidence(Map<String, Object> insight) {
        return (Map<String, Object>) insight.get("evidence");
    }

    @Test
    void theProseNamesTheLocationAndNotTheWholeStackLine() {
        // The frame reduces to its location, so the summary's own "at" is the
        // only one and there is nothing to unpick.
        new ClientErrorCollector(buffer, settings(true)).capture("uncaught",
                "orders", detail("failed", "/VAADIN/build/chart.js:44",
                        "at renderChart (chart.js:44)"),
                0, null);

        Map<String, Object> insight = clientErrorInsight();
        Assertions.assertTrue(
                insight.get("summary").toString().contains("at chart.js:44"),
                () -> "unexpected summary: " + insight.get("summary"));
        Assertions.assertFalse(
                insight.get("summary").toString().contains("at at"),
                () -> "doubled 'at': " + insight.get("summary"));
        Assertions.assertEquals("chart.js:44", evidence(insight).get("frame"));
        // The name travels separately, under the detail gate.
        Assertions.assertEquals("renderChart",
                evidence(insight).get("function"));
    }

    @Test
    void theTwoFieldsTheCollectorActuallySendsAreUsedAsSent() {
        // What the browser posts: the location it extracted, and the name in
        // its own field. Nothing has to be split apart again on this side.
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put(ClientErrorCollector.DETAIL_SOURCE, "/chart.js:9");
        detail.put(ClientErrorCollector.DETAIL_FRAME, "chart.js:44:13");
        detail.put(ClientErrorCollector.DETAIL_FUNCTION, "renderChart");
        new ClientErrorCollector(buffer, settings(true)).capture("uncaught",
                "orders", detail, 0, null);

        Map<String, Object> insight = clientErrorInsight();
        Assertions.assertTrue(
                insight.get("summary").toString().contains("at chart.js:44:13"),
                () -> "unexpected summary: " + insight.get("summary"));
        Assertions.assertEquals("chart.js:44:13",
                evidence(insight).get("frame"));
        Assertions.assertEquals("renderChart",
                evidence(insight).get("function"));
    }

    @Test
    void aScopedPackagePathIsNotSplitAtItsAt() {
        // The live bug this replaced: /node_modules/@vaadin/... parsed as a
        // Firefox frame in "vaadin/router/router.js" called
        // "/node_modules/", so the published location pointed at a path that
        // does not exist.
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put(ClientErrorCollector.DETAIL_FRAME,
                "/node_modules/@vaadin/router/router.js:12:3");
        new ClientErrorCollector(buffer, settings(true)).capture("uncaught",
                "orders", detail, 0, null);

        CapturedClientError error = buffer.snapshot().get(0);
        Assertions.assertEquals("/node_modules/@vaadin/router/router.js:12:3",
                error.frame());
        Assertions.assertNull(error.function());
    }

    @Test
    void theFunctionNameIsWithheldWithTheMessage() {
        new ClientErrorCollector(buffer, settings(false))
                .capture("uncaught", "orders",
                        detail("failed", "/VAADIN/build/chart.js:44",
                                "at handleCardNumber4111 (chart.js:44)"),
                        0, null);

        Map<String, Object> evidence = evidence(clientErrorInsight());
        Assertions.assertEquals("chart.js:44", evidence.get("frame"),
                "the location is published either way");
        Assertions.assertNull(evidence.get("function"),
                "the name is a string the page chose, so it is gated");
        Assertions.assertTrue(
                evidence.get("detail").toString().contains("function name"),
                () -> "the payload should say so: " + evidence.get("detail"));
    }

    @Test
    void theSourceFallbackKeepsTheProsesAt() {
        // No stack: the summary points at the script instead, and that does
        // need the "at" the frame branch strips.
        new ClientErrorCollector(buffer, settings(true)).capture("promise",
                "orders", detail("failed", "/VAADIN/build/chart.js:44", ""), 0,
                null);

        Assertions.assertTrue(
                clientErrorInsight().get("summary").toString()
                        .contains("at /VAADIN/build/chart.js:44"),
                "the source fallback should read as a location");
    }

    /** What the collector retained for a report carrying these two fields. */
    private CapturedClientError retained(String source, String frame) {
        RecentClientErrors errors = new RecentClientErrors(1);
        new ClientErrorCollector(errors, settings(false)).capture("uncaught",
                "uc5", detail("m", source, frame), 0, null);
        return errors.snapshot().get(0);
    }

    @Test
    void bothTheSourceAndTheFrameGoThroughTheLocationFilter() {
        // The rules themselves are pinned by StackFramesTest; what this pins
        // is that the collector applies them to both fields, since both are
        // published whatever the detail policy says.
        CapturedClientError real = retained("/VAADIN/build/chart.js:44",
                "at renderChart (chart.js:44)");
        Assertions.assertEquals("/VAADIN/build/chart.js:44", real.source());
        Assertions.assertEquals("chart.js:44", real.frame());
        Assertions.assertNull(real.function(),
                "this fixture has detail off, so no name is kept");

        CapturedClientError crafted = retained(
                "card number 4111 1111 1111 1111 declined:1",
                "TypeError: card number 4111 is bad");
        Assertions.assertNull(crafted.source(),
                "free text is not a source, and source is not gated");
        Assertions.assertNull(crafted.frame(),
                "free text is not a frame, and frame is not gated");
    }

    @Test
    void aFloodOfCraftedLocationsCannotBuryTheRealFinding() {
        // source and frame are the only parts of the grouping key the browser
        // chooses, and any script on the page can post them through the
        // @ClientCallable: one report per location fills the payload with one
        // group per report. Capped and ranked instead, so what a reader sees
        // is what most browsers actually hit.
        ClientErrorCollector collector = new ClientErrorCollector(buffer,
                settings(false));
        for (int i = 0; i < 5; i++) {
            collector.capture(
                    "uncaught", "orders", detail("boom",
                            "/VAADIN/build/chart.js:44", "chart.js:44:13"),
                    0, null);
        }
        for (int i = 0; i < 60; i++) {
            collector.capture("uncaught", "orders",
                    detail("boom", "/a.js:1:" + i, "/a.js:1:" + i), 0, null);
        }

        List<Map<String, Object>> insights = clientErrorInsights();
        Assertions.assertEquals(20, insights.size(),
                () -> "65 reports should not be 61 insights: " + insights.size()
                        + " groups in the payload");
        Map<String, Object> first = evidence(insights.get(0));
        Assertions.assertEquals(5, first.get("occurrences"),
                "the most-reported group comes first");
        Assertions.assertEquals("chart.js:44:13", first.get("frame"),
                "and it is the one real browsers hit");
    }

    @Test
    void aLocationThatNamesThePageItselfIsNotKept() {
        // For an error from an inline script, an inline handler or executeJs
        // code, a browser reports the document URL as the filename and writes
        // it into the frame. That is the page's own path with its query
        // string -- not a script location, and published above the detail
        // gate, so the token in it would be too.
        Map<String, String> detail = detail("boom",
                "https://app.example.com/orders/17?token=abc123:0",
                "https://app.example.com/orders/17?token=abc123:1:5");
        detail.put(ClientErrorCollector.DETAIL_ROUTE, "/orders/17");
        RecentClientErrors errors = new RecentClientErrors(1);
        new ClientErrorCollector(errors, settings(false)).capture("uncaught",
                "orders/:id", detail, 0, null);

        CapturedClientError kept = errors.snapshot().get(0);
        Assertions.assertNull(kept.source(),
                "the document URL is not a script location");
        Assertions.assertNull(kept.frame(),
                "and neither is it when it arrives as a frame");
    }

    @Test
    void aScriptServedFromThePageIsStillKept() {
        // The rule is about the page itself, not about the origin it came
        // from: a real script on the same host has to survive it.
        Map<String, String> detail = detail("boom",
                "https://app.example.com/VAADIN/build/chart.js:44",
                "https://app.example.com/VAADIN/build/chart.js:44:13");
        detail.put(ClientErrorCollector.DETAIL_ROUTE, "/orders/17");
        RecentClientErrors errors = new RecentClientErrors(1);
        new ClientErrorCollector(errors, settings(false)).capture("uncaught",
                "orders/:id", detail, 0, null);

        CapturedClientError kept = errors.snapshot().get(0);
        Assertions.assertEquals(
                "https://app.example.com/VAADIN/build/chart.js:44",
                kept.source());
        Assertions.assertEquals(
                "https://app.example.com/VAADIN/build/chart.js:44:13",
                kept.frame());
    }

    @Test
    void aReportWithNoLocationAtAllStillReadsAsProse() {
        // Only the count and the route: the kit's own collector always sends a
        // source, so this is a crafted or truncated payload. The sentinel the
        // grouping key uses must not reach the prose.
        Map<String, String> routeOnly = new LinkedHashMap<>();
        routeOnly.put(ClientErrorCollector.DETAIL_ROUTE, "uc5");
        new ClientErrorCollector(buffer, settings(true)).capture("uncaught",
                "uc5", routeOnly, 0, null);

        Map<String, Object> insight = clientErrorInsight();
        Assertions.assertFalse(
                insight.get("summary").toString().contains("at _unknown"),
                () -> "sentinel in the prose: " + insight.get("summary"));
        Assertions.assertFalse(
                insight.get("suggestion").toString().contains("_unknown"),
                () -> "sentinel in the prose: " + insight.get("suggestion"));
    }

    @Test
    void replayOpensTheRouteTemplateRatherThanAPath() {
        // What is retained is the route the path resolved to, so the step reads
        // as a route -- unlike the interaction insights, which keep the
        // concrete path the user was on and open it with a leading slash.
        new ClientErrorCollector(buffer, settings(true)).capture("uncaught",
                "orders/:orderID",
                detail("failed", "/chart.js:44", "at run (app.js:1)"), 0, null);

        Assertions.assertEquals("Open route 'orders/:orderID'",
                ((List<?>) clientErrorInsight().get("replay")).get(0));
    }

    @Test
    void capturesTheDetailTheCounterCannotCarry() {
        new ClientErrorCollector(buffer, settings(true)).capture("uncaught",
                "uc5",
                detail("rendering the sales chart failed",
                        "/VAADIN/build/chart.js:44",
                        "at renderChart (chart.js:44)"),
                0, null);

        CapturedClientError error = buffer.snapshot().get(0);
        Assertions.assertEquals("uncaught", error.kind());
        Assertions.assertEquals("uc5", error.route());
        Assertions.assertEquals("rendering the sales chart failed",
                error.message());
        Assertions.assertEquals("chart.js:44", error.frame());
        Assertions.assertEquals("renderChart", error.function());
    }

    @Test
    void withholdsTheMessageUnlessDetailsAreEnabled() {
        new ClientErrorCollector(buffer, settings(false)).capture("promise",
                "uc5",
                detail("fetching /api/quotes?token=abc failed", "/uc5", ""), 0,
                null);

        CapturedClientError error = buffer.snapshot().get(0);
        Assertions.assertNull(error.message(),
                "a browser error message can quote anything the page held");
        Assertions.assertFalse(error.detailsIncluded());
        // The insight has to say the message was withheld, or a reader cannot
        // tell that from an error that carried none.
        Assertions.assertTrue(
                evidence(clientErrorInsight()).get("detail").toString()
                        .contains("insights-details"),
                "the payload should name the switch that would collect it");
    }

    @Test
    void aCraftedKindCannotSplitTheInsight() {
        ClientErrorCollector collector = new ClientErrorCollector(buffer,
                settings(true));
        collector.capture("uncaught", "uc5",
                detail("a", "app.js:1", "at run (app.js:1)"), 0, null);
        collector.capture("made-up-kind", "uc5",
                detail("a", "app.js:1", "at run (app.js:1)"), 0, null);

        Assertions.assertEquals(MeterNames.KIND_UNKNOWN,
                buffer.snapshot().get(0).kind());
    }

    @Test
    void aCountOnlyReportRetainsNothing() {
        // The kit's own collector sends detail with every error, but a report
        // without it is still a valid count and must not produce an empty
        // insight.
        new ClientErrorCollector(buffer, settings(true)).capture("uncaught",
                "uc5", Map.of(), 0, null);

        Assertions.assertEquals(List.of(), buffer.snapshot());
    }

    @Test
    void theSameScriptFailingInManyTabsIsOneInsight() {
        ClientErrorCollector collector = new ClientErrorCollector(buffer,
                settings(true));
        for (int i = 0; i < 5; i++) {
            collector.capture("uncaught", "uc5",
                    detail("rendering the sales chart failed",
                            "/VAADIN/build/chart.js:44",
                            "at renderChart (chart.js:44)"),
                    0, null);
        }

        Assertions.assertEquals(5,
                evidence(clientErrorInsight()).get("occurrences"));
    }

    @Test
    void aDifferentFrameIsADifferentInsight() {
        ClientErrorCollector collector = new ClientErrorCollector(buffer,
                settings(true));
        collector.capture("uncaught", "uc5", detail("failed", "/chart.js:44",
                "at renderChart (chart.js:44)"), 0, null);
        collector.capture("uncaught", "uc5",
                detail("failed", "/grid.js:12", "at renderGrid (grid.js:12)"),
                0, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> insights = (List<Map<String, Object>>) new InsightsService(
                null, null, buffer).payload().get("insights");
        Assertions.assertEquals(2, insights.size(),
                "two locations are two findings, not one with two occurrences");
    }

    @Test
    void reportsHowLongTheBrowserHadToHoldTheReport() {
        // The point of the whole feature: this error happened while that
        // browser could not reach the server, so no server log contains it.
        ClientErrorCollector collector = new ClientErrorCollector(buffer,
                settings(true));
        collector.capture("uncaught", "uc5",
                detail("failed", "/chart.js:9", "at run (app.js:1)"), 1000,
                null);
        collector.capture("uncaught", "uc5",
                detail("failed", "/chart.js:9", "at run (app.js:1)"), 7400,
                null);

        Map<String, Object> insight = clientErrorInsight();
        Assertions.assertEquals(7400L, evidence(insight).get("maxBufferedMs"));
        Assertions.assertTrue(
                insight.get("summary").toString()
                        .contains("after the browser got the server back"),
                "the summary should say the report outlived an outage");
        // A group maximum, and the group's own occurrences say so: claiming
        // the latest one waited would be the wrong inference to invite.
        Assertions.assertTrue(
                evidence(insight).get("measures").toString()
                        .contains("longest any occurrence"),
                () -> "unexpected measures: "
                        + evidence(insight).get("measures"));
    }

    @Test
    void anImmediateReportSaysNothingAboutAnOutage() {
        new ClientErrorCollector(buffer, settings(true)).capture("uncaught",
                "uc5", detail("failed", "/chart.js:9", "at run (app.js:1)"), 0,
                null);

        Map<String, Object> insight = clientErrorInsight();
        Assertions.assertEquals(0L, evidence(insight).get("maxBufferedMs"));
        Assertions.assertFalse(insight.get("summary").toString()
                .contains("after the browser got the server back"));
    }

    @Test
    void browserErrorsAloneCountAsActiveInstrumentation() {
        buffer.add(new CapturedClientError(Instant.now(), "uc5", "uncaught",
                null, "/chart.js:44", "/chart.js:44:9", null, 0, false, "hash",
                3));

        Assertions.assertEquals("active",
                new InsightsService(null, null, buffer).payload()
                        .get("instrumentation"));
    }

    @Test
    void everyRenderedStringIsFullyFormatted() {
        // A format specifier that survives into the payload is invisible to an
        // assertion on any one field, so sweep the whole insight: `%d` reached
        // `suggestion` once, because `+` binds looser than the `.formatted`
        // that was meant to consume it.
        ClientErrorCollector collector = new ClientErrorCollector(buffer,
                settings(true));
        collector.capture(
                "uncaught", "uc5", detail("rendering the sales chart failed",
                        "/chart.js:44", "at renderChart (chart.js:44)"),
                7400, null);

        assertNoFormatSpecifiers(clientErrorInsight());
    }

    @Test
    void anUnheldReportRendersNoFormatSpecifiersEither() {
        // The other branch of the same conditional.
        new ClientErrorCollector(buffer, settings(false)).capture("promise",
                "uc5", detail("failed", "/uc5", ""), 0, null);

        assertNoFormatSpecifiers(clientErrorInsight());
    }

    /**
     * Fails on any {@code %s} / {@code %d} left in a rendered value, however
     * deeply nested in the insight.
     */
    private static void assertNoFormatSpecifiers(Object value) {
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(
                    ClientErrorCollectorTest::assertNoFormatSpecifiers);
        } else if (value instanceof Iterable<?> items) {
            items.forEach(ClientErrorCollectorTest::assertNoFormatSpecifiers);
        } else if (value instanceof String text) {
            Assertions.assertFalse(FORMAT_SPECIFIER.matcher(text).find(),
                    () -> "unformatted " + "specifier left in the payload: "
                            + text);
        }
    }

    @Test
    void aNegativeAgeIsNotReported() {
        // ageMs arrives from the browser; a clock adjustment mid-outage must
        // not produce a negative delay in the payload.
        new ClientErrorCollector(buffer, settings(true)).capture("uncaught",
                "uc5", detail("failed", "/chart.js:9", "at run (app.js:1)"),
                -5000, null);

        Assertions.assertEquals(0L, buffer.snapshot().get(0).bufferedMs());
    }
}
