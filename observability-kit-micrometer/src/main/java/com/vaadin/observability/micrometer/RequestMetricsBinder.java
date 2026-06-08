/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.function.BiConsumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinRequestInterceptor;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.observability.micrometer.trace.ObservationNames;

/**
 * Measures request duration and counts errors.
 * <p>
 * Two modes:
 * <ul>
 * <li>If {@code settings.isTraces()} and an {@link ObservationRegistry} is
 * supplied, requests are driven through the Observation API. The Observation
 * name matches the Timer name ({@link MeterNames#REQUEST_DURATION}) so a
 * {@code DefaultMeterObservationHandler} produces the same Timer that the
 * direct-recording path would. The Observation's {@code contextualName} carries
 * the span-friendly name ({@code vaadin.request}) used by tracing
 * handlers.</li>
 * <li>Otherwise (no obs registry / traces disabled / observation handler
 * unavailable), the binder falls back to recording the Timer directly.</li>
 * </ul>
 */
final class RequestMetricsBinder implements VaadinRequestInterceptor {

    private static final BiConsumer<VaadinRequest, String> NO_ENRICH = (r,
            t) -> {
    };

    private final MeterRegistry registry;
    private final ObservationRegistry observationRegistry;
    private final ObservabilitySettings settings;
    private final BiConsumer<VaadinRequest, String> httpObservationEnricher;
    private final ThreadLocal<Timer.Sample> sample = new ThreadLocal<>();
    private final ThreadLocal<Boolean> errored = ThreadLocal
            .withInitial(() -> Boolean.FALSE);
    private final ThreadLocal<Observation> observation = new ThreadLocal<>();
    private final ThreadLocal<Observation.Scope> observationScope = new ThreadLocal<>();

    RequestMetricsBinder(MeterRegistry registry,
            ObservabilitySettings settings) {
        this(registry, null, settings, NO_ENRICH);
    }

    RequestMetricsBinder(MeterRegistry registry,
            ObservationRegistry observationRegistry,
            ObservabilitySettings settings) {
        this(registry, observationRegistry, settings, NO_ENRICH);
    }

    RequestMetricsBinder(MeterRegistry registry,
            ObservationRegistry observationRegistry,
            ObservabilitySettings settings,
            BiConsumer<VaadinRequest, String> httpObservationEnricher) {
        this.registry = registry;
        this.observationRegistry = observationRegistry;
        this.settings = settings;
        this.httpObservationEnricher = httpObservationEnricher != null
                ? httpObservationEnricher
                : NO_ENRICH;
    }

    private boolean useObservation() {
        return settings.isTraces() && observationRegistry != null;
    }

    @Override
    public void requestStart(VaadinRequest request, VaadinResponse response) {
        // Drop any stale thread-local state left by a previous request whose
        // requestEnd was skipped (e.g. mid-request server shutdown). Without
        // this a pooled thread could carry errored=TRUE into the next request
        // and misreport it as an error.
        errored.remove();
        sample.remove();
        observation.remove();
        observationScope.remove();
        // Drop any interaction marker left by a previous request on this
        // pooled thread; poll/navigation listeners re-mark during handling.
        RequestInteraction.clear();
        if (useObservation()) {
            String type = requestType(request);
            // Let DI integrations (Spring/Boot) lift Vaadin type into the
            // parent HTTP span so the trace UI shows the request type
            // without drilling down. Defaults to no-op for standalone.
            httpObservationEnricher.accept(request, type);
            Observation obs = Observation
                    .createNotStarted(MeterNames.REQUEST_DURATION,
                            observationRegistry)
                    .contextualName(ObservationNames.REQUEST + "." + type)
                    .lowCardinalityKeyValue(ObservationNames.KEY_REQUEST_TYPE,
                            type)
                    .lowCardinalityKeyValue(ObservationNames.KEY_HTTP_METHOD,
                            httpMethod(request))
                    .lowCardinalityKeyValue(ObservationNames.KEY_UI_ID,
                            uiId(request))
                    .lowCardinalityKeyValue(
                            ObservationNames.KEY_CLIENT_LOCATION,
                            clientLocation(request))
                    .start();
            observation.set(obs);
            observationScope.set(obs.openScope());
        } else if (settings.isRequests()) {
            sample.set(Timer.start(registry));
        }
    }

    private static String httpMethod(VaadinRequest request) {
        if (request == null) {
            return "unknown";
        }
        String m = request.getMethod();
        return m == null ? "unknown" : m;
    }

    private static String uiId(VaadinRequest request) {
        if (request == null) {
            return ObservationNames.UI_ID_UNKNOWN;
        }
        String id = request.getParameter("v-uiId");
        return id != null ? id : ObservationNames.UI_ID_UNKNOWN;
    }

