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

import com.vaadin.observability.micrometer.MeterNames;

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
 * @param caption
 *            what that component is called on screen, e.g.
 *            {@code Process return}, so a replay step names the one button the
 *            reader is looking for rather than its widget kind; {@code null}
 *            when it has no caption and in production, where captions are not
 *            collected at all
 * @param event
 *            the client event that triggered the invocation, e.g. {@code click}
 * @param rpcType
 *            the Flow RPC invocation type, e.g. {@code event}
 * @param precedingSteps
 *            what the user did in this UI before this interaction, oldest
 *            first, at most {@link InteractionTrail#MAX_STEPS}; empty in
 *            production, where no trail is kept
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
        String location, String component, String caption, String event,
        String rpcType, List<InteractionStep> precedingSteps, String outcome,
        long durationMs, long thresholdMs, boolean detailsIncluded,
        String exceptionType, String exceptionMessage, String applicationFrame,
        List<String> stackTop, String sessionId, int uiId) {

    // Aliased from the Meter tag values rather than repeated, so an insight
    // and the meter it can be correlated with cannot drift apart.
    public static final String OUTCOME_SUCCESS = MeterNames.OUTCOME_SUCCESS;
    public static final String OUTCOME_ERROR = MeterNames.OUTCOME_ERROR;

    /**
     * An interaction captured without the screen detail development mode adds:
     * no caption, no trail. This is what a production payload carries, and what
     * a caller that has nothing to say about either should build.
     *
     * @param timestamp
     *            when the interaction completed
     * @param route
     *            the route template of the active view
     * @param location
     *            the concrete location the interaction happened on
     * @param component
     *            fully-qualified class of the interacted component
     * @param event
     *            the client event that triggered the invocation
     * @param rpcType
     *            the Flow RPC invocation type
     * @param outcome
     *            {@link #OUTCOME_SUCCESS} or {@link #OUTCOME_ERROR}
     * @param durationMs
     *            server-side RPC handling time, {@code -1} if unknown
     * @param thresholdMs
     *            the UX budget measured against, {@code -1} if not applicable
     * @param detailsIncluded
     *            whether sensitive detail was collected
     * @param exceptionType
     *            fully-qualified class of the root cause, or {@code null}
     * @param exceptionMessage
     *            message of the root cause, or {@code null}
     * @param applicationFrame
     *            first stack frame in application code, or {@code null}
     * @param stackTop
     *            top frames of the root-cause stack, or {@code null}
     * @param sessionId
     *            the session id, raw or hashed
     * @param uiId
     *            UI id within the session
     */
    public CapturedInteraction(Instant timestamp, String route, String location,
            String component, String event, String rpcType, String outcome,
            long durationMs, long thresholdMs, boolean detailsIncluded,
            String exceptionType, String exceptionMessage,
            String applicationFrame, List<String> stackTop, String sessionId,
            int uiId) {
        this(timestamp, route, location, component, null, event, rpcType,
                List.of(), outcome, durationMs, thresholdMs, detailsIncluded,
                exceptionType, exceptionMessage, applicationFrame, stackTop,
                sessionId, uiId);
    }
}
