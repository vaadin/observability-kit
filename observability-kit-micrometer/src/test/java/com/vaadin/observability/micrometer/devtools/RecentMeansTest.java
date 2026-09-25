/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.devtools;

import java.util.Set;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RecentMeansTest {

    private static final long WINDOW = RecentMeans.WINDOW.toMillis();

    private final RecentMeans means = new RecentMeans();
    private final Meter.Id id = new Meter.Id("vaadin.rpc.duration",
            Tags.empty(), "ms", null, Meter.Type.TIMER);

    @Test
    void firstReading_hasNothingToCompareWith() {
        Assertions.assertTrue(Double.isNaN(means.record(id, 10, 1000, 0)));
    }

    @Test
    void meanIsOverTheSamplesBetweenReadings() {
        means.record(id, 10, 1000, 0);

        // Two samples of 20 ms since: the cumulative mean is 1040/12 ≈ 87.
        Assertions.assertEquals(20, means.record(id, 12, 1040, 3000));
    }

    @Test
    void slowBurstAgesOutOfTheWindow() {
        // A burst of ten 1 s samples, then fast ones: once the burst is older
        // than the window it no longer weighs on the mean, as it no longer
        // weighs on the registry's max.
        means.record(id, 0, 0, 0);
        means.record(id, 10, 10_000, 1000);
        means.record(id, 20, 10_100, WINDOW);
        double mean = means.record(id, 30, 10_200, WINDOW + 2000);

        Assertions.assertEquals(10, mean);
    }

    @Test
    void noSamplesInTheWindow_isNoMean() {
        means.record(id, 10, 1000, 0);

        Assertions.assertTrue(Double.isNaN(means.record(id, 10, 1000, 3000)));
    }

    @Test
    void readingsOlderThanTheWindowAreDropped_evenAfterAGap() {
        // The panel was closed for an hour: the reading from before is not a
        // baseline for "the last two minutes".
        means.record(id, 10, 1000, 0);

        Assertions.assertTrue(
                Double.isNaN(means.record(id, 500, 50_000, 60 * 60 * 1000)));
    }

    @Test
    void countGoingBackwards_startsANewSeries() {
        means.record(id, 100, 10_000, 0);
        Assertions.assertTrue(Double.isNaN(means.record(id, 2, 50, 1000)));

        Assertions.assertEquals(5, means.record(id, 4, 60, 2000));
    }

    @Test
    void retainOnly_forgetsRemovedMeters() {
        means.record(id, 10, 1000, 0);
        means.retainOnly(Set.of());

        Assertions.assertTrue(Double.isNaN(means.record(id, 12, 1040, 1000)));
    }
}
