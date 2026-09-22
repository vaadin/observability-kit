/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies the JDBC tally the data query insights read: what a window counts,
 * and the cases it must call unknown rather than empty.
 */
class DatabaseActivityTest {

    @Test
    void aWindowCountsTheQueriesAndRowsInsideIt() {
        DatabaseActivity.instrumented();
        DatabaseActivity.queryExecuted();
        DatabaseActivity.rowsRead(7);

        DatabaseActivity.Work start = DatabaseActivity.current();
        DatabaseActivity.queryExecuted();
        DatabaseActivity.queryExecuted();
        DatabaseActivity.rowsRead(40);
        DatabaseActivity.rowsRead(2);
        DatabaseActivity.Work work = DatabaseActivity.since(start);

        Assertions.assertEquals(2, work.queries(),
                "work before the window opened is not this query's");
        Assertions.assertEquals(42, work.rows());
    }

    @Test
    void windowsNest() {
        // A count runs inside a request that is already being measured
        // elsewhere; neither window may clear the other's baseline.
        DatabaseActivity.instrumented();
        DatabaseActivity.Work outer = DatabaseActivity.current();
        DatabaseActivity.queryExecuted();

        DatabaseActivity.Work inner = DatabaseActivity.current();
        DatabaseActivity.queryExecuted();
        Assertions.assertEquals(1, DatabaseActivity.since(inner).queries());

        DatabaseActivity.queryExecuted();
        Assertions.assertEquals(3, DatabaseActivity.since(outer).queries());
    }

    @Test
    void aWindowWithoutQueriesIsUnknownRatherThanZero() {
        DatabaseActivity.instrumented();
        DatabaseActivity.Work start = DatabaseActivity.current();

        Assertions.assertFalse(DatabaseActivity.since(start).isKnown(),
                "counting nothing on this thread is indistinguishable from "
                        + "the queries having run on another one");
    }

    @Test
    void queriesOnAnotherThreadDoNotCountTowardsThisOne() throws Exception {
        DatabaseActivity.instrumented();
        DatabaseActivity.Work start = DatabaseActivity.current();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> elsewhere = executor.submit(() -> {
                DatabaseActivity.queryExecuted();
                DatabaseActivity.rowsRead(1_000);
            });
            elsewhere.get();
        } finally {
            executor.shutdown();
        }

        Assertions.assertFalse(DatabaseActivity.since(start).isKnown(),
                "the tally is thread-confined, and borrowing another thread's "
                        + "would attribute work to the wrong query");
    }
}
