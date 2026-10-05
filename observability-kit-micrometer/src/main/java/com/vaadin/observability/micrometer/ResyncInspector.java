/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import io.micrometer.core.instrument.MeterRegistry;

import com.vaadin.flow.shared.ApplicationConstants;

/**
 * Servlet-level glue shared by the resync/resend detection filters: it
 * recognizes UIDL requests, derives the per-UI key, and drives a
 * {@link ResyncDetector} against per-UI state kept in the HTTP session.
 * <p>
 * This class is deliberately framework-agnostic (servlet API only) so it can
 * back both the portable {@link ResyncDetectionFilter} and Spring-specific
 * filters. It holds no state of its own; the {@code clientId} history lives in
 * the session, keyed by UI id, so it is bounded by and cleaned up with the
 * session.
 */
public final class ResyncInspector {

    private static final String LAST_CLIENT_ID_ATTR_PREFIX = ResyncInspector.class
            .getName() + ".lastClientId.";

    private final ResyncDetector detector;

    /**
     * Creates an inspector recording into the given registry.
     *
     * @param registry
     *            the meter registry, not {@code null}
     */
    public ResyncInspector(MeterRegistry registry) {
        this.detector = new ResyncDetector(registry);
    }

    /**
     * A UIDL request is a POST whose query string carries {@code v-r=uidl}.
     * Checking the query string (rather than {@code getParameter}) avoids
     * triggering body parsing on the original request.
     *
     * @param request
     *            the request to test
     * @return {@code true} if this is a UIDL POST
     */
    public static boolean isUidl(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String query = request.getQueryString();
        return query != null
                && query.contains(ApplicationConstants.REQUEST_TYPE_PARAMETER
                        + "=" + ApplicationConstants.REQUEST_TYPE_UIDL);
    }

    /**
     * Returns the UI id carried by the request, or {@code "-"} when absent.
     *
     * @param request
     *            the request
     * @return the UI id, never {@code null}
     */
    public static String uiId(HttpServletRequest request) {
        String id = request.getParameter(ApplicationConstants.UI_ID_PARAMETER);
        return id != null ? id : "-";
    }

    /**
     * Classifies a UIDL body against the {@code clientId} last seen for the
     * same UI, records a counter for resend/resync, and stores the new
     * {@code clientId} back on the session.
     * <p>
     * The read-modify-write on the session attribute is guarded by
     * {@code mutex}: a resend can overlap the original request for the same UI
     * (both reach the filter before Flow's per-session lock), and without the
     * guard the two could clobber each other's stored {@code clientId}.
     *
     * @param body
     *            the UIDL request body (JSON); may be {@code null}/empty
     * @param session
     *            the HTTP session holding per-UI state, or {@code null}
     * @param uiId
     *            the UI id used to key the session attribute
     * @param mutex
     *            the monitor to guard the read-modify-write on
     */
    public void inspect(String body, HttpSession session, String uiId,
            Object mutex) {
        String attr = LAST_CLIENT_ID_ATTR_PREFIX + uiId;
        synchronized (mutex) {
            int previous = ResyncDetector.NO_CLIENT_ID;
            if (session != null
                    && session.getAttribute(attr) instanceof Integer stored) {
                previous = stored;
            }

            ResyncDetector.Result result = detector.inspect(body, previous);

            if (session != null) {
                session.setAttribute(attr, result.lastClientId());
            }
        }
    }
}
