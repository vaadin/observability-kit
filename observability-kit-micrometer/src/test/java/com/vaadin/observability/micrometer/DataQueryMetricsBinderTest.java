/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.data.DataCountEndedEvent;
import com.vaadin.flow.server.data.DataCountStartedEvent;
import com.vaadin.flow.server.data.DataFetchEndedEvent;
import com.vaadin.flow.server.data.DataFetchStartedEvent;

class DataQueryMetricsBinderTest {

    @Tag("test-grid")
    private static class TestComponent extends Component {
    }

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final UI ui = new UI();
    private final Component component = new TestComponent();

    private DataQueryMetricsBinder binder() {
        return new DataQueryMetricsBinder(registry, null,
                ObservabilitySettings.builder().traces(false).build());
    }

    @Test
    void countRecordsTimerWithOutcomeAndFilterFlag() {
        DataQueryMetricsBinder binder = binder();

        binder.countStarted(new DataCountStartedEvent(ui, component, true));
        binder.countEnded(new DataCountEndedEvent(ui, component, true, 250));

        Timer timer = registry.find(MeterNames.DATA_COUNT_DURATION)
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .tag(MeterNames.TAG_FILTERED, "true").timer();
        Assertions.assertNotNull(timer,
                "a filtered count should be recorded as such");
        Assertions.assertEquals(1L, timer.count());
    }

    @Test
    void countThatThrewIsRecordedAsAnError() {
        DataQueryMetricsBinder binder = binder();

        binder.countStarted(new DataCountStartedEvent(ui, component, false));
        // -1 is how the event contract reports a query that threw
        binder.countEnded(new DataCountEndedEvent(ui, component, false, -1));

        Assertions
                .assertNotNull(
                        registry.find(MeterNames.DATA_COUNT_DURATION)
                                .tag(MeterNames.TAG_OUTCOME,
                                        MeterNames.OUTCOME_ERROR)
                                .timer(),
                        "a count reporting -1 should be tagged as an error");
    }

    @Test
    void fetchRecordsRequestedAndReturnedSeparately() {
        DataQueryMetricsBinder binder = binder();

        binder.fetchStarted(
                new DataFetchStartedEvent(ui, component, 0, 50, false));
        // The provider returned a short page: 30 of the 50 asked for
        binder.fetchEnded(
                new DataFetchEndedEvent(ui, component, 0, 50, false, 30));

        DistributionSummary requested = registry
                .find(MeterNames.DATA_FETCH_REQUESTED).summary();
        DistributionSummary rows = registry.find(MeterNames.DATA_FETCH_ROWS)
                .summary();
        Assertions.assertNotNull(requested);
        Assertions.assertNotNull(rows);
        Assertions.assertEquals(50, requested.totalAmount(),
                "the limit the component asked for");
        Assertions.assertEquals(30, rows.totalAmount(),
                "the rows the provider actually returned");
    }

    @Test
    void fetchThatThrewRecordsNoRowSummaries() {
        DataQueryMetricsBinder binder = binder();

        binder.fetchStarted(
                new DataFetchStartedEvent(ui, component, 0, 50, false));
        binder.fetchEnded(
                new DataFetchEndedEvent(ui, component, 0, 50, false, -1));

        Assertions.assertNull(
                registry.find(MeterNames.DATA_FETCH_ROWS).summary(),
                "-1 is a failure marker, not a row count to record");
        Assertions
                .assertNotNull(
                        registry.find(MeterNames.DATA_FETCH_DURATION)
                                .tag(MeterNames.TAG_OUTCOME,
                                        MeterNames.OUTCOME_ERROR)
                                .timer(),
                        "the failed fetch should still be timed");
    }

