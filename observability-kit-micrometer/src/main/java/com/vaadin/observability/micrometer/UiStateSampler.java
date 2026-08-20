/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.dom.ElementUtil;
import com.vaadin.flow.internal.StateNode;
import com.vaadin.flow.router.Route;

/**
 * Measures how much state one UI holds on the server.
 * <p>
 * Flow keeps every open browser tab's component tree in server memory, so for a
 * server-driven application the number that predicts when to add capacity is
 * not how many users are connected but how much state each of them costs: a
 * hundred users on a dashboard with three grids cost nothing like a hundred
 * users on a login form.
 * <p>
 * The count comes from Flow's own state tree
 * ({@link StateNode#visitNodeTree(java.util.function.Consumer)}) rather than
 * from an {@code Element.getChildren()} walk, because the public walk cannot
 * reach <em>virtual</em> children — and in Flow 25 the route target is attached
 * to its UI as a virtual child, so a public walk of a live UI finds a couple of
 * nodes and no view at all. Overlays, dialogs and grid editors are invisible
 * the same way. Walking the state tree is also the only pass that can see the
 * components and retained route targets counted here.
 * <p>
 * <strong>Threading:</strong> a state tree may only be read while its own
 * session's lock is held, so a UI is always sampled by itself — from a request,
 * navigation or RPC belonging to that session — never by another user's request
 * thread walking into it. {@link UiStateMetricsBinder} is built around that
 * rule.
 * <p>
 * <strong>What this does not measure:</strong> bytes. A node count is a proxy
 * for retained heap, not a measurement of it — one {@code Grid} node backed by
 * 100 000 rows counts as a single node. See
 * {@link ObservabilitySettings#getUiStateBytesPerNode()} for turning nodes into
 * a byte figure with a cost measured for the application at hand.
 */
final class UiStateSampler {

    private UiStateSampler() {
    }

    /**
     * Measures one UI. Must be called on a thread holding that UI's session
     * lock.
     *
     * @param ui
     *            the UI to measure, not {@code null}
     * @return the measurement
     */
    static UiStateSample sample(UI ui) {
        StateWalk walk = new StateWalk();
        ui.getElement().getNode().visitNodeTree(walk::visit);
        return new UiStateSample(walk.nodes, walk.components,
                walk.viewInstances, System.currentTimeMillis());
    }

    /**
     * One pass over the state tree, counting nodes, the components attached to
     * them, and the route targets among those components.
     */
    private static final class StateWalk {

        private int nodes;
        private int components;
        private int viewInstances;

        private void visit(StateNode node) {
            nodes++;
            ElementUtil.from(node).flatMap(Element::getComponent)
                    .ifPresent(this::countComponent);
        }

        private void countComponent(Component component) {
            components++;
            if (component.getClass().isAnnotationPresent(Route.class)) {
                viewInstances++;
            }
        }
    }
}
