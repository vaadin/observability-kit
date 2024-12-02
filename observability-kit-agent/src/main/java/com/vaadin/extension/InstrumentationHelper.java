/*-
 * Copyright (C) 2022 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.extension;

import static com.vaadin.extension.Constants.FLOW_VERSION;
import static com.vaadin.extension.Constants.REQUEST_TYPE;
import static com.vaadin.extension.Constants.SESSION_ID;
import static com.vaadin.flow.server.Constants.VAADIN_MAPPING;
import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.semconv.HttpAttributes.HTTP_REQUEST_METHOD;
import static io.opentelemetry.semconv.HttpAttributes.HTTP_RESPONSE_STATUS_CODE;
import static io.opentelemetry.semconv.HttpAttributes.HTTP_ROUTE;
import static io.opentelemetry.semconv.ServerAttributes.SERVER_ADDRESS;
import static io.opentelemetry.semconv.UrlAttributes.URL_PATH;
import static io.opentelemetry.semconv.UrlAttributes.URL_QUERY;
import static io.opentelemetry.semconv.UrlAttributes.URL_SCHEME;

import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerRoute;
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerRouteSource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.RouteConfiguration;
import com.vaadin.flow.server.Version;
import com.vaadin.flow.shared.ApplicationConstants;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.instrumenter.LocalRootSpan;

import java.time.Instant;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class InstrumentationHelper {
    public static final String INSTRUMENTATION_NAME = "com.vaadin.observability.instrumentation";
    public static final String INSTRUMENTATION_VERSION = "3.1";

    private static final SpanNameGenerator generator = new SpanNameGenerator();
    private static final SpanAttributeGenerator attrGet = new SpanAttributeGenerator();

    public static final Instrumenter<InstrumentationRequest, Void> INSTRUMENTER = Instrumenter
            .<InstrumentationRequest, Void> builder(GlobalOpenTelemetry.get(),
                    INSTRUMENTATION_NAME, generator)
            .setInstrumentationVersion(INSTRUMENTATION_VERSION)
            .addAttributesExtractor(attrGet)
            .buildInstrumenter(InstrumentationRequest::getSpanKind);

    private static final TextMapGetter<HttpServletRequest> REQUEST_GETTER =
            new TextMapGetter<>() {
                @Override
                public String get(HttpServletRequest carrier,
                        String key) {
                    if (carrier == null) {
                        return null;
                    }

                    Enumeration<String> headerNames = carrier.getHeaderNames();
                    if (headerNames == null) {
                        return null;
                    }

                    while (headerNames.hasMoreElements()) {
                        String headerName = headerNames.nextElement();
                        if (headerName.equals(key)) {
                            return carrier.getHeader(headerName);
                        }
                    }

                    return null;
                }

                @Override
                public Iterable<String> keys(HttpServletRequest carrier) {
                    Set<String> set = new HashSet<>();
                    Enumeration<String> headerNames = carrier.getHeaderNames();
                    if (headerNames != null) {
                        while (headerNames.hasMoreElements()) {
                            set.add(headerNames.nextElement());
                        }
                    }
                    return set;
                }
            };

    public static Tracer getTracer() {
        return GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME,
                INSTRUMENTATION_VERSION);
    }

    /**
     * Creates and starts a new span with the provided name. Also adds common
     * attributes provided by Vaadin contexts.
     *
     * @param spanName
     *            the name of the span
     * @return the new span
     */
    public static Span startSpan(String spanName) {
        return startSpan(spanName, null);
    }

    /**
     * Creates and starts a new span with the provided name, at the provided
     * start timestamp. Also adds common attributes provided by Vaadin contexts.
     *
     * @param spanName
     *            the name of the span
     * @param startTimestamp
     *            the start timestamp of the span
     * @return the new span
     */
    public static Span startSpan(String spanName, Instant startTimestamp) {
        SpanBuilder spanBuilder = getTracer().spanBuilder(spanName);
        if (startTimestamp != null) {
            spanBuilder.setStartTimestamp(startTimestamp);
        }
        Span span = spanBuilder.startSpan();
        Context context = currentContext();

        String sessionId = context.get(ContextKeys.SESSION_ID);
        if (sessionId != null && !sessionId.isEmpty()) {
            span.setAttribute(SESSION_ID, sessionId);
        }

        return span;
    }

    /**
     * Ends the provided span. If throwable is not null, then the error message
     * and stacktrace will be added to the span, and the span status is set to
     * {@link StatusCode#ERROR}. If a scope is provided, then the scope will be
     * closed as well.
     *
     * @param span
     *            the span to end
     * @param throwable
     *            the throwable to record, or null
     * @param scope
     *            the scope to close, or null
     */
    public static void endSpan(Span span, Throwable throwable, Scope scope) {
        if (scope != null) {
            scope.close();
        }
        if (span != null) {
            handleException(span, throwable);
            span.end();
        }
    }

    /**
     * Creates a server root span from the HTTP servlet request, and returns a
     * context containing the created span
     *
     * @param servletRequest
     *            the servlet request
     * @return the context that was created
     */
    public static Context startRootSpan(HttpServletRequest servletRequest) {
        Map<String, String> spanMap = new HashMap<>();

        // Add semantic HTTP attributes
        spanMap.put(URL_SCHEME.getKey(), servletRequest.getScheme());
        spanMap.put(HTTP_REQUEST_METHOD.getKey(), servletRequest.getMethod());
        spanMap.put(SERVER_ADDRESS.getKey(), servletRequest.getRemoteHost());
        String httpTarget = servletRequest.getContextPath()
                + servletRequest.getPathInfo();
        spanMap.put(URL_PATH.getKey(), httpTarget);
        String queryString = servletRequest.getQueryString();
        spanMap.put(URL_QUERY.getKey(), queryString);
        spanMap.put(HTTP_ROUTE.getKey(), servletRequest.getPathInfo());

        String rootSpanName = servletRequest.getPathInfo();
        InstrumentationRequest request = new InstrumentationRequest(
                rootSpanName, SpanKind.SERVER, spanMap);

        Context context = Context.current();
        if (servletRequest.getHeader("traceparent") != null) {
            context = GlobalOpenTelemetry.get().getPropagators()
                    .getTextMapPropagator().extract(context, servletRequest,
                            REQUEST_GETTER);
        }
        return INSTRUMENTER.start(context, request);
    }

    /**
     * Ends the root span from the specified context.
     *
     * @param servletResponse
     *            the response for the current server root span
     * @param context
     *            the context that contains the root span
     * @param throwable
     *            the throwable to record, or null
     */
    public static void endRootSpan(HttpServletResponse servletResponse,
            Context context, Throwable throwable) {
        Span rootSpan = LocalRootSpan.fromContextOrNull(context);
        if (rootSpan != null) {
            rootSpan.setAttribute(HTTP_RESPONSE_STATUS_CODE.getKey(),
                    servletResponse.getStatus());
            if (servletResponse.getStatus() == HttpStatusCode.NOT_FOUND
                    .getCode()) {
                rootSpan.setStatus(StatusCode.ERROR, "Request was not handled");
            }
        }
        INSTRUMENTER.end(context, null, null, throwable);
    }

    /**
     * Enhances the root span with data from a servlet request adds additional
     * data to the context, like session ID.
     *
     * @param servletRequest
     *            the request
     * @return the created context
     */
    public static Context enhanceRootSpan(HttpServletRequest servletRequest,
            Context context) {
        // Set Vaadin specific attributes on root span
        Span rootSpan = LocalRootSpan.fromContext(context);
        HttpSession session = servletRequest.getSession();

        rootSpan.setAttribute(FLOW_VERSION, Version.getFullVersion());
        rootSpan.setAttribute(SESSION_ID, session.getId());
        rootSpan.setAttribute(REQUEST_TYPE, servletRequest
                .getParameter(ApplicationConstants.REQUEST_TYPE_PARAMETER));

        // Add session id to context
        return context.with(ContextKeys.SESSION_ID, session.getId());
    }

    /**
     * Determines whether a higher-level instrumentation, for example a servlet
     * or application server instrumentation, has already created a root server
     * span.
     *
     * @return whether a server root span already exists
     */
    public static boolean checkRootSpan() {
        // For now assume that a root span will be a server root span
        Context currentContext = Context.current();
        return LocalRootSpan.fromContextOrNull(currentContext) != null;
    }

    public static void updateHttpRoute(UI ui) {
        Span localRootSpan = LocalRootSpan.fromContextOrNull(Context.current());

        if (localRootSpan == null) {
            return;
        }

        Optional<String> routeTemplate = getActiveRouteTemplate(ui);

        if (routeTemplate.isPresent()) {
            String route = "/" + routeTemplate.get();
            // Update root span name to contain the route.
            localRootSpan.updateName(route);
            localRootSpan.setAttribute(HTTP_ROUTE, route);
            // Also update using HttpServerRoute to prevent subsequent
            // instrumentations from overwriting the route.
            // HttpServerRouteSource.NESTED_CONTROLLER is the most specific type of
            // route
            HttpServerRoute.update(Context.current(),
                HttpServerRouteSource.NESTED_CONTROLLER, route);
        }
        // Update http.target to contain actual path with params
        String locationPath = "/"
                + ui.getInternals().getActiveViewLocation().getPath();
        localRootSpan.setAttribute(URL_PATH, locationPath);
    }

    /**
     * Get the route template for the currently active view.
     *
     * @param ui
     *            Current UI to get active view path for.
     * @return view template if available, else {@link Optional#empty()}
     */
    public static Optional<String> getActiveRouteTemplate(UI ui) {
        // Update root span name and http.route attribute to contain route
        // template
        List<HasElement> activeRouterTargetsChain = ui.getInternals()
                .getActiveRouterTargetsChain();
        if (activeRouterTargetsChain.isEmpty()) {
            return Optional.empty();
        }

        return RouteConfiguration.forSessionScope().getTemplate(
                ((Component) activeRouterTargetsChain.get(0)).getClass());
    }

    /**
     * Get the route template for the provided location
     *
     * @param location
     *            the location for which to get the route
     * @return view template if available, else {@link Optional#empty()}
     */
    public static Optional<String> getRouteTemplateForLocation(
            String location) {
        RouteConfiguration routeConfiguration = RouteConfiguration
                .forSessionScope();
        Optional<Class<? extends Component>> route = routeConfiguration
                .getRoute(location);

        return route.flatMap(routeConfiguration::getTemplate);
    }

    public static void handleException(Span span, Throwable throwable) {
        if (throwable != null) {
            // Mark the span as error
            span.setStatus(StatusCode.ERROR, throwable.getMessage());
            // Add exception as event to the span
            span.recordException(throwable);
            // Also mark root span as having an error, as several monitoring
            // solutions (New Relic, DataDog) only monitor for errors in root /
            // server spans
            String errorName = throwable.getClass().getCanonicalName() + ": "
                    + throwable.getMessage();
            final Span root = LocalRootSpan.current();
            root.setStatus(StatusCode.ERROR, errorName);
        }
    }

    /**
     * Get the file name from the HTTP request.
     *
     * @param request
     *            http request to get file name from
     * @return file name
     */
    public static String getRequestFilename(HttpServletRequest request) {
        // http://localhost:8888/context/servlet/folder/file.js
        // ->
        // /servlet/folder/file.js
        //
        // http://localhost:8888/context/servlet/VAADIN/folder/file.js
        // ->
        // /VAADIN/folder/file.js
        //
        // http://localhost:8888/context/servlet/sw.js
        // ->
        // /sw.js
        if (request.getPathInfo() == null) {
            return request.getServletPath();
        } else if (request.getPathInfo().startsWith("/" + VAADIN_MAPPING)
                || request.getPathInfo().startsWith("/themes/")
                || request.getPathInfo().startsWith("/sw.js")) {
            return request.getPathInfo();
        }
        return request.getServletPath() + request.getPathInfo();
    }

    public static boolean isRequestType(HttpServletRequest servletRequest,
            String requestType) {
        return requestType.equals(servletRequest.getParameter
                (ApplicationConstants.REQUEST_TYPE_PARAMETER));
    }
}
