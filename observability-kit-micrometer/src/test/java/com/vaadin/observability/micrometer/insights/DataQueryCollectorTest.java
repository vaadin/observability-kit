/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.data.DataCountEndedEvent;
import com.vaadin.flow.server.data.DataCountStartedEvent;
import com.vaadin.flow.server.data.DataFetchEndedEvent;
import com.vaadin.flow.server.data.DataFetchFailedEvent;
import com.vaadin.flow.server.data.DataFetchStartedEvent;
import com.vaadin.observability.micrometer.DatabaseActivity;
import com.vaadin.observability.micrometer.ObservabilitySettings;

class DataQueryCollectorTest {

    /** Budget of 0 makes any query qualify as slow. */
    private static final long CAPTURE_ALL = 0;
    /** Budget beyond any elapsed time, so nothing qualifies as slow. */
    private static final long CAPTURE_NONE = Long.MAX_VALUE;

    @Tag("test-combo")
    private static class TestComponent extends Component {
    }

    private final RecentQueries buffer = new RecentQueries(100);
    private final UI ui = new UI();
    private final Component component = new TestComponent();

    private DataQueryCollector collector(long budget) {
        return new DataQueryCollector(buffer, ObservabilitySettings.builder()
                .errors(true).requests(true).build(), budget);
    }

    @Test
    void capturesSlowFetchWithTheRangeAndRowCount() {
        DataQueryCollector collector = collector(CAPTURE_ALL);
        collector.fetchStarted(
                new DataFetchStartedEvent(ui, component, 100, 50, true));
        collector.fetchEnded(
                new DataFetchEndedEvent(ui, component, 100, 50, true, 30));

        List<CapturedQuery> snapshot = buffer.snapshot();
        Assertions.assertEquals(1, snapshot.size());
        CapturedQuery query = snapshot.get(0);
        Assertions.assertEquals(CapturedQuery.KIND_FETCH, query.kind());
        Assertions.assertEquals(CapturedQuery.OUTCOME_SUCCESS, query.outcome());
        Assertions.assertEquals(component.getClass().getName(),
                query.component());
        Assertions.assertEquals(100, query.offset());
        Assertions.assertEquals(50, query.limit());
        Assertions.assertEquals(30, query.rows(),
                "a short page must stay distinguishable from the limit");
        Assertions.assertTrue(query.filtered());
    }

    @Test
    void capturesFailedFetchWithTheExceptionType() {
        collector(CAPTURE_NONE)
                .fetchFailed(new DataFetchFailedEvent(ui, component, 0, 50,
                        false, new IllegalStateException("backend down")));

        CapturedQuery query = buffer.snapshot().get(0);
        Assertions.assertEquals(CapturedQuery.OUTCOME_ERROR, query.outcome());
        Assertions.assertEquals("java.lang.IllegalStateException",
                query.exceptionType());
        Assertions.assertEquals(-1, query.rows(),
                "a failed fetch has no row count");
    }

    @Test
    void reportsTheRootCauseRatherThanTheWrapper() {
        collector(CAPTURE_NONE).fetchFailed(new DataFetchFailedEvent(ui,
                component, 0, 10, false, new RuntimeException("wrapped",
                        new IllegalArgumentException("the real problem"))));

        Assertions.assertEquals("java.lang.IllegalArgumentException",
                buffer.snapshot().get(0).exceptionType(),
                "the root cause is what identifies the problem");
    }

    @Test
    void doesNotCaptureFastQueries() {
        DataQueryCollector collector = collector(CAPTURE_NONE);
        collector.countStarted(new DataCountStartedEvent(ui, component, false));
        collector
                .countEnded(new DataCountEndedEvent(ui, component, false, 250));

        Assertions.assertTrue(buffer.snapshot().isEmpty(),
                "a query within the budget is not retained");
    }

