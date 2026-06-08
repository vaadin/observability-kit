/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.micrometer.core.instrument.MeterRegistry;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.router.RouteConfiguration;
import com.vaadin.observability.micrometer.MeterNames;
import com.vaadin.observability.micrometer.ObservabilitySettings;
import com.vaadin.observability.micrometer.RouteTagResolver;

/**
 * Validates and records samples emitted by the in-browser collector.
 * <p>
 * Applies the metric-name allowlist, route-template resolution against the
 * current session's {@link RouteConfiguration}, cardinality capping via
 * {@link RouteTagResolver}, and bounded-length tag validation.
 */
public final class ClientMetricsBinder {

    private static final int MAX_TAG_KEY_LEN = 64;
    private static final int MAX_TAG_VALUE_LEN = 200;
    private static final String[] EMPTY = new String[0];

    private final MeterRegistry registry;
    private final RouteTagResolver routes;

    public ClientMetricsBinder(MeterRegistry registry,
            ObservabilitySettings settings) {
        this.registry = registry;
        this.routes = new RouteTagResolver(settings.getRouteCardinalityLimit());
    }

    public void ingest(List<ClientSample> samples) {
        if (samples == null) {
            return;
        }
        for (ClientSample sample : samples) {
            String name = sample.getName();
            if (!ClientMetricNames.isAllowed(name)) {
                continue;
            }
            String[] tags = buildTags(sample);
            if (ClientMetricNames.isCounter(name)) {
                registry.counter(name, tags).increment();
            } else {
                long nanos = (long) (sample.getValueMs() * 1_000_000.0);
                if (nanos < 0) {
                    continue;
                }
                registry.timer(name, tags).record(Duration.ofNanos(nanos));
            }
        }
    }

    public void recordDropped(int count) {
        if (count > 0) {
            registry.counter(MeterNames.CLIENT_DROPPED).increment(count);
        }
    }

    public void recordThrottled(int count) {
        if (count > 0) {
            registry.counter(MeterNames.CLIENT_THROTTLED).increment(count);
        }
    }

    private String[] buildTags(ClientSample sample) {
        Map<String, String> raw = sample.getTags();
        if (raw == null || raw.isEmpty()) {
            return EMPTY;
        }
        List<String> out = new ArrayList<>(raw.size() * 2);
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null || key.isEmpty()) {
                continue;
            }
            if (key.length() > MAX_TAG_KEY_LEN) {
                continue;
            }
            if (MeterNames.TAG_ROUTE.equals(key)) {
                value = templateRoute(value);
            }
            if (value.length() > MAX_TAG_VALUE_LEN) {
                value = MeterNames.ROUTE_OTHER;
            }
            out.add(key);
            out.add(value);
        }
        return out.toArray(new String[0]);
    }

    private String templateRoute(String rawPath) {
        if (rawPath == null) {
            return MeterNames.ROUTE_UNKNOWN;
        }
        String path = rawPath;
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        try {
            RouteConfiguration rc = RouteConfiguration.forSessionScope();
            Optional<Class<? extends Component>> target = rc.getRoute(path);
            if (target.isPresent()) {
                Optional<String> template = rc.getTemplate(target.get());
                if (template.isPresent()) {
                    return routes.tagForTemplate(template.get());
                }
                return routes.tagFor(target.get());
            }
        } catch (RuntimeException ignored) {
            // no session in scope or registry not initialized
        }
        return MeterNames.ROUTE_UNKNOWN;
    }
}
