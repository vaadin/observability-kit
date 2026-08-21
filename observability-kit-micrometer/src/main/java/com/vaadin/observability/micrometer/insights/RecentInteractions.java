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
 * Bounded in-memory ring buffer of captured interactions. Oldest entries are
 * evicted first; memory use is hard-capped by {@code capacity}.
 */
public class RecentInteractions {

    /** Default hard cap on retained interactions. */
    public static final int DEFAULT_CAPACITY = 100;

    private final int capacity;
    private final Deque<CapturedInteraction> interactions = new ArrayDeque<>();

    public RecentInteractions(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException(
                    "Capacity needs to be 1 or more");
        }
        this.capacity = capacity;
    }

    public synchronized void add(CapturedInteraction interaction) {
        if (interactions.size() == capacity) {
            interactions.removeFirst();
        }
        interactions.addLast(interaction);
    }

    /** Returns a snapshot, newest first. */
    public synchronized List<CapturedInteraction> snapshot() {
        return interactions.reversed().stream().toList();
    }
}
