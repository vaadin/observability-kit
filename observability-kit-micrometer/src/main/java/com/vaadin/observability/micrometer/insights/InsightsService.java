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
import java.util.stream.Collectors;

/**
 * Turns raw error exemplars into insights. Groups failures by (route,
 * component, event, exception) so that N users clicking the same broken button
 * produce ONE insight with N occurrences, and renders the payload served at
 * {@code /actuator/vaadin/observability}.
 * <p>
 * The output is a stable, AI-agent-readable contract: an agent with access to
 * the application codebase can open {@code evidence.applicationFrame}, verify
 * the bug and propose a fix; a human can follow {@code replay} to reproduce.
 */
public class InsightsService {

    private static final int EXEMPLARS_PER_INSIGHT = 3;

    private final ErrorExemplarBuffer buffer;

    public InsightsService(ErrorExemplarBuffer buffer) {
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
        Map<String, List<ErrorExemplar>> groups = buffer.snapshot().stream()
                .collect(Collectors.groupingBy(
                        e -> String.join("|", nullSafe(e.route()),
                                nullSafe(e.component()), nullSafe(e.event()),
                                nullSafe(e.exceptionType())),
                        LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> insights = new ArrayList<>();
        groups.values().forEach(group -> insights.add(insight(group)));
        return insights;
    }

    private Map<String, Object> insight(List<ErrorExemplar> group) {
        // Snapshot is newest-first, so the head is the latest occurrence.
        ErrorExemplar latest = group.get(0);
        Instant firstSeen = group.stream().map(ErrorExemplar::timestamp)
                .min(Comparator.naturalOrder()).orElseThrow();

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

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("route", latest.route());
        evidence.put("component", latest.component());
        evidence.put("event", latest.event());
        evidence.put("rpcType", latest.rpcType());
        evidence.put("exception", latest.exceptionType());
        evidence.put("message", latest.exceptionMessage());
        evidence.put("applicationFrame", latest.applicationFrame());
        evidence.put("occurrences", group.size());
        evidence.put("firstSeen", firstSeen.toString());
        evidence.put("lastSeen", latest.timestamp().toString());
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

        insight.put("exemplars", group.stream().limit(EXEMPLARS_PER_INSIGHT)
                .map(InsightsService::exemplarJson).toList());
        return insight;
    }

    private static Map<String, Object> exemplarJson(ErrorExemplar exemplar) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("timestamp", exemplar.timestamp().toString());
        json.put("sessionId", exemplar.sessionId());
        json.put("uiId", exemplar.uiId());
        json.put("stackTop", exemplar.stackTop());
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
