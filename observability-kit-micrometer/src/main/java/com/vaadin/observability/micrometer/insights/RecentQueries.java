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
 * Bounded in-memory ring buffer of captured data provider queries. Oldest
 * entries are evicted first; memory use is hard-capped by {@code capacity}.
 */
public class RecentQueries {

    /** Default hard cap on retained queries. */
    public static final int DEFAULT_CAPACITY = 100;

    private final int capacity;
    private final Deque<CapturedQuery> queries = new ArrayDeque<>();

    public RecentQueries(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException(
                    "Capacity needs to be 1 or more");
        }
        this.capacity = capacity;
    }

    public synchronized void add(CapturedQuery query) {
        if (queries.size() == capacity) {
            queries.removeFirst();
        }
        queries.addLast(query);
    }

    /** Returns a snapshot, newest first. */
    public synchronized List<CapturedQuery> snapshot() {
        return queries.reversed().stream().toList();
    }
}
