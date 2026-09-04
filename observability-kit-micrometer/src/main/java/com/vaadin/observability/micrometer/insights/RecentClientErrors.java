/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Bounded in-memory ring buffer of errors reported by browsers. Oldest entries
 * are evicted first; memory use is hard-capped by {@code capacity}.
 * <p>
 * Kept apart from {@link RecentInteractions} for the same reason the query
 * buffer is: reports arrive from every browser at once when connectivity comes
 * back, and a burst of them must not evict the server-side interactions.
 */
public class RecentClientErrors {

    private final int capacity;
    private final Deque<CapturedClientError> errors = new ArrayDeque<>();

    public RecentClientErrors(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException(
                    "Capacity needs to be 1 or more");
        }
        this.capacity = capacity;
    }

    public synchronized void add(CapturedClientError error) {
        if (errors.size() == capacity) {
            errors.removeFirst();
        }
        errors.addLast(error);
    }

    /** Returns a snapshot, newest first. */
    public synchronized List<CapturedClientError> snapshot() {
        return errors.reversed().stream().toList();
    }
}
