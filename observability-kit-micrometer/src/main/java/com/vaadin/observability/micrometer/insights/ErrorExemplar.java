/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.time.Instant;
import java.util.List;

/**
 * One captured failed user interaction, with everything needed to backtrack and
 * replicate it: which route, which component, which user action, which
 * exception, and where in application code it blew up.
 *
 * @param timestamp
 *            when the failure happened
 * @param route
 *            the active view location, e.g. {@code orders}
 * @param component
 *            fully-qualified class of the component the user interacted with
 * @param event
 *            the client event that triggered the invocation, e.g. {@code click}
 * @param rpcType
 *            the Flow RPC invocation type, e.g. {@code event}
 * @param exceptionType
 *            fully-qualified class of the root-cause exception
 * @param exceptionMessage
 *            message of the root cause, may be {@code null}
 * @param applicationFrame
 *            first stack frame in application code (not JDK/framework), the
 *            most likely location of the bug
 * @param stackTop
 *            the top frames of the root-cause stack trace
 * @param sessionId
 *            Vaadin session id, for correlating with logs
 * @param uiId
 *            UI id within the session
 */
public record ErrorExemplar(Instant timestamp, String route, String component,
        String event, String rpcType, String exceptionType,
        String exceptionMessage, String applicationFrame, List<String> stackTop,
        String sessionId, int uiId) {
}
