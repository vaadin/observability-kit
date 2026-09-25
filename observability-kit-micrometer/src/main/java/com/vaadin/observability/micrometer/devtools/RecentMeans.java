/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.devtools;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import io.micrometer.core.instrument.Meter;

/**
 * The mean of a timer or distribution summary over the last {@link #WINDOW},
 * derived from the cumulative count and total the meter exposes.
 * <p>
 * A meter's own {@code mean()} is cumulative since the meter was created, while
 * its {@code max()} decays over {@code distributionStatisticExpiry}. Shown side
 * by side, a burst of slow samples that has aged out of the max is still in the
 * mean, and the panel reads "mean 800 ms · max 40 ms". This keeps the count and
 * total seen at each poll and takes the difference across the window, so the
 * mean describes the same recent samples the max does.
 * <p>
 * Samples are only taken when the panel polls, so nothing is known about the
 * time before the panel was first opened, or while it was closed: until two
 * polls inside the window have seen the count move, there is no recent mean.
 * Every poll from any browser feeds the same samples, which is fine because the
 * window is measured in time rather than in polls.
 */
class RecentMeans {

    /**
     * Micrometer's default {@code distributionStatisticExpiry}: the window the
     * registry's max decays over, so mean and max describe the same samples. An
     * application that configures a different expiry gets a max over its own
     * window; this one stays at the default.
     */
    static final Duration WINDOW = Duration.ofMinutes(2);

    private record Sample(long atMillis, long count, double total) {
    }

    private final Map<Meter.Id, Deque<Sample>> samples = new HashMap<>();

    /**
     * Records the meter's cumulative figures as seen now and returns the mean
     * of the samples recorded in the last {@link #WINDOW}.
     *
     * @param id
     *            the meter the figures belong to
     * @param count
     *            the meter's cumulative count
     * @param total
     *            the meter's cumulative total, in the unit the mean is wanted
     *            in
     * @param nowMillis
     *            the time of this reading
     * @return the mean over the window, or {@code NaN} when no sample was
     *         recorded in it or there is no earlier reading to compare with
     */
    synchronized double record(Meter.Id id, long count, double total,
            long nowMillis) {
        Deque<Sample> history = samples.computeIfAbsent(id,
                key -> new ArrayDeque<>());
        Sample last = history.peekLast();
        if (last != null && count < last.count()) {
            // The meter was removed and registered again under the same id:
            // the earlier readings belong to a different series.
            history.clear();
        }
        history.addLast(new Sample(nowMillis, count, total));
        long cutoff = nowMillis - WINDOW.toMillis();
        while (history.peekFirst().atMillis() < cutoff) {
            history.removeFirst();
        }
        Sample base = history.peekFirst();
        long samplesInWindow = count - base.count();
        if (samplesInWindow <= 0) {
            return Double.NaN;
        }
        return (total - base.total()) / samplesInWindow;
    }

    /**
     * Forgets the meters not in {@code live}, so a meter removed from the
     * registry does not keep its readings forever.
     *
     * @param live
     *            the meters reported by the latest snapshot
     */
    synchronized void retainOnly(Set<Meter.Id> live) {
        samples.keySet().retainAll(live);
    }
}
