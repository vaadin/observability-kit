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
 * Bounded in-memory ring buffer of error exemplars. Oldest entries are evicted
 * first; memory use is hard-capped by {@code capacity}.
 */
public class ErrorExemplarBuffer {

    /** Default hard cap on retained exemplars. */
    public static final int DEFAULT_CAPACITY = 100;

    private final int capacity;
    private final Deque<ErrorExemplar> exemplars = new ArrayDeque<>();

    public ErrorExemplarBuffer(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void add(ErrorExemplar exemplar) {
        if (exemplars.size() == capacity) {
            exemplars.removeFirst();
        }
        exemplars.addLast(exemplar);
    }

    /** Returns a snapshot, newest first. */
    public synchronized List<ErrorExemplar> snapshot() {
        return exemplars.reversed().stream().toList();
    }
}
