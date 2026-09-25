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
    private LatestInteraction latest;
    private int latestRequestId;

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

    /**
     * Records the invocation that just ended as the latest interaction.
     * <p>
     * One user action is often several invocations in one request - a text
     * field's value sync ahead of the button click that submits it - so an
     * invocation from the same request as the previous one is folded into it:
     * the server time adds up, and a property sync does not replace the event
     * the user actually triggered as the headline.
     *
     * @param requestId
     *            identifies the request the invocation arrived in
     * @param interaction
     *            the invocation, as a latest interaction of its own
     * @param propertySync
     *            whether the invocation was a property sync
     */
    public synchronized void recordLatest(int requestId,
            LatestInteraction interaction, boolean propertySync) {
        LatestInteraction previous = latest;
        if (previous == null || requestId != latestRequestId) {
            latest = interaction;
        } else {
            LatestInteraction headline = propertySync ? previous
                    : interaction;
            String outcome = CapturedInteraction.OUTCOME_ERROR
                    .equals(previous.outcome()) ? previous.outcome()
                            : interaction.outcome();
            latest = new LatestInteraction(interaction.timestamp(),
                    headline.route(), headline.view(), headline.component(),
                    headline.caption(), headline.event(), outcome,
                    previous.serverMs() + interaction.serverMs(),
                    previous.invocations() + 1);
        }
        latestRequestId = requestId;
    }

    /**
     * The latest interaction, or {@code null} when none has been recorded.
     *
     * @return the latest interaction, or {@code null}
     */
    public synchronized LatestInteraction latest() {
        return latest;
    }
}
