/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import com.vaadin.flow.component.UI;
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
 * <p>
 * Both paths publish {@link MeterNames#REQUEST_DURATION} with the same tag
 * keys, all bounded: {@code vaadin.request.type}, {@code vaadin.interaction},
 * {@code http.method}, {@code outcome} and {@code error}. Keeping the two in
 * step matters because a metrics backend such as Prometheus rejects same-named
 * meters whose tag-key sets differ, and dashboards must not have to know which
 * path recorded a sample. The {@code error} tag is the one
 * {@code DefaultMeterObservationHandler} adds by itself on the Observation
 * path, so the direct-recording path adds it explicitly.
 * <p>
 * The UI id and the client location are attached as high-cardinality
 * key-values, so they enrich the span without multiplying the Timer's time
 * series: a UI id is unbounded over an application's lifetime, and the client
 * location is deliberately kept un-templated.
 * <p>
 * This interceptor only ever sees exceptions that <em>escape</em> request
 * handling. The failures a user triggers are caught by Flow and routed to the
 * session error handler, where {@link ErrorMetricsBinder} counts them and
 * relays them back here through {@link RequestError} so the request outcome
 * reflects them.
 */
final class RequestMetricsBinder implements VaadinRequestInterceptor {

    private final MeterRegistry registry;
    private final ObservationRegistry observationRegistry;
    private final ObservabilitySettings settings;
    private final ErrorCounter errors;
    /**
     * How many distinct route templates may reach the framework's {@code uri}
     * tag before the rest collapse into {@code _other}. Deliberately well under
     * Spring Boot's {@code management.metrics.web.server.max-uri-tags} default
     * of 100: Boot's {@code MaximumAllowableTagsMeterFilter} DENIES a meter
     * outright once the distinct {@code uri} count crosses its cap — the series
     * is never created, not bucketed — and admission is first-come-first-served
     * across every endpoint of the application, so blowing the budget would
     * silently delete arbitrary {@code http.server.requests} series, Vaadin or
     * not. Half of Boot's default leaves the other half for actuator endpoints
     * and REST controllers.
     */
    static final int HTTP_URI_ROUTE_LIMIT = 50;

    private final HttpObservationHooks hooks;
    private final RouteTagResolver routes;
    private final ThreadLocal<Timer.Sample> sample = new ThreadLocal<>();
    private final ThreadLocal<Boolean> errored = ThreadLocal
            .withInitial(() -> Boolean.FALSE);
    // Simple class name of the exception passed to handleException, mirroring
    // what DefaultMeterObservationHandler reads off the Observation context so
    // the direct-recording path can tag its Timer the same way.
    private final ThreadLocal<String> errorType = new ThreadLocal<>();
    private final ThreadLocal<Observation> observation = new ThreadLocal<>();
    private final ThreadLocal<Observation.Scope> observationScope = new ThreadLocal<>();

    RequestMetricsBinder(MeterRegistry registry,
            ObservabilitySettings settings) {
        this(registry, null, settings, HttpObservationHooks.NONE);
    }

    RequestMetricsBinder(MeterRegistry registry,
            ObservationRegistry observationRegistry,
            ObservabilitySettings settings) {
        this(registry, observationRegistry, settings,
                HttpObservationHooks.NONE);
    }

    RequestMetricsBinder(MeterRegistry registry,
            ObservationRegistry observationRegistry,
            ObservabilitySettings settings, HttpObservationHooks hooks) {
        this(registry, observationRegistry, settings, hooks,
                settings.isErrors() ? new ErrorCounter(registry, settings)
                        : null);
    }

    /**
     * @param hooks
     *            callbacks into the framework-level HTTP observation, or
     *            {@code null} for none (standalone deployments)
     * @param errors
     *            the counter shared with {@link ErrorMetricsBinder}, or
     *            {@code null} when error metrics are off — this interceptor is
     *            also installed for request metrics alone, and then there is
     *            nothing to count
     */
    RequestMetricsBinder(MeterRegistry registry,
            ObservationRegistry observationRegistry,
            ObservabilitySettings settings, HttpObservationHooks hooks,
            ErrorCounter errors) {
        this.registry = registry;
        this.observationRegistry = observationRegistry;
        this.settings = settings;
        this.errors = errors;
        this.hooks = hooks != null ? hooks : HttpObservationHooks.NONE;
        this.routes = new RouteTagResolver(Math.min(HTTP_URI_ROUTE_LIMIT,
                settings.getRouteCardinalityLimit()));
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
        errorType.remove();
        sample.remove();
        observation.remove();
        // Close (not just drop) a leaked scope so the stale observation stops
        // being the registry's current one and this request's span is not
        // parented onto it. Only closed while it is still current, so an
        // enclosing live scope (the Spring/Boot HTTP observation) survives.
        ObservationScopes.closeStale(observationRegistry, observationScope);
        // Drop any interaction marker left by a previous request on this
        // pooled thread; poll/navigation listeners re-mark during handling.
        RequestInteraction.clear();
        // Same for a failure relayed by the session error handler.
        RequestError.clear();
        // And for the UI reference the binders mark during handling.
        RequestUi.clear();
        // Let DI integrations (Spring/Boot) lift the Vaadin type into the
        // framework HTTP observation. Not gated on any kit setting: the hook
        // enriches an observation the framework emits anyway (its uri tag on
        // http.server.requests is a metric, not a span), and it defaults to a
        // no-op for standalone deployments.
        hooks.requestType(request, requestType(request));
        if (useObservation()) {
            String type = requestType(request);
            Observation obs = Observation
                    .createNotStarted(MeterNames.REQUEST_DURATION,
                            observationRegistry)
                    .contextualName(ObservationNames.REQUEST + "." + type)
                    .lowCardinalityKeyValue(ObservationNames.KEY_REQUEST_TYPE,
                            type)
                    .lowCardinalityKeyValue(ObservationNames.KEY_HTTP_METHOD,
                            httpMethod(request))
                    // Span-only: the UI id is unbounded over an application's
                    // lifetime and the client location is un-templated, so
                    // neither may become a Timer tag.
                    .highCardinalityKeyValue(ObservationNames.KEY_UI_ID,
                            uiId(request))
                    .highCardinalityKeyValue(
                            ObservationNames.KEY_CLIENT_LOCATION,
                            clientLocation(request))
                    // Always emit the interaction key so every
                    // vaadin.request.duration Timer shares one tag-key set
                    // (Prometheus rejects same-named meters with differing
                    // keys). UIDL requests override this in requestEnd once a
                    // poll/navigation listener has resolved the real kind.
                    .lowCardinalityKeyValue(ObservationNames.KEY_INTERACTION,
                            ObservationNames.INTERACTION_NONE)
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
     * Referer header path so we always emit something useful when reading a
     * trace, without ever exposing PII. The path is deliberately kept
     * un-templated so the span captures the literal client path; that is also
     * why it is attached as a high-cardinality key-value and never as a Timer
     * tag. For a templated, cardinality-capped view attribution use the
     * {@code route} tag of the navigation meters instead.
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
            VaadinSession vaadinSession, Exception exception) {
        errored.set(Boolean.TRUE);
        if (exception == null) {
            return;
        }
        errorType.set(exception.getClass().getSimpleName());
        if (errors != null) {
            // Flow reports the same throwable to the session error handler
            // right after this call; mark it so ErrorMetricsBinder does not
            // count the one failure a second time.
            errors.increment(exception, null);
            RequestError.markCounted(exception);
        }
        Observation obs = observation.get();
        if (obs != null) {
            obs.error(exception);
        }
        // Also mark the framework-level HTTP observation (e.g. Spring's
        // ServerHttpObservationFilter span). For a UIDL request Vaadin
        // swallows the exception and responds 200, so the framework would
        // otherwise record it as successful — and several monitoring
        // solutions (New Relic, DataDog) only watch root or server spans for
        // errors. For other request types Vaadin rethrows as ServiceException
        // and the framework records that itself; there this marker merely
        // front-runs it with the root cause. No-op for standalone
        // deployments, and deliberately not gated on the traces or errors
        // settings: this corrects the status of an observation the framework
        // emits anyway, rather than emitting new telemetry.
        hooks.error(request, exception);
    }

    @Override
    public void requestEnd(VaadinRequest request, VaadinResponse response,
            VaadinSession session) {
        boolean interceptorError = errored.get();
        errored.remove();
        String error = errorType.get();
        errorType.remove();
        // An exception Flow routed to the session error handler (a failing
        // component listener, UI.access body or navigation callback) never
        // reaches handleException, yet the interaction the request carried did
        // fail; without this the span would claim outcome=success.
        Throwable handledError = RequestError.takeHandled();
        boolean wasError = interceptorError || handledError != null;
        if (error == null && handledError != null) {
            // Parity with the Observation path, where the obs.error() below
            // makes DefaultMeterObservationHandler add the error tag for us.
            error = handledError.getClass().getSimpleName();
        }
        String outcome = wasError ? MeterNames.OUTCOME_ERROR
                : MeterNames.OUTCOME_SUCCESS;
        Observation.Scope scope = observationScope.get();
        observationScope.remove();
        // Unwind anything nested instrumentation leaked on top of our scope
        // before closing it, so the thread is left exactly as it was found.
        // Cleaning up only at the next requestStart would leave a dead
        // observation current for whatever runs on this pooled thread in
        // between, including ContextSnapshot.captureAll() in TracingExecutor.
        ObservationScopes.closeWithNested(observationRegistry, scope);
        Observation obs = observation.get();
        observation.remove();
        // Consume whatever a poll/navigation listener recorded for this
        // request so the span name reflects what actually happened instead
        // of the opaque protocol-level "uidl".
        String interaction = RequestInteraction.take();
        String type = requestType(request);
        // The UI the handlers marked while processing this request. Consumed
        // unconditionally so a pooled thread never carries it over.
        UI ui = RequestUi.take();
        if (ObservationNames.REQUEST_TYPE_UIDL.equals(type) && ui != null) {
            // Lift the active view's route template into the framework HTTP
            // observation, so its uri tag and span name read /orders/:id
            // instead of the protocol-level /vaadin/uidl. Resolved here, at
            // request end, after any navigation during handling has settled.
            // UI.getCurrent() is no longer bound here (the UIDL handler
            // clears it with the session lock), so the UI comes from the
            // RequestUi relay the binders fill during handling. Not gated
            // on traces: the uri tag this feeds is a metric. Template-only
            // resolution: the concrete-location fallback would feed literal
            // paths (orders/17, orders/18, ...) into a bounded budget.
            String route = routes.templateForActiveRoute(ui);
            if (!MeterNames.ROUTE_UNKNOWN.equals(route)) {
                // A blank template is the root route: for a UIDL request a
                // view is always active, so blank cannot mean "no view".
                hooks.route(request, route);
            }
        }
        // Resolve the interaction once, for whichever path records: a UIDL
        // request takes the listener's marker (defaulting to the generic
        // "rpc"), anything else has no interaction to report.
        String kind = ObservationNames.REQUEST_TYPE_UIDL.equals(type)
                ? (interaction != null ? interaction
                        : ObservationNames.INTERACTION_RPC)
                : ObservationNames.INTERACTION_NONE;
        Timer.Sample s = sample.get();
        sample.remove();
        if (s != null) {
            // Tag with the very constants the Observation path uses above, so
            // the two paths cannot drift into publishing
            // vaadin.request.duration under differing tag-key sets. The error
            // tag replicates the one DefaultMeterObservationHandler adds for
            // us there.
            s.stop(Timer.builder(MeterNames.REQUEST_DURATION)
                    .tag(ObservationNames.KEY_REQUEST_TYPE, type)
                    .tag(ObservationNames.KEY_HTTP_METHOD, httpMethod(request))
                    .tag(ObservationNames.KEY_INTERACTION, kind)
                    .tag(ObservationNames.KEY_OUTCOME, outcome)
                    .tag(MeterNames.TAG_ERROR,
                            error != null ? error : MeterNames.ERROR_NONE)
                    .register(registry));
        }
        if (obs != null) {
            if (handledError != null && !interceptorError) {
                obs.error(handledError);
            }
            if (ObservationNames.REQUEST_TYPE_UIDL.equals(type)) {
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
        // /themes/ and /sw.js are what 4.1's agent treated as static assets
        // besides the Vaadin resource folder; without them theme resources and
        // the service worker land in the "other" bucket next to page loads.
        if (path != null && (path.startsWith("/VAADIN/")
                || path.startsWith("/static/") || path.startsWith("/themes/")
                || path.startsWith("/sw.js"))) {
            return ObservationNames.REQUEST_TYPE_STATIC;
        }
        return ObservationNames.REQUEST_TYPE_OTHER;
    }
}
