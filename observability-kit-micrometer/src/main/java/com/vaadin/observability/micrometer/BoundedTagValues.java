/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admits tag values up to a fixed number of distinct ones and buckets every
 * further value under a single overflow value.
 * <p>
 * Metric tag values that derive from application classes (route templates,
 * component classes, exception types) are bounded in a well-behaved application
 * but nothing stops a generated or anonymous class from producing an unbounded
 * stream of them, and every distinct value costs a time series in the metrics
 * backend. Admission is first-come-first-served: the values seen while the
 * application warms up are the ones kept.
 */
final class BoundedTagValues {

    private final int limit;
    private final String overflow;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    /**
     * @param limit
     *            the maximum number of distinct values to admit
     * @param overflow
     *            the value returned once the limit is exhausted
     */
    BoundedTagValues(int limit, String overflow) {
        this.limit = limit;
        this.overflow = overflow;
    }

    /**
     * Returns {@code value} if it has already been admitted or still fits
     * within the limit, otherwise the overflow value.
     * <p>
     * The limit is enforced exactly: a check-then-add would let concurrent
     * callers admit more than {@code limit} distinct values, which is precisely
     * what happens under the flood of generated types this class exists to cap.
     * Only the admission of a value not yet seen synchronizes; both the hit and
     * the overflow path stay lock-free, so a flood costs no contention once the
     * limit is reached.
     */
    String admit(String value) {
        if (seen.contains(value)) {
            return value;
        }
        if (seen.size() >= limit) {
            return overflow;
        }
        synchronized (this) {
            // Re-checked under the lock: another thread may have admitted this
            // very value (then it is admitted, whether or not the set is now
            // full) or filled the last slot with a different one.
            if (seen.contains(value) || seen.size() < limit) {
                seen.add(value);
                return value;
            }
        }
        return overflow;
    }

    int trackedCount() {
        return seen.size();
    }
}
