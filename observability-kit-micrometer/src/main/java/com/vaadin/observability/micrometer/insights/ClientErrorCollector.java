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
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.UI;
import com.vaadin.observability.micrometer.MeterNames;
import com.vaadin.observability.micrometer.ObservabilitySettings;

/**
 * Retains the detail of an error a browser reported, which
 * {@code vaadin.client.errors} counts but cannot describe.
 * <p>
 * The counter and this collector are fed by the same sample: the in-browser
 * collector sends the message, the script it came from and the first stack
 * frame alongside the count, in a field that never becomes a meter tag. The
 * count answers "how often"; the insight answers "what broke, and where" —
 * which is the difference between knowing that browsers are failing and being
 * able to fix it.
 * <p>
 * The detail policy is the one {@link InteractionCollector} applies to a
 * server-side failure, and the line it draws is between a location and text the
 * page chose.
 * <p>
 * Published unconditionally: the kind, the source, and the location of the
 * first stack frame. The latter two go through {@link StackFrames}, which
 * returns a location or nothing — that is what makes "none of this is
 * free-form" true by construction rather than by enumeration.
 * <p>
 * Gated on {@link ObservabilitySettings#isInsightsDetails()}: the error's
 * message, and the frame's <em>function name</em>. The message because it can
 * quote anything the page was working with; the name for the same reason, since
 * {@code Object.defineProperty(f, 'name', …)} takes any string and V8 prints
 * it. With the kit's own collector the browser does not even gather the
 * message, and this class is the second of those two gates.
 */
public class ClientErrorCollector {

    /** Detail key: the path the browser was on when the error happened. */
    public static final String DETAIL_ROUTE = "route";

    /** Detail key: the error message the browser reported. */
    public static final String DETAIL_MESSAGE = "message";

    /** Detail key: where the browser said the error came from. */
    public static final String DETAIL_SOURCE = "source";

    /** Detail key: the first frame of the error's stack. */
    public static final String DETAIL_FRAME = "frame";

    /**
     * Detail key: the function name from that frame. Sent separately from the
     * frame, and only when the browser was told to collect detail, because it
     * is a string the page chose rather than a location.
     */
    public static final String DETAIL_FUNCTION = "function";

    private final RecentClientErrors buffer;
    private final boolean details;

    public ClientErrorCollector(RecentClientErrors buffer,
            ObservabilitySettings settings) {
        this.buffer = buffer;
        this.details = settings.isInsightsDetails();
    }

    /**
     * Retains one reported browser error.
     * <p>
     * Best-effort throughout: an error report that cannot be understood is
     * dropped rather than allowed to fail the ingest of the batch it arrived
     * in, which may be carrying the samples of a whole outage.
     *
     * @param kind
     *            {@link MeterNames#KIND_UNCAUGHT} or
     *            {@link MeterNames#KIND_PROMISE}, as the browser tagged the
     *            sample; a kind outside that set is grouped as
     *            {@link MeterNames#KIND_UNKNOWN}
     * @param route
     *            the route template of the view the error happened on, already
     *            resolved and cardinality-capped, may be {@code null}
     * @param detail
     *            the browser's description of the error, keyed by
     *            {@link #DETAIL_ROUTE}, {@link #DETAIL_MESSAGE},
     *            {@link #DETAIL_SOURCE}, {@link #DETAIL_FRAME} and
     *            {@link #DETAIL_FUNCTION}. The last two of those are the
     *            location and the name of one stack frame, sent apart because
     *            they are trusted differently
     * @param bufferedMs
     *            how long the <em>report</em> waited for the browser to reach
     *            the server again, measured on the browser's clock; {@code 0}
     *            for a report that was only waiting for the next flush. Not the
     *            same as the error happening during an outage — a report taken
     *            while the browser was connected still accrues an outage that
     *            begins before the next flush
     * @param ui
     *            the UI that reported it, may be {@code null}
     */
    public void capture(String kind, String route, Map<String, String> detail,
            long bufferedMs, UI ui) {
        if (detail == null || detail.isEmpty()) {
            // Only the count was sent; there is no insight to build from it.
            return;
        }
        try {
            StackFrames.Frame frame = StackFrames
                    .frame(detail.get(DETAIL_FRAME));
            // The page the browser said it was on, as the browser wrote it:
            // the raw path, before the route templating the tag went through.
            // What the document-URL rule is applied against.
            String pagePath = detail.get(DETAIL_ROUTE);
            buffer.add(new CapturedClientError(Instant.now(), route, kind(kind),
                    details ? InsightDetails
                            .truncate(detail.get(DETAIL_MESSAGE)) : null,
                    // Source and frame both reduce to a location: both are
                    // published whatever the detail policy says, so a location
                    // is all either may be.
                    script(StackFrames.location(detail.get(DETAIL_SOURCE)),
                            pagePath),
                    frame != null ? script(frame.location(), pagePath) : null,
                    // The function name is a string the page chose, so it is
                    // gated with the message rather than published beside the
                    // location. Truncated, not validated: there is no rule that
                    // separates a real name from a crafted one, which is the
                    // reason it is not part of the frame any more.
                    //
                    // The collector sends it under its own key; the fallback
                    // covers a payload that put a whole stack line in `frame`
                    // instead, which is what a crafted one does and what an
                    // older page would still be doing.
                    details ? InsightDetails
                            .truncate(functionName(detail, frame)) : null,
                    Math.max(0, bufferedMs), details,
                    InsightDetails.sessionId(ui, details),
                    ui != null ? ui.getUIId() : -1));
        } catch (RuntimeException e) {
            // Collection is best-effort enrichment; a malformed report must
            // not cost the samples that arrived with it. Logged rather than
            // counted: the sample itself was recorded into the counter before
            // this ran, so vaadin.client.dropped would misreport it as lost.
            LoggerFactory.getLogger(ClientErrorCollector.class).debug(
                    "Could not retain a reported browser error as an insight",
                    e);
        }
    }

    /**
     * A location, unless it names the page's own document rather than a script.
     * <p>
     * A browser reports the document URL — the page's path <em>with its query
     * string</em> — for an error from an inline script, an inline handler or
     * {@code executeJs} code, both as the error's {@code filename} and in the
     * stack frame it writes. That is not a script location: it is the value
     * route templating exists to fold away, and it travels above the detail
     * gate. The collector in the browser drops the plain case before it sends;
     * this is where a crafted payload, and a frame rather than a
     * {@code filename}, meet the same rule.
     */
    private static @Nullable String script(@Nullable String location,
            @Nullable String pagePath) {
        return StackFrames.namesDocument(location, pagePath) ? null : location;
    }

    /**
     * The function name the report carries, from its own key or from whatever
     * stood in front of the location in {@code frame}. Never validated — see
     * {@link StackFrames} — so this only runs behind the detail gate.
     */
    private static @Nullable String functionName(Map<String, String> detail,
            StackFrames.@Nullable Frame frame) {
        String reported = detail.get(DETAIL_FUNCTION);
        if (reported != null && !reported.isBlank()) {
            return reported;
        }
        return frame != null ? frame.function() : null;
    }

    /**
     * Keeps the grouping key inside the two kinds the collector emits, so a
     * crafted payload cannot split one insight into many.
     */
    private static String kind(String reported) {
        if (reported == null) {
            return MeterNames.KIND_UNKNOWN;
        }
        String value = reported.toLowerCase(Locale.ROOT);
        return MeterNames.KINDS.contains(value) ? value
                : MeterNames.KIND_UNKNOWN;
    }
}