    /**
     * Extracts the page path the UIDL request was sent from. Falls back to the
     * Referer header path so we always emit something useful for dashboards
     * filtering by view, without ever exposing PII (the path goes through the
     * parent navigation observation's route template mapping in dashboards; we
     * deliberately keep it un-templated here so the span captures the literal
     * client path).
     */
    private static String clientLocation(VaadinRequest request) {
        if (request == null) {
            return ObservationNames.LOCATION_UNKNOWN;
        }
        String referer = request.getHeader("Referer");
        if (referer == null || referer.isEmpty()) {
            return ObservationNames.LOCATION_UNKNOWN;
        }
        // Strip scheme+host: keep just the path (and optional query) so
        // tag cardinality stays modest and we never emit hostnames.
        int schemeEnd = referer.indexOf("://");
        if (schemeEnd < 0) {
            return referer;
        }
        int pathStart = referer.indexOf('/', schemeEnd + 3);
        if (pathStart < 0) {
            return "/";
        }
        int queryStart = referer.indexOf('?', pathStart);
        int fragmentStart = referer.indexOf('#', pathStart);
        // pathEnd is the earliest of '?' and '#' (each only if present at or
        // after pathStart), so hash-router URLs don't inflate tag cardinality.
        int pathEnd = -1;
        if (queryStart >= 0 && fragmentStart >= 0) {
            pathEnd = Math.min(queryStart, fragmentStart);
        } else if (queryStart >= 0) {
            pathEnd = queryStart;
        } else if (fragmentStart >= 0) {
            pathEnd = fragmentStart;
        }
        if (pathEnd < 0) {
            return referer.substring(pathStart);
        }
        return referer.substring(pathStart, pathEnd);
    }

    @Override
    public void handleException(VaadinRequest request, VaadinResponse response,
            VaadinSession vaadinSession, Exception t) {
        errored.set(Boolean.TRUE);
        if (settings.isErrors() && t != null) {
            Counter.builder(MeterNames.ERRORS)
                    .tag(MeterNames.TAG_EXCEPTION, t.getClass().getSimpleName())
                    .register(registry).increment();
        }
        Observation obs = observation.get();
        if (obs != null && t != null) {
            obs.error(t);
        }
    }

    @Override
    public void requestEnd(VaadinRequest request, VaadinResponse response,
            VaadinSession session) {
        boolean wasError = errored.get();
        errored.remove();
        String outcome = wasError ? MeterNames.OUTCOME_ERROR
                : MeterNames.OUTCOME_SUCCESS;
        Timer.Sample s = sample.get();
        sample.remove();
        if (s != null) {
            s.stop(registry.timer(MeterNames.REQUEST_DURATION,
                    MeterNames.TAG_OUTCOME, outcome));
        }
        Observation.Scope scope = observationScope.get();
        observationScope.remove();
        if (scope != null) {
            scope.close();
        }
        Observation obs = observation.get();
        observation.remove();
        // Consume whatever a poll/navigation listener recorded for this
        // request so the span name reflects what actually happened instead
        // of the opaque protocol-level "uidl".
        String interaction = RequestInteraction.take();
        if (obs != null) {
            String type = requestType(request);
            if (ObservationNames.REQUEST_TYPE_UIDL.equals(type)) {
                String kind = interaction != null ? interaction
                        : ObservationNames.INTERACTION_RPC;
                obs.lowCardinalityKeyValue(ObservationNames.KEY_INTERACTION,
                        kind);
                obs.contextualName(ObservationNames.REQUEST + "." + kind);
            }
            obs.lowCardinalityKeyValue(ObservationNames.KEY_OUTCOME, outcome);
            obs.stop();
        }
    }

    private static String requestType(VaadinRequest request) {
        if (request == null) {
            return ObservationNames.REQUEST_TYPE_OTHER;
        }
        String path = request.getPathInfo();
        if (path != null) {
            if (path.contains("/PUSH/")) {
                return ObservationNames.REQUEST_TYPE_PUSH;
            }
            if (path.contains("/HEARTBEAT/")) {
                return ObservationNames.REQUEST_TYPE_HEARTBEAT;
            }
        }
        String vr = request.getParameter("v-r");
        if ("uidl".equals(vr)) {
            return ObservationNames.REQUEST_TYPE_UIDL;
        }
        if ("heartbeat".equals(vr)) {
            return ObservationNames.REQUEST_TYPE_HEARTBEAT;
        }
        if (path != null && (path.startsWith("/VAADIN/")
                || path.startsWith("/static/"))) {
            return ObservationNames.REQUEST_TYPE_STATIC;
        }
        return ObservationNames.REQUEST_TYPE_OTHER;
    }
}
