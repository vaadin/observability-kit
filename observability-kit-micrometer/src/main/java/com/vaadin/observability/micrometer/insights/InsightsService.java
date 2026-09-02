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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

/**
 * Turns captured interactions into insights, and renders the payload served at
 * {@code /actuator/vaadin/observability}. Interactions are grouped so that N
 * users hitting the same problem produce ONE insight with N occurrences; each
 * insight type is a rule over the shared interaction buffer:
 * <ul>
 * <li>{@code user-interaction-error}: failed interactions, grouped by (route,
 * component, event, exception);</li>
 * <li>{@code slow-user-interaction}: successful interactions over the
 * {@link InteractionCollector#UX_BUDGET_MS UX budget}, grouped by (route,
 * component, event);</li>
 * <li>{@code client-error}: errors raised in a browser, grouped by (route,
 * kind, source, frame). These come from the in-browser collector rather than
 * the interaction buffer, and are the one insight kind that can describe a
 * failure the server never saw.</li>
 * </ul>
 * The output is a stable, AI-agent-readable contract: an agent with access to
 * the application codebase can open {@code evidence.applicationFrame} (or the
 * component's event handler), verify the problem and propose a fix; a human can
 * follow {@code replay} to reproduce.
 */
public class InsightsService {

    private final RecentQueries queries;

    private static final int EXAMPLES_PER_INSIGHT = 3;

    /**
     * Every string in the payload is built through here rather than through
     * {@link String#formatted(Object...)}, so that the numbers in it do not
     * depend on the server's default locale.
     * <p>
     * They would otherwise. {@code %,d} takes its grouping separator from the
     * default format locale — {@code 2,000,000} in one, {@code 2.000.000} or
     * {@code 2 000 000} in another — and plain {@code %d} takes its
     * <em>digits</em> from it, so a server running under {@code ar-EG} renders
     * {@code took ١٢ ms}. Neither belongs in something documented as a stable
     * contract and read by machines: the payload has to say the same thing
     * whoever is running it.
     *
     * @param format
     *            the format string
     * @param args
     *            its arguments
     * @return the formatted string, in {@link Locale#ROOT}
     */
    private static String text(String format, Object... args) {
        return String.format(Locale.ROOT, format, args);
    }

    private final @Nullable RecentInteractions buffer;

    private final @Nullable RecentClientErrors clientErrors;

    /**
     * @param buffer
     *            the interactions recorded so far, or {@code null} when the kit
     *            registered no instrumentation — the payload then says so
     *            explicitly instead of reporting an empty result
     */
    public InsightsService(@Nullable RecentInteractions buffer) {
        this(buffer, null, null);
    }

    /**
     * @param buffer
     *            retained interactions, or {@code null} when the interaction
     *            collector was not registered
     * @param queries
     *            retained data provider queries, or {@code null} when the query
     *            collector was not registered
     */
    public InsightsService(@Nullable RecentInteractions buffer,
            @Nullable RecentQueries queries) {
        this(buffer, queries, null);
    }

    /**
     * @param buffer
     *            retained interactions, or {@code null} when the interaction
     *            collector was not registered
     * @param queries
     *            retained data provider queries, or {@code null} when the query
     *            collector was not registered
     * @param clientErrors
     *            retained browser errors, or {@code null} when the in-browser
     *            collector was not registered
     */
    public InsightsService(@Nullable RecentInteractions buffer,
            @Nullable RecentQueries queries,
            @Nullable RecentClientErrors clientErrors) {
        this.queries = queries;
        this.buffer = buffer;
        this.clientErrors = clientErrors;
    }

