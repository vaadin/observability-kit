/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;

/**
 * The last few things the user did in one UI, kept so that an interaction
 * captured later can say what led up to it.
 * <p>
 * Held on the UI itself rather than in a map keyed by UI: a trail is worth
 * exactly as long as the tab it describes, and attaching it to the UI is what
 * makes it go away with the tab instead of having to be reaped. Everything here
 * runs while the session lock is held — that is the contract of RPC handling —
 * so the deque needs no synchronization of its own; what it must not do is
 * outlive its UI, which is why nothing else holds a reference to it.
 * <p>
 * Maintained in development mode only, because its steps carry captions and
 * values; see {@link ComponentCaptions}.
 */
final class InteractionTrail {

    /**
     * Steps kept per UI, which is also the most an insight can report.
     * <p>
     * Five covers the shape this is for — fill in a form, then press the button
     * that fails — while staying short enough that the reader of a finding
     * reads the steps instead of skipping them, and small enough that an idle
     * tab costs nothing worth measuring.
     */
    static final int MAX_STEPS = 5;

    private final Deque<InteractionStep> steps = new ArrayDeque<>();

    private InteractionTrail() {
    }

    /**
     * The trail of a UI, created and attached on first use.
     *
     * @param ui
     *            the UI whose trail is wanted, may be {@code null}
     * @return the trail, or {@code null} when there is no UI to hold one
     */
    static @Nullable InteractionTrail of(@Nullable UI ui) {
        if (ui == null) {
            return null;
        }
        InteractionTrail trail = ComponentUtil.getData(ui,
                InteractionTrail.class);
        if (trail == null) {
            trail = new InteractionTrail();
            ComponentUtil.setData(ui, InteractionTrail.class, trail);
        }
        return trail;
    }

    /**
     * Appends a step, evicting the oldest once {@link #MAX_STEPS} are held.
     * <p>
     * A step repeating the previous one replaces it instead of being appended:
     * a text field in eager value-change mode reports one invocation per
     * keystroke, and eight steps setting the same field would push the rest of
     * the trail out to say what one step says.
     *
     * @param step
     *            the step to append
     */
    void add(InteractionStep step) {
        if (step.sameAction(steps.peekLast())) {
            steps.removeLast();
        } else if (steps.size() == MAX_STEPS) {
            steps.removeFirst();
        }
        steps.addLast(step);
    }

    /** Returns the steps, oldest first, as they are to be replayed. */
    List<InteractionStep> snapshot() {
        return List.copyOf(steps);
    }
}
