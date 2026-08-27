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

/**
 * One captured data provider query worth surfacing as an insight: which
 * component asked for data, on which route, how much it asked for, how much
 * came back, and how long it took.
 * <p>
 * Kept separate from {@link CapturedInteraction} rather than folded into it. An
 * interaction is a user action and carries a DOM event, an RPC type and, when
 * it fails, a stack frame in application code. A query has none of those when
 * it is merely slow, and carries a range and a row count instead. One record
 * covering both would be mostly empty fields either way, and the JSON at the
 * insights endpoint is a published contract, so a reader is better served by
 * two honest shapes than one loose one.
 *
 * @param timestamp
 *            when the query finished
 * @param route
 *            the route <em>template</em> of the view the query belongs to, so
 *            every parameter value groups under one insight
 * @param component
 *            fully-qualified class of the component whose data was loaded, or
 *            {@code null} when the data communicator is driven by a bare
 *            element
 * @param kind
 *            {@link #KIND_COUNT} or {@link #KIND_FETCH}
 * @param filtered
 *            whether the query carried a filter, which distinguishes a combo
 *            box loading matches for typed text from one loading everything
 * @param offset
 *            index of the first item requested, {@code -1} for a count
 * @param limit
 *            number of items requested, {@code -1} for a count
 * @param rows
 *            for a fetch, the items actually returned; for a count, the
 *            reported item total; {@code -1} when the query threw
 * @param durationMs
 *            how long the query took, in milliseconds
 * @param thresholdMs
 *            the budget this query was measured against, {@code -1} when it was
 *            not retained for being slow
 * @param outcome
 *            {@link #OUTCOME_SUCCESS} or {@link #OUTCOME_ERROR}
 * @param exceptionType
 *            fully-qualified class of the throwable, {@code null} for
 *            successful queries
 */
public record CapturedQuery(Instant timestamp, String route, String component,
        String kind, boolean filtered, int offset, int limit, int rows,
        long durationMs, long thresholdMs, String outcome,
        String exceptionType) {

    public static final String KIND_COUNT = "count";
    public static final String KIND_FETCH = "fetch";

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_ERROR = "error";
}
