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

class ExemplarBufferTest {

    private static InteractionExemplar exemplar(String route) {
        return new InteractionExemplar(Instant.now(), route, "Button", "click",
                "event", InteractionExemplar.OUTCOME_SUCCESS, 1200, null, null,
                null, null, "session", 0);
    }

    @Test
    void snapshotReturnsNewestFirst() {
        ExemplarBuffer buffer = new ExemplarBuffer(10);
        buffer.add(exemplar("first"));
        buffer.add(exemplar("second"));
        buffer.add(exemplar("third"));

        List<InteractionExemplar> snapshot = buffer.snapshot();

        Assertions.assertEquals(List.of("third", "second", "first"),
                snapshot.stream().map(InteractionExemplar::route).toList(),
                "snapshot should be ordered newest first");
    }

    @Test
    void evictsOldestBeyondCapacity() {
        ExemplarBuffer buffer = new ExemplarBuffer(2);
        buffer.add(exemplar("first"));
        buffer.add(exemplar("second"));
        buffer.add(exemplar("third"));

        List<InteractionExemplar> snapshot = buffer.snapshot();

        Assertions.assertEquals(2, snapshot.size(), "capacity is a hard cap");
        Assertions.assertEquals(List.of("third", "second"),
                snapshot.stream().map(InteractionExemplar::route).toList(),
                "oldest entry should have been evicted");
    }

    @Test
    void snapshotIsIndependentOfLaterAdditions() {
        ExemplarBuffer buffer = new ExemplarBuffer(10);
        buffer.add(exemplar("first"));

        List<InteractionExemplar> snapshot = buffer.snapshot();
        buffer.add(exemplar("second"));

        Assertions.assertEquals(1, snapshot.size(),
                "an earlier snapshot must not observe later additions");
    }

    @Test
    void emptyBufferSnapshotIsEmpty() {
        Assertions.assertTrue(new ExemplarBuffer(10).snapshot().isEmpty());
    }
}
