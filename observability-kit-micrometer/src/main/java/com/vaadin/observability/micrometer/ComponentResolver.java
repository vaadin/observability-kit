/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.Optional;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.StateNode;
import com.vaadin.flow.internal.StateTree;
import com.vaadin.flow.server.communication.RpcInvocationEvent;

/**
 * Resolves the {@link com.vaadin.flow.component.Component} an RPC invocation
 * targets. Shared by the instrumentation that enriches spans and the insights
 * collector that records interactions.
 */
public final class ComponentResolver {

    private ComponentResolver() {
    }

    /**
     * Resolves the class name of the component the invocation targets, by
     * looking up the target {@code StateNode} in the UI's state tree and
     * walking up to the nearest enclosing component. Returns
     * {@link Optional#empty()} if the invocation does not target a node, the
     * node is no longer attached, or no component can be resolved. Resolution
     * is best-effort enrichment and never throws.
     *
     * @param event
     *            the RPC invocation to resolve the target component for
     * @return the fully-qualified component class name, or empty
     */
    public static Optional<String> resolveComponentType(
            RpcInvocationEvent event) {
        int nodeId = event.getNodeId();
        UI ui = event.getUI();
        if (nodeId < 0 || ui == null) {
            return Optional.empty();
        }
        try {
            StateTree tree = ui.getInternals().getStateTree();
            StateNode node = tree.getNodeById(nodeId);
            if (node == null) {
                return Optional.empty();
            }
            return ComponentUtil.findParentComponent(Element.get(node))
                    .map(component -> component.getClass().getName());
        } catch (RuntimeException e) {
            // Resolution is best-effort enrichment; never let it break the
            // invocation or the surrounding span.
            return Optional.empty();
        }
    }
}