    @Test
    void doesNotCaptureACountThatThrewAsSlow() {
        // -1 marks a failure, which countFailed already captured; it must not
        // also surface as a slow successful count.
        DataQueryCollector collector = collector(CAPTURE_ALL);
        collector.countStarted(new DataCountStartedEvent(ui, component, false));
        collector.countEnded(new DataCountEndedEvent(ui, component, false, -1));

        Assertions.assertTrue(buffer.snapshot().isEmpty(),
                "a failed count is not a slow successful one");
    }

    @Test
    void slowQueriesBecomeAGroupedInsight() {
        DataQueryCollector collector = collector(CAPTURE_ALL);
        for (int i = 0; i < 3; i++) {
            collector.fetchStarted(
                    new DataFetchStartedEvent(ui, component, 0, 50, false));
            collector.fetchEnded(
                    new DataFetchEndedEvent(ui, component, 0, 50, false, 50));
        }

        Map<String, Object> payload = new InsightsService(null, buffer)
                .payload();
        Assertions.assertEquals("active", payload.get("instrumentation"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> insights = (List<Map<String, Object>>) payload
                .get("insights");
        Assertions.assertEquals(1, insights.size(),
                "three occurrences of one problem are one insight");
        Map<String, Object> insight = insights.get(0);
        Assertions.assertEquals("slow-data-query", insight.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) insight
                .get("evidence");
        Assertions.assertEquals(3, evidence.get("occurrences"));
        Assertions.assertEquals(CapturedQuery.KIND_FETCH,
                evidence.get("queryKind"));
        Assertions.assertEquals(50, evidence.get("requested"));
        Assertions.assertEquals(50, evidence.get("returned"));
    }

    @Test
    void interactionAndQueryInsightsCoexistInOnePayload() {
        DataQueryCollector collector = collector(CAPTURE_ALL);
        collector.fetchStarted(
                new DataFetchStartedEvent(ui, component, 0, 50, false));
        collector.fetchEnded(
                new DataFetchEndedEvent(ui, component, 0, 50, false, 50));

        Map<String, Object> payload = new InsightsService(
                new RecentInteractions(10), buffer).payload();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> insights = (List<Map<String, Object>>) payload
                .get("insights");
        Assertions.assertEquals(1, insights.size(),
                "an empty interaction buffer contributes nothing, but the "
                        + "query insight still appears");
    }

    @Test
    void aCyclicCauseChainDoesNotSpinForever() {
        // A caused by B caused by A. This runs while the server is already
        // handling a failure, so looping here would hang the request.
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        collector(CAPTURE_NONE).fetchFailed(
                new DataFetchFailedEvent(ui, component, 0, 10, false, a));

        Assertions.assertEquals("java.lang.RuntimeException",
                buffer.snapshot().get(0).exceptionType(),
                "the walk stops at the loop rather than never returning");
    }

    @Test
    void aSlowCountInsightReportsWhatItCounted() {
        // The duration alone does not explain a slow count. The size of the
        // result does, and the collector already captures it.
        DataQueryCollector collector = collector(CAPTURE_ALL);
        collector.countStarted(new DataCountStartedEvent(ui, component, false));
        collector.countEnded(
                new DataCountEndedEvent(ui, component, false, 2_000_000));

        Map<String, Object> payload = new InsightsService(null, buffer)
                .payload();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> insights = (List<Map<String, Object>>) payload
                .get("insights");
        Map<String, Object> insight = insights.get(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) insight
                .get("evidence");
        Assertions.assertEquals(2_000_000, evidence.get("counted"),
                "a slow count has to say how much it counted");
        Assertions.assertTrue(
                insight.get("summary").toString().contains("2,000,000"),
                "and say it where a reader looks first");
    }

    @Test
    void theSummaryReadsTheSameUnderEveryDefaultLocale() {
        // The payload is a contract read by machines, so the numbers in it
        // cannot come from the server's locale. They did: `%,d` takes its
        // grouping separator from the default format locale (2.000.000 under
        // de-DE, 2 000 000 under fi-FI) and plain `%d` takes its digits from
        // it, so ar-EG rendered "takes ١٢ ms".
        Locale original = Locale.getDefault(Locale.Category.FORMAT);
        try {
            String reference = null;
            for (String tag : new String[] { "en-US", "fi-FI", "de-DE", "ar-EG",
                    "hi-IN-u-nu-deva" }) {
                Locale.setDefault(Locale.Category.FORMAT,
                        Locale.forLanguageTag(tag));

                RecentQueries queries = new RecentQueries(10);
                DataQueryCollector collector = new DataQueryCollector(queries,
                        ObservabilitySettings.builder().errors(true)
                                .requests(true).build(),
                        CAPTURE_ALL);
                collector.countStarted(
                        new DataCountStartedEvent(ui, component, false));
                collector.countEnded(new DataCountEndedEvent(ui, component,
                        false, 2_000_000));

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> insights = (List<Map<String, Object>>) new InsightsService(
                        null, queries).payload().get("insights");
                String summary = insights.get(0).get("summary").toString();

                Assertions.assertTrue(summary.contains("2,000,000"),
                        () -> "grouping separator followed the locale under "
                                + tag + ": " + summary);
                if (reference == null) {
                    reference = summary;
                } else {
                    Assertions.assertEquals(reference, summary,
                            () -> "the summary differed under " + tag);
                }
            }
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, original);
        }
    }

    @Test
    void aSlowFetchInsightStillReportsRequestedAgainstReturned() {
        DataQueryCollector collector = collector(CAPTURE_ALL);
        collector.fetchStarted(
                new DataFetchStartedEvent(ui, component, 0, 50, false));
        collector.fetchEnded(
                new DataFetchEndedEvent(ui, component, 0, 50, false, 30));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> insights = (List<Map<String, Object>>) new InsightsService(
                null, buffer).payload().get("insights");
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) insights.get(0)
                .get("evidence");

        Assertions.assertEquals(50, evidence.get("requested"));
        Assertions.assertEquals(30, evidence.get("returned"));
        Assertions.assertNull(evidence.get("counted"),
                "a fetch reports a range, not a count");
    }

