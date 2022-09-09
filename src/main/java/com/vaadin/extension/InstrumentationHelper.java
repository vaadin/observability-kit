package com.vaadin.extension;

import static com.vaadin.extension.Constants.SESSION_ID;
import static com.vaadin.flow.server.Constants.VAADIN_MAPPING;
import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.RouteConfiguration;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.instrumenter.LocalRootSpan;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;

public class InstrumentationHelper {
    public static final String INSTRUMENTATION_NAME = "com.vaadin.observability.instrumentation";
    public static final String INSTRUMENTATION_VERSION = "1.0-alpha";

    private static final SpanNameGenerator generator = new SpanNameGenerator();
    private static final SpanAttributeGenerator attrGet = new SpanAttributeGenerator();

    public static final Instrumenter<InstrumentationRequest, Void> INSTRUMENTER = Instrumenter
            .<InstrumentationRequest, Void> builder(GlobalOpenTelemetry.get(),
                    INSTRUMENTATION_NAME, generator)
            .setInstrumentationVersion(INSTRUMENTATION_VERSION)
            .addAttributesExtractor(attrGet)
            .buildInstrumenter(InstrumentationRequest::getSpanKind);

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

    public static void updateHttpRoute(UI ui) {
        Span localRootSpan = LocalRootSpan.fromContextOrNull(Context.current());

        if (localRootSpan == null) {
            return;
        }

        Optional<String> routeTemplate = getActiveRouteTemplate(ui);

        if (routeTemplate.isPresent()) {
            // Update root span name to contain the route.
            // Not using HttpRouteHolder.updateHttpRoute here, as that uses
            // additional logic that might prevent an update, for example
            // when the route was already updated by a previous instrumentation.
            // Updating the root span directly allows us to cover the case
            // where a request is made against the current route, but the
            // request actually navigates to a new route, in which case the
            // root span should show the new route rather than the previous one.
            String route = "/" + routeTemplate.get();
            localRootSpan.updateName(route);
            localRootSpan.setAttribute(SemanticAttributes.HTTP_ROUTE, route);
        }
        // Update http.target to contain actual path with params
        String locationPath = "/"
                + ui.getInternals().getActiveViewLocation().getPath();
        localRootSpan.setAttribute(SemanticAttributes.HTTP_TARGET,
                locationPath);
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
}