    @Test
    void severalCountsInOneRequestAreAllRecorded() {
        // The N+1 signature of an expensive hierarchy: one count per expanded
        // parent, all within a single flush.
        DataQueryMetricsBinder binder = binder();

        for (int i = 0; i < 5; i++) {
            binder.countStarted(
                    new DataCountStartedEvent(ui, component, false));
            binder.countEnded(
                    new DataCountEndedEvent(ui, component, false, 10));
        }

        Assertions.assertEquals(5L,
                registry.find(MeterNames.DATA_COUNT_DURATION).timer().count(),
                "every count query in the flush should be measured");
    }

    @Test
    void endedWithoutStartedIsIgnoredRatherThanRecordedAsZero() {
        // A listener registered mid-query would otherwise fabricate a sample.
        DataQueryMetricsBinder binder = binder();

        binder.countEnded(new DataCountEndedEvent(ui, component, false, 5));

        Assertions.assertNull(
                registry.find(MeterNames.DATA_COUNT_DURATION).timer(),
                "an ended event with no start should not invent a timing");
    }

    @Test
    void aNoOpObservationStillProducesATimer() {
        // An ObservationRegistry with no handlers hands back Observation.NOOP,
        // which records nothing. Without falling through to the timer path the
        // query would be measured nowhere at all.
        DataQueryMetricsBinder binder = new DataQueryMetricsBinder(registry,
                ObservationRegistry.create(),
                ObservabilitySettings.builder().traces(true).build());

        binder.countStarted(new DataCountStartedEvent(ui, component, false));
        binder.countEnded(new DataCountEndedEvent(ui, component, false, 10));

        Assertions.assertNotNull(
                registry.find(MeterNames.DATA_COUNT_DURATION).timer(),
                "a no-op observation must not swallow the measurement");
    }

    @Test
    void aNoOpObservationStillProducesAFetchTimer() {
        DataQueryMetricsBinder binder = new DataQueryMetricsBinder(registry,
                ObservationRegistry.create(),
                ObservabilitySettings.builder().traces(true).build());

        binder.fetchStarted(
                new DataFetchStartedEvent(ui, component, 0, 50, false));
        binder.fetchEnded(
                new DataFetchEndedEvent(ui, component, 0, 50, false, 50));

        Assertions.assertNotNull(
                registry.find(MeterNames.DATA_FETCH_DURATION).timer(),
                "a no-op observation must not swallow the measurement");
    }

    @Test
    void aFetchOnAnotherThreadIsStillMeasured() throws Exception {
        // A component using DataCommunicator#enablePushUpdates fetches on its
        // own executor, where none of the getCurrent() thread locals are set.
        // The started and ended events arrive on that thread, so the timing
        // state has to live there and the event has to carry its own context.
        DataQueryMetricsBinder binder = binder();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                binder.fetchStarted(
                        new DataFetchStartedEvent(ui, component, 0, 50, false));
                binder.fetchEnded(new DataFetchEndedEvent(ui, component, 0, 50,
                        false, 50));
            }).get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        Assertions.assertNotNull(
                registry.find(MeterNames.DATA_FETCH_DURATION).timer(),
                "a fetch off the request thread must still be timed");
        Assertions.assertEquals(50, registry.find(MeterNames.DATA_FETCH_ROWS)
                .summary().totalAmount(), "and its rows recorded");
    }

    @Test
    void aCountOnTheRequestThreadDoesNotSeeAFetchLeftOnAnother() {
        // Count and fetch keep separate thread-local slots precisely because
        // the two can run on different threads for the same component.
        DataQueryMetricsBinder binder = binder();

        binder.fetchStarted(
                new DataFetchStartedEvent(ui, component, 0, 50, false));
        binder.countStarted(new DataCountStartedEvent(ui, component, false));
        binder.countEnded(new DataCountEndedEvent(ui, component, false, 10));

        Assertions.assertEquals(1L,
                registry.find(MeterNames.DATA_COUNT_DURATION).timer().count(),
                "the count completes on its own slot");
        Assertions.assertNull(
                registry.find(MeterNames.DATA_FETCH_DURATION).timer(),
                "the still-open fetch is untouched by the count ending");
    }
}
