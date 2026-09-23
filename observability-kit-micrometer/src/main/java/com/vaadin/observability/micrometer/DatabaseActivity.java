/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

/**
 * A running tally, per thread, of the JDBC work done on it: queries executed
 * and result-set rows read.
 * <p>
 * It exists so that instrumentation which only sees the Vaadin side of a data
 * load — the data provider events, which say a component asked for 150 items
 * and got 150 back — can also report what that load cost underneath. Those two
 * numbers are the same whether the page came out of one indexed query or out of
 * sixty thousand, and the difference between those two is the whole finding.
 * <p>
 * The tally is monotonic and never reset. A caller measures a window by taking
 * {@link #current()} at its start and {@link #since(Work)} at its end, so
 * windows can nest without one clearing another's baseline.
 * <p>
 * <b>Threading.</b> The counters are thread-confined, which is what makes them
 * cheap — a plain increment, no contention. It also means a window only counts
 * the queries issued on the thread that opened it: a data provider that hands
 * its fetch to another thread would see none of them. That case is reported as
 * unknown rather than as zero, since the two are indistinguishable here and a
 * confident "no database work" would be the more damaging of the two answers.
 * <p>
 * The counters cost two {@code long}s on each thread that touches JDBC and are
 * never removed, which is deliberate: they belong to pooled request threads
 * that outlive any single query, and a {@code remove()} on a window boundary
 * would throw away the baseline of an enclosing window.
 */
public final class DatabaseActivity {

    /**
     * Whether any JDBC instrumentation is installed. Until it is, every window
     * is unknown rather than empty — an application whose {@code DataSource} is
     * not wrapped has not measured zero queries, it has measured nothing.
     */
    private static volatile boolean instrumented;

    /** {@code [queries, rows]} for the current thread. */
    private static final ThreadLocal<long[]> COUNTS = ThreadLocal
            .withInitial(() -> new long[2]);

    private DatabaseActivity() {
    }

    /**
     * The JDBC work counted in one window: how many queries ran and how many
     * result-set rows they read.
     *
     * @param queries
     *            queries executed, {@code -1} when not measured
     * @param rows
     *            rows read from their result sets, {@code -1} when not measured
     */
    public record Work(long queries, long rows) {

        /** No measurement: no instrumentation, or none on this thread. */
        public static final Work UNKNOWN = new Work(-1, -1);

        /**
         * @return whether this carries an actual measurement
         */
        public boolean isKnown() {
            return queries >= 0;
        }
    }

    /**
     * Declares that JDBC queries are being counted. Called by whichever
     * instrumentation wraps the application's data sources.
     */
    public static void instrumented() {
        instrumented = true;
    }

    /**
     * @return whether JDBC queries are being counted at all
     */
    public static boolean isInstrumented() {
        return instrumented;
    }

    /**
     * Counts one executed query on the current thread.
     */
    public static void queryExecuted() {
        if (instrumented) {
            COUNTS.get()[0]++;
        }
    }

    /**
     * Counts rows read from one result set on the current thread.
     *
     * @param rows
     *            the number of rows read, ignored when negative
     */
    public static void rowsRead(long rows) {
        if (instrumented && rows > 0) {
            COUNTS.get()[1] += rows;
        }
    }

    /**
     * Opens a measurement window.
     *
     * @return the current thread's running tally, to be handed back to
     *         {@link #since(Work)}, or {@link Work#UNKNOWN} when nothing is
     *         being counted
     */
    public static Work current() {
        if (!instrumented) {
            return Work.UNKNOWN;
        }
        long[] counts = COUNTS.get();
        return new Work(counts[0], counts[1]);
    }

    /**
     * Closes a measurement window opened by {@link #current()}.
     *
     * @param start
     *            the tally taken when the window opened, may be {@code null}
     * @return the work done on this thread since then, or {@link Work#UNKNOWN}
     *         when there is nothing to report — no instrumentation, no
     *         baseline, or no query counted, the last of which also covers a
     *         window whose queries ran on another thread
     */
    public static Work since(Work start) {
        if (!instrumented || start == null || !start.isKnown()) {
            return Work.UNKNOWN;
        }
        long[] counts = COUNTS.get();
        long queries = counts[0] - start.queries();
        if (queries <= 0) {
            return Work.UNKNOWN;
        }
        return new Work(queries, Math.max(0, counts[1] - start.rows()));
    }
}
