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

import java.util.TreeMap;

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
 * one session attribute, keyed by UI id and capped at {@link #MAX_TRACKED_UIS}
 * UIs, so it is bounded within a session and cleaned up with it.
 */
public final class ResyncInspector {

    /**
     * The largest UIDL body, in bytes, the detection filters buffer for
     * inspection. A longer body is passed on to Flow untouched but not
     * classified: buffering it without a bound would let any client (the
     * filters run before Flow and security, and a session is optional) make the
     * server hold arbitrarily large copies of a request, and inspecting only a
     * prefix could miss the {@code clientId}/{@code resynchronize} fields,
     * which the UIDL JSON places after the potentially large {@code rpc} array.
     * Regular UIDL messages are a few kilobytes, so this only skips the rare
     * huge one.
     */
    public static final int MAX_INSPECTED_BODY_BYTES = 1024 * 1024;

    /**
     * The most UIs per session whose {@code clientId} is remembered. The UI id
     * comes from the request, and nothing at servlet level sees a UI close, so
     * without a bound every tab ever opened in a session — or every made-up id
     * a client sends — would stay in it. Past the bound the lowest UI id is
     * dropped: Flow hands ids out in increasing order, so that is the oldest
     * tab, most likely closed. Forgetting a live one only means its next resend
     * goes uncounted.
     */
    static final int MAX_TRACKED_UIS = 32;

    /**
     * One session attribute for all UIs: a {@link TreeMap} of UI id to the last
     * {@code clientId} seen. A JDK type, so the session stays serializable
     * without a kit class in it.
     */
    private static final String LAST_CLIENT_IDS_ATTR = ResyncInspector.class
            .getName() + ".lastClientIds";

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
     *            the UI id the {@code clientId} is remembered under; one that
     *            is not a non-negative integer names no UI, so nothing is
     *            remembered for it
     * @param mutex
     *            the monitor to guard the read-modify-write on
     */
    public void inspect(String body, HttpSession session, String uiId,
            Object mutex) {
        int ui = parseUiId(uiId);
        synchronized (mutex) {
            TreeMap<Integer, Integer> lastClientIds = session != null && ui >= 0
                    ? lastClientIds(session)
                    : null;
            int previous = ResyncDetector.NO_CLIENT_ID;
            if (lastClientIds != null) {
                previous = lastClientIds.getOrDefault(ui,
                        ResyncDetector.NO_CLIENT_ID);
            }

            ResyncDetector.Result result = detector.inspect(body, previous);

            if (lastClientIds != null) {
                lastClientIds.put(ui, result.lastClientId());
                while (lastClientIds.size() > MAX_TRACKED_UIS) {
                    lastClientIds.pollFirstEntry();
                }
                session.setAttribute(LAST_CLIENT_IDS_ATTR, lastClientIds);
            }
        }
    }

    /**
     * A copy of the session's map, so the stored one is replaced rather than
     * changed in place while a container may be serializing it.
     */
    @SuppressWarnings("unchecked")
    private static TreeMap<Integer, Integer> lastClientIds(
            HttpSession session) {
        Object stored = session.getAttribute(LAST_CLIENT_IDS_ATTR);
        return stored instanceof TreeMap<?, ?> map
                ? new TreeMap<>((TreeMap<Integer, Integer>) map)
                : new TreeMap<>();
    }

    private static int parseUiId(String uiId) {
        try {
            return uiId == null ? -1 : Integer.parseInt(uiId);
        } catch (NumberFormatException notAUiId) {
            return -1;
        }
    }
}
