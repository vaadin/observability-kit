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
        return new CapturedInteraction(Instant.now(), route, route + "/1",
                "Button", "click", "event", CapturedInteraction.OUTCOME_SUCCESS,
                1200, 1000, true, null, null, null, null, "session", 0);
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

    private static LatestInteraction latest(String event, String outcome,
            double serverMs) {
        return new LatestInteraction(Instant.now(), "orders", "OrderView",
                "Button", "Save", event, outcome, serverMs, 1);
    }

    @Test
    void latest_isNullUntilSomethingIsRecorded() {
        Assertions.assertNull(new RecentInteractions(10).latest());
    }

    @Test
    void latest_fromAnotherRequestReplacesThePrevious() {
        RecentInteractions buffer = new RecentInteractions(10);
        buffer.recordLatest(1, latest("click", "success", 3), false);
        buffer.recordLatest(2, latest("dblclick", "success", 2), false);

        LatestInteraction last = buffer.latest();
        Assertions.assertEquals("dblclick", last.event());
        Assertions.assertEquals(2, last.serverMs());
        Assertions.assertEquals(1, last.invocations());
    }

    @Test
    void latest_fromTheSameRequestAddsUpAndKeepsTheUserEvent() {
        RecentInteractions buffer = new RecentInteractions(10);
        buffer.recordLatest(1, latest("click", "error", 3), false);
        // A property sync in the same request is not what the user did.
        buffer.recordLatest(1, latest("value", "success", 1.5), true);

        LatestInteraction last = buffer.latest();
        Assertions.assertEquals("click", last.event());
        Assertions.assertEquals(4.5, last.serverMs());
        Assertions.assertEquals(2, last.invocations());
        Assertions.assertEquals("error", last.outcome(),
                "a failure anywhere in the request is the request's outcome");
    }
}
