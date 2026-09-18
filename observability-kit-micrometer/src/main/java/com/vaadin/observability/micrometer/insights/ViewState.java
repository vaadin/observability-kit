/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.UI;

/**
 * Reads the values a view is holding, so that a captured interaction can say
 * what state it ran against.
 * <p>
 * Read at the moment of capture rather than accumulated as the user works,
 * which is the difference between reporting the state a failure actually saw
 * and reporting a history of how it got there. A history says the same field
 * twice when the user changed their mind, in the order the browser happened to
 * send its events, mixed in with the events a component fires at itself. A
 * snapshot says each field once, as it stood, in the order the fields appear on
 * screen.
 * <p>
 * Scoped to the view, not the page: an application's shell — its navigation,
 * its app switcher — is on screen throughout and has nothing to do with the
 * failure, so it would be noise in every finding the application ever produces.
 * The scope is the innermost route target holding the interacted component, or,
 * for a component the route target does not hold (a dialog or overlay the UI
 * owns directly), that component's own top-level ancestor.
 * <p>
 * Narrowed again to what the user changed, since a replay starts from a freshly
 * opened view and every untouched field is already at the value the reader will
 * find there — see {@link TouchedFields}.
 * <p>
 * Collected in development mode only, since captions and values are application
 * text; see {@link ComponentCaptions}.
 */
final class ViewState {

    /**
     * Most values one finding reports. A form with more fields than this has
     * its first ten reported, which is where a reader stops reading anyway.
     */
    static final int MAX_VALUES = 10;

    /**
     * Components visited before the walk gives up. The walk runs under the
     * session lock, so it is bounded rather than trusted to be small: a view
     * with a large grid or a deeply nested layout must not turn capturing one
     * failure into a measurable pause.
     */
    private static final int MAX_VISITED = 500;

    /**
     * A value whose text is {@code java.lang.Object}'s, e.g.
     * {@code com.example.Order@6f2b958e}. Nothing in it can be typed into a
     * field, so the component it came from is left out rather than reported
     * with text no reader can use.
     */
    private static final Pattern DEFAULT_TO_STRING = Pattern
            .compile("\\S+@[0-9a-fA-F]+");

    private ViewState() {
    }

    /**
     * The values the user set in the interacted component's view, in the order
     * their components appear in it.
     *
     * @param ui
     *            the UI the interaction happened in, may be {@code null}
     * @param interacted
     *            the component the interaction targeted, may be {@code null}
     *            when it could not be resolved
     * @param touched
     *            the fields the user worked in this UI, may be {@code null}, in
     *            which case nothing is reported: with no way to tell a set
     *            value from the one the view came with, every field would be
     *            reported and the noise is worse than the omission
     * @return the state, possibly empty, never {@code null}
     */
    static List<ComponentState> of(@Nullable UI ui,
            @Nullable Component interacted, @Nullable TouchedFields touched) {
        try {
            Component scope = scopeOf(ui, interacted);
            if (scope == null || touched == null) {
                return List.of();
            }
            List<ComponentState> values = new ArrayList<>();
            collect(scope, values, touched, new int[] { MAX_VISITED });
            return List.copyOf(values);
        } catch (RuntimeException e) {
            // Reading the state is best-effort enrichment; never let it break
            // the invocation being observed.
            return List.of();
        }
    }

    /** The part of the component tree worth reading. */
    private static @Nullable Component scopeOf(@Nullable UI ui,
            @Nullable Component interacted) {
        Component view = activeView(ui);
        if (interacted == null) {
            return view;
        }
        if (view != null && contains(view, interacted)) {
            return view;
        }
        // Not in the route target: a dialog, an overlay, or a piece of the
        // shell. Its own top-level ancestor is then the closest thing to "the
        // screen this happened on" that can be had.
        return topLevelAncestor(interacted);
    }

    /** The innermost route target, i.e. the view rather than its layouts. */
    private static @Nullable Component activeView(@Nullable UI ui) {
        if (ui == null) {
            return null;
        }
        for (HasElement target : ui.getInternals()
                .getActiveRouterTargetsChain()) {
            if (target instanceof Component component) {
                return component;
            }
        }
        return null;
    }

    private static boolean contains(Component ancestor, Component component) {
        for (Component current = component; current != null; current = current
                .getParent().orElse(null)) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** The outermost ancestor that is not the UI itself. */
    private static Component topLevelAncestor(Component component) {
        Component top = component;
        for (Component parent = component.getParent()
                .orElse(null); parent != null
                        && !(parent instanceof UI); parent = parent.getParent()
                                .orElse(null)) {
            top = parent;
        }
        return top;
    }

    private static void collect(Component component,
            List<ComponentState> values, TouchedFields touched, int[] budget) {
        if (values.size() >= MAX_VALUES || --budget[0] < 0) {
            return;
        }
        ComponentState state = stateOf(component, touched);
        if (state != null) {
            values.add(state);
        }
        for (Component child : component.getChildren().toList()) {
            if (values.size() >= MAX_VALUES || budget[0] < 0) {
                return;
            }
            collect(child, values, touched, budget);
        }
    }

    /**
     * What one component contributes, or {@code null} when it contributes
     * nothing: it holds no value, the user never touched it, it has no caption
     * the reader could find it by, or its value has no readable text.
     */
    private static @Nullable ComponentState stateOf(Component component,
            TouchedFields touched) {
        if (!ComponentCaptions.holdsValue(component)
                || !touched.contains(component)) {
            return null;
        }
        String caption = ComponentCaptions.captionOf(component);
        if (caption == null) {
            return null;
        }
        String value = ComponentCaptions.valueOf(component);
        if (value != null && DEFAULT_TO_STRING.matcher(value).matches()) {
            return null;
        }
        return new ComponentState(component.getClass().getName(), caption,
                value);
    }
}
