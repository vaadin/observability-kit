/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.vaadin.flow.component.UI;

/**
 * The detail policy shared by the collectors in this package: what a captured
 * record may carry about the user it came from, and how long free-form text may
 * be.
 * <p>
 * Centralized because the insights payload is meant to travel — into issue
 * trackers, AI agents and whatever a consumer forwards it to — so the two
 * collectors that write into it must withhold the same things. A server
 * exception message and a browser error message are the same kind of risk.
 */
final class InsightDetails {

    /**
     * Free-form messages are truncated to this many characters even when detail
     * is enabled: a message can carry a whole payload.
     */
    static final int MAX_MESSAGE_LENGTH = 200;

    /** Hex characters kept of the hashed session id. */
    private static final int SESSION_HASH_LENGTH = 12;

    private InsightDetails() {
    }

    /**
     * The session id of a UI, reduced to a short one-way hash unless detail is
     * enabled. The hash still correlates the examples of one insight while not
     * being an identifier that could be replayed against the running
     * application.
     *
     * @param ui
     *            the UI the record came from, may be {@code null}
     * @param details
     *            whether the application opted in to sensitive detail
     * @return the raw or hashed session id, or {@code null} when there is none
     */
    static String sessionId(UI ui, boolean details) {
        if (ui == null || ui.getSession() == null
                || ui.getSession().getSession() == null) {
            return null;
        }
        String id = ui.getSession().getSession().getId();
        if (id == null) {
            return null;
        }
        return details ? id : hash(id);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0,
                    SESSION_HASH_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; never fall back to the raw id.
            return null;
        }
    }

    /** Truncates to {@link #MAX_MESSAGE_LENGTH}, marking that it was cut. */
    static String truncate(String message) {
        return truncate(message, MAX_MESSAGE_LENGTH);
    }

    /**
     * Truncates to {@code max} characters, marking that it was cut.
     *
     * @param text
     *            the text to truncate, may be {@code null}
     * @param max
     *            the maximum number of characters to keep
     * @return the text, shortened with an ellipsis when it was longer
     */
    static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }
}
