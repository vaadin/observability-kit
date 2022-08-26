package com.vaadin.extension;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.StateNode;
import com.vaadin.flow.internal.StateTree;

import java.util.List;
import java.util.Optional;

/**
 * Provides common information about a Flow DOM node that can be used in
 * instrumentations
 */
public class ElementInstrumentationInfo {
    private final StateNode node;
    private final Element element;
    private final String elementLabel;
    private Component view;
    private String viewLabel;

    public StateNode getNode() {
        return node;
    }

    public Element getElement() {
        return element;
    }

    public String getElementLabel() {
        return elementLabel;
    }

    public Component getView() {
        return view;
    }

    public String getViewLabel() {
        return viewLabel;
    }

    /**
     * Creates an {@link ElementInstrumentationInfo} instance from an element's
     * state node
     *
     * @param node
     *            the state node for the element
     */
    public ElementInstrumentationInfo(StateNode node) {
        // Provide element info
        this.node = node;
        element = Element.get(node);

        String identifier = getIdentifier();
        elementLabel = element.getTag() + identifier;

        // If possible add info for active view class
        view = null;
        viewLabel = null;
        if (node.getOwner() instanceof StateTree) {
            final UI ui = ((StateTree) node.getOwner()).getUI();
            if (ui != null) {
                final List<HasElement> activeRouterTargetsChain = ui
                        .getInternals().getActiveRouterTargetsChain();
                if (!activeRouterTargetsChain.isEmpty()) {
                    view = (Component) activeRouterTargetsChain.get(0);
                    viewLabel = view.getClass().getSimpleName();
                }
            }
        }
    }

    /**
     * Get the most informative identifier for the handled element.
     * 
     * @return most informative identifier
     */
    private String getIdentifier() {
        String identifier = "";

        final Optional<Component> component = element.getComponent();
        if (component.isPresent() && component.get().getId().isPresent()) {
            identifier = String.format("[id='%s']",
                    component.get().getId().get());
        } else if (element.getText() != null && !element.getText().isEmpty()) {
            identifier = String.format("[%s]", element.getText());
        }

        return identifier;
    }
}
