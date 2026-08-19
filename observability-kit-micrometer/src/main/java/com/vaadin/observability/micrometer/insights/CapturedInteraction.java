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

/**
 * One captured user interaction worth surfacing as an insight, with everything
 * needed to backtrack and replicate it: which route, which component, which
 * user action, how long it took, and, for failures, which exception and where
 * in application code it blew up.
 * <p>
 * Only interesting interactions are retained: failed ones and ones slower than
 * the UX budget ({@link InteractionCollector#UX_BUDGET_MS}).
 *
 * @param timestamp
 *            when the interaction completed
 * @param route
 *            the route <em>template</em> of the active view, e.g.
 *            {@code orders/:orderId}, so that every parameter value groups
 *            under one insight rather than one per value
 * @param location
 *            the concrete location the interaction happened on, e.g.
 *            {@code orders/17}; reported per example rather than used for
 *            grouping
 * @param component
 *            fully-qualified class of the component the user interacted with
 * @param event
 *            the client event that triggered the invocation, e.g. {@code click}
 * @param rpcType
 *            the Flow RPC invocation type, e.g. {@code event}
 * @param outcome
 *            {@link #OUTCOME_SUCCESS} or {@link #OUTCOME_ERROR}
 * @param durationMs
 *            server-side RPC handling time in milliseconds, {@code -1} if
 *            unknown. This covers the invocation only: not session-lock wait,
 *            network, or client-side rendering
 * @param thresholdMs
 *            the UX budget this interaction was measured against, {@code -1}
 *            when it was not retained for being slow. Carried per interaction
 *            so a report never has to guess which budget was in force
 * @param detailsIncluded
 *            whether potentially sensitive detail was collected for this
 *            interaction, i.e. the raw session id, the exception message and
 *            the stack frames. Recorded per interaction so a report can say
 *            that a field was withheld rather than absent
 * @param exceptionType
 *            fully-qualified class of the root-cause exception, {@code null}
 *            for successful interactions
 * @param exceptionMessage
 *            message of the root cause, truncated; {@code null} when it was not
 *            collected (see {@code detailsIncluded}) or the cause had none
 * @param applicationFrame
 *            first stack frame in application code (not JDK/framework), the
 *            most likely location of the bug; {@code null} for successful
 *            interactions
 * @param stackTop
 *            the top frames of the root-cause stack trace; {@code null} for
 *            successful interactions and when detail was not collected
 * @param sessionId
 *            the Vaadin session id when detail was collected, otherwise a short
 *            one-way hash of it, which still correlates the examples of one
 *            insight without identifying the session
 * @param uiId
 *            UI id within the session
 */
public record CapturedInteraction(Instant timestamp, String route,
        String location, String component, String event, String rpcType,
        String outcome, long durationMs, long thresholdMs,
        boolean detailsIncluded, String exceptionType, String exceptionMessage,
        String applicationFrame, List<String> stackTop, String sessionId,
        int uiId) {

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_ERROR = "error";
}