    @Test
    void aSlowFetchReportsTheSqlWorkBehindIt() {
        // The shape that started this: a grid page of 150 items, answered by
        // one query for the table and one per row. "requested 150, returned
        // 150" is the same sentence either way, so without the SQL numbers the
        // insight describes a healthy fetch and a broken one identically.
        DatabaseActivity.instrumented();
        DataQueryCollector collector = collector(CAPTURE_ALL);
        collector.fetchStarted(
                new DataFetchStartedEvent(ui, component, 0, 150, false));
        for (int query = 0; query < 60_001; query++) {
            DatabaseActivity.queryExecuted();
        }
        DatabaseActivity.rowsRead(210_000);
        collector.fetchEnded(
                new DataFetchEndedEvent(ui, component, 0, 150, false, 150));

        CapturedQuery captured = buffer.snapshot().get(0);
        Assertions.assertEquals(60_001, captured.dbQueries());
        Assertions.assertEquals(210_000, captured.dbRows());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> insights = (List<Map<String, Object>>) new InsightsService(
                null, buffer).payload().get("insights");
        Map<String, Object> insight = insights.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) insight
                .get("evidence");

        Assertions.assertEquals(60_001L, evidence.get("sqlQueries"));
        Assertions.assertEquals(210_000L, evidence.get("sqlRowsRead"));
        Assertions.assertTrue(
                insight.get("summary").toString()
                        .contains("ran 60,001 SQL queries"),
                () -> "the cost belongs where a reader looks first: "
                        + insight.get("summary"));
        Assertions.assertTrue(
                insight.get("suggestion").toString()
                        .contains("60,001 SQL queries for 150 items returned"),
                () -> "a query per item is the finding, and the suggestion has "
                        + "to name it: " + insight.get("suggestion"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> examples = (List<Map<String, Object>>) insight
                .get("examples");
        Assertions.assertEquals(60_001L, examples.get(0).get("sqlQueries"),
                "each occurrence carries its own count, so a reader can see "
                        + "whether the cost is stable or growing");
    }

    @Test
    void aFetchThatCountedNoQueriesSaysNothingAboutTheDatabase() {
        // Nothing counted is not the same as nothing run: the count is
        // thread-confined, so a fetch on another thread measures nothing.
        // Reporting that as "0 SQL queries" would clear the database of a
        // problem it may well have.
        DatabaseActivity.instrumented();
        DataQueryCollector collector = collector(CAPTURE_ALL);
        collector.fetchStarted(
                new DataFetchStartedEvent(ui, component, 0, 50, false));
        collector.fetchEnded(
                new DataFetchEndedEvent(ui, component, 0, 50, false, 50));

        Assertions.assertEquals(-1, buffer.snapshot().get(0).dbQueries());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> insights = (List<Map<String, Object>>) new InsightsService(
                null, buffer).payload().get("insights");
        Map<String, Object> insight = insights.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) insight
                .get("evidence");

        Assertions.assertNull(evidence.get("sqlQueries"));
        Assertions.assertNull(insight.get("suggestion"),
                "nothing measured, nothing to suggest");
        Assertions.assertFalse(
                insight.get("summary").toString().contains("SQL"),
                () -> "the summary must not mention SQL it never saw: "
                        + insight.get("summary"));
    }

