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

/**
 * Turns raw interaction exemplars into insights, and renders the payload served
 * at {@code /actuator/vaadin/observability}. Exemplars are grouped so that N
 * users hitting the same problem produce ONE insight with N occurrences; each
 * insight type is a rule over the shared exemplar buffer:
 * <ul>
 * <li>{@code user-interaction-error}: failed interactions, grouped by (route,
 * component, event, exception);</li>
 * <li>{@code slow-user-interaction}: successful interactions over the
 * {@link InteractionExemplarCollector#UX_BUDGET_MS UX budget}, grouped by
 * (route, component, event).</li>
 * </ul>
 * The output is a stable, AI-agent-readable contract: an agent with access to
 * the application codebase can open {@code evidence.applicationFrame} (or the
 * component's event handler), verify the problem and propose a fix; a human can
 * follow {@code replay} to reproduce.
 */
public class InsightsService {

    private static final int EXEMPLARS_PER_INSIGHT = 3;

    private final ExemplarBuffer buffer;

    public InsightsService(ExemplarBuffer buffer) {
        this.buffer = buffer;
    }

    public Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("generated", Instant.now().toString());
        payload.put("insights", insights());
        return payload;
    }

    private List<Map<String, Object>> insights() {
        List<InteractionExemplar> all = buffer.snapshot();
        List<Map<String, Object>> insights = new ArrayList<>();
        groups(all, e -> InteractionExemplar.OUTCOME_ERROR.equals(e.outcome()),
                e -> String.join("|", nullSafe(e.route()),
                        nullSafe(e.component()), nullSafe(e.event()),
                        nullSafe(e.exceptionType())))
                .forEach(group -> insights.add(errorInsight(group)));
        groups(all, e -> InteractionExemplar.OUTCOME_SUCCESS.equals(e.outcome())
                && e.durationMs() >= InteractionExemplarCollector.UX_BUDGET_MS,
                e -> String.join("|", nullSafe(e.route()),
                        nullSafe(e.component()), nullSafe(e.event())))
                .forEach(group -> insights.add(slowInsight(group)));
        return insights;
    }

    private static List<List<InteractionExemplar>> groups(
            List<InteractionExemplar> exemplars,
            Predicate<InteractionExemplar> rule,
            Function<InteractionExemplar, String> key) {
        return List.copyOf(exemplars.stream().filter(rule).collect(Collectors
                .groupingBy(key, LinkedHashMap::new, Collectors.toList()))
                .values());
    }

    private static Map<String, Object> errorInsight(
            List<InteractionExemplar> group) {
        // Snapshot is newest-first, so the head is the latest occurrence.
        InteractionExemplar latest = group.get(0);

        Map<String, Object> insight = new LinkedHashMap<>();
        insight.put("id", "user-interaction-error");
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
        evidence.put("message", latest.exceptionMessage());
        evidence.put("applicationFrame", latest.applicationFrame());
        insight.put("evidence", evidence);

        insight.put("replay", List.of(
                "Open route '/%s'".formatted(nullSafe(latest.route())),
                "Locate component %s".formatted(simpleName(latest.component())),
                "Trigger a '%s' event on it"
                        .formatted(nullSafe(latest.event())),
                "Expect %s: %s".formatted(simpleName(latest.exceptionType()),
                        nullSafe(latest.exceptionMessage()))));

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

        insight.put("exemplars", exemplarsJson(group));
        return insight;
    }

    private static Map<String, Object> slowInsight(
            List<InteractionExemplar> group) {
        InteractionExemplar latest = group.get(0);
        long maxMs = group.stream().mapToLong(InteractionExemplar::durationMs)
                .max().orElse(-1);

        Map<String, Object> insight = new LinkedHashMap<>();
        insight.put("id", "slow-user-interaction");
        insight.put("severity", "warning");
        insight.put("category", "performance");
        insight.put("summary",
                "User interaction '%s' on %s took up to %d ms, over the %d ms UX budget (%d occurrence%s)"
                        .formatted(nullSafe(latest.event()),
                                simpleName(latest.component()), maxMs,
                                InteractionExemplarCollector.UX_BUDGET_MS,
                                group.size(), group.size() == 1 ? "" : "s"));

        Map<String, Object> evidence = commonEvidence(group);
        evidence.put("maxDurationMs", maxMs);
        evidence.put("thresholdMs", InteractionExemplarCollector.UX_BUDGET_MS);
        insight.put("evidence", evidence);

        insight.put("replay", List.of(
                "Open route '/%s'".formatted(nullSafe(latest.route())),
                "Locate component %s".formatted(simpleName(latest.component())),
                "Trigger a '%s' event on it"
                        .formatted(nullSafe(latest.event())),
                "Expect the UI to stay unresponsive for roughly %d ms"
                        .formatted(maxMs)));

        insight.put("suggestion",
                ("The '%s' handler in %s blocks the request for up to %d ms, "
                        + "over the %d ms UX budget. An AI agent with codebase "
                        + "access should inspect the handler and make the slow "
                        + "work faster, paginated, or move it off the request "
                        + "thread and push the result via ui.access().")
                        .formatted(nullSafe(latest.event()),
                                simpleName(latest.component()), maxMs,
                                InteractionExemplarCollector.UX_BUDGET_MS));

        insight.put("exemplars", exemplarsJson(group));
        return insight;
    }

    private static Map<String, Object> commonEvidence(
            List<InteractionExemplar> group) {
        InteractionExemplar latest = group.get(0);
        Instant firstSeen = group.stream().map(InteractionExemplar::timestamp)
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

    private static List<Map<String, Object>> exemplarsJson(
            List<InteractionExemplar> group) {
        return group.stream().limit(EXEMPLARS_PER_INSIGHT)
                .map(InsightsService::exemplarJson).toList();
    }

    private static Map<String, Object> exemplarJson(
            InteractionExemplar exemplar) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("timestamp", exemplar.timestamp().toString());
        json.put("durationMs", exemplar.durationMs());
        json.put("sessionId", exemplar.sessionId());
        json.put("uiId", exemplar.uiId());
        if (exemplar.stackTop() != null) {
            json.put("stackTop", exemplar.stackTop());
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
