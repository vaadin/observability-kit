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
 * component, event).</li>
 * </ul>
 * The output is a stable, AI-agent-readable contract: an agent with access to
 * the application codebase can open {@code evidence.applicationFrame} (or the
 * component's event handler), verify the problem and propose a fix; a human can
 * follow {@code replay} to reproduce.
 */
public class InsightsService {

    private final RecentQueries queries;

    private static final int EXAMPLES_PER_INSIGHT = 3;

    private final @Nullable RecentInteractions buffer;

    /**
     * @param buffer
     *            the interactions recorded so far, or {@code null} when the kit
     *            registered no instrumentation — the payload then says so
     *            explicitly instead of reporting an empty result
     */
    public InsightsService(@Nullable RecentInteractions buffer) {
        this(buffer, null);
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
        this.queries = queries;
        this.buffer = buffer;
    }

    public Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("generated", Instant.now().toString());
        // "no insights" and "nothing was watching" are different answers, and a
        // consumer cannot act on the second one without being told.
        payload.put("instrumentation",
                buffer == null && queries == null ? "inactive" : "active");
        List<Map<String, Object>> insights = new ArrayList<>();
        if (buffer != null) {
            insights.addAll(insights());
        }
        if (queries != null) {
            insights.addAll(queryInsights());
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
     * Insights for data provider queries: one grouped by component and
     * exception for failures, one grouped by component and kind for slow
     * queries.
     */
    private List<Map<String, Object>> queryInsights() {
        List<CapturedQuery> all = queries.snapshot();
        List<Map<String, Object>> insights = new ArrayList<>();
        queryGroups(all, q -> CapturedQuery.OUTCOME_ERROR.equals(q.outcome()),
                q -> String.join("|", nullSafe(q.route()),
                        nullSafe(q.component()), q.kind(),
                        nullSafe(q.exceptionType())))
                .forEach(group -> insights.add(queryErrorInsight(group)));
        queryGroups(all, q -> CapturedQuery.OUTCOME_SUCCESS.equals(q.outcome()),
                q -> String.join("|", nullSafe(q.route()),
                        nullSafe(q.component()), q.kind()))
                .forEach(group -> insights.add(slowQueryInsight(group)));
        return insights;
    }

    private static List<List<CapturedQuery>> queryGroups(
            List<CapturedQuery> all, Predicate<CapturedQuery> rule,
            Function<CapturedQuery, String> key) {
        return List.copyOf(
                all.stream().filter(rule).collect(Collectors.groupingBy(key,
                        LinkedHashMap::new, Collectors.toList())).values());
    }

    private static Map<String, Object> queryErrorInsight(
            List<CapturedQuery> group) {
        CapturedQuery latest = group.get(0);
        Map<String, Object> insight = new LinkedHashMap<>();
        insight.put("type", "data-query-error");
        insight.put("severity", "error");
        insight.put("category", "reliability");
        insight.put("summary",
                "%s query for %s failed with %s (%d occurrence%s)".formatted(
                        latest.kind(), simpleName(latest.component()),
                        simpleName(latest.exceptionType()), group.size(),
                        group.size() == 1 ? "" : "s"));
        insight.put("evidence", queryEvidence(group));
        insight.put("replay", List.of(
                "Open route '%s'".formatted(nullSafe(latest.route())),
                "Load data into %s".formatted(simpleName(latest.component())),
                "Expect the %s query to fail with %s".formatted(latest.kind(),
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
        insight.put("summary",
                ("The %s query for %s takes %d ms (max %d ms), over the %d ms "
                        + "budget. The component cannot render until it "
                        + "returns, so this is time the user waits.").formatted(
                                latest.kind(), simpleName(latest.component()),
                                medianMs, maxMs, latest.thresholdMs()));
        insight.put("evidence", queryEvidence(group));
        insight.put("replay",
                List.of("Open route '%s'".formatted(nullSafe(latest.route())),
                        "Load data into %s"
                                .formatted(simpleName(latest.component())),
                        "Expect the %s query to take around %d ms"
                                .formatted(latest.kind(), medianMs)));
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

    private static List<List<CapturedInteraction>> groups(
            List<CapturedInteraction> interactions,
            Predicate<CapturedInteraction> rule,
            Function<CapturedInteraction, String> key) {
        return List.copyOf(interactions.stream().filter(rule).collect(Collectors
                .groupingBy(key, LinkedHashMap::new, Collectors.toList()))
                .values());
    }

    private static Map<String, Object> errorInsight(
            List<CapturedInteraction> group) {
        // Snapshot is newest-first, so the head is the latest occurrence.
        CapturedInteraction latest = group.get(0);

        Map<String, Object> insight = new LinkedHashMap<>();
        insight.put("type", "user-interaction-error");
        insight.put("severity", "error");
        insight.put("category", "reliability");
        insight.put("summary",
                "User interaction '%s' on %s failed with %s (%d occurrence%s)"
                        .formatted(nullSafe(latest.event()),
                                simpleName(latest.component()),
                                simpleName(latest.exceptionType()),
                                group.size(), group.size() == 1 ? "" : "s"));

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
                "Open route '/%s'".formatted(nullSafe(latest.location())),
                "Locate component %s".formatted(simpleName(latest.component())),
                "Trigger a '%s' event on it"
                        .formatted(nullSafe(latest.event())),
                latest.exceptionMessage() == null
                        ? "Expect %s"
                                .formatted(simpleName(latest.exceptionType()))
                        : "Expect %s: %s".formatted(
                                simpleName(latest.exceptionType()),
                                latest.exceptionMessage())));

        insight.put("suggestion",
                ("Inspect %s; the '%s' handler in %s throws %s. An AI agent with "
                        + "codebase access should open that location, identify the "
                        + "root cause and propose a fix.")
                        .formatted(
                                latest.applicationFrame() != null
                                        ? latest.applicationFrame()
                                        : "the component's event listener",
                                nullSafe(latest.event()),
                                simpleName(latest.component()),
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
        insight.put("summary",
                ("Server handling of user interaction '%s' on %s took %d ms "
                        + "at the median (worst %d ms), over the %d ms UX "
                        + "budget (%d occurrence%s)")
                        .formatted(nullSafe(latest.event()),
                                simpleName(latest.component()), medianMs, maxMs,
                                thresholdMs, group.size(),
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
                "Open route '/%s'".formatted(nullSafe(latest.location())),
                "Locate component %s".formatted(simpleName(latest.component())),
                "Trigger a '%s' event on it"
                        .formatted(nullSafe(latest.event())),
                "Expect the server to spend roughly %d ms handling it"
                        .formatted(medianMs)));

        insight.put("suggestion",
                ("The '%s' handler in %s occupies the request thread for about "
                        + "%d ms at the median and up to %d ms at worst, over "
                        + "the %d ms UX budget. That is server-side "
                        + "handling alone, so what the user feels is at least "
                        + "this much. An AI agent with codebase access should "
                        + "inspect the handler and make the slow work faster, "
                        + "paginated, or move it off the request thread and "
                        + "push the result via ui.access().")
                        .formatted(nullSafe(latest.event()),
                                simpleName(latest.component()), medianMs, maxMs,
                                thresholdMs));

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
