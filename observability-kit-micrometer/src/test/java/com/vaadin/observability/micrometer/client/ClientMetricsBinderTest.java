/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.client;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.observability.micrometer.MeterNames;
import com.vaadin.observability.micrometer.ObservabilitySettings;
import com.vaadin.observability.micrometer.insights.CapturedClientError;
import com.vaadin.observability.micrometer.insights.ClientErrorCollector;
import com.vaadin.observability.micrometer.insights.RecentClientErrors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link ClientMetricsBinder}.
 */
class ClientMetricsBinderTest {

    private SimpleMeterRegistry registry;
    private ClientMetricsBinder binder;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        binder = new ClientMetricsBinder(registry,
                ObservabilitySettings.builder().build());
    }

    // --- ingest: timer ---

    @Test
    void ingestBootstrapSampleRecordsTimer() {
        ClientSample sample = sample(MeterNames.CLIENT_BOOTSTRAP_DURATION,
                250.0, Map.of());
        binder.ingest(List.of(sample));

        assertEquals(1L,
                registry.timer(MeterNames.CLIENT_BOOTSTRAP_DURATION,
                        MeterNames.TAG_ROUTE, MeterNames.ROUTE_UNKNOWN)
                        .count());
    }

    @Test
    void ingestNavigationSampleRecordsTimer() {
        ClientSample sample = sample(MeterNames.CLIENT_NAVIGATION_DURATION,
                100.0, Map.of("route", "/home", "trigger", "programmatic"));
        binder.ingest(List.of(sample));

        assertEquals(1L, registry.timer(MeterNames.CLIENT_NAVIGATION_DURATION,
                "route", "_unknown", "trigger", "programmatic").count());
    }

    @Test
    void ingestLcpSampleRecordsTimer() {
        ClientSample sample = sample(MeterNames.CLIENT_WEB_VITALS_LCP, 1200.0,
                Map.of());
        binder.ingest(List.of(sample));

        assertEquals(1L,
                registry.timer(MeterNames.CLIENT_WEB_VITALS_LCP,
                        MeterNames.TAG_ROUTE, MeterNames.ROUTE_UNKNOWN)
                        .count());
    }

    @Test
    void ingestFcpSampleRecordsTimer() {
        ClientSample sample = sample(MeterNames.CLIENT_WEB_VITALS_FCP, 800.0,
                Map.of());
        binder.ingest(List.of(sample));

        assertEquals(1L,
                registry.timer(MeterNames.CLIENT_WEB_VITALS_FCP,
                        MeterNames.TAG_ROUTE, MeterNames.ROUTE_UNKNOWN)
                        .count());
    }

    // --- ingest: counter ---

    @Test
    void ingestErrorSampleIncrementsCounter() {
        ClientSample sample = sample(MeterNames.CLIENT_ERRORS, 0.0,
                Map.of("kind", "uncaught"));
        binder.ingest(List.of(sample));

        assertEquals(1.0, registry
                .counter(MeterNames.CLIENT_ERRORS, "kind", "uncaught").count(),
                1e-9);
    }

    @Test
    void ingestMultipleErrorsAccumulates() {
        binder.ingest(List.of(
                sample(MeterNames.CLIENT_ERRORS, 0.0,
                        Map.of("kind", "promise")),
                sample(MeterNames.CLIENT_ERRORS, 0.0,
                        Map.of("kind", "promise"))));

        assertEquals(2.0, registry
                .counter(MeterNames.CLIENT_ERRORS, "kind", "promise").count(),
                1e-9);
    }

    // --- ingest: disallowed name is silently skipped ---

    @Test
    void ingestDisallowedNameIsDropped() {
        ClientSample sample = sample("vaadin.client.rpc.duration", 50.0,
                Map.of());
        binder.ingest(List.of(sample));

        // The disallowed sample is NOT recorded as a timer
        assertEquals(0L, registry.find("vaadin.client.rpc.duration").timers()
                .stream().mapToLong(t -> t.count()).sum());
        // No dropped counter incremented here (dropped is only for
        // unrecognized *incoming* names; recordDropped is a separate method)
        assertEquals(0.0, registry.counter(MeterNames.CLIENT_DROPPED).count(),
                1e-9);
    }

    @Test
    void ingestNullListDoesNotThrow() {
        binder.ingest(null); // must not throw
    }

    @Test
    void ingestNullSampleIsSkipped() {
        // The list is deserialized from a browser payload, so a null element
        // in it is a payload away.
        binder.ingest(Collections.singletonList(null));
    }

    @Test
    void ingestSampleWithNegativeValueIsSkipped() {
        ClientSample sample = sample(MeterNames.CLIENT_BOOTSTRAP_DURATION, -1.0,
                Map.of());
        binder.ingest(List.of(sample));

        assertEquals(0L,
                registry.timer(MeterNames.CLIENT_BOOTSTRAP_DURATION,
                        MeterNames.TAG_ROUTE, MeterNames.ROUTE_UNKNOWN)
                        .count());
    }

    // --- tag keys are the meter's, not the browser's ---

    @Test
    void tagKeyTheBrowserInventedIsDropped() {
        // A meter's tag key set is fixed at its first registration, and a
        // registry that enforces that (Prometheus does) refuses every later
        // sample whose keys differ. One crafted key would otherwise poison the
        // meter for the legitimate samples behind it.
        binder.ingest(List.of(sample(MeterNames.CLIENT_BOOTSTRAP_DURATION,
                100.0, Map.of("route", "/home", "x", "y"))));

        assertEquals(List.of(MeterNames.TAG_ROUTE),
                tagKeysOf(MeterNames.CLIENT_BOOTSTRAP_DURATION));
    }

    @Test
    void tagKeyTheBrowserOmittedIsFilledIn() {
        // The other half of the same problem: a payload that leaves a key out
        // must not register a narrower meter than the next payload needs.
        binder.ingest(List.of(sample(MeterNames.CLIENT_ERRORS, 0.0, Map.of()),
                sample(MeterNames.CLIENT_ERRORS, 0.0, Map
                        .of(MeterNames.TAG_KIND, MeterNames.KIND_UNCAUGHT))));

        assertEquals(
                1.0, registry.counter(MeterNames.CLIENT_ERRORS,
                        MeterNames.TAG_KIND, MeterNames.KIND_UNKNOWN).count(),
                1e-9);
        assertEquals(
                1.0, registry.counter(MeterNames.CLIENT_ERRORS,
                        MeterNames.TAG_KIND, MeterNames.KIND_UNCAUGHT).count(),
                1e-9);
    }

    @Test
    void craftedErrorKindIsBucketed() {
        // kind is browser-reported, so an unbounded stream of values would be
        // an unbounded stream of time series.
        binder.ingest(List.of(
                sample(MeterNames.CLIENT_ERRORS, 0.0, Map.of("kind", "k-1")),
                sample(MeterNames.CLIENT_ERRORS, 0.0,
                        Map.of("kind", "k-" + "x".repeat(300)))));

        assertEquals(
                2.0, registry.counter(MeterNames.CLIENT_ERRORS,
                        MeterNames.TAG_KIND, MeterNames.KIND_UNKNOWN).count(),
                1e-9);
    }

    @Test
    void craftedNavigationTriggerIsBucketed() {
        binder.ingest(List.of(sample(MeterNames.CLIENT_NAVIGATION_DURATION,
                10.0, Map.of("route", "/home", "trigger", "made-up"))));

        assertEquals(1L,
                registry.find(MeterNames.CLIENT_NAVIGATION_DURATION)
                        .tag(MeterNames.TAG_TRIGGER, MeterNames.TRIGGER_UNKNOWN)
                        .timer().count());
    }

    @Test
    void aRouteLongerThanAnyRealOneIsUnknown() {
        binder.ingest(List.of(sample(MeterNames.CLIENT_BOOTSTRAP_DURATION,
                100.0, Map.of("route", "/" + "p".repeat(400)))));

        assertEquals(1L,
                registry.timer(MeterNames.CLIENT_BOOTSTRAP_DURATION,
                        MeterNames.TAG_ROUTE, MeterNames.ROUTE_UNKNOWN)
                        .count());
    }

    // --- a registry that refuses a sample must not fail the request ---

    @Test
    void aSampleTheRegistryRefusesIsCountedAsDroppedRatherThanThrown() {
        // The ClientCallable that carries these samples runs inside the user's
        // own request. An exception escaping ingest would be counted into
        // vaadin.errors and flip that request to outcome=error, which is a
        // browser inflating the server's error rate.
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                if (MeterNames.CLIENT_ERRORS.equals(id.getName())) {
                    throw new IllegalArgumentException("refused");
                }
                return id;
            }
        });

        binder.ingest(List.of(sample(MeterNames.CLIENT_ERRORS, 0.0,
                Map.of(MeterNames.TAG_KIND, MeterNames.KIND_UNCAUGHT))));

        assertEquals(1.0, registry.counter(MeterNames.CLIENT_DROPPED).count(),
                1e-9);
    }

    @Test
    void aRefusedSampleDoesNotStopTheRestOfTheBatch() {
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                if (MeterNames.CLIENT_ERRORS.equals(id.getName())) {
                    throw new IllegalArgumentException("refused");
                }
                return id;
            }
        });

        binder.ingest(List.of(
                sample(MeterNames.CLIENT_ERRORS, 0.0,
                        Map.of(MeterNames.TAG_KIND, MeterNames.KIND_UNCAUGHT)),
                sample(MeterNames.CLIENT_CONNECTION, 0.0, Map.of(
                        MeterNames.TAG_STATE, MeterNames.STATE_CONNECTED))));

        assertEquals(1.0,
                registry.counter(MeterNames.CLIENT_CONNECTION,
                        MeterNames.TAG_STATE, MeterNames.STATE_CONNECTED)
                        .count(),
                1e-9);
    }

    // --- recordThrottled ---

    @Test
    void recordThrottledIncrementsCounter() {
        binder.recordThrottled(3);

        assertEquals(3.0, registry.counter(MeterNames.CLIENT_THROTTLED).count(),
                1e-9);
    }

    @Test
    void recordThrottledZeroDoesNotRegisterCounter() {
        binder.recordThrottled(0);

        // recordThrottled(0) must not increment the counter
        assertEquals(0.0, registry.counter(MeterNames.CLIENT_THROTTLED).count(),
                1e-9);
    }

    // --- recordDropped ---

    @Test
    void recordDroppedIncrementsCounter() {
        binder.recordDropped(2);

        assertEquals(2.0, registry.counter(MeterNames.CLIENT_DROPPED).count(),
                1e-9);
    }

    // --- ingest: connection state ---

    @Test
    void connectionSampleCountsTheStateEntered() {
        binder.ingest(List.of(sample(MeterNames.CLIENT_CONNECTION, 0.0, Map
                .of(MeterNames.TAG_STATE, MeterNames.STATE_CONNECTION_LOST))));

        assertEquals(1.0,
                registry.counter(MeterNames.CLIENT_CONNECTION,
                        MeterNames.TAG_STATE, MeterNames.STATE_CONNECTION_LOST)
                        .count(),
                1e-9);
    }

    @Test
    void connectionSampleIgnoresEverySecondTagTheBrowserSends() {
        // The state tag set is fixed. A browser that adds tags of its own must
        // not be able to fan this counter out into extra series.
        binder.ingest(List.of(sample(MeterNames.CLIENT_CONNECTION, 0.0,
                Map.of(MeterNames.TAG_STATE, MeterNames.STATE_RECONNECTING,
                        "session", "u-42", "attempt", "7"))));

        assertEquals(List.of(MeterNames.TAG_STATE),
                tagKeysOf(MeterNames.CLIENT_CONNECTION));
    }

    @Test
    void craftedConnectionStateIsBucketed() {
        binder.ingest(List.of(
                sample(MeterNames.CLIENT_CONNECTION, 0.0,
                        Map.of(MeterNames.TAG_STATE, "state-" + 1)),
                sample(MeterNames.CLIENT_CONNECTION, 0.0,
                        Map.of(MeterNames.TAG_STATE, "state-" + 2))));

        assertEquals(2.0,
                registry.counter(MeterNames.CLIENT_CONNECTION,
                        MeterNames.TAG_STATE, MeterNames.STATE_UNKNOWN).count(),
                1e-9);
    }

    @Test
    void downtimeIsRecordedAgainstTheStateItWasSpentIn() {
        binder.ingest(List.of(sample(MeterNames.CLIENT_CONNECTION_DOWNTIME,
                4200.0, Map.of(MeterNames.TAG_STATE,
                        MeterNames.STATE_CONNECTION_LOST))));

        Timer timer = registry.find(MeterNames.CLIENT_CONNECTION_DOWNTIME)
                .tag(MeterNames.TAG_STATE, MeterNames.STATE_CONNECTION_LOST)
                .timer();
        assertEquals(1L, timer.count());
        assertEquals(4200.0, timer.totalTime(TimeUnit.MILLISECONDS), 1.0);
    }

    @Test
    void anOutageThatNeverGaveUpRetryingIsStillTimed() {
        // Flow only reaches connection-lost once it has exhausted its retries,
        // so a short outage lives entirely in reconnecting. Timing that state
        // is what keeps a brief disconnection measurable at all.
        binder.ingest(List.of(sample(MeterNames.CLIENT_CONNECTION_DOWNTIME,
                800.0,
                Map.of(MeterNames.TAG_STATE, MeterNames.STATE_RECONNECTING))));

        assertEquals(800.0, registry.find(MeterNames.CLIENT_CONNECTION_DOWNTIME)
                .tag(MeterNames.TAG_STATE, MeterNames.STATE_RECONNECTING)
                .timer().totalTime(TimeUnit.MILLISECONDS), 1.0);
    }

    @Test
    void downtimeCarriesTheStateTagAndNothingElse() {
        binder.ingest(
                List.of(sample(MeterNames.CLIENT_CONNECTION_DOWNTIME, 4200.0,
                        Map.of(MeterNames.TAG_STATE,
                                MeterNames.STATE_CONNECTION_LOST, "route",
                                "/uc5", "session", "u-42"))));

        // Downtime is an aggregate over browsers; the route a tab happened to
        // be on when it lost the server does not narrow it.
        assertEquals(List.of(MeterNames.TAG_STATE),
                tagKeysOf(MeterNames.CLIENT_CONNECTION_DOWNTIME));
    }

    @Test
    void downtimeInAStateTheBrowserCannotBeUnreachableInIsBucketed() {
        // "connected" on a downtime sample is a contradiction, and the value
        // comes from the browser.
        binder.ingest(List.of(
                sample(MeterNames.CLIENT_CONNECTION_DOWNTIME, 10.0,
                        Map.of(MeterNames.TAG_STATE,
                                MeterNames.STATE_CONNECTED)),
                sample(MeterNames.CLIENT_CONNECTION_DOWNTIME, 10.0,
                        Map.of(MeterNames.TAG_STATE, "made-up"))));

        assertEquals(2L,
                registry.find(MeterNames.CLIENT_CONNECTION_DOWNTIME)
                        .tag(MeterNames.TAG_STATE, MeterNames.STATE_UNKNOWN)
                        .timer().count());
    }

    // --- ingest: browser error detail ---

    @Test
    void errorDetailIsRetainedAsAnInsightAlongsideTheCount() {
        RecentClientErrors errors = new RecentClientErrors(10);
        ClientMetricsBinder detailed = new ClientMetricsBinder(registry,
                ObservabilitySettings.builder().insightsDetails(true).build(),
                new ClientErrorCollector(errors, ObservabilitySettings.builder()
                        .insightsDetails(true).build()));

        ClientSample s = sample(MeterNames.CLIENT_ERRORS, 0.0,
                Map.of("kind", "uncaught"));
        s.setAgeMs(3200);
        s.setDetail(Map.of(ClientErrorCollector.DETAIL_ROUTE, "/uc5",
                ClientErrorCollector.DETAIL_MESSAGE,
                "rendering the chart failed",
                ClientErrorCollector.DETAIL_SOURCE, "/VAADIN/build/chart.js:44",
                ClientErrorCollector.DETAIL_FRAME, "chart.js:44:9",
                ClientErrorCollector.DETAIL_FUNCTION, "renderChart"));
        detailed.ingest(List.of(s));

        assertEquals(1.0, registry
                .counter(MeterNames.CLIENT_ERRORS, "kind", "uncaught").count(),
                1e-9);
        CapturedClientError captured = errors.snapshot().get(0);
        assertEquals("uncaught", captured.kind());
        assertEquals("rendering the chart failed", captured.message());
        // The browser sends the location and the name in separate fields now.
        assertEquals("chart.js:44:9", captured.frame());
        assertEquals("renderChart", captured.function());
        // Measured in the browser, because the report could only be sent once
        // the connection it describes came back.
        assertEquals(3200L, captured.bufferedMs());
    }

    @Test
    void errorMessageIsWithheldUnlessDetailsAreEnabled() {
        RecentClientErrors errors = new RecentClientErrors(10);
        ObservabilitySettings settings = ObservabilitySettings.builder()
                .build();
        ClientMetricsBinder plain = new ClientMetricsBinder(registry, settings,
                new ClientErrorCollector(errors, settings));

        ClientSample s = sample(MeterNames.CLIENT_ERRORS, 0.0,
                Map.of("kind", "promise"));
        s.setDetail(Map.of(ClientErrorCollector.DETAIL_MESSAGE,
                "fetching /api/quotes?token=secret failed",
                ClientErrorCollector.DETAIL_SOURCE,
                "/VAADIN/build/quotes.js:31"));
        plain.ingest(List.of(s));

        CapturedClientError captured = errors.snapshot().get(0);
        assertNull(captured.message());
        // What stays is still actionable, and is not free-form user data --
        // and the source has to be a location to stay at all: a page path,
        // which is what this fixture used to carry, is not one.
        assertEquals("promise", captured.kind());
        assertEquals("/VAADIN/build/quotes.js:31", captured.source());
    }

    @Test
    void errorWithoutDetailStillCountsAndRetainsNothing() {
        RecentClientErrors errors = new RecentClientErrors(10);
        ObservabilitySettings settings = ObservabilitySettings.builder()
                .build();
        ClientMetricsBinder plain = new ClientMetricsBinder(registry, settings,
                new ClientErrorCollector(errors, settings));

        plain.ingest(List.of(sample(MeterNames.CLIENT_ERRORS, 0.0,
                Map.of("kind", "uncaught"))));

        assertEquals(1.0, registry
                .counter(MeterNames.CLIENT_ERRORS, "kind", "uncaught").count(),
                1e-9);
        assertEquals(List.of(), errors.snapshot());
    }

    @Test
    void errorDetailNeverBecomesATag() {
        RecentClientErrors errors = new RecentClientErrors(10);
        ObservabilitySettings settings = ObservabilitySettings.builder()
                .build();
        ClientMetricsBinder plain = new ClientMetricsBinder(registry, settings,
                new ClientErrorCollector(errors, settings));

        ClientSample s = sample(MeterNames.CLIENT_ERRORS, 0.0,
                Map.of("kind", "uncaught"));
        s.setDetail(Map.of(ClientErrorCollector.DETAIL_MESSAGE,
                "one message per user would be one series per user"));
        plain.ingest(List.of(s));

        assertEquals(List.of("kind"),
                registry.find(MeterNames.CLIENT_ERRORS).counter().getId()
                        .getTags().stream().map(Tag::getKey).toList());
    }

    // --- route resolution: the prefix the app is served under ---

    @Test
    void theApplicationsOwnPrefixComesOffTheBrowsersPath() {
        // Every pathname a browser reports carries the context path and the
        // servlet mapping; no route does. Left on, a WAR under /myapp would
        // resolve nothing and tag every client sample _unknown -- and the
        // browser-error insight would group on that and tell a reader to open
        // route '_unknown'.
        assertEquals("orders/17", ClientMetricsBinder
                .stripPrefix("/myapp/orders/17", "/myapp").substring(1));
        assertEquals("", ClientMetricsBinder.stripPrefix("/myapp", "/myapp"));
        // Nothing to take off: the common root deployment.
        assertEquals("/orders/17",
                ClientMetricsBinder.stripPrefix("/orders/17", ""));
        assertEquals("/orders/17",
                ClientMetricsBinder.stripPrefix("/orders/17", "/"));
    }

    @Test
    void aPrefixThatOnlyLooksLikeOneIsLeftAlone() {
        // "/uc" is not a prefix of "/uc5" in path terms, and treating it as
        // one would resolve the route "5".
        assertEquals("/uc5/orders",
                ClientMetricsBinder.stripPrefix("/uc5/orders", "/uc"));
    }

    // --- helpers ---

    private List<String> tagKeysOf(String meterName) {
        return registry.find(meterName).meters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(Tag::getKey).distinct().toList();
    }

    private static ClientSample sample(String name, double valueMs,
            Map<String, String> tags) {
        ClientSample s = new ClientSample();
        s.setName(name);
        s.setValueMs(valueMs);
        s.setTags(tags);
        return s;
    }
}