    public Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("generated", Instant.now().toString());
        // "no insights" and "nothing was watching" are different answers, and a
        // consumer cannot act on the second one without being told.
        payload.put("instrumentation",
                buffer == null && queries == null && clientErrors == null
                        ? "inactive"
                        : "active");
        List<Map<String, Object>> insights = new ArrayList<>();
        if (buffer != null) {
            insights.addAll(insights());
        }
        if (queries != null) {
            insights.addAll(queryInsights());
        }
        if (clientErrors != null) {
            insights.addAll(clientErrorInsights());
        }
        payload.put("insights", insights);
        return payload;
    }

    private List<Map<String, Object>> insights() {
        List<CapturedInteraction> all = buffer.snapshot();
        List<Map<String, Object>> insights = new ArrayList<>();
        groups(all, e -> CapturedInteraction.OUTCOME_ERROR.equals(e.outcome()),
                e -> String.join("|", nullSafe(e.route()),
                        nullSafe(e.component()), nullSafe(e.event()),
                        nullSafe(e.exceptionType())))
                .forEach(group -> insights.add(errorInsight(group)));
        // No threshold re-check here: the collector only retains a successful
        // interaction when it already exceeded the budget it was configured
        // with. Re-filtering against the static default would silently drop
        // interactions captured under a lower budget.
        groups(all,
                e -> CapturedInteraction.OUTCOME_SUCCESS.equals(e.outcome()),
                e -> String.join("|", nullSafe(e.route()),
                        nullSafe(e.component()), nullSafe(e.event())))
                .forEach(group -> insights.add(slowInsight(group)));
        return insights;
    }

    /**
     * Insights for errors browsers reported: one per (route, kind, source,
     * frame), so the same script failing in a hundred tabs is one finding with
     * a hundred occurrences.
     * <p>
     * These are the only insights that can describe a failure the server never
     * handled — including ones that happened while the browser could not reach
     * it at all, which {@code bufferedMs} makes visible.
     */
    private List<Map<String, Object>> clientErrorInsights() {
        // Every reported error is an insight: unlike an interaction, a
        // browser error has no successful counterpart to filter out.
        return groups(clientErrors.snapshot(), error -> true,
                e -> String.join("|", nullSafe(e.route()), e.kind(),
                        nullSafe(e.source()), nullSafe(e.frame())))
                .stream().map(InsightsService::clientErrorInsight).toList();
    }

    private static Map<String, Object> clientErrorInsight(
            List<CapturedClientError> group) {
        // Snapshot is newest-first, so the head is the latest occurrence.
        CapturedClientError latest = group.get(0);
        long maxBufferedMs = group.stream()
                .mapToLong(CapturedClientError::bufferedMs).max().orElse(0);
        // The prose supplies its own "at", and the source fallback needs it.
        // A report with neither a frame nor a source gets a phrase rather than
        // the "_unknown" sentinel: it has to read inside three sentences.
        String where = location(latest);

        Map<String, Object> insight = new LinkedHashMap<>();
        insight.put("type", "client-error");
        insight.put("severity", "error");
        insight.put("category", "reliability");
        insight.put("summary", text(
                "A browser error (%s) at %s on route '%s' (%d occurrence%s)%s",
                latest.kind(), where, nullSafe(latest.route()), group.size(),
                group.size() == 1 ? "" : "s",
                // A group maximum, so at least one -- not necessarily the
                // latest, which is what the rest of the summary describes.
                maxBufferedMs > 0
                        ? ", at least one of them reported "
                                + "only after the browser got the server back"
                        : ""));

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("route", latest.route());
        evidence.put("kind", latest.kind());
        evidence.put("source", latest.source());
        evidence.put("frame", latest.frame());
        if (latest.function() != null) {
            // Only present when detail was collected: a function's name is a
            // string the page chose, so it travels under the same gate as the
            // message rather than inside `frame`.
            evidence.put("function", latest.function());
        }
        if (!latest.detailsIncluded()) {
            // Without this a reader cannot tell a field that was not collected
            // from one the browser never reported. "Not collected" rather than
            // "withheld", because with the setting off nothing here knows
            // whether there was a message or a name to withhold -- an unnamed
            // V8 frame and Firefox's bare @ have no name at all.
            evidence.put("detail",
                    "message and function name were not collected; enable "
                            + "vaadin.observability.insights-details to "
                            + "collect them");
        } else if (latest.message() != null) {
            evidence.put("message", latest.message());
        } else {
            // Collection was on and there was still nothing: a cross-origin
            // "Script error." carries no message, and neither does a
            // rejection with no reason. Saying so beats a null field that
            // reads like the setting is off.
            evidence.put("detail",
                    "message collection is on, but the browser reported none "
                            + "for this error");
        }
        evidence.put("occurrences", group.size());
        // The giveaway that this could not be reported when it happened: the
        // browser had lost the server and held the report until it was back.
        evidence.put("maxBufferedMs", maxBufferedMs);
        evidence.put("firstSeen",
                group.stream().map(CapturedClientError::timestamp)
                        .min(Comparator.naturalOrder()).orElseThrow()
                        .toString());
        evidence.put("lastSeen", latest.timestamp().toString());
        // Be explicit about what the timestamps mean: they are arrival times,
        // and an arrival can lag the error by a whole outage. And about which
        // occurrence maxBufferedMs describes -- it is the worst of the group,
        // so it says at least one of these happened during an outage, never
        // that the latest one did.
        evidence.put("measures",
                "timestamps are when the server received the report; "
                        + "maxBufferedMs is the longest any occurrence in this "
                        + "group spent waiting in the browser for the server "
                        + "to become reachable again, measured on the "
                        + "browser's clock -- see examples[].bufferedMs for "
                        + "which ones waited");
        insight.put("evidence", evidence);

        insight.put("replay", List.of(
                // The route template, so no leading slash: the interaction
                // insights open a concrete path, this one a route.
                text("Open route '%s'", nullSafe(latest.route())),
                text("Exercise the page until %s runs", where),
                latest.message() == null
                        ? text("Expect an %s browser error", latest.kind())
                        : text("Expect an %s browser error: %s", latest.kind(),
                                latest.message())));

        // Built first, and on its own: appended inline it would be an argument
        // to the outer text() rather than a format string of its own, and its
        // %d would reach the payload verbatim.
        // Says only what the number measures. A report is stamped with the
        // offline time between being taken and being sent, so an error raised
        // while the browser was connected still carries the outage that began
        // moments later -- the wait is evidence that the report could not get
        // out, never that the error happened while it could not.
        String heldNote = maxBufferedMs > 0 ? text(
                " A report in this group waited %d ms for the browser to reach the server again, so at least one of these could not be delivered when it was raised.",
                maxBufferedMs) : "";
        insight.put("suggestion", text(
                "Inspect %s. The browser raised this %s error, so the server "
                        + "handled nothing and no server-side stack trace "
                        + "exists. An AI agent with codebase access should open "
                        + "that location — a client-side view, a web component "
                        + "or a bundled dependency — and propose a fix.%s",
                where, latest.kind(), heldNote));

        insight.put("examples",
                group.stream().limit(EXAMPLES_PER_INSIGHT).map(e -> {
                    Map<String, Object> example = new LinkedHashMap<>();
                    example.put("at", e.timestamp().toString());
                    example.put("bufferedMs", e.bufferedMs());
                    example.put("sessionId", e.sessionId());
                    example.put("uiId", e.uiId());
                    if (e.message() != null) {
                        example.put("message", e.message());
                    }
                    return example;
                }).toList());
        return insight;
    }

    /**
     * Insights for data provider queries: one grouped by component and
     * exception for failures, one grouped by component and kind for slow
     * queries.
     */
    private List<Map<String, Object>> queryInsights() {
        List<CapturedQuery> all = queries.snapshot();
        List<Map<String, Object>> insights = new ArrayList<>();
        groups(all, q -> CapturedQuery.OUTCOME_ERROR.equals(q.outcome()),
                q -> String.join("|", nullSafe(q.route()),
                        nullSafe(q.component()), q.kind(),
                        nullSafe(q.exceptionType())))
                .forEach(group -> insights.add(queryErrorInsight(group)));
        groups(all, q -> CapturedQuery.OUTCOME_SUCCESS.equals(q.outcome()),
                q -> String.join("|", nullSafe(q.route()),
                        nullSafe(q.component()), q.kind()))
                .forEach(group -> insights.add(slowQueryInsight(group)));
        return insights;
    }

    private static Map<String, Object> queryErrorInsight(
            List<CapturedQuery> group) {
        CapturedQuery latest = group.get(0);
        Map<String, Object> insight = new LinkedHashMap<>();
        insight.put("type", "data-query-error");
        insight.put("severity", "error");
        insight.put("category", "reliability");
        insight.put("summary",
                text("%s query for %s failed with %s (%d occurrence%s)",
                        latest.kind(), simpleName(latest.component()),
                        simpleName(latest.exceptionType()), group.size(),
                        group.size() == 1 ? "" : "s"));
        insight.put("evidence", queryEvidence(group));
        insight.put("replay", List.of(
                text("Open route '%s'", nullSafe(latest.route())),
                text("Load data into %s", simpleName(latest.component())),
                text("Expect the %s query to fail with %s", latest.kind(),
                        simpleName(latest.exceptionType()))));
        insight.put("examples", queryExamples(group));
        return insight;
    }

    private static Map<String, Object> slowQueryInsight(
            List<CapturedQuery> group) {
        CapturedQuery latest = group.get(0);
        long medianMs = medianQueryMs(group);
        long maxMs = group.stream().mapToLong(CapturedQuery::durationMs).max()
                .orElse(-1);
        Map<String, Object> insight = new LinkedHashMap<>();
        insight.put("type", "slow-data-query");
        insight.put("severity", "warning");
        insight.put("category", "performance");
        String scale = CapturedQuery.KIND_COUNT.equals(latest.kind())
                && latest.rows() >= 0
                        ? text(" It counted %,d items.", latest.rows())
                        : "";
        insight.put("summary",
                text("The %s query for %s takes %d ms (max %d ms), over the "
                        + "%d ms budget. The component cannot render until it "
                        + "returns, so this is time the user waits.%s",
                        latest.kind(), simpleName(latest.component()), medianMs,
                        maxMs, latest.thresholdMs(), scale));
        insight.put("evidence", queryEvidence(group));
        insight.put("replay",
                List.of(text("Open route '%s'", nullSafe(latest.route())),
                        text("Load data into %s",
                                simpleName(latest.component())),
                        text("Expect the %s query to take around %d ms",
                                latest.kind(), medianMs)));
        insight.put("examples", queryExamples(group));
        return insight;
    }

    private static long medianQueryMs(List<CapturedQuery> group) {
        long[] sorted = group.stream().mapToLong(CapturedQuery::durationMs)
                .sorted().toArray();
        if (sorted.length == 0) {
            return -1;
        }
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle]
                : (sorted[middle - 1] + sorted[middle]) / 2;
    }

    private static Map<String, Object> queryEvidence(
            List<CapturedQuery> group) {
        CapturedQuery latest = group.get(0);
        Instant firstSeen = group.stream().map(CapturedQuery::timestamp)
                .min(Comparator.naturalOrder()).orElseThrow();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("route", latest.route());
        evidence.put("component", latest.component());
        evidence.put("queryKind", latest.kind());
        evidence.put("filtered", latest.filtered());
        if (CapturedQuery.KIND_FETCH.equals(latest.kind())) {
            evidence.put("requested", latest.limit());
            evidence.put("returned", latest.rows());
        } else if (latest.rows() >= 0) {
            // A count carries its result in the same field. Reporting it is
            // the point of a slow-count insight: "took 4 s" is far less
            // actionable than "took 4 s counting 2,000,000 items".
            evidence.put("counted", latest.rows());
        }
        if (latest.exceptionType() != null) {
            evidence.put("exception", latest.exceptionType());
        }
        evidence.put("occurrences", group.size());
        evidence.put("firstSeen", firstSeen.toString());
        evidence.put("lastSeen", latest.timestamp().toString());
        return evidence;
    }

    private static List<Map<String, Object>> queryExamples(
            List<CapturedQuery> group) {
        return group.stream().limit(EXAMPLES_PER_INSIGHT).map(q -> {
            Map<String, Object> example = new LinkedHashMap<>();
            example.put("at", q.timestamp().toString());
            example.put("durationMs", q.durationMs());
            if (CapturedQuery.KIND_FETCH.equals(q.kind())) {
                example.put("offset", q.offset());
                example.put("limit", q.limit());
                example.put("rows", q.rows());
            }
            return example;
        }).toList();
    }

    /**
     * The grouping every insight kind is built on: keep the records the rule
     * admits, gather them by key, and hand back the groups in the order their
     * first member appeared. The buffers are newest-first, so each group's head
     * is its latest occurrence — which is what the insights report as
     * {@code lastSeen} and build their summary from.
     *
     * @param <T>
     *            the captured record type
     * @param records
     *            the snapshot to group, newest first
     * @param rule
     *            which records this insight kind is about
     * @param key
     *            what makes two records the same finding
     * @return the groups, each newest-first
     */
    private static <T> List<List<T>> groups(List<T> records, Predicate<T> rule,
            Function<T, String> key) {
        return List.copyOf(
                records.stream().filter(rule).collect(Collectors.groupingBy(key,
                        LinkedHashMap::new, Collectors.toList())).values());
    }

    private static Map<String, Object> errorInsight(
            List<CapturedInteraction> group) {
        // Snapshot is newest-first, so the head is the latest occurrence.
        CapturedInteraction latest = group.get(0);

        Map<String, Object> insight = new LinkedHashMap<>();
        insight.put("type", "user-interaction-error");
        insight.put("severity", "error");
        insight.put("category", "reliability");
        insight.put("summary", text(
                "User interaction '%s' on %s failed with %s (%d occurrence%s)",
                nullSafe(latest.event()), simpleName(latest.component()),
                simpleName(latest.exceptionType()), group.size(),
                group.size() == 1 ? "" : "s"));

        Map<String, Object> evidence = commonEvidence(group);
        evidence.put("exception", latest.exceptionType());
        if (latest.detailsIncluded()) {
            evidence.put("message", latest.exceptionMessage());
        } else {
            // Without this a reader cannot tell a withheld message from an
            // exception that carried none.
            evidence.put("detail", "message and stack frames withheld; enable "
                    + "vaadin.observability.insights-details to collect them");
        }
        evidence.put("applicationFrame", latest.applicationFrame());
        insight.put("evidence", evidence);

        insight.put("replay", List.of(
                text("Open route '/%s'", nullSafe(latest.location())),
                text("Locate component %s", simpleName(latest.component())),
                text("Trigger a '%s' event on it", nullSafe(latest.event())),
                latest.exceptionMessage() == null
                        ? text("Expect %s", simpleName(latest.exceptionType()))
                        : text("Expect %s: %s",
                                simpleName(latest.exceptionType()),
                                latest.exceptionMessage())));

        insight.put("suggestion", text(
                "Inspect %s; the '%s' handler in %s throws %s. An AI agent with "
                        + "codebase access should open that location, identify the "
                        + "root cause and propose a fix.",
                latest.applicationFrame() != null ? latest.applicationFrame()
                        : "the component's event listener",
                nullSafe(latest.event()), simpleName(latest.component()),
                simpleName(latest.exceptionType())));

        insight.put("examples", examplesJson(group));
        return insight;
    }

    private static Map<String, Object> slowInsight(
            List<CapturedInteraction> group) {
        CapturedInteraction latest = group.get(0);
        long maxMs = group.stream().mapToLong(CapturedInteraction::durationMs)
                .max().orElse(-1);
        // The headline number is the median, not the worst case: one outlier
        // would otherwise describe a group that is only just over budget.
        long medianMs = medianDurationMs(group);

        // The budget travels with the interaction, so a report reflects the
        // budget that was actually in force rather than the static default.
        long thresholdMs = latest.thresholdMs();

        Map<String, Object> insight = new LinkedHashMap<>();
        insight.put("type", "slow-user-interaction");
        insight.put("severity", "warning");
        insight.put("category", "performance");
        insight.put("summary", text(
                "Server handling of user interaction '%s' on %s took %d "
                        + "ms at the median (worst %d ms), over the %d ms UX "
                        + "budget (%d occurrence%s)",
                nullSafe(latest.event()), simpleName(latest.component()),
                medianMs, maxMs, thresholdMs, group.size(),
                group.size() == 1 ? "" : "s"));

        Map<String, Object> evidence = commonEvidence(group);
        evidence.put("medianDurationMs", medianMs);
        evidence.put("maxDurationMs", maxMs);
        evidence.put("thresholdMs", thresholdMs);
        // Be explicit about what was measured: the number below is the RPC
        // invocation only, so a user-perceived delay may be larger.
        evidence.put("measures",
                "server-side RPC handling only; excludes session-lock wait, "
                        + "network transfer and client-side rendering");
        insight.put("evidence", evidence);

        insight.put("replay", List.of(
                text("Open route '/%s'", nullSafe(latest.location())),
                text("Locate component %s", simpleName(latest.component())),
                text("Trigger a '%s' event on it", nullSafe(latest.event())),
                text("Expect the server to spend roughly %d ms handling it",
                        medianMs)));

        insight.put("suggestion", text(
                "The '%s' handler in %s occupies the request thread for about "
                        + "%d ms at the median and up to %d ms at worst, over "
                        + "the %d ms UX budget. That is server-side "
                        + "handling alone, so what the user feels is at least "
                        + "this much. An AI agent with codebase access should "
                        + "inspect the handler and make the slow work faster, "
                        + "paginated, or move it off the request thread and "
                        + "push the result via ui.access().",
                nullSafe(latest.event()), simpleName(latest.component()),
                medianMs, maxMs, thresholdMs));

        insight.put("examples", examplesJson(group));
        return insight;
    }

    /**
     * The median duration of a group. Even-sized groups average the two middle
     * values, so a two-occurrence group is not represented by either extreme.
     */
    private static long medianDurationMs(List<CapturedInteraction> group) {
        long[] sorted = group.stream()
                .mapToLong(CapturedInteraction::durationMs).sorted().toArray();
        if (sorted.length == 0) {
            return -1;
        }
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle]
                : (sorted[middle - 1] + sorted[middle]) / 2;
    }

    private static Map<String, Object> commonEvidence(
            List<CapturedInteraction> group) {
        CapturedInteraction latest = group.get(0);
        Instant firstSeen = group.stream().map(CapturedInteraction::timestamp)
                .min(Comparator.naturalOrder()).orElseThrow();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("route", latest.route());
        evidence.put("component", latest.component());
        evidence.put("event", latest.event());
        evidence.put("rpcType", latest.rpcType());
        evidence.put("occurrences", group.size());
        evidence.put("firstSeen", firstSeen.toString());
        evidence.put("lastSeen", latest.timestamp().toString());
        return evidence;
    }

    private static List<Map<String, Object>> examplesJson(
            List<CapturedInteraction> group) {
        return group.stream().limit(EXAMPLES_PER_INSIGHT)
                .map(InsightsService::exampleJson).toList();
    }

    private static Map<String, Object> exampleJson(
            CapturedInteraction interaction) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("timestamp", interaction.timestamp().toString());
        // The concrete location, as opposed to the grouped-on route template.
        json.put("location", interaction.location());
        json.put("durationMs", interaction.durationMs());
        json.put("sessionId", interaction.sessionId());
        json.put("uiId", interaction.uiId());
        if (interaction.stackTop() != null) {
            json.put("stackTop", interaction.stackTop());
        }
        return json;
    }

    /**
     * Where a browser error is to be looked for, as the summary and the
     * suggestion name it: the location of the first stack frame when there is
     * one, the script the browser blamed otherwise.
     * <p>
     * Both are already locations rather than whole stack lines — the function
     * name that stood in front of a frame is not part of it, so the prose does
     * not have to unpick "at renderChart (…)" to avoid reading "at at".
     */
    private static String location(CapturedClientError error) {
        if (error.frame() != null && !error.frame().isBlank()) {
            return error.frame();
        }
        if (error.source() != null && !error.source().isBlank()) {
            return error.source();
        }
        return "an unreported script";
    }

    private static String nullSafe(String value) {
        return value != null ? value : "_unknown";
    }

    private static String simpleName(String className) {
        if (className == null) {
            return "_unknown";
        }
        int lastDot = className.lastIndexOf('.');
        return lastDot >= 0 ? className.substring(lastDot + 1) : className;
    }
}
