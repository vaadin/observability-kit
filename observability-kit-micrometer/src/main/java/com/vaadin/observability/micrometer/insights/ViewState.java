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
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.StateNode;
import com.vaadin.flow.internal.StateTree;

/**
 * Reads the values a view is holding, so that a captured interaction can say
 * what state it ran against.
 * <p>
 * Read at the moment of capture rather than accumulated as the user works,
 * which is the difference between reporting the state a failure actually saw
 * and reporting a history of how it got there. A history says the same field
 * twice when the user changed their mind, in the order the browser happened to
 * send its events, mixed in with the events a component fires at itself. A
 * snapshot says each field once, as it stood, in the order the user last
 * changed them.
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
     * A value whose text is {@code java.lang.Object}'s, e.g.
     * {@code com.example.Order@6f2b958e}. Nothing in it can be typed into a
     * field, so the component it came from is left out rather than reported
     * with text no reader can use.
     * <p>
     * A dotted class name is required, because the giveaway is the package and
     * not the {@code @}: a real value may well contain one, and
     * {@code BATCH@1a2b3c} is a code someone typed rather than an address
     * nobody can. That leaves a class in the default package unrecognized,
     * which is the right way round — reporting a value nobody can use costs one
     * confusing line, silencing one that mattered costs the finding.
     */
    private static final Pattern DEFAULT_TO_STRING = Pattern
            .compile("([\\w$]+\\.)+[\\w$]+@[0-9a-fA-F]+");

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
            // The fields the user worked are already known by node id, so the
            // tree they live in is looked up rather than walked: a handful of
            // lookups instead of every component on the view, under the
            // session lock, each time an interaction is captured.
            StateTree tree = treeOf(scope);
            if (tree == null) {
                return List.of();
            }
            List<ComponentState> values = new ArrayList<>();
            for (Integer nodeId : touched.nodeIds()) {
                if (values.size() >= MAX_VALUES) {
                    break;
                }
                ComponentState state = stateOf(tree, nodeId, scope);
                if (state != null) {
                    values.add(state);
                }
            }
            return List.copyOf(values);
        } catch (RuntimeException e) {
            // Reading the state is best-effort enrichment; never let it break
            // the invocation being observed.
            return List.of();
        }
    }

    /**
     * The state tree the scope belongs to, taken from the scope rather than
     * from the UI because that is the tree its descendants are in.
     */
    private static @Nullable StateTree treeOf(Component scope) {
        return scope.getElement().getNode().getOwner() instanceof StateTree tree
                ? tree
                : null;
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

    /**
     * What one worked field contributes, or {@code null} when it contributes
     * nothing: its node is gone, it is no longer in the scope being reported,
     * it holds no value, it has no caption the reader could find it by, or its
     * value has no readable text.
     */
    private static @Nullable ComponentState stateOf(StateTree tree,
            Integer nodeId, Component scope) {
        StateNode node = tree.getNodeById(nodeId);
        if (node == null) {
            // A field of a view the user has navigated away from. Node ids are
            // never reused, so this is the whole of the cleanup that needs
            // doing.
            return null;
        }
        Component component = ComponentUtil
                .findParentComponent(Element.get(node)).orElse(null);
        if (component == null || !contains(scope, component)
                || !ComponentCaptions.holdsValue(component)) {
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
