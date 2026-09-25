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

/**
 * The most recent user interaction, whatever its outcome or duration, as the
 * development-mode Copilot panel shows it under "your last interaction".
 * <p>
 * Unlike a {@link CapturedInteraction} this is not evidence for an insight:
 * nothing went wrong, and only one is ever kept. It is recorded in development
 * mode only, since the caption it carries is read off the screen.
 *
 * @param timestamp
 *            when the last invocation of the interaction ended
 * @param route
 *            the route template the view was on
 * @param view
 *            the simple class name of the active navigation target, or
 *            {@code null}
 * @param component
 *            the class name of the component the user worked, or {@code null}
 * @param caption
 *            the component's caption, or {@code null}
 * @param event
 *            the event name, e.g. {@code click}
 * @param outcome
 *            {@link CapturedInteraction#OUTCOME_SUCCESS} or
 *            {@link CapturedInteraction#OUTCOME_ERROR}
 * @param serverMs
 *            the time the application's listeners took, summed over every
 *            invocation the request carried
 * @param invocations
 *            how many invocations the request carried
 */
public record LatestInteraction(Instant timestamp, String route, String view,
        String component, String caption, String event, String outcome,
        double serverMs, int invocations) {
}
