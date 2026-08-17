/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RecentInteractionsTest {

    private static CapturedInteraction interaction(String route) {
        return new CapturedInteraction(Instant.now(), route, "Button", "click",
                "event", CapturedInteraction.OUTCOME_SUCCESS, 1200, null, null,
                null, null, "session", 0);
    }

    @Test
    void snapshotReturnsNewestFirst() {
        RecentInteractions buffer = new RecentInteractions(10);
        buffer.add(interaction("first"));
        buffer.add(interaction("second"));
        buffer.add(interaction("third"));

        List<CapturedInteraction> snapshot = buffer.snapshot();

        Assertions.assertEquals(List.of("third", "second", "first"),
                snapshot.stream().map(CapturedInteraction::route).toList(),
                "snapshot should be ordered newest first");
    }

    @Test
    void evictsOldestBeyondCapacity() {
        RecentInteractions buffer = new RecentInteractions(2);
        buffer.add(interaction("first"));
        buffer.add(interaction("second"));
        buffer.add(interaction("third"));

        List<CapturedInteraction> snapshot = buffer.snapshot();

        Assertions.assertEquals(2, snapshot.size(), "capacity is a hard cap");
        Assertions.assertEquals(List.of("third", "second"),
                snapshot.stream().map(CapturedInteraction::route).toList(),
                "oldest entry should have been evicted");
    }

    @Test
    void snapshotIsIndependentOfLaterAdditions() {
        RecentInteractions buffer = new RecentInteractions(10);
        buffer.add(interaction("first"));

        List<CapturedInteraction> snapshot = buffer.snapshot();
        buffer.add(interaction("second"));

        Assertions.assertEquals(1, snapshot.size(),
                "an earlier snapshot must not observe later additions");
    }

    @Test
    void emptyBufferSnapshotIsEmpty() {
        Assertions.assertTrue(new RecentInteractions(10).snapshot().isEmpty());
    }
}
