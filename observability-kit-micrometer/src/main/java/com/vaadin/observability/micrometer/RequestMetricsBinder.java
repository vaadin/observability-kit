/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.Locale;
import java.util.Set;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.BootstrapHandler;
import com.vaadin.flow.server.RequestEndedEvent;
import com.vaadin.flow.server.RequestHandler;
import com.vaadin.flow.server.RequestStartedEvent;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinServiceEventBus;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WrappedSession;
import com.vaadin.flow.server.communication.HeartbeatHandler;
import com.vaadin.flow.server.communication.StreamRequestHandler;
import com.vaadin.flow.server.communication.UidlRequestHandler;
import com.vaadin.flow.shared.Registration;
import com.vaadin.observability.micrometer.trace.ObservationNames;

/**
 * Measures request duration and counts errors, from the
 * {@link RequestStartedEvent} and {@link RequestEndedEvent} Flow fires on the
 * {@link com.vaadin.flow.server.VaadinService#getEventBus() service event bus}.
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
 * unavailable), the binder falls back to recording the Timer directly, with the
 * duration Flow measured for the request.</li>
 * </ul>
 * <p>
 * Both modes are gated on the {@code requests} setting: with it off the binder
 * times nothing and emits no request observation, and only its error handling
 * and the framework HTTP-observation callbacks stay active.
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
 * location is deliberately kept un-templated. With
 * {@code settings.isTracesSessionId()} the HTTP session id is attached the same
 * way.
 * <p>
 * The ended event only reports the exception that made handling the request
 * fail. The failures a user triggers are caught by Flow and routed to the
 * session error handler, where {@link ErrorMetricsBinder} counts them and
 * relays them back here through {@link RequestError} so the request outcome
 * reflects them.
 */
final class RequestMetricsBinder {

    private static final Logger LOGGER = LoggerFactory
            .getLogger(RequestMetricsBinder.class);

    private final MeterRegistry registry;
    private final ObservationRegistry observationRegistry;
    private final ObservabilitySettings settings;
    private final ErrorCounter errors;
    private final ExceptionTags exceptionTags;
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

    /**
     * The path prefix Flow's stream request handler serves downloads and
     * uploads under. Taken from Flow rather than spelled out here, so a change
     * on that side cannot silently stop stream requests being recognised.
     */
    private static final String STREAM_PATH = StreamRequestHandler.DYN_RES_PREFIX;

    /**
     * {@code Sec-Fetch-Dest} values a browser sends for a request whose
     * response it will render as a document — a page load, in other words.
     * Everything else a browser fetches (scripts, styles, images, XHR and
     * {@code fetch}, the service worker) reports a destination outside this
     * set, so an application's own endpoints under the Vaadin servlet stay in
     * the {@code other} bucket instead of being counted as page loads.
     * <p>
     * The embedded destinations are in deliberately: a route opened in an
     * iframe is served the same {@code index.html} and gets a UI of its own, so
     * it is a page load in every sense the server can see — and the
     * browser-side {@code vaadin.client.bootstrap.duration} records it too.
     * Leaving them out would make the two disagree, and would hide the
     * embedded-application case entirely. What it costs is that a view
     * embedding another of its own routes reports a second {@code bootstrap};
     * that is one more UI being built, which is what the type measures.
     */
    private static final Set<String> PAGE_FETCH_DESTINATIONS = Set
            .of("document", "iframe", "frame", "embed", "object");

    /**
     * The HTTP methods that may reach the {@code http.method} tag as-is. The
     * method is client-controlled and servlet containers accept any token, so
     * passing it through would let a client mint a new Timer per request;
     * anything outside this set is tagged {@link #HTTP_METHOD_OTHER}.
     */
    private static final Set<String> KNOWN_HTTP_METHODS = Set.of("GET", "HEAD",
            "POST", "PUT", "DELETE", "OPTIONS", "PATCH", "TRACE");

    private static final String HTTP_METHOD_OTHER = "_other";