    @Test
    void aFailedFetchReportsTheSqlWorkBehindIt() {
        DatabaseActivity.instrumented();
        DataQueryCollector collector = collector(CAPTURE_NONE);
        collector.fetchStarted(
                new DataFetchStartedEvent(ui, component, 0, 50, false));
        for (int query = 0; query < 51; query++) {
            DatabaseActivity.queryExecuted();
        }
        collector.fetchFailed(new DataFetchFailedEvent(ui, component, 0, 50,
                false, new IllegalStateException("backend down")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> insights = (List<Map<String, Object>>) new InsightsService(
                null, buffer).payload().get("insights");
        Map<String, Object> insight = insights.get(0);
        Assertions.assertEquals("data-query-error", insight.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) insight
                .get("evidence");

        Assertions.assertEquals(51L, evidence.get("sqlQueries"));
        Assertions.assertEquals(50, evidence.get("requested"));
    }

    @Test
    void theSizeAndTheSqlWorkComeFromTheSameOccurrence() {
        // A group spans fetches of different page sizes. When the latest one
        // measured nothing, the SQL figures come from an earlier one, and the
        // page they are set against has to be that one's too.
        DatabaseActivity.instrumented();
        DataQueryCollector collector = collector(CAPTURE_ALL);
        collector.fetchStarted(
                new DataFetchStartedEvent(ui, component, 0, 50, false));
        for (int query = 0; query < 51; query++) {
            DatabaseActivity.queryExecuted();
        }
        collector.fetchEnded(
                new DataFetchEndedEvent(ui, component, 0, 50, false, 50));
        collector.fetchStarted(
                new DataFetchStartedEvent(ui, component, 50, 100, false));
        collector.fetchEnded(
                new DataFetchEndedEvent(ui, component, 50, 100, false, 100));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> insights = (List<Map<String, Object>>) new InsightsService(
                null, buffer).payload().get("insights");
        Map<String, Object> insight = insights.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) insight
                .get("evidence");

        Assertions.assertEquals(2, evidence.get("occurrences"));
        Assertions.assertEquals(51L, evidence.get("sqlQueries"));
        Assertions.assertEquals(50, evidence.get("requested"));
        Assertions.assertEquals(50, evidence.get("returned"));
        Assertions.assertTrue(
                insight.get("suggestion").toString()
                        .contains("51 SQL queries for 50 items returned"),
                () -> String.valueOf(insight.get("suggestion")));
    }
}
