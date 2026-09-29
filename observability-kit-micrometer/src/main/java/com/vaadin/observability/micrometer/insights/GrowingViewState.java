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

import org.jspecify.annotations.Nullable;

import com.vaadin.flow.component.UI;

/**
 * A collection one view instance keeps in a field, which has grown at every
 * measurement of its UI that saw it change — the shape of a view accumulating
 * what it loads instead of replacing it.
 *
 * @param viewClass
 *            fully qualified class name of the view
 * @param field
 *            the field, as {@code DeclaringClass.fieldName} with the declaring
 *            class fully qualified
 * @param route
 *            the view's route template, or {@code null} when it has none
 * @param elements
 *            elements held at the latest measurement
 * @param firstElements
 *            elements held when the run of growth began
 * @param growthSamples
 *            measurements at which the collection had grown, since it last
 *            shrank or was first seen
 * @param firstSeen
 *            when the run of growth began
 * @param lastGrew
 *            the latest measurement that found it grown
 * @param sessionId
 *            the session, hashed unless insight details are enabled
 * @param uiId
 *            the UI within the session
 */
public record GrowingViewState(String viewClass, String field,
        @Nullable String route, int elements, int firstElements,
        int growthSamples, Instant firstSeen, Instant lastGrew,
        @Nullable String sessionId, int uiId) {

    /**
     * The session id to report for a UI: reduced to a short one-way hash unless
     * detail is enabled, as for every other insight.
     *
     * @param ui
     *            the UI, may be {@code null}
     * @param details
     *            whether the application opted in to sensitive detail
     * @return the raw or hashed session id, or {@code null} when there is none
     */
    public static @Nullable String sessionIdOf(@Nullable UI ui,
            boolean details) {
        return InsightDetails.sessionId(ui, details);
    }
}