    private final HttpObservationHooks hooks;
    private final RouteTagResolver routes;
    /**
     * The type the request was classified as when it started, from its URL.
     * Kept for the end of the request, where the request handler replaces it
     * when it tells more. A push message is only recognisable at the start: it
     * is the one request reported without a response there.
     */
    private final ThreadLocal<String> startType = new ThreadLocal<>();
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
                new ExceptionTags(settings));
    }

    private RequestMetricsBinder(MeterRegistry registry,
            ObservationRegistry observationRegistry,
            ObservabilitySettings settings, HttpObservationHooks hooks,
            ExceptionTags exceptionTags) {
        this(registry, observationRegistry, settings, hooks,
                settings.isErrors()
                        ? new ErrorCounter(registry, settings, exceptionTags)
                        : null,
                exceptionTags);
    }

    RequestMetricsBinder(MeterRegistry registry,
            ObservationRegistry observationRegistry,
            ObservabilitySettings settings, HttpObservationHooks hooks,
            ErrorCounter errors) {
        this(registry, observationRegistry, settings, hooks, errors,
                new ExceptionTags(settings));
    }

    /**
     * @param hooks
     *            callbacks into the framework-level HTTP observation, or
     *            {@code null} for none (standalone deployments)
     * @param errors
     *            the counter shared with {@link ErrorMetricsBinder}, or
     *            {@code null} when error metrics are off — this binder is also
     *            installed for request metrics alone, and then there is nothing
     *            to count
     * @param exceptionTags
     *            the exception-type budget the {@code error} tag is drawn from,
     *            shared with {@code errors} and the RPC timer
     */
    RequestMetricsBinder(MeterRegistry registry,
            ObservationRegistry observationRegistry,
            ObservabilitySettings settings, HttpObservationHooks hooks,
            ErrorCounter errors, ExceptionTags exceptionTags) {
        this.registry = registry;
        this.observationRegistry = observationRegistry;
        this.settings = settings;
        this.errors = errors;
        this.exceptionTags = exceptionTags;
        this.hooks = hooks != null ? hooks : HttpObservationHooks.NONE;
        this.routes = new RouteTagResolver(Math.min(HTTP_URI_ROUTE_LIMIT,
                settings.getRouteCardinalityLimit()));
    }

    private boolean useObservation() {
        return settings.isTraces() && observationRegistry != null;
    }

    /**
     * Subscribes to the request events on the given bus.
     *
     * @param eventBus
     *            the service event bus to listen on
     * @return a handle removing every subscription made here
     */
    Registration register(VaadinServiceEventBus eventBus) {
        return Registration.combine(
                eventBus.addListener(RequestStartedEvent.class,
                        this::requestStarted),
                eventBus.addListener(RequestEndedEvent.class,
                        this::requestEnded));
    }

    void requestStarted(RequestStartedEvent event) {
        VaadinRequest request = event.getRequest();
        // Flow fires the ended event for every started one, on the same
        // thread, but drop any state a request cut short before it (e.g.
        // mid-request server shutdown) left on this pooled thread anyway.
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
        // A message sent over a push connection is reported as a request of
        // its own, and the only one without a response. Its URL is the one of
        // the push connection, which says nothing about the message.
        String type = event.getResponse().isPresent() ? requestType(request)
                : ObservationNames.REQUEST_TYPE_PUSH;
        startType.set(type);
        if (!useObservation() || !settings.isRequests()) {
            // The requests setting turns off the kit's own request timing,
            // which on the Observation path means the whole request
            // observation: the Timer is produced from it by the meter
            // observation handler, so span and Timer cannot be split.
            return;
        }
        Observation obs = Observation
                .createNotStarted(MeterNames.REQUEST_DURATION,
                        observationRegistry)
                // Provisional, like the type key below: requestEnded settles
                // both once the request handler is known.
                .contextualName(ObservationNames.REQUEST + "." + type)
                .lowCardinalityKeyValue(ObservationNames.KEY_REQUEST_TYPE, type)
                .lowCardinalityKeyValue(ObservationNames.KEY_HTTP_METHOD,
                        httpMethod(request))
                // Span-only: the UI id is unbounded over an application's
                // lifetime and the client location is un-templated, so
                // neither may become a Timer tag.
                .highCardinalityKeyValue(ObservationNames.KEY_UI_ID,
                        uiId(request))
                .highCardinalityKeyValue(ObservationNames.KEY_CLIENT_LOCATION,
                        clientLocation(request))
                // Always emit the interaction key so every
                // vaadin.request.duration Timer shares one tag-key set
                // (Prometheus rejects same-named meters with differing keys).
                // UIDL requests override this in requestEnded once a
                // poll/navigation listener has resolved the real kind.
                .lowCardinalityKeyValue(ObservationNames.KEY_INTERACTION,
                        ObservationNames.INTERACTION_NONE)
                .start();
        observation.set(obs);
        observationScope.set(obs.openScope());
    }

    private static String httpMethod(VaadinRequest request) {
        if (request == null) {
            return "unknown";
        }
        String m = request.getMethod();
        if (m == null) {
            return "unknown";
        }
        return KNOWN_HTTP_METHODS.contains(m) ? m : HTTP_METHOD_OTHER;
    }

    private static String uiId(VaadinRequest request) {
        if (request == null) {
            return ObservationNames.UI_ID_UNKNOWN;
        }
        String id = request.getParameter("v-uiId");
        return id != null ? id : ObservationNames.UI_ID_UNKNOWN;
    }

    /**
     * The id of the HTTP session the request belongs to, or {@code null} when
     * there is none. Prefers the Vaadin session Flow resolved for the request
     * and falls back to the request's own session without ever creating one: a
     * static-resource request must not start a session just to be traced.
     */
    private static String sessionId(VaadinRequest request,
            VaadinSession session) {
        try {
            WrappedSession wrapped = session != null ? session.getSession()
                    : null;
            if (wrapped == null && request != null) {
                wrapped = request.getWrappedSession(false);
            }
            return wrapped != null ? wrapped.getId() : null;
        } catch (IllegalStateException e) {
            // The session was invalidated during the request (a logout);
            // the container refuses to hand out its id any more.
            return null;
        }
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

    /**
     * Runs a call into the framework-level HTTP observation. The hooks are
     * overridable integration code, and this binder still has cleanup to do
     * after them — closing its scope and stopping its observation, which would
     * otherwise stay current on the pooled thread — so a failing hook is logged
     * and skipped rather than allowed to cut that short. Telemetry must never
     * break the request it observes either.
     */
    private static void callHook(Runnable hook) {
        try {
            hook.run();
        } catch (RuntimeException e) {
            LOGGER.debug("HTTP observation hook failed; continuing without it",
                    e);
        }
    }

    void requestEnded(RequestEndedEvent event) {
        VaadinRequest request = event.getRequest();
        String startedAs = startType.get();
        startType.remove();
        String type = event.getHandler().map(RequestMetricsBinder::handlerType)
                .orElse(startedAs != null ? startedAs : requestType(request));
        // The exception that made handling the request fail, or else the one
        // Flow routed to the session error handler (a failing component
        // listener, UI.access body or navigation callback). The latter does
        // not fail the request, yet the interaction the request carried did
        // fail; without it the span would claim outcome=success.
        Exception failure = event.getFailure().orElse(null);
        Throwable handled = RequestError.takeHandled();
        Throwable error = failure != null ? failure : handled;
        if (failure != null && failure != handled && errors != null) {
            // Flow hands a failure to the session error handler before it
            // reports it here, and ErrorMetricsBinder counts it there. One the
            // decorated handler never saw — there was no session to route it
            // to — is counted here instead.
            errors.increment(failure, null);
        }
        // Let DI integrations (Spring/Boot) lift the Vaadin type into the
        // framework HTTP observation, which reads it when it stops, after this
        // event. Not gated on any kit setting: the hook enriches an
        // observation the framework emits anyway (its uri tag on
        // http.server.requests is a metric, not a span), and it defaults to a
        // no-op for standalone deployments.
        callHook(() -> hooks.requestType(request, type));
        if (error != null) {
            // Also mark the framework-level HTTP observation (e.g. Spring's
            // ServerHttpObservationFilter span). For a UIDL request Vaadin
            // swallows the exception and responds 200, and a failure the
            // session error handler got does not fail the request at all, so
            // the framework would otherwise record it as successful — and
            // several monitoring solutions (New Relic, DataDog) only watch root
            // or server spans for errors. For other request types Vaadin
            // rethrows as ServiceException and the framework records that
            // itself; there this marker merely front-runs it with the root
            // cause. No-op for standalone deployments, and deliberately not
            // gated on the traces, requests or errors settings: this corrects
            // the status of an observation the framework emits anyway, rather
            // than emitting new telemetry.
            callHook(() -> hooks.error(request, error));
        }
        String outcome = error != null ? MeterNames.OUTCOME_ERROR
                : MeterNames.OUTCOME_SUCCESS;
        Observation.Scope scope = observationScope.get();
        observationScope.remove();
        // Unwind anything nested instrumentation leaked on top of our scope
        // before closing it, so the thread is left exactly as it was found.
        // Cleaning up only at the next requestStarted would leave a dead
        // observation current for whatever runs on this pooled thread in
        // between, including ContextSnapshot.captureAll() in TracingExecutor.
        ObservationScopes.closeWithNested(observationRegistry, scope);
        Observation obs = observation.get();
        observation.remove();
        // Consume whatever a poll/navigation listener recorded for this
        // request so the span name reflects what actually happened instead
        // of the opaque protocol-level "uidl".
        String interaction = RequestInteraction.take();
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
            callHook(() -> {
                String route = routes.templateForActiveRoute(ui);
                if (!MeterNames.ROUTE_UNKNOWN.equals(route)) {
                    // A blank template is the root route: for a UIDL request
                    // a view is always active, so blank cannot mean "no view".
                    hooks.route(request, route);
                }
            });
        }
        // Resolve the interaction once, for whichever path records: a UIDL
        // request takes the listener's marker (defaulting to the generic
        // "rpc"), anything else has no interaction to report.
        String kind = ObservationNames.REQUEST_TYPE_UIDL.equals(type)
                ? (interaction != null ? interaction
                        : ObservationNames.INTERACTION_RPC)
                : ObservationNames.INTERACTION_NONE;
        if (!useObservation() && settings.isRequests()) {
            // Tag with the very constants the Observation path uses below, so
            // the two paths cannot drift into publishing
            // vaadin.request.duration under differing tag-key sets. The error
            // tag replicates the one DefaultMeterObservationHandler adds there:
            // the exception's simple class name, but drawn from the shared
            // bounded set so a flood of generated exception types collapses
            // into _other. The Observation path's tag is written by that
            // handler and is not bounded.
            Timer.builder(MeterNames.REQUEST_DURATION)
                    .tag(ObservationNames.KEY_REQUEST_TYPE, type)
                    .tag(ObservationNames.KEY_HTTP_METHOD, httpMethod(request))
                    .tag(ObservationNames.KEY_INTERACTION, kind)
                    .tag(ObservationNames.KEY_OUTCOME, outcome)
                    .tag(MeterNames.TAG_ERROR,
                            error != null ? exceptionTags.tag(error)
                                    : MeterNames.ERROR_NONE)
                    .register(registry).record(event.getDuration());
        }
        if (obs != null) {
            if (error != null) {
                obs.error(error);
            }
            // The type the observation started with was the URL's guess; the
            // request handler has the final word.
            obs.lowCardinalityKeyValue(ObservationNames.KEY_REQUEST_TYPE, type);
            obs.lowCardinalityKeyValue(ObservationNames.KEY_INTERACTION, kind);
            obs.contextualName(ObservationNames.REQUEST + "."
                    + (ObservationNames.REQUEST_TYPE_UIDL.equals(type) ? kind
                            : type));
            if (settings.isTracesSessionId()) {
                // Resolved at request end so the page load that creates the
                // session is attributed too. Span-only, like the UI id: a
                // session id is unbounded and must never become a Timer tag.
                String sessionId = sessionId(request,
                        event.getSession().orElse(null));
                if (sessionId != null) {
                    obs.highCardinalityKeyValue(ObservationNames.KEY_SESSION_ID,
                            sessionId);
                }
            }
            obs.lowCardinalityKeyValue(ObservationNames.KEY_OUTCOME, outcome);
            obs.stop();
        }
    }

    /**
     * The request type the request handler that handled the request stands for,
     * or {@code null} when the handler does not tell and the type is left to
     * {@link #requestType(VaadinRequest)}.
     * <p>
     * The push handler is deliberately not referenced: it needs Atmosphere,
     * which an application without push may not have on the classpath at all.
     * Every request it handles carries {@code v-r=push}, which the URL
     * classification recognises.
     */
    private static String handlerType(RequestHandler handler) {
        if (handler instanceof UidlRequestHandler) {
            return ObservationNames.REQUEST_TYPE_UIDL;
        }
        if (handler instanceof HeartbeatHandler) {
            return ObservationNames.REQUEST_TYPE_HEARTBEAT;
        }
        if (handler instanceof StreamRequestHandler) {
            return ObservationNames.REQUEST_TYPE_STREAM;
        }
        if (handler instanceof BootstrapHandler) {
            // The index.html of a page load, the init request that has its UI
            // created, and the bootstrap of an embedded web component.
            return ObservationNames.REQUEST_TYPE_BOOTSTRAP;
        }
        return null;
    }

    /**
     * Classifies a request from its URL and headers into the
     * {@code vaadin.request.type} vocabulary: {@code push}, {@code heartbeat},
     * {@code stream} (a download or an upload), {@code uidl}, {@code bootstrap}
     * (a page load), {@code static} and {@code other} for everything left. The
     * order matters — a stream request lives under {@code /VAADIN/}, and a page
     * load is only what none of the protocol-level types claimed.
     * <p>
     * Used for the requests whose handler does not tell the type: one no
     * handler handled (an expired session, say), one served by another handler,
     * and the provisional type of the request observation until the handler is
     * known.
     */
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
            // Downloads and uploads: everything Flow's StreamRequestHandler
            // serves lives under this prefix, the new streams API included.
            // Matched before the static prefixes below, which /VAADIN/dynamic/
            // would otherwise swallow.
            if (path.contains(STREAM_PATH)) {
                return ObservationNames.REQUEST_TYPE_STREAM;
            }
        }
        String vr = request.getParameter("v-r");
        if ("push".equals(vr)) {
            // The push connection itself, any transport. Its path is under
            // /VAADIN/, so it must be matched before the static prefixes.
            return ObservationNames.REQUEST_TYPE_PUSH;
        }
        if ("uidl".equals(vr)) {
            return ObservationNames.REQUEST_TYPE_UIDL;
        }
        if ("heartbeat".equals(vr)) {
            return ObservationNames.REQUEST_TYPE_HEARTBEAT;
        }
        if ("init".equals(vr)) {
            // The client engine asking the server to create the UI: the
            // second half of a page load, and the part that runs the
            // application's own code.
            return ObservationNames.REQUEST_TYPE_BOOTSTRAP;
        }
        // /themes/ and /sw.js are what 4.1's agent treated as static assets
        // besides the Vaadin resource folder; without them theme resources and
        // the service worker land in the "other" bucket next to page loads.
        if (path != null && (path.startsWith("/VAADIN/")
                || path.startsWith("/static/") || path.startsWith("/themes/")
                || path.startsWith("/sw.js"))) {
            return ObservationNames.REQUEST_TYPE_STATIC;
        }
        if (vr == null && isPageRequest(request)) {
            return ObservationNames.REQUEST_TYPE_BOOTSTRAP;
        }
        return ObservationNames.REQUEST_TYPE_OTHER;
    }

    /**
     * Whether this looks like a request for the HTML page — the first half of a
     * page load, which Flow answers with {@code index.html}. Deliberately
     * conservative: a request that cannot be told apart from an application's
     * own endpoint stays {@code other}, because over-reporting bootstrap would
     * make the type useless for the thing it exists to answer ("how long does
     * opening the application take"), while under-reporting only leaves the odd
     * non-browser page load out of an average.
     */
    private static boolean isPageRequest(VaadinRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String dest = request.getHeader("Sec-Fetch-Dest");
        if (dest != null) {
            return PAGE_FETCH_DESTINATIONS
                    .contains(dest.toLowerCase(Locale.ROOT));
        }
        // Browsers too old to send Sec-Fetch-Dest: a document request asks for
        // HTML first, an XHR asks for */* or a specific media type.
        return prefersHtml(request.getHeader("Accept"));
    }

    /**
     * Whether an {@code Accept} header asks for HTML <em>first</em>. Merely
     * listing {@code text/html} somewhere is not enough — it appears in the
     * default header of several HTTP clients, and this is the branch that runs
     * for every request without a {@code Sec-Fetch-Dest}, so a loose match
     * would quietly turn scripted traffic into page loads. Every browser that
     * predates {@code Sec-Fetch-Dest} puts {@code text/html} at the head of the
     * list when navigating.
     */
    private static boolean prefersHtml(String accept) {
        if (accept == null) {
            return false;
        }
        int end = accept.indexOf(',');
        String first = (end < 0 ? accept : accept.substring(0, end)).trim();
        // Drop any parameters (";q=0.9", ";charset=...") from the media range.
        int params = first.indexOf(';');
        if (params >= 0) {
            first = first.substring(0, params).trim();
        }
        return "text/html".equalsIgnoreCase(first);
    }
}
