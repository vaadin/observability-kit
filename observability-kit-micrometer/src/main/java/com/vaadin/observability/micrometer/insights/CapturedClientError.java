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
 * One error raised in a browser, with the detail {@code vaadin.client.errors}
 * cannot carry.
 * <p>
 * The counter says how many browser errors happened and whether each was an
 * uncaught throw or an unhandled rejection. That is all it can say: a message,
 * a script URL and a stack frame are free-form text, and putting them on meter
 * tags would make one time series per distinct message. They are retained here
 * instead, the same way a failed server interaction is retained as a
 * {@link CapturedInteraction}, so a report can name the error rather than count
 * it.
 *
 * @param timestamp
 *            when the server received the report. The error happened
 *            {@code bufferedMs} earlier on the browser's clock
 * @param route
 *            the route template of the view the browser was on, resolved from
 *            the path the browser reported
 * @param kind
 *            {@code uncaught} for a throw that reached {@code window.onerror},
 *            {@code promise} for an unhandled rejection — the same values that
 *            tag the counter
 * @param message
 *            the error message, truncated; {@code null} when it was not
 *            collected (see {@code detailsIncluded})
 * @param source
 *            where the browser said it came from, as {@code script-url:line};
 *            {@code null} when the browser named no location
 * @param frame
 *            the location named by the first frame of the error's stack, as
 *            {@code script-url:line:col} — the browser-side equivalent of
 *            {@link CapturedInteraction#applicationFrame()}. The location
 *            <em>only</em>, without the function name that stood in front of
 *            it: see {@link StackFrames}. {@code null} when the stack held no
 *            frame that named a location
 * @param function
 *            the name of the function that frame was in, exactly as the browser
 *            wrote it and truncated; {@code null} when the frame named none, or
 *            whenever detail was not collected (see {@code detailsIncluded}).
 *            Gated with the message rather than published beside the location,
 *            because a page can set a function's name to any string it likes
 * @param bufferedMs
 *            how long the <em>report</em> waited for the browser to reach the
 *            server again, measured on the browser's own clock. Offline time
 *            only, not the wait for the next flush, so zero for an ordinary
 *            report however long it sat in the buffer. Greater than zero means
 *            this report could not be delivered when it was raised — which is
 *            not the same as the error happening during an outage: a report
 *            taken while the browser was connected still accrues an outage that
 *            begins before the next flush. Read it as evidence that something
 *            went wrong the server could not be told about at the time, not as
 *            a measurement of when it went wrong
 * @param detailsIncluded
 *            whether potentially sensitive detail was collected: the raw
 *            session id, the error message and the function name. Recorded per
 *            error so a report can say that a field was withheld rather than
 *            absent
 * @param sessionId
 *            the Vaadin session id when detail was collected, otherwise a short
 *            one-way hash of it
 * @param uiId
 *            UI id within the session, i.e. which browser tab reported it
 */
public record CapturedClientError(Instant timestamp, String route, String kind,
        String message, String source, String frame, String function,
        long bufferedMs, boolean detailsIncluded, String sessionId, int uiId) {
}
