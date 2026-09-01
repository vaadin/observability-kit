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
 * Applies the metric-name allowlist, the per-meter tag-key set with its bounded
 * values, route-template resolution against the current session's
 * {@link RouteConfiguration}, and cardinality capping via
 * {@link RouteTagResolver}.
 */
public final class ClientMetricsBinder {

    private static final int MAX_ROUTE_LEN = 200;
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
            if (sample == null
                    || !ClientMetricNames.isAllowed(sample.getName())) {
                continue;
            }
            try {
                record(sample.getName(), sample);
            } catch (RuntimeException e) {
                // The registry refused the sample, and it must not take the
                // request that carried it down with it: this runs inside a
                // @ClientCallable on the user's own request, so an exception
                // escaping here would be counted into vaadin.errors and flip
                // that request to outcome=error -- a browser able to inflate
                // the server's error rate by sending a payload the registry
                // dislikes. Count it where samples that never made it into a
                // meter are counted instead.
                recordDropped(1);
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

    private void record(String name, ClientSample sample) {
        String[] tags = buildTags(name, sample);
        if (ClientMetricNames.isCounter(name)) {
            registry.counter(name, tags).increment();
        } else {
            recordDuration(name, tags, sample);
        }
    }

    private void recordDuration(String name, String[] tags,
            ClientSample sample) {
        long nanos = (long) (sample.getValueMs() * 1_000_000.0);
        if (nanos < 0) {
            return;
        }
        registry.timer(name, tags).record(Duration.ofNanos(nanos));
    }

    /**
     * Builds the meter's tag set: the keys it declares, all of them and in
     * order, each with the value the payload reported for it as far as the
     * bounded set for that key admits. The browser chooses which of the
     * declared values a sample lands on, and nothing beyond that -- neither the
     * keys the meter carries nor how many series they can grow to.
     */
    private String[] buildTags(String name, ClientSample sample) {
        List<String> keys = ClientMetricNames.tagKeys(name);
        if (keys.isEmpty()) {
            return EMPTY;
        }
        Map<String, String> raw = sample.getTags();
        String[] tags = new String[keys.size() * 2];
        int at = 0;
        for (String key : keys) {
            tags[at++] = key;
            tags[at++] = tagValue(name, key, raw == null ? null : raw.get(key));
        }
        return tags;
    }

    private String tagValue(String name, String key, String reported) {
        return switch (key) {
        case MeterNames.TAG_ROUTE -> templateRoute(reported);
        case MeterNames.TAG_TRIGGER ->
            ClientMetricNames.navigationTrigger(reported);
        case MeterNames.TAG_KIND -> ClientMetricNames.errorKind(reported);
        case MeterNames.TAG_STATE ->
            MeterNames.CLIENT_CONNECTION_DOWNTIME.equals(name)
                    ? ClientMetricNames.downtimeState(reported)
                    : ClientMetricNames.connectionState(reported);
        // Every key a meter declares has a rule above; a key added there
        // without one is bucketed rather than let through to the registry.
        default -> MeterNames.ROUTE_UNKNOWN;
        };
    }

    private String templateRoute(String rawPath) {
        if (rawPath == null || rawPath.length() > MAX_ROUTE_LEN) {
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
