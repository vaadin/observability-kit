/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
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

    private static final Duration DURATION = Duration.ofMillis(42);

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
        binder.countEnded(
                new DataCountEndedEvent(ui, component, true, 250, DURATION));

        Timer timer = registry.find(MeterNames.DATA_COUNT_DURATION)
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .tag(MeterNames.TAG_FILTERED, "true").timer();
        Assertions.assertNotNull(timer,
                "a filtered count should be recorded as such");
        Assertions.assertEquals(1L, timer.count());
        Assertions.assertEquals(DURATION.toNanos(),
                timer.totalTime(TimeUnit.NANOSECONDS),
                "the timer should record the duration Flow measured");
    }

    @Test
    void countThatThrewIsRecordedAsAnError() {
        DataQueryMetricsBinder binder = binder();

        binder.countStarted(new DataCountStartedEvent(ui, component, false));
        // -1 is how the event contract reports a query that threw
        binder.countEnded(
                new DataCountEndedEvent(ui, component, false, -1, DURATION));

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
        binder.fetchEnded(new DataFetchEndedEvent(ui, component, 0, 50, false,
                30, DURATION));

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
        binder.fetchEnded(new DataFetchEndedEvent(ui, component, 0, 50, false,
                -1, DURATION));

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
            binder.countEnded(new DataCountEndedEvent(ui, component, false, 10,
                    DURATION));
        }

        Assertions.assertEquals(5L,
                registry.find(MeterNames.DATA_COUNT_DURATION).timer().count(),
                "every count query in the flush should be measured");
    }

    @Test
    void endedWithoutStartedIsTimedFromTheEvent() {
        // A listener registered mid-query sees only the ended event, which
        // still carries the duration Flow measured from the query's start.
        DataQueryMetricsBinder binder = binder();

        binder.countEnded(
                new DataCountEndedEvent(ui, component, false, 5, DURATION));

        Assertions.assertEquals(DURATION.toNanos(),
                registry.find(MeterNames.DATA_COUNT_DURATION).timer()
                        .totalTime(TimeUnit.NANOSECONDS),
                "the duration comes from the event, not from a start seen here");
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
        binder.countEnded(
                new DataCountEndedEvent(ui, component, false, 10, DURATION));

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
        binder.fetchEnded(new DataFetchEndedEvent(ui, component, 0, 50, false,
                50, DURATION));

        Assertions.assertNotNull(
                registry.find(MeterNames.DATA_FETCH_DURATION).timer(),
                "a no-op observation must not swallow the measurement");
    }

    @Test
    void aNoOpObservationLeavesNoScopeCurrent() {
        // A registry with no handlers hands back an observation that is a
        // no-op for recording but still opens a real scope, and that scope
        // registers itself in the thread-local Micrometer shares across every
        // registry. Not closing it would leave a dead observation current on
        // this pooled thread, so everything started on it afterwards - the
        // next query, the next request, an actuator scrape - would be parented
        // onto the finished query.
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        DataQueryMetricsBinder binder = new DataQueryMetricsBinder(registry,
                observationRegistry,
                ObservabilitySettings.builder().traces(true).build());

        Assertions.assertNull(observationRegistry.getCurrentObservation(),
                "precondition: no observation is current on this thread");

        binder.countStarted(new DataCountStartedEvent(ui, component, false));
        binder.countEnded(
                new DataCountEndedEvent(ui, component, false, 10, DURATION));
        binder.fetchStarted(
                new DataFetchStartedEvent(ui, component, 0, 50, false));
        binder.fetchEnded(new DataFetchEndedEvent(ui, component, 0, 50, false,
                50, DURATION));

        Assertions.assertNull(observationRegistry.getCurrentObservationScope(),
                "no scope may stay current once the queries ended");
        Assertions.assertNull(observationRegistry.getCurrentObservation(),
                "no observation may stay current once the queries ended");
    }

    @Test
    void aFetchOnAnotherThreadIsStillMeasured() throws Exception {
        // A component using DataCommunicator#enablePushUpdates fetches on its
        // own executor, where none of the getCurrent() thread locals are set.
        // The started and ended events arrive on that thread, so the event has
        // to carry its own context.
        DataQueryMetricsBinder binder = binder();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                binder.fetchStarted(
                        new DataFetchStartedEvent(ui, component, 0, 50, false));
                binder.fetchEnded(new DataFetchEndedEvent(ui, component, 0, 50,
                        false, 50, DURATION));
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
        // Count and fetch keep separate thread-local observation slots
        // precisely because the two can run on different threads for the same
        // component.
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(
                new DefaultMeterObservationHandler(registry));
        DataQueryMetricsBinder binder = new DataQueryMetricsBinder(registry,
                observationRegistry,
                ObservabilitySettings.builder().traces(true).build());

        binder.fetchStarted(
                new DataFetchStartedEvent(ui, component, 0, 50, false));
        binder.countStarted(new DataCountStartedEvent(ui, component, false));
        binder.countEnded(
                new DataCountEndedEvent(ui, component, false, 10, DURATION));

        Assertions.assertEquals(1L,
                registry.find(MeterNames.DATA_COUNT_DURATION).timer().count(),
                "the count completes on its own slot");
        Assertions.assertNull(
                registry.find(MeterNames.DATA_FETCH_DURATION).timer(),
                "the still-open fetch is untouched by the count ending");

        binder.fetchEnded(new DataFetchEndedEvent(ui, component, 0, 50, false,
                50, DURATION));
        Assertions.assertEquals(1L,
                registry.find(MeterNames.DATA_FETCH_DURATION).timer().count(),
                "the fetch then completes on its own slot");
    }
}
