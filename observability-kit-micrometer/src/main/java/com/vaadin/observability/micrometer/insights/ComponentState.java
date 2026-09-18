/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import org.jspecify.annotations.Nullable;

/**
 * One value a view held when an interaction was captured: which component, as
 * it is known on screen, and what was in it.
 * <p>
 * A failure is often not caused by the interaction that reports it but by the
 * state it ran against: the returns desk blows up on "Process return" because
 * the reason is "Defective", and a replay that only says "click the button"
 * reproduces nothing. The state of the view, read at the moment the interaction
 * was captured, is what makes a {@code replay} reproduce.
 * <p>
 * Collected in development mode only, since a caption and a value are
 * application text that may carry user data; see {@link ComponentCaptions}.
 *
 * @param component
 *            fully-qualified class of the component holding the value
 * @param caption
 *            what the component is called on screen; never {@code null},
 *            because a value the reader cannot find on screen is not a step
 *            they can carry out
 * @param value
 *            what it held, or {@code null} when it held nothing
 */
public record ComponentState(@Nullable String component, String caption,
        @Nullable String value) {
}
