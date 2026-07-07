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
 * Bounded in-memory ring buffer of interaction exemplars. Oldest entries are
 * evicted first; memory use is hard-capped by {@code capacity}.
 */
public class ExemplarBuffer {

    /** Default hard cap on retained exemplars. */
    public static final int DEFAULT_CAPACITY = 100;

    private final int capacity;
    private final Deque<InteractionExemplar> exemplars = new ArrayDeque<>();

    public ExemplarBuffer(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void add(InteractionExemplar exemplar) {
        if (exemplars.size() == capacity) {
            exemplars.removeFirst();
        }
        exemplars.addLast(exemplar);
    }

    /** Returns a snapshot, newest first. */
    public synchronized List<InteractionExemplar> snapshot() {
        return exemplars.reversed().stream().toList();
    }
}
