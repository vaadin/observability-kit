/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * One thing a user did on the way to a captured interaction: which component
 * they touched, what they did to it, and — for a field — what they left in it.
 * <p>
 * A failure is often not caused by the interaction that reports it but by the
 * state set up before it: the returns desk blows up on "Process return" because
 * the reason is "Defective", and a replay that only says "click the button"
 * reproduces nothing. A handful of these steps, kept per UI and attached to
 * whatever interaction is captured next, is what turns the {@code replay} of an
 * insight into instructions that actually reproduce.
 * <p>
 * Collected in development mode only, since a caption and a value are
 * application text that may carry user data; see {@link ComponentCaptions}.
 *
 * @param component
 *            fully-qualified class of the component the user touched
 * @param caption
 *            what the component is called on screen, or {@code null} when it
 *            has no caption
 * @param event
 *            the client event or synchronized property name, e.g. {@code click}
 *            or {@code value}
 * @param rpcType
 *            the Flow RPC invocation type, e.g. {@code event} or {@code mSync}
 * @param value
 *            the value the component held after the step, for the field updates
 *            whose value is the point of the step; {@code null} otherwise
 */
public record InteractionStep(@Nullable String component,
        @Nullable String caption, @Nullable String event,
        @Nullable String rpcType, @Nullable String value) {

    /**
     * Whether this step and {@code other} are the same component being worked
     * the same way, which is what lets a trail keep one step for a field the
     * user typed eight characters into rather than eight.
     * <p>
     * Compared by what a step says rather than by the component instance, which
     * it does not hold: two consecutive steps naming the same kind of component
     * by the same caption are taken to be the same widget. Two captionless
     * fields of one class in a row therefore collapse into one, which costs a
     * replay step of a component nobody could have named anyway.
     *
     * @param other
     *            the step to compare against, may be {@code null}
     * @return {@code true} when only the value may differ
     */
    boolean sameAction(@Nullable InteractionStep other) {
        return other != null && Objects.equals(component, other.component())
                && Objects.equals(caption, other.caption())
                && Objects.equals(event, other.event())
                && Objects.equals(rpcType, other.rpcType());
    }
}
