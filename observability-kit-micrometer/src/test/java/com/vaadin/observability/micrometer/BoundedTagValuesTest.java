/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BoundedTagValuesTest {

    @Test
    void valuesWithinTheLimitAreAdmitted() {
        BoundedTagValues values = new BoundedTagValues(2, "_other");

        Assertions.assertEquals("a", values.admit("a"));
        Assertions.assertEquals("b", values.admit("b"));
        Assertions.assertEquals(2, values.trackedCount());
    }

    @Test
    void furtherValuesAreBucketedButKnownOnesStillPassThrough() {
        BoundedTagValues values = new BoundedTagValues(1, "_other");
        values.admit("a");

        Assertions.assertEquals("_other", values.admit("b"),
                "a value beyond the limit must be bucketed");
        Assertions.assertEquals("a", values.admit("a"),
                "an already admitted value must keep its own tag value");
        Assertions.assertEquals(1, values.trackedCount(),
                "bucketing must not cost a tracked value");
    }

    @Test
    void concurrentAdmissionNeverExceedsTheLimit() throws Exception {
        // A check-then-add lets several threads pass the size check at once,
        // admitting more than the limit — exactly under the flood of generated
        // types the limit exists to cap.
        int threads = 16;
        int limit = 4;
        BoundedTagValues values = new BoundedTagValues(limit, "_other");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                String value = "value-" + i;
                pool.submit(() -> {
                    start.await();
                    return values.admit(value);
                });
            }
            start.countDown();
            pool.shutdown();
            Assertions.assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS),
                    "admission must not block");
        } finally {
            pool.shutdownNow();
        }

        Assertions.assertEquals(limit, values.trackedCount(),
                "the limit must hold under concurrent admission");
    }
}
