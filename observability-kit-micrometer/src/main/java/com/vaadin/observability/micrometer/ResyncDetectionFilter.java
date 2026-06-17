/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

import io.micrometer.core.instrument.MeterRegistry;

import com.vaadin.flow.server.communication.UidlRequestHandler;
import com.vaadin.flow.shared.ApplicationConstants;

/**
 * Servlet filter that observes UIDL message resends and resynchronization
 * requests (prototype, kit-only, no Flow changes).
 * <p>
 * Flow recovers from lost responses entirely inside {@link UidlRequestHandler}
 * by catching {@code ClientResentPayloadException} (replay the cached response)
 * and {@code ResynchronizationRequiredException} (rebuild the UI state);
 * neither surfaces to any Flow listener SPI the kit uses. This filter
 * reconstructs the same signal from the incoming request by buffering the UIDL
 * body (via {@link CachedBodyHttpServletRequest} so Flow can still read it) and
 * handing it to a {@link ResyncDetector}.
 * <p>
 * Per-UI state (the last {@code clientId} seen) is kept as an HTTP session
 * attribute keyed by UI id, so it is bounded by and cleaned up with the
 * session. Instrumentation never fails the request: any error while inspecting
 * is swallowed.
 */
public final class ResyncDetectionFilter implements Filter {

    private static final String LAST_CLIENT_ID_ATTR_PREFIX = ResyncDetectionFilter.class
            .getName() + ".lastClientId.";

    private final ResyncDetector detector;

    /**
     * Creates the filter recording into the given registry.
     *
     * @param registry
     *            the meter registry, not {@code null}
     */
    public ResyncDetectionFilter(MeterRegistry registry) {
        this.detector = new ResyncDetector(registry);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest http) || !isUidl(http)) {
            chain.doFilter(request, response);
            return;
        }

        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(
                http);
        try {
            inspect(wrapped);
        } catch (RuntimeException instrumentationFailure) {
            // Never break a request because of observability.
        }
        chain.doFilter(wrapped, response);
    }

    private void inspect(CachedBodyHttpServletRequest request) {
        HttpSession session = request.getSession(false);
        String attr = LAST_CLIENT_ID_ATTR_PREFIX + uiId(request);
        int previous = ResyncDetector.NO_CLIENT_ID;
        if (session != null
                && session.getAttribute(attr) instanceof Integer stored) {
            previous = stored;
        }

        ResyncDetector.Result result = detector.inspect(request.getCachedBody(),
                previous);

        if (session != null) {
            session.setAttribute(attr, result.lastClientId());
        }
    }

    private static String uiId(HttpServletRequest request) {
        String id = request.getParameter(ApplicationConstants.UI_ID_PARAMETER);
        return id != null ? id : "-";
    }

    /**
     * A UIDL request is a POST whose query string carries {@code v-r=uidl}.
     * Checking the query string (rather than {@code getParameter}) avoids
     * triggering body parsing on the original request.
     */
    private static boolean isUidl(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String query = request.getQueryString();
        return query != null
                && query.contains(ApplicationConstants.REQUEST_TYPE_PARAMETER
                        + "=" + ApplicationConstants.REQUEST_TYPE_UIDL);
    }
}
