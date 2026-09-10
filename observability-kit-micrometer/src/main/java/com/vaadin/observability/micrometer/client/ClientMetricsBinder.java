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
import org.jspecify.annotations.Nullable;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.RouteConfiguration;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.observability.micrometer.MeterNames;
import com.vaadin.observability.micrometer.ObservabilitySettings;
import com.vaadin.observability.micrometer.RouteTagResolver;
import com.vaadin.observability.micrometer.insights.ClientErrorCollector;

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

    /**
     * Longest duration a browser sample may report. A page load, a navigation
     * or a web vital measured in hours is not a measurement; what it is, is a
     * value chosen to move a timer's sum, which no later sample can undo.
     */
    private static final double MAX_DURATION_MS = 3_600_000.0;

    private static final String[] EMPTY = new String[0];

    private final MeterRegistry registry;
    private final RouteTagResolver routes;
    private final @Nullable ClientErrorCollector clientErrors;

    public ClientMetricsBinder(MeterRegistry registry,
            ObservabilitySettings settings) {
        this(registry, settings, null);
    }

    /**
     * @param registry
     *            the registry client samples are recorded into
     * @param settings
     *            instrumentation settings
     * @param clientErrors
     *            retains the detail of a reported browser error as an insight,
     *            or {@code null} to record only the count
     */
    public ClientMetricsBinder(MeterRegistry registry,
            ObservabilitySettings settings,
            @Nullable ClientErrorCollector clientErrors) {
        this.registry = registry;
        this.routes = new RouteTagResolver(settings.getRouteCardinalityLimit());
        this.clientErrors = clientErrors;
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
            if (MeterNames.CLIENT_ERRORS.equals(name)) {
                captureErrorDetail(sample);
            }
        } else {
            recordDuration(name, tags, sample);
        }
    }

    private void recordDuration(String name, String[] tags,
            ClientSample sample) {
        double valueMs = sample.getValueMs();
        // The browser clamps this, but the browser is not the enforcement
        // point: any script on the page can call the @ClientCallable directly,
        // and the rate limiter bounds how many samples arrive, not what is in
        // them. A cast is no filter either -- NaN becomes 0, recording a
        // phantom 0 ms sample, and 1e300 saturates to Long.MAX_VALUE, which
        // adds some 292 years to the timer's sum. That sum is monotonic, so
        // rate(sum)/rate(count) stays wrecked for the life of the process and
        // a second such sample wraps the adder negative. One hour is past
        // anything a browser timing means.
        if (!Double.isFinite(valueMs) || valueMs < 0
                || valueMs > MAX_DURATION_MS) {
            // Counted where every other sample that never reached a meter is,
            // rather than discarded in silence.
            recordDropped(1);
            return;
        }
        long nanos = (long) (valueMs * 1_000_000.0);
        registry.timer(name, tags).record(Duration.ofNanos(nanos));
    }

    /**
     * Hands the description of a browser error to the insight buffer. The
     * counter has already been incremented; this is the part of the report a
     * tag cannot hold.
     */
    private void captureErrorDetail(ClientSample sample) {
        if (clientErrors == null) {
            return;
        }
        Map<String, String> detail = sample.getDetail();
        if (detail.isEmpty()) {
            return;
        }
        Map<String, String> raw = sample.getTags();
        // The reported kind, not the tag value: the collector applies the same
        // bounded set, so the insight and the counter agree either way, and
        // the binder does not have to know which of the two owns the rule.
        clientErrors.capture(raw == null ? null : raw.get(MeterNames.TAG_KIND),
                templateRoute(detail.get(ClientErrorCollector.DETAIL_ROUTE)),
                detail, sample.getAgeMs(), UI.getCurrent());
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
        try {
            // Inside the guard, because it reads the current request through
            // getters that can throw: a recycled Tomcat RequestFacade answers
            // getContextPath with an IllegalStateException, and a wrapper is
            // free to do the same. On the error path this runs after the
            // counter was incremented, so a throw escaping to ingest() would
            // have it count the very same sample as dropped -- the double
            // count ClientErrorCollector says must not happen. The existing
            // _unknown fallback covers it instead.
            String path = appRelative(rawPath);
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

    /**
     * Turns a browser-reported {@code window.location.pathname} into a path the
     * route registry can resolve, by taking off the prefix the application is
     * served under. That prefix is in every pathname the browser reports and in
     * no route, so an application deployed anywhere but the root of the host
     * would otherwise resolve nothing and tag every client sample
     * {@code _unknown}.
     */
    private static String appRelative(String pathname) {
        String path = pathname;
        VaadinRequest request = VaadinRequest.getCurrent();
        if (request != null) {
            path = stripPrefix(path, request.getContextPath());
            // Empty for the usual /* mapping, "/ui" for a servlet mapped at
            // /ui/* -- also part of the browser's path and of no route. Only
            // for a prefix mapping though, which is what a non-null pathInfo
            // identifies: under the default "/" mapping the servlet path is
            // the whole request path, and taking it off would leave nothing.
            if (request instanceof VaadinServletRequest servletRequest
                    && servletRequest.getPathInfo() != null) {
                path = stripPrefix(path, servletRequest.getServletPath());
            }
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    /**
     * Removes {@code prefix} from {@code path} when it is one: the whole of it,
     * or all of it up to a path separator. A prefix that only happens to match
     * the start of a longer segment is left alone — {@code /uc} must not turn
     * {@code /uc5} into {@code 5}.
     */
    static String stripPrefix(String path, String prefix) {
        if (prefix == null || prefix.isEmpty() || "/".equals(prefix)
                || !path.startsWith(prefix)) {
            return path;
        }
        String rest = path.substring(prefix.length());
        return rest.isEmpty() || rest.startsWith("/") ? rest : path;
    }
}
