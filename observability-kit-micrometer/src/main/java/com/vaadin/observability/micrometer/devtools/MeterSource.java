/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.devtools;

import java.util.ArrayList;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.step.StepMeterRegistry;

/**
 * The registry the panel reads its figures from, and whether those figures are
 * per publishing interval rather than since startup.
 * <p>
 * A step registry (Datadog, Elastic, Influx, CloudWatch and the like) answers
 * {@code count()}, {@code totalTime()} and {@code mean()} for its last
 * completed interval only. A composite answers them from whichever child its
 * meter happens to hold first, which is an identity-hash order: with a step
 * registry among its children, the same row could be cumulative on one run and
 * per interval on the next. So a composite is looked through, and a cumulative
 * child read directly when there is one.
 *
 * @param registry
 *            the registry to read the meters from
 * @param perInterval
 *            {@code true} when counts, means and totals read from it cover the
 *            last publishing interval rather than everything since startup
 */
record MeterSource(MeterRegistry registry, boolean perInterval) {

    /**
     * Picks what to read for the registry instrumentation was bound to.
     *
     * @param bound
     *            the registry the meters were registered with
     * @return the registry to read, preferring a cumulative one
     */
    static MeterSource of(MeterRegistry bound) {
        List<MeterRegistry> leaves = new ArrayList<>();
        collectLeaves(bound, leaves);
        if (leaves.isEmpty()) {
            // An empty composite holds noop meters: nothing to prefer.
            return new MeterSource(bound, false);
        }
        for (MeterRegistry leaf : leaves) {
            if (!(leaf instanceof StepMeterRegistry)) {
                return new MeterSource(leaf, false);
            }
        }
        return new MeterSource(leaves.get(0), true);
    }

    private static void collectLeaves(MeterRegistry registry,
            List<MeterRegistry> leaves) {
        if (registry instanceof CompositeMeterRegistry composite) {
            for (MeterRegistry child : composite.getRegistries()) {
                collectLeaves(child, leaves);
            }
        } else {
            leaves.add(registry);
        }
    }
}
